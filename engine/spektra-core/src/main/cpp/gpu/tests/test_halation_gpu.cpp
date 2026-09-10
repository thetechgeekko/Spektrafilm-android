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
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "kernels/exponential_filter.h"
#include "model/diffusion.h"
#include "model/glare.h"

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

    // The FIR tap radius is int(3 * sigma + 0.5), a step function of sigma, so
    // deriving it from the f32 sigma on the device takes an extra tap pair
    // whenever rounding sigma up crosses one of those steps. Reachable slider
    // values do land there, so the boundaries are gated directly: a sigma one
    // ulp below 0.5, 1.5 and 2.5 px keeps the CPU on the lower radius while an
    // f32 copy would round onto the higher one.
    for (double boundary : {0.5, 1.5, 2.5}) {
        const double sigma_px = std::nextafter(boundary, 0.0);
        spk::HalationParams fr{};
        fr.active = true;
        fr.scatter_amount = 1.0;
        fr.scatter_spatial_scale = 1.0;
        for (int c = 0; c < 3; ++c) {
            fr.scatter_core_um[c] = sigma_px;  // pitch 1 um below, so sigma == this
            fr.scatter_tail_um[c] = 0.0;
            fr.scatter_tail_weight[c] = 0.0;
        }
        fr.halation_n_bounces = 0;
        std::vector<double> rCpu = raw, rGpu(raw.size());
        spk::apply_halation_um(rCpu.data(), w, h, fr, 1.0);
        spk::gpu::HalationScatterDiagnostics dr{};
        const bool rOk = spk::gpu::halation_scatter(request_from(fr, raw, w, h, 1.0), rGpu.data(), &dr);
        double rmax = 0.0, rrms = 0.0;
        if (rOk) log10_metrics(rCpu, rGpu, &rmax, &rrms);
        std::printf("FIR radius boundary, sigma = nextafter(%.1f, 0) px: ok=%d engaged=%d; vs CPU on log10(raw) max_abs=%.3e rms=%.3e\n",
                    boundary, rOk ? 1 : 0, dr.engaged ? 1 : 0, rmax, rrms);
        check(rOk && dr.engaged && rmax <= 1e-4 && rrms <= 1e-5,
              "FIR tap radius matches the CPU at a sigma one ulp below " + std::to_string(boundary));
    }

    // The CPU blends with the raw scatter tail weight, and a weight outside
    // [0, 1] - which a hand-edited or shared preset can carry, since the import
    // range check covers only the resource-amplifying params - turns that blend
    // into a difference of larger numbers that the f32 sum buffer cannot hold
    // inside the band (5.3e-4 on log10(raw) at w = 1.2 when it was allowed
    // through). The pass must decline it, not clamp it: clamping would render
    // a different image from the CPU with nothing to say so.
    {
        spk::HalationParams oob = p;
        const double weights[3] = {1.2, -0.3, 0.5};
        for (int c = 0; c < 3; ++c) oob.scatter_tail_weight[c] = weights[c];
        std::vector<double> oGpu(raw.size(), -1.0);
        spk::gpu::HalationScatterDiagnostics dOob{};
        const bool oOk = spk::gpu::halation_scatter(request_from(oob, raw, w, h, pixel_size_um), oGpu.data(), &dOob);
        bool oUntouched = true;
        for (double v : oGpu) oUntouched = oUntouched && v == -1.0;
        std::printf("scatter tail weight outside [0,1] (1.2/-0.3/0.5): ok=%d reason=%s untouched=%d\n",
                    oOk ? 1 : 0, dOob.reason, oUntouched ? 1 : 0);
        check(!oOk && std::strcmp(dOob.reason, "tail-weight-out-of-range") == 0 && oUntouched,
              "an out-of-range scatter tail weight is declined, leaving the CPU to render it");
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

    // THE f32 BLUR ENTRY (#223). Same pass, same sigmas, a float plane instead of
    // a double one.
    //
    // It exists for the grain stage's final blur, which holds an f32 density
    // plane: converting 37.5M elements to f64 and back, to hand them to a pass
    // that immediately converts to f32 itself, costs more than the blur does.
    // So the check is that the two entry points agree -- the f32 one must be the
    // same filter, not a differently-rounded one, and any gap between them would
    // be the conversion round trip showing up as a numerical difference.
    {
        const double sg[3] = {0.7, 0.7, 0.7};
        std::vector<double> f64_plane = raw;
        std::vector<float> f32_plane(raw.size());
        for (size_t i = 0; i < raw.size(); ++i) f32_plane[i] = static_cast<float>(raw[i]);

        const bool ok64 = spk::gpu::gaussian_blur_rgb(f64_plane.data(), w, h, sg);
        const bool ok32 = spk::gpu::gaussian_blur_rgb_f32(f32_plane.data(), w, h, sg);
        check(ok64 && ok32, "both blur entry points engaged");
        if (ok64 && ok32) {
            double worst = 0.0, peak = 0.0;
            for (size_t i = 0; i < raw.size(); ++i) {
                worst = std::max(worst, std::fabs(f64_plane[i] - f32_plane[i]));
                peak = std::max(peak, std::fabs(f64_plane[i]));
            }
            std::printf("  f32 blur entry: worst |f32 - f64| = %.3e  peak %.4g\n",
                        worst, peak);
            // The f64 entry converts its result to double at the end; the f32
            // one does not. Both compute in f32 on the device, so they agree to
            // the conversion and no further.
            check(worst <= 1e-5 * std::max(peak, 1.0),
                  "the f32 blur entry is the same filter as the f64 one");
            // And it must have DONE something -- an entry point that quietly
            // returned its input would pass the comparison above only if the
            // f64 one did too, but not if it actually blurred.
            bool moved = false;
            for (size_t i = 0; i < raw.size(); ++i)
                if (static_cast<double>(f32_plane[i]) != raw[i]) { moved = true; break; }
            check(moved, "the f32 blur altered the plane");
        }
    }

    // FRAME RESIDENCY (#220). The same pass, reading and writing the resident
    // plane instead of the caller's f64 buffers.
    //
    // The bar is EXACT equality, not a tolerance, and it can be: residency
    // changes where the bytes live and nothing else -- same shader, same
    // parameters, same f32 arithmetic, with two device-to-device copies standing
    // in for the banded host staging. Anything but a byte match is a plumbing
    // bug (a stale ping-pong half, a missing barrier, a copy of the wrong
    // buffer), so a tolerance here would only hide one.
    {
        spk::gpu::FrameDiagnostics fo{}, fc{};
        if (!spk::gpu::frame_open(raw.data(), w, h, &fo)) {
            check(false, std::string("frame_open refused: ") + fo.reason);
        } else {
            spk::gpu::HalationScatterDiagnostics dres{};
            // Null host planes on purpose: a resident pass must not need them,
            // or every caller would still have to keep them alive.
            spk::gpu::HalationScatterRequest rr = r;
            rr.raw_rgb = nullptr;
            const bool okr = spk::gpu::halation_scatter(rr, nullptr, &dres);
            check(okr && dres.engaged && dres.resident,
                  "halation runs resident with null host planes");
            std::vector<double> back(raw.size(), -1.0);
            const bool closed = spk::gpu::frame_close(back.data(), &fc);
            check(closed, "frame_close reads the halation result back");
            if (okr && closed) {
                double worst = 0.0;
                for (size_t i = 0; i < gpu.size(); ++i)
                    worst = std::max(worst, std::fabs(gpu[i] - back[i]));
                std::printf("  halation residency: worst |resident - staged| = %.3e\n",
                            worst);
                check(worst == 0.0,
                      "resident halation is byte-identical to the staged path");
            }
            spk::gpu::frame_discard();
        }
    }

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


    // --- gpu::gaussian_blur_rgb vs the CPU blur it replaces --------------------
    // Three CPU stages call gaussian_blur_per_channel_d(rgb, w, h, 3, sigma): the
    // camera lens blur, the scanner lens blur, and the unsharp mask's blur term.
    // gpu::gaussian_blur_rgb expresses all three on this same kernel, so what has
    // to be gated is that it really is the same filter -- across the FIR/IIR class
    // boundary at sigma 3, and on the unequal-sigma path, which takes a different
    // route through the mixture (three components instead of one).
    {
        struct Case { double s0, s1, s2; const char* what; };
        // FIR-class only: the pass refuses sigma >= 3, because the device says the
        // GPU loses to the CPU's O(1) IIR above that (0.62x at 12.5 MP). The
        // refusals are gated separately below.
        const Case cases[] = {
            {0.7, 0.7, 0.7, "unsharp default sigma 0.7 (FIR, uniform)"},
            {2.9, 2.9, 2.9, "sigma 2.9, just under the FIR/IIR boundary"},
            {1.5, 2.5, 2.9, "unequal sigmas: one component per channel"},
        };
        for (const Case& c : cases) {
            std::vector<double> cpu = raw, gpu = raw;
            const double sg[3] = {c.s0, c.s1, c.s2};
            spk::gaussian_blur_per_channel_d(cpu.data(), w, h, 3, sg);
            const bool ok = spk::gpu::gaussian_blur_rgb(gpu.data(), w, h, sg);
            if (!ok) {
                check(false, std::string("blur engaged: ") + c.what);
                continue;
            }
            double max_abs = 0.0, rms = 0.0;
            abs_metrics(cpu, gpu, &max_abs, &rms);
            std::printf("blur %s: engaged=1 vs CPU max_abs=%.3e rms=%.3e\n",
                        c.what, max_abs, rms);
            // Same band the DIR-shaped diffusion cases above are held to. This is
            // Fast GPU, so the band is the claim -- not byte equality.
            check(max_abs <= 1e-4 && rms <= 1e-5,
                  std::string("blur within the parity band vs the CPU filter: ") + c.what);
        }

        // The IIR-class refusal is a PERFORMANCE decision expressed as a
        // correctness-shaped gate, so it is worth stating plainly: the CPU is
        // faster there, so taking the GPU path would be a measured regression.
        // A sigma either side of the threshold must therefore behave differently.
        {
            std::vector<double> just_under = raw, just_over = raw;
            const double lo[3] = {2.99, 2.99, 2.99};
            const double hi[3] = {3.0, 3.0, 3.0};
            check(spk::gpu::gaussian_blur_rgb(just_under.data(), w, h, lo),
                  "sigma just under 3 is accepted (FIR class, GPU wins)");
            // THE IIR GATE IS NOW AN OVERRIDE, NOT AN OPT-IN. It used to refuse
            // sigma >= 3 unconditionally, on a measurement taken while
            // halation's work buffers were still host-coherent -- i.e. on the
            // memory bug, for a filter that is almost entirely memory traffic.
            // So the default is now open and SPK_GPU_BLUR_IIR=0 closes it, and
            // BOTH directions are checked here rather than only the one that
            // happens to be the default.
            setenv("SPK_GPU_BLUR_IIR", "0", 1);
            const bool over = spk::gpu::gaussian_blur_rgb(just_over.data(), w, h, hi);
            check(!over && bytes_eq(just_over, raw),
                  "with the gate closed, sigma at the IIR threshold is refused");
            std::vector<double> wide_closed = raw;
            const double w18[3] = {18.0, 18.0, 18.0};
            check(!spk::gpu::gaussian_blur_rgb(wide_closed.data(), w, h, w18) &&
                      bytes_eq(wide_closed, raw),
                  "with the gate closed, a wide IIR blur is refused, buffer untouched");
            unsetenv("SPK_GPU_BLUR_IIR");

            // Open (the default): a wide blur must RUN, and agree with the CPU
            // filter. Accepting it is only worth anything if the answer is right,
            // and an IIR blur is where the f32 pole placement could bite -- which
            // is why this compares against the CPU rather than just checking it
            // returned true.
            std::vector<double> wide_open = raw;
            const bool ran = spk::gpu::gaussian_blur_rgb(wide_open.data(), w, h, w18);
            check(ran, "with the gate open, a wide IIR blur runs");
            if (ran) {
                std::vector<double> cpu_wide = raw;
                spk::gaussian_blur_per_channel_d(cpu_wide.data(), w, h, 3, w18);
                double worst = 0.0, peak = 0.0;
                for (size_t i = 0; i < raw.size(); ++i) {
                    worst = std::max(worst, std::fabs(cpu_wide[i] - wide_open[i]));
                    peak = std::max(peak, std::fabs(cpu_wide[i]));
                }
                std::printf("  wide IIR blur (sigma 18): max_abs %.3e  peak %.4g\n",
                            worst, peak);
                check(worst <= 1e-4 * std::max(peak, 1.0),
                      "the wide IIR blur matches the CPU filter");
            }
        }

        // A blur is a MIX-FREE resolve (amount 1), so a mean-preserving check is
        // not enough on its own -- a pass that returned the input unblurred would
        // also preserve the mean. Assert the image actually changed.
        {
            std::vector<double> gpu = raw;
            const double sg[3] = {2.5, 2.5, 2.5};   // FIR class, so accepted
            const bool ok = spk::gpu::gaussian_blur_rgb(gpu.data(), w, h, sg);
            check(ok && !bytes_eq(gpu, raw), "the blur actually altered the image");
        }

        // The sigma cap must be enforced on MIXTURE sigmas. It was not: sigmaMax
        // was built only from scatter_core/scatter_tail, which mixture mode does
        // not use, so kHalMaxSigma was checking values the request never touched
        // and an arbitrarily wide blur passed validation. Refusal here is the
        // fail-closed contract, and the caller runs the CPU blur.
        {
            std::vector<double> gpu = raw;
            const double sg[3] = {5000.0, 5000.0, 5000.0};   // far past kHalMaxSigma 256
            const bool ok = spk::gpu::gaussian_blur_rgb(gpu.data(), w, h, sg);
            check(!ok && bytes_eq(gpu, raw),
                  "a sigma past the cap is refused and leaves the buffer untouched");
        }

        // Degenerate requests must refuse rather than dispatch.
        {
            std::vector<double> gpu = raw;
            const double zero[3] = {0.0, 0.0, 0.0};
            const bool ok = spk::gpu::gaussian_blur_rgb(gpu.data(), w, h, zero);
            check(!ok && bytes_eq(gpu, raw), "sigma 0 is refused (the CPU blur is a no-op there)");
        }
    }


    // --- gpu::glare_field vs the CPU field it replaces ------------------------
    // A different RNG realisation, so nothing pixel-wise can be asserted. What
    // CAN be is the distribution, and it is closed-form: the lognormal is
    // constructed from (mean m, std s) = (amount, roughness*amount), so after the
    // /100 the field has
    //     E[field]  = amount / 100
    //     sd[field] = roughness * amount / 100      (before the blur)
    // and the blur preserves the mean while reducing the sd by a known factor.
    // Comparing sd against the CPU FIELD rather than against the unblurred
    // closed form is what catches a skipped or mis-sized blur.
    {
        const int gw = 512, gh = 512;
        const size_t gn = static_cast<size_t>(gw) * gh;
        struct GCase { double amount, roughness, blur; const char* what; };
        const GCase gcases[] = {
            {0.03, 0.7, 0.5, "schema defaults (amount 0.03, roughness 0.7, blur 0.5)"},
            {0.03, 0.7, 0.0, "no blur (radius 0)"},
            {0.10, 1.2, 1.2, "stronger and wider (radius 4)"},
        };
        for (const GCase& g : gcases) {
            std::vector<float> cpu(gn, 0.0f), gpu(gn, 0.0f);
            spk::compute_random_glare_amount(
                static_cast<float>(g.amount), static_cast<float>(g.roughness),
                static_cast<float>(g.blur), gw, gh, 12345u, cpu.data(),
                spk::StatsRng::Generator::Exact);

            spk::gpu::GlareRequest req;
            req.amount = g.amount;
            req.roughness = g.roughness;
            req.blur_px = g.blur;
            req.seed = 12345u;
            spk::gpu::GlareDiagnostics diag{};
            const bool ok = spk::gpu::glare_field(gpu.data(), gw, gh, req, &diag);
            std::printf("glare %s: ok=%d engaged=%d reason=%s radius=%u gpu=%.2fms\n",
                        g.what, ok ? 1 : 0, diag.engaged ? 1 : 0,
                        diag.reason && *diag.reason ? diag.reason : "none",
                        diag.blur_radius, diag.gpu_ms);
            if (!ok || !diag.engaged) {
                check(false, std::string("glare field engaged: ") + g.what);
                continue;
            }

            double sc = 0.0, sg = 0.0, qc = 0.0, qg = 0.0;
            for (size_t i = 0; i < gn; ++i) {
                sc += cpu[i]; qc += static_cast<double>(cpu[i]) * cpu[i];
                sg += gpu[i]; qg += static_cast<double>(gpu[i]) * gpu[i];
            }
            const double n = static_cast<double>(gn);
            const double mc = sc / n, mg = sg / n;
            const double vc = qc / n - mc * mc, vg = qg / n - mg * mg;
            const double sdc = vc > 0 ? std::sqrt(vc) : 0.0;
            const double sdg = vg > 0 ? std::sqrt(vg) : 0.0;
            const double expect_mean = g.amount / 100.0;
            std::printf("  mean cpu %.6e gpu %.6e (closed form %.6e)  sd cpu %.6e gpu %.6e  ratio %.4f\n",
                        mc, mg, expect_mean, sdc, sdg, sdc > 0 ? sdg / sdc : 0.0);
            // 6-sigma on the combined standard error of two independent means.
            const double band = 6.0 * std::sqrt(2.0) * sdc / std::sqrt(n);
            check(std::fabs(mg - mc) < band, std::string("glare mean matches the CPU field: ") + g.what);
            check(std::fabs(mg - expect_mean) < 6.0 * sdg / std::sqrt(n) + 1e-9,
                  std::string("glare mean matches the closed form: ") + g.what);
            check(sdc > 0.0 && std::fabs(sdg / sdc - 1.0) < 0.05,
                  std::string("glare noise magnitude matches the CPU field (the blur was not skipped): ") + g.what);
        }

        // Determinism, and that the seed actually moves the field.
        {
            std::vector<float> a(gn, 0.0f), b(gn, 0.0f), cdiff(gn, 0.0f);
            spk::gpu::GlareRequest req;
            req.amount = 0.03; req.roughness = 0.7; req.blur_px = 0.5; req.seed = 7u;
            spk::gpu::GlareDiagnostics dd{};
            const bool o1 = spk::gpu::glare_field(a.data(), gw, gh, req, &dd);
            const bool o2 = spk::gpu::glare_field(b.data(), gw, gh, req, &dd);
            req.seed = 8u;
            const bool o3 = spk::gpu::glare_field(cdiff.data(), gw, gh, req, &dd);
            check(o1 && o2 && a == b, "same glare request is byte-identical (same device)");
            check(o3 && cdiff != a, "a new glare seed draws a new field");
        }

        // Refusals. An IIR-class sigma cannot be expressed by a recompute loop at
        // all, and a radius past the cap would need a truncated kernel - a
        // different filter. Both must leave the caller's buffer alone.
        {
            std::vector<float> untouched(gn, 42.0f);
            spk::gpu::GlareRequest req;
            req.amount = 0.03; req.roughness = 0.7; req.blur_px = 3.0; req.seed = 1u;
            spk::gpu::GlareDiagnostics dd{};
            const bool ok = spk::gpu::glare_field(untouched.data(), gw, gh, req, &dd);
            bool all42 = true;
            for (float v : untouched) if (v != 42.0f) { all42 = false; break; }
            check(!ok && all42 && std::string(dd.reason) == "blur-is-iir-class",
                  "an IIR-class blur sigma is refused, buffer untouched");

            std::vector<float> untouched2(gn, 42.0f);
            req.blur_px = 2.0;   // radius int(6.5) = 6 > kGlareMaxBlurRadius
            const bool ok2 = spk::gpu::glare_field(untouched2.data(), gw, gh, req, &dd);
            bool all42b = true;
            for (float v : untouched2) if (v != 42.0f) { all42b = false; break; }
            check(!ok2 && all42b && std::string(dd.reason) == "blur-too-wide",
                  "a blur radius past the cap is refused, buffer untouched");
        }
    }

    std::printf(failures == 0 ? "test_halation_gpu: ALL OK\n" : "test_halation_gpu: FAILURES\n");
    return failures == 0 ? 0 : 1;
}
