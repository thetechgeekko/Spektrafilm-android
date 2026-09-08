// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Host gate for the GPU halation/scatter pass (issue #206), run under a real or
// software Vulkan device (WSL lavapipe in CI). It proves, against the f64 CPU
// pass model/diffusion.cpp::apply_halation_um on the same synthetic image:
//   1. tolerance: the GPU result is within the Fast GPU band on log10(raw), the
//      quantity every later stage consumes, with the numbers printed;
//   2. determinism: three warm runs are byte-identical;
//   3. banding: the upload/readback staging band is transfer granularity only,
//      so a 1-row band and the host-chosen band give byte-identical output;
//   4. flat field: an IIR-class blur of a constant plane at the export-scale
//      sigmas (up to the pass's cap) keeps the constant, i.e. the recursion's
//      DC gain holds inside the band. A flat field CANNOT see a change in the
//      filter's SHAPE, so it is never the only large-sigma check here:
//   5. shape: the same synthetic scene through a wide IIR blur, at every
//      sigma the UI can reach (halation first sigma x spatial scale up to
//      157 px, and the pass's 256 px cap), against the f64 CPU pass;
//   6. mixed classes: one blur whose three channels straddle the sigma = 3
//      FIR/IIR boundary, because host and device must agree per channel about
//      which dispatches write it;
//   7. no-op parameters leave the image unchanged and report engaged=false;
//   8. a request the host must refuse (sigma past the cap) falls back
//      without touching the output, and the warm kernel agrees afterwards;
//   9. the DIR-coupler diffusion shape at the 12.5 MP pitch and at the widest
//      tail the UI allows (500 um), against the CPU filters and blend.
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

void abs_metrics(const std::vector<double>& a, const std::vector<double>& b, double* max_abs, double* rms) {
    double m = 0.0, sse = 0.0;
    for (size_t i = 0; i < a.size(); ++i) {
        const double d = std::fabs(a[i] - b[i]);
        if (d > m) m = d;
        sse += d * d;
    }
    *max_abs = m;
    *rms = std::sqrt(sse / static_cast<double>(a.size()));
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

// The DIR-coupler diffusion (model/couplers.cpp) is the scatter step with
// amount 1: (1 - w) * G(size) + w * Exp(tail). Reference = the CPU filters
// and blend exactly as couplers.cpp runs them, compared on the plane itself
// in density units.
void dir_shaped(const std::vector<double>& plane, int w, int h, double pitch,
                double size_um, double tail_um, double tail_w, const char* label) {
    std::vector<double> cpuCorr = plane, tailPlane(plane.size());
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
    std::vector<double> gpuCorr(plane.size());
    spk::gpu::HalationScatterDiagnostics dd{};
    const bool dirOk = spk::gpu::halation_scatter(rd, gpuCorr.data(), &dd);
    double dmax = 0.0, drms = 0.0;
    if (dirOk) abs_metrics(cpuCorr, gpuCorr, &dmax, &drms);
    std::printf("DIR-shaped diffusion (%s, tail sigma %.1f px): ok=%d engaged=%d reason=%s bands=%u; vs CPU filters max_abs=%.3e rms=%.3e\n",
                label, 2.7684 * tail_um / pitch, dirOk ? 1 : 0, dd.engaged ? 1 : 0, dd.reason, dd.bands, dmax, drms);
    check(dirOk && dd.engaged && dmax <= 1e-4 && drms <= 1e-5,
          std::string("DIR-shaped diffusion within the parity band vs the CPU filters (") + label + ")");
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
    std::printf("halation_scatter: ok=%d attempted=%d engaged=%d reason=%s bands=%u dispatches=%u up=%.1fms gpu=%.1fms down=%.1fms\n",
                ok ? 1 : 0, d.attempted ? 1 : 0, d.engaged ? 1 : 0, d.reason, d.bands, d.dispatches,
                d.upload_ms, d.gpu_ms, d.readback_ms);
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
    check(d.engaged && d.bands >= 1 && d.dispatches >= 8, "engaged with at least one band");

    double max_abs = 0.0, rms = 0.0;
    log10_metrics(cpu, gpu, &max_abs, &rms);
    std::printf("gpu vs cpu on log10(raw): max_abs=%.3e rms=%.3e (bound: max_abs <= 1e-4, rms <= 1e-5)\n",
                max_abs, rms);
    // The f32 pass reproduces the f64 CPU pass inside the parity band on this
    // image. This is a tolerance, not identity: the bytes differ, and the
    // product contract for the GPU route stays "tolerance-bounded Fast GPU"
    // (#149, #206).
    check(max_abs <= 1e-4 && rms <= 1e-5, "gpu pass within the parity band vs the f64 CPU pass");
    bool finite = true;
    for (double v : gpu) finite = finite && std::isfinite(v);
    check(finite, "every output value finite");

    std::vector<double> gpu2(raw.size()), gpu3(raw.size());
    check(spk::gpu::halation_scatter(r, gpu2.data(), nullptr) &&
              spk::gpu::halation_scatter(r, gpu3.data(), nullptr) &&
              bytes_eq(gpu, gpu2) && bytes_eq(gpu, gpu3),
          "three warm runs byte-identical");

    // Banding: a staging band of one row (many upload/readback copies) must
    // give the very same bytes as the host-chosen band.
    spk::gpu::HalationScatterRequest banded = r;
    banded.staging_bytes_override = static_cast<uint32_t>(w) * 12u;
    std::vector<double> gpuBanded(raw.size());
    spk::gpu::HalationScatterDiagnostics db{};
    const bool bandedOk = spk::gpu::halation_scatter(banded, gpuBanded.data(), &db);
    std::printf("1-row staging bands: ok=%d bands=%u\n", bandedOk ? 1 : 0, db.bands);
    check(bandedOk && db.bands == static_cast<uint32_t>(h) && bytes_eq(gpu, gpuBanded),
          "one-row staging bands give the same bytes as the host-chosen band");

    // FIR-class halation (sigma sqrt(3) < 3 px) against the CPU.
    spk::HalationParams fir = p;
    for (int c = 0; c < 3; ++c) fir.halation_first_sigma_um[c] = 8.0;
    std::vector<double> firGpu(raw.size()), firCpu = raw;
    const spk::gpu::HalationScatterRequest rf = request_from(fir, raw, w, h, pixel_size_um);
    const bool firOk = spk::gpu::halation_scatter(rf, firGpu.data(), nullptr);
    spk::apply_halation_um(firCpu.data(), w, h, fir, pixel_size_um);
    double fir_max = 0.0, fir_rms = 0.0;
    if (firOk) log10_metrics(firCpu, firGpu, &fir_max, &fir_rms);
    std::printf("FIR class gpu vs cpu on log10(raw): ok=%d max_abs=%.3e rms=%.3e\n", firOk ? 1 : 0, fir_max, fir_rms);
    check(firOk && fir_max <= 1e-4 && fir_rms <= 1e-5, "FIR-class pass within the parity band vs the f64 CPU pass");

    // Flat field through an IIR-class core blur: the constant must survive at
    // the export-scale sigmas and at the pass's cap. Compared to the CPU pass
    // on the same plane (which holds the constant to f64 rounding).
    for (double sigma_px : {12.0, 63.0, 128.0, 256.0}) {
        std::vector<double> flat(raw.size());
        for (size_t i = 0; i < flat.size(); ++i) flat[i] = 0.75 + 0.25 * static_cast<double>(i % 3);
        spk::HalationParams fp{};
        fp.active = true;
        fp.scatter_amount = 1.0;
        fp.scatter_spatial_scale = 1.0;
        for (int c = 0; c < 3; ++c) {
            fp.scatter_core_um[c] = sigma_px * pixel_size_um;
            fp.scatter_tail_um[c] = 0.0;
            fp.scatter_tail_weight[c] = 0.0;
        }
        fp.halation_n_bounces = 0;
        std::vector<double> flatCpu = flat, flatGpu(flat.size());
        spk::apply_halation_um(flatCpu.data(), w, h, fp, pixel_size_um);
        spk::gpu::HalationScatterDiagnostics df{};
        const bool flatOk = spk::gpu::halation_scatter(request_from(fp, flat, w, h, pixel_size_um), flatGpu.data(), &df);
        double fmax = 0.0, frms = 0.0;
        if (flatOk) abs_metrics(flatCpu, flatGpu, &fmax, &frms);
        std::printf("flat field, core sigma %.0f px: ok=%d engaged=%d reason=%s; vs CPU max_abs=%.3e rms=%.3e\n",
                    sigma_px, flatOk ? 1 : 0, df.engaged ? 1 : 0, df.reason, fmax, frms);
        check(flatOk && df.engaged && fmax <= 1e-4 && frms <= 1e-5,
              "flat field survives an IIR-class blur at sigma " + std::to_string(static_cast<int>(sigma_px)) + " px");
    }

    // Shape, not just level: the scene through a wide IIR blur. A flat field
    // pins only the DC gain, and rounding the IIR coefficients moves the poles
    // (which is what a f32 coefficient did: 0.20 of a unit step at 256 px), so
    // every sigma the UI can reach is compared to the f64 CPU pass on the real
    // image. 65 um x scale 4 and 200 um x scale 4 are the halation slider
    // maxima; 460 um reaches the pass's own 256 px cap at this pitch.
    for (double first_um : {65.0, 260.0, 800.0, 2000.0}) {
        for (double scale : {1.0, 4.0}) {
            spk::HalationParams wide = p;
            wide.halation_spatial_scale = scale;
            for (int c = 0; c < 3; ++c) wide.halation_first_sigma_um[c] = first_um;
            const double widest = first_um * scale * std::sqrt(3.0) / pixel_size_um;
            if (widest > 256.0) continue;  // the host refuses these; covered below
            std::vector<double> wCpu = raw, wGpu(raw.size());
            spk::apply_halation_um(wCpu.data(), w, h, wide, pixel_size_um);
            spk::gpu::HalationScatterDiagnostics dw{};
            const bool wOk = spk::gpu::halation_scatter(request_from(wide, raw, w, h, pixel_size_um), wGpu.data(), &dw);
            double wmax = 0.0, wrms = 0.0;
            if (wOk) log10_metrics(wCpu, wGpu, &wmax, &wrms);
            std::printf("wide halation, first sigma %.0f um x scale %.0f (bounce-3 sigma %.0f px): ok=%d engaged=%d reason=%s; vs CPU on log10(raw) max_abs=%.3e rms=%.3e\n",
                        first_um, scale, widest, wOk ? 1 : 0, dw.engaged ? 1 : 0, dw.reason, wmax, wrms);
            check(wOk && dw.engaged && wmax <= 1e-4 && wrms <= 1e-5,
                  "wide IIR halation within the parity band (first sigma " +
                      std::to_string(static_cast<int>(first_um)) + " um x scale " +
                      std::to_string(static_cast<int>(scale)) + ")");
        }
    }

    // Mixed classes inside one blur: the host decides FIR vs IIR per channel
    // and that decision selects the dispatches, so a blur whose channels
    // straddle sigma = 3 px exercises both paths writing the same sum buffer.
    {
        spk::HalationParams mixed = p;
        mixed.halation_n_bounces = 1;  // bounce sigma = first sigma
        const double mixed_um[3] = {2.0 * pixel_size_um, 2.999 * pixel_size_um, 12.0 * pixel_size_um};
        const double core_um[3] = {2.9 * pixel_size_um, 3.1 * pixel_size_um, 3.0 * pixel_size_um};
        for (int c = 0; c < 3; ++c) {
            mixed.halation_first_sigma_um[c] = mixed_um[c];
            mixed.scatter_core_um[c] = core_um[c];
            mixed.halation_strength[c] = 0.3;
        }
        std::vector<double> mCpu = raw, mGpu(raw.size());
        spk::apply_halation_um(mCpu.data(), w, h, mixed, pixel_size_um);
        spk::gpu::HalationScatterDiagnostics dm{};
        const bool mOk = spk::gpu::halation_scatter(request_from(mixed, raw, w, h, pixel_size_um), mGpu.data(), &dm);
        double mmax = 0.0, mrms = 0.0;
        if (mOk) log10_metrics(mCpu, mGpu, &mmax, &mrms);
        std::printf("mixed FIR/IIR channels (core 2.9/3.1/3.0 px, bounce 2.0/2.999/12.0 px): ok=%d engaged=%d; vs CPU on log10(raw) max_abs=%.3e rms=%.3e\n",
                    mOk ? 1 : 0, dm.engaged ? 1 : 0, mmax, mrms);
        check(mOk && dm.engaged && mmax <= 1e-4 && mrms <= 1e-5,
              "a blur whose channels straddle the FIR/IIR boundary is fully written");
    }

    // A zero bounce decay is reachable from the UI (the slider runs 0..1) and
    // the CPU's std::pow(0, 0) is 1, while GLSL's pow(0, 0) is undefined; the
    // weights are computed on the host for exactly this reason.
    {
        spk::HalationParams zero = p;
        zero.halation_bounce_decay = 0.0;
        std::vector<double> zCpu = raw, zGpu(raw.size());
        spk::apply_halation_um(zCpu.data(), w, h, zero, pixel_size_um);
        const bool zOk = spk::gpu::halation_scatter(request_from(zero, raw, w, h, pixel_size_um), zGpu.data(), nullptr);
        double zmax = 0.0, zrms = 0.0;
        if (zOk) log10_metrics(zCpu, zGpu, &zmax, &zrms);
        bool zFinite = true;
        for (double v : zGpu) zFinite = zFinite && std::isfinite(v);
        std::printf("bounce decay 0: ok=%d finite=%d; vs CPU on log10(raw) max_abs=%.3e rms=%.3e\n",
                    zOk ? 1 : 0, zFinite ? 1 : 0, zmax, zrms);
        check(zOk && zFinite && zmax <= 1e-4 && zrms <= 1e-5,
              "a zero bounce decay matches the CPU and produces no NaN");
    }

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
    huge.halation_first_sigma_um[0] = 10000.0;  // sigma ~1900 px, past the cap
    std::vector<double> refused(raw.size(), -1.0);
    spk::gpu::HalationScatterDiagnostics dh{};
    const spk::gpu::HalationScatterRequest rh = request_from(huge, raw, w, h, pixel_size_um);
    const bool hugeOk = spk::gpu::halation_scatter(rh, refused.data(), &dh);
    bool untouched = true;
    for (double v : refused) untouched = untouched && v == -1.0;
    check(!hugeOk && std::strcmp(dh.reason, "sigma-too-large") == 0 && untouched,
          "sigma past the cap is refused with the output untouched");
    // A refusal leaves the kernel warm; the next call must still agree byte for byte.
    std::vector<double> rebuilt(raw.size());
    check(spk::gpu::halation_scatter(r, rebuilt.data(), nullptr) && bytes_eq(gpu, rebuilt),
          "after a refusal the kernel reproduces the same bytes");

    // DIR-coupler diffusion shape: digested defaults (size 20 um, tail 200 um,
    // tail weight 0.06) at the 18 um pitch of a 3 MP export and at the 8.8 um
    // pitch of the 12.5 MP export, where the tail sigma reaches 63 px. The
    // correction is silver density times the inhibitor matrix, so the
    // synthetic scene is scaled into that range (0..~3) before the comparison.
    {
        std::vector<double> plane(raw.size());
        for (size_t i = 0; i < raw.size(); ++i) plane[i] = raw[i] * 0.08;
        dir_shaped(plane, w, h, 18.0, 20.0, 200.0, 0.06, "3 MP pitch");
        dir_shaped(plane, w, h, 8.8, 20.0, 200.0, 0.06, "12.5 MP pitch");
        // The coupler sliders reach a 500 um tail and a tail weight of 1, which
        // put the widest surrogate at 157 px; the pass accepts them, so they
        // are gated rather than assumed.
        dir_shaped(plane, w, h, 8.8, 20.0, 200.0, 1.00, "12.5 MP pitch, tail weight 1");
        dir_shaped(plane, w, h, 8.8, 20.0, 500.0, 0.06, "12.5 MP pitch, 500 um tail");
        dir_shaped(plane, w, h, 8.8, 20.0, 500.0, 1.00, "12.5 MP pitch, 500 um tail, weight 1");
    }

    std::printf(failures == 0 ? "test_halation_gpu: ALL OK\n" : "test_halation_gpu: FAILURES\n");
    return failures == 0 ? 0 : 1;
}
