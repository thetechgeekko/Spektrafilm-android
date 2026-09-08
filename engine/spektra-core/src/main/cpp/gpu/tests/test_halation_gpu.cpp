// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Host gate for the GPU halation/scatter pass (issue #206), run under a real or
// software Vulkan device (WSL lavapipe in CI). It proves, against the f64 CPU
// pass model/diffusion.cpp::apply_halation_um on the same synthetic image:
//   1. tolerance: the GPU result is within the Fast GPU band on log10(raw), the
//      quantity every later stage consumes, with the numbers printed;
//   2. determinism: three warm runs are byte-identical;
//   3. slicing: for FIR-class sigmas a 41-row slice height and the host-chosen
//      one give byte-identical output (the halo covers the exact neighbourhood);
//      for IIR-class sigmas the slice-local recursion start leaves a residual
//      the 10-sigma halo keeps an order of magnitude inside the band;
//   4. no-op parameters leave the image unchanged and report engaged=false;
//   5. a request the host must refuse (sigma past the halo cap) falls back
//      without touching the output, and the rebuilt kernel agrees afterwards.
// Without a Vulkan device the pass must report a reason and return false,
// which is the CPU-fallback law; the binary then prints ALL OK for the
// fallback law only and says so.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "kernels/exponential_filter.h"
#include "model/diffusion.h"

namespace {

int failures = 0;

void check(bool ok, const std::string& what) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) ++failures;
}

bool bytes_eq(const std::vector<double>& a, const std::vector<double>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(double)) == 0;
}

// A smooth scene with hard bright edges and a few point highlights: the shape
// halation acts on. Values are linear irradiance in [0, ~40].
std::vector<double> synthetic(int w, int h) {
    std::vector<double> img(static_cast<size_t>(w) * h * 3);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const double fx = static_cast<double>(x) / w, fy = static_cast<double>(y) / h;
            double r = 0.05 + 0.4 * fx, g = 0.08 + 0.3 * fy, b = 0.03 + 0.2 * (1.0 - fx) * fy;
            if (x > w / 3 && x < w / 2 && y > h / 4 && y < 3 * h / 4) { r += 6.0; g += 5.0; b += 4.0; }
            if (((x * 7 + y * 13) % 997) == 0) { r += 30.0; g += 25.0; b += 35.0; }
            const size_t i = (static_cast<size_t>(y) * w + x) * 3;
            img[i] = r; img[i + 1] = g; img[i + 2] = b;
        }
    }
    return img;
}

void log10_metrics(const std::vector<double>& cpu, const std::vector<double>& gpu,
                   double* max_abs, double* rms) {
    double m = 0.0, sse = 0.0;
    for (size_t i = 0; i < cpu.size(); ++i) {
        const double a = std::log10(std::fmax(cpu[i], 0.0) + 1e-10);
        const double b = std::log10(std::fmax(gpu[i], 0.0) + 1e-10);
        const double d = std::fabs(a - b);
        if (d > m) m = d;
        sse += d * d;
    }
    *max_abs = m;
    *rms = std::sqrt(sse / static_cast<double>(cpu.size()));
}

spk::gpu::HalationScatterRequest request_from(const spk::HalationParams& p, const std::vector<double>& raw,
                                              int w, int h, double pixel_size_um) {
    spk::gpu::HalationScatterRequest r{};
    r.raw_rgb = raw.data();
    r.width = w;
    r.height = h;
    r.pixel_size_um = pixel_size_um;
    r.scatter_amount = p.scatter_amount;
    r.scatter_spatial_scale = p.scatter_spatial_scale;
    r.halation_amount = p.halation_amount;
    r.halation_spatial_scale = p.halation_spatial_scale;
    r.halation_n_bounces = p.halation_n_bounces;
    r.halation_bounce_decay = p.halation_bounce_decay;
    r.halation_renormalize = p.halation_renormalize;
    for (int c = 0; c < 3; ++c) {
        r.scatter_core_um[c] = p.scatter_core_um[c];
        r.scatter_tail_um[c] = p.scatter_tail_um[c];
        r.scatter_tail_weight[c] = p.scatter_tail_weight[c];
        r.halation_strength[c] = p.halation_strength[c];
        r.halation_first_sigma_um[c] = p.halation_first_sigma_um[c];
    }
    return r;
}

}  // namespace

int main() {
    const int w = 640, h = 400;
    const double pixel_size_um = 9.0;  // ~36 mm across 4000 px, an export-scale pitch
    const std::vector<double> raw = synthetic(w, h);

    // Portra-like digested parameters: upstream's defaults for the scatter core
    // and tail, a visible three-bounce halation.
    spk::HalationParams p{};
    p.active = true;
    p.scatter_amount = 1.0;
    p.scatter_spatial_scale = 1.0;
    const double core[3] = {2.2, 2.0, 1.6}, tail[3] = {9.3, 9.7, 9.1}, weight[3] = {0.78, 0.65, 0.67};
    const double strength[3] = {0.22, 0.10, 0.04}, first_sigma[3] = {60.0, 55.0, 50.0};
    for (int c = 0; c < 3; ++c) {
        p.scatter_core_um[c] = core[c];
        p.scatter_tail_um[c] = tail[c];
        p.scatter_tail_weight[c] = weight[c];
        p.halation_strength[c] = strength[c];
        p.halation_first_sigma_um[c] = first_sigma[c];
    }
    p.halation_amount = 1.0;
    p.halation_spatial_scale = 1.0;
    p.halation_n_bounces = 3;
    p.halation_bounce_decay = 0.5;
    p.halation_renormalize = true;

    std::vector<double> cpu = raw;
    spk::apply_halation_um(cpu.data(), w, h, p, pixel_size_um);

    std::vector<double> gpu(raw.size(), -1.0);
    spk::gpu::HalationScatterDiagnostics d{};
    const spk::gpu::HalationScatterRequest r = request_from(p, raw, w, h, pixel_size_um);
    const bool ok = spk::gpu::halation_scatter(r, gpu.data(), &d);
    std::printf("halation_scatter: ok=%d attempted=%d engaged=%d reason=%s slices=%u dispatches=%u halo=%u\n",
                ok ? 1 : 0, d.attempted ? 1 : 0, d.engaged ? 1 : 0, d.reason, d.slices, d.dispatches, d.halo_rows);
    if (!ok) {
        check(!d.engaged && std::strcmp(d.reason, "none") != 0,
              "fallback law: a failed pass reports a reason and does not claim engagement");
        bool untouched = true;
        for (double v : gpu) untouched = untouched && v == -1.0;
        check(untouched, "fallback law: output untouched on failure");
        std::printf("no Vulkan device engaged; only the fallback law was checked\n");
        std::printf(failures == 0 ? "test_halation_gpu: ALL OK\n" : "test_halation_gpu: FAILURES\n");
        return failures == 0 ? 0 : 1;
    }
    check(d.engaged && d.slices >= 1 && d.dispatches >= 8, "engaged with at least one slice");

    double max_abs = 0.0, rms = 0.0;
    log10_metrics(cpu, gpu, &max_abs, &rms);
    std::printf("gpu vs cpu on log10(raw): max_abs=%.3e rms=%.3e (bound: max_abs <= 1e-4, rms <= 1e-5)\n",
                max_abs, rms);
    // The f32 pass reproduces the f64 CPU pass inside the parity band on this
    // image (measured 2.2e-5 / 3.8e-6 under lavapipe). This is a tolerance, not
    // identity: the bytes differ, and the product contract for the GPU route
    // stays "tolerance-bounded Fast GPU" (#149, #206).
    check(max_abs <= 1e-4 && rms <= 1e-5, "gpu pass within the parity band vs the f64 CPU pass");
    bool finite = true;
    for (double v : gpu) finite = finite && std::isfinite(v);
    check(finite, "every output value finite");

    std::vector<double> gpu2(raw.size()), gpu3(raw.size());
    check(spk::gpu::halation_scatter(r, gpu2.data(), nullptr) &&
              spk::gpu::halation_scatter(r, gpu3.data(), nullptr) &&
              bytes_eq(gpu, gpu2) && bytes_eq(gpu, gpu3),
          "three warm runs byte-identical");

    // Slicing. The bounce sigmas here (6.7-11.5 px) take the IIR, whose slice-
    // local edge initialisation leaves a transient the 10-sigma halo damps but
    // does not remove, so 41-row slices are compared to the whole-image run
    // under a tolerance an order inside the band; the FIR-class request below must
    // be byte-identical, because its halo covers the exact neighbourhood.
    spk::gpu::HalationScatterRequest sliced = r;
    sliced.slice_rows_override = 41;
    std::vector<double> gpuSliced(raw.size());
    spk::gpu::HalationScatterDiagnostics ds{};
    const bool slicedOk = spk::gpu::halation_scatter(sliced, gpuSliced.data(), &ds);
    double slice_max = 0.0, slice_rms = 0.0;
    if (slicedOk) log10_metrics(gpu, gpuSliced, &slice_max, &slice_rms);
    std::printf("41-row slices (IIR class): ok=%d slices=%u dispatches=%u halo=%u; vs whole image max_abs=%.3e rms=%.3e\n",
                slicedOk ? 1 : 0, ds.slices, ds.dispatches, ds.halo_rows, slice_max, slice_rms);
    check(slicedOk && ds.slices >= 8 && slice_max <= 1e-5 && slice_rms <= 1e-6,
          "IIR-class 41-row slices agree with the whole-image run far inside the band");

    spk::HalationParams fir = p;
    for (int c = 0; c < 3; ++c) fir.halation_first_sigma_um[c] = 8.0;  // sigma sqrt(3) < 3 px: FIR class
    std::vector<double> firWhole(raw.size()), firSliced(raw.size());
    spk::gpu::HalationScatterRequest rf = request_from(fir, raw, w, h, pixel_size_um);
    spk::gpu::HalationScatterRequest rfSliced = rf;
    rfSliced.slice_rows_override = 41;
    spk::gpu::HalationScatterDiagnostics df{};
    const bool firOk = spk::gpu::halation_scatter(rf, firWhole.data(), nullptr) &&
                       spk::gpu::halation_scatter(rfSliced, firSliced.data(), &df);
    std::printf("41-row slices (FIR class): ok=%d slices=%u halo=%u\n", firOk ? 1 : 0, df.slices, df.halo_rows);
    check(firOk && df.slices >= 8 && bytes_eq(firWhole, firSliced),
          "FIR-class 41-row slices give the same bytes as the host-chosen slice height");
    std::vector<double> firCpu = raw;
    spk::apply_halation_um(firCpu.data(), w, h, fir, pixel_size_um);
    double fir_max = 0.0, fir_rms = 0.0;
    log10_metrics(firCpu, firWhole, &fir_max, &fir_rms);
    std::printf("FIR class gpu vs cpu on log10(raw): max_abs=%.3e rms=%.3e\n", fir_max, fir_rms);
    check(fir_max <= 1e-4 && fir_rms <= 1e-5, "FIR-class pass within the parity band vs the f64 CPU pass");

    spk::HalationParams off = p;
    off.scatter_amount = 0.0;
    off.halation_n_bounces = 0;
    std::vector<double> unchanged(raw.size());
    spk::gpu::HalationScatterDiagnostics dn{};
    const spk::gpu::HalationScatterRequest rn = request_from(off, raw, w, h, pixel_size_um);
    check(spk::gpu::halation_scatter(rn, unchanged.data(), &dn) && !dn.engaged &&
              std::strcmp(dn.reason, "nothing-to-do") == 0 && bytes_eq(unchanged, raw),
          "inactive parameters: success, not engaged, image unchanged");

    spk::HalationParams huge = p;
    huge.halation_first_sigma_um[0] = 10000.0;  // sigma > the 256 px FIR cap
    std::vector<double> refused(raw.size(), -1.0);
    spk::gpu::HalationScatterDiagnostics dh{};
    const spk::gpu::HalationScatterRequest rh = request_from(huge, raw, w, h, pixel_size_um);
    const bool hugeOk = spk::gpu::halation_scatter(rh, refused.data(), &dh);
    bool untouched = true;
    for (double v : refused) untouched = untouched && v == -1.0;
    check(!hugeOk && std::strcmp(dh.reason, "sigma-too-large") == 0 && untouched,
          "sigma past the FIR cap is refused with the output untouched");
    // A refusal leaves the kernel warm; the next call must still agree byte for byte.
    std::vector<double> rebuilt(raw.size());
    check(spk::gpu::halation_scatter(r, rebuilt.data(), nullptr) && bytes_eq(gpu, rebuilt),
          "after a refusal the kernel reproduces the same bytes");

    // The DIR-coupler diffusion (model/couplers.cpp) is the scatter step with
    // amount 1: (1 - w) * G(size) + w * Exp(tail). Digested defaults at an
    // 18 um pitch: size 20 um (FIR class), tail 200 um (IIR class, sigma up to
    // ~31 px). Reference = the CPU filters and blend exactly as couplers.cpp
    // runs them, compared on the plane itself in density units: the correction
    // is silver density times the inhibitor matrix, so the synthetic scene is
    // scaled into that range (0..~3) before the comparison.
    {
        const double pitch = 18.0, size_um = 20.0, tail_um = 200.0, tail_w = 0.06;
        std::vector<double> plane(raw.size());
        for (size_t i = 0; i < raw.size(); ++i) plane[i] = raw[i] * 0.08;
        std::vector<double> cpuCorr = plane, tailPlane(raw.size());
        const double lt[3] = {tail_um / pitch, tail_um / pitch, tail_um / pitch};
        const double sg[3] = {size_um / pitch, size_um / pitch, size_um / pitch};
        spk::exponential_filter_per_channel_d(plane.data(), w, h, 3, lt, tailPlane.data());
        spk::gaussian_blur_per_channel_d(cpuCorr.data(), w, h, 3, sg);
        for (size_t i = 0; i < cpuCorr.size(); ++i) cpuCorr[i] = (1.0 - tail_w) * cpuCorr[i] + tail_w * tailPlane[i];
        spk::gpu::HalationScatterRequest rd{};
        rd.raw_rgb = plane.data();
        rd.width = w;
        rd.height = h;
        rd.pixel_size_um = pitch;
        rd.scatter_amount = 1.0;
        rd.scatter_spatial_scale = 1.0;
        rd.halation_n_bounces = 0;
        for (int c = 0; c < 3; ++c) {
            rd.scatter_core_um[c] = size_um;
            rd.scatter_tail_um[c] = tail_um;
            rd.scatter_tail_weight[c] = tail_w;
        }
        std::vector<double> gpuCorr(raw.size());
        spk::gpu::HalationScatterDiagnostics dd{};
        const bool dirOk = spk::gpu::halation_scatter(rd, gpuCorr.data(), &dd);
        double dmax = 0.0, dsse = 0.0;
        for (size_t i = 0; i < cpuCorr.size(); ++i) {
            const double diff = std::fabs(cpuCorr[i] - gpuCorr[i]);
            if (diff > dmax) dmax = diff;
            dsse += diff * diff;
        }
        const double drms = std::sqrt(dsse / static_cast<double>(cpuCorr.size()));
        std::printf("DIR-shaped diffusion: ok=%d engaged=%d halo=%u slices=%u; vs CPU filters max_abs=%.3e rms=%.3e\n",
                    dirOk ? 1 : 0, dd.engaged ? 1 : 0, dd.halo_rows, dd.slices, dmax, drms);
        check(dirOk && dd.engaged && dmax <= 1e-4 && drms <= 1e-5,
              "DIR-shaped diffusion within the parity band vs the CPU filters");
    }

    std::printf(failures == 0 ? "test_halation_gpu: ALL OK\n" : "test_halation_gpu: FAILURES\n");
    return failures == 0 ? 0 : 1;
}
