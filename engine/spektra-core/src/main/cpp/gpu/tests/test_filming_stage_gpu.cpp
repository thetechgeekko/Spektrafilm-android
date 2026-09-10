// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Gate for the pointwise filming stages on the GPU (#218), run under a Vulkan ICD.
//
// The host parity suite CANNOT see this: tools/parity/run_engine_parity.sh
// compiles without SPK_ENABLE_VULKAN, so gpu::available() is false there and
// every line under gpu/ is dead. 44/44 is not evidence for this file.
//
// WHAT IS ASSERTED
//
//   1. EXPOSE, both placements of the log10. expose_impl fuses the log10 into
//      its float32 output when every stage between the irradiance and the log10
//      is inert, and applies it separately otherwise; the shader reproduces both
//      via one flag, so both are checked. The reference is the engine's own CPU
//      expose with the latch clear -- i.e. the thing the GPU is supposed to
//      agree with, not a re-derivation of it that could share a mistake.
//   2. EXPOSE from the f32-plus-gain source, because that is the entry point a
//      real export uses (spektra.cpp calls expose_f32_gain with 2^ae_ev) and a
//      gain applied in the wrong place is invisible at gain 1.0.
//   3. DEVELOP against kernels' interpolate_exposure_to_density directly, with
//      a NON-UNIFORM axis so a wrong binary search shows up, and with samples
//      deliberately off both ends so the clamped edges are exercised.
//   4. THE LATCH REACHES THE PASS: the GPU and CPU renders must DIFFER (f32 vs
//      f64), because a byte-identical pair means the route was never taken.
//      That is the failure the GPU glare field actually shipped with.
//   5. REFUSALS leave the caller's buffer untouched.
//
// TOLERANCE, not equality. Fast GPU: f32 against f64.
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "kernels/interp.h"
#include "runtime/params.h"
#include "runtime/stages/filming.h"

namespace {

int g_failures = 0;
bool g_engaged = false;

void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "[PASS]" : "[FAIL]", what);
    if (!ok) ++g_failures;
}

// A smooth, strictly positive tc_lut. The real one is the Hanatos2025 spectral
// surface; what this gate needs is a surface the 4x4 Mitchell interpolation
// actually varies over, so a transposed axis or an off-by-one cell shows.
spk::NdArray make_tc_lut(int L) {
    spk::NdArray a;
    a.shape = {L, L, 3};
    a.data.resize(static_cast<size_t>(L) * L * 3);
    for (int i = 0; i < L; ++i)
        for (int j = 0; j < L; ++j) {
            const double u = static_cast<double>(i) / (L - 1);
            const double v = static_cast<double>(j) / (L - 1);
            const size_t o = (static_cast<size_t>(i) * L + j) * 3;
            a.data[o + 0] = 0.20 + 0.60 * u + 0.10 * v * v;
            a.data[o + 1] = 0.35 + 0.20 * u * v + 0.25 * v;
            a.data[o + 2] = 0.50 - 0.30 * u + 0.35 * v;
        }
    return a;
}

// A scene with real chromatic spread: the expose kernel indexes the LUT by
// chromaticity, so a grey ramp would exercise one cell of it.
std::vector<double> make_scene(int npix) {
    std::vector<double> rgb(static_cast<size_t>(npix) * 3);
    for (int p = 0; p < npix; ++p) {
        const double t = static_cast<double>(p) / (npix > 1 ? npix - 1 : 1);
        rgb[p * 3 + 0] = 0.02 + 1.40 * t * t;
        rgb[p * 3 + 1] = 0.05 + 0.80 * (1.0 - t);
        rgb[p * 3 + 2] = 0.01 + 0.60 * std::fabs(std::sin(6.0 * t));
    }
    return rgb;
}

struct Err {
    double max_abs = 0.0;
    double peak = 0.0;
    bool differs = false;
};

template <typename T>
Err compare(const std::vector<T>& a, const std::vector<T>& b) {
    Err e;
    for (size_t i = 0; i < a.size(); ++i) {
        const double x = static_cast<double>(a[i]), y = static_cast<double>(b[i]);
        if (x != y) e.differs = true;
        e.max_abs = std::max(e.max_abs, std::fabs(x - y));
        e.peak = std::max(e.peak, std::fabs(x));
    }
    return e;
}

spk::FilmingParams base_params() {
    spk::FilmingParams p;
    p.pixel_size_um = 5.0;
    return p;
}

// One expose case: run the engine's own expose with the latch clear and again
// with it set, and hold the pair to the parity band.
void expose_case(const char* label, bool force_unfused, bool f32_source, double gain) {
    const int w = 257, h = 3;              // deliberately not a multiple of 64
    const int npix = w * h;
    const spk::NdArray tc = make_tc_lut(12);
    const std::vector<double> rgb64 = make_scene(npix);
    std::vector<float> rgb32(rgb64.size());
    for (size_t i = 0; i < rgb64.size(); ++i)
        rgb32[i] = static_cast<float>(rgb64[i] / (gain != 0.0 ? gain : 1.0));

    spk::FilmingParams p = base_params();
    if (force_unfused) {
        // boost_ev > 0 is the cheapest way to take expose_impl's materialized
        // branch: it is the first gate `pointwise_fused` tests, and it runs the
        // same highlight boost on both sides so it cannot bias the comparison.
        p.halation.boost_ev = 1.0;
        p.halation.boost_range = 0.5;
        p.halation.protect_ev = 0.0;
    }

    std::vector<float> cpu(static_cast<size_t>(npix) * 3, -1.0f);
    std::vector<float> gpu(static_cast<size_t>(npix) * 3, -1.0f);

    p.allow_gpu_filming = false;
    if (f32_source) spk::expose_f32_gain(rgb32.data(), gain, w, h, p, tc, cpu.data());
    else            spk::expose(rgb64.data(), w, h, p, tc, cpu.data());

    p.allow_gpu_filming = true;
    if (f32_source) spk::expose_f32_gain(rgb32.data(), gain, w, h, p, tc, gpu.data());
    else            spk::expose(rgb64.data(), w, h, p, tc, gpu.data());

    const Err e = compare(cpu, gpu);
    // log_raw is a log10, so its "peak" is a poor scale; bound the absolute
    // difference in log units instead, which is what the density curves index by.
    std::printf("  %s: max_abs %.3e (log10 units)  differs=%d\n",
                label, e.max_abs, e.differs ? 1 : 0);
    check(e.max_abs <= 1e-4, label);
    check(e.differs, "expose: latch reaches the GPU pass");
    if (e.differs) g_engaged = true;
}

void develop_case() {
    const int npix = 4096;
    const int n = 37;                       // odd, so the binary search midpoints bite
    // A NON-UNIFORM axis: a uniform one is interpolated correctly by an
    // off-by-one search as often as not.
    std::vector<float> axis(static_cast<size_t>(n) * 3);
    std::vector<float> curve(static_cast<size_t>(n) * 3);
    for (int k = 0; k < n; ++k) {
        const double t = static_cast<double>(k) / (n - 1);
        for (int c = 0; c < 3; ++c) {
            axis[k * 3 + c] = static_cast<float>(-4.0 + 6.0 * t * t + 0.35 * c * t);
            curve[k * 3 + c] = static_cast<float>(0.05 + 2.3 * std::pow(t, 1.0 + 0.4 * c));
        }
    }
    // Samples off BOTH ends as well as inside, so the clamped edges are covered.
    std::vector<float> log_raw(static_cast<size_t>(npix) * 3);
    for (int p = 0; p < npix; ++p) {
        const double t = static_cast<double>(p) / (npix - 1);
        for (int c = 0; c < 3; ++c)
            log_raw[p * 3 + c] = static_cast<float>(-6.0 + 10.0 * t + 0.2 * c);
    }

    std::vector<float> cpu(static_cast<size_t>(npix) * 3, -1.0f);
    std::vector<float> gpu(static_cast<size_t>(npix) * 3, -1.0f);

    // The CPU reference is interpolate_exposure_to_density's own contract:
    // fast_interp over the per-channel axis. Its gamma-1.0 form takes
    // log_exposure and divides by gamma, so feeding the axis directly means
    // passing gamma 1.0 and a log_exposure equal to the first channel is wrong
    // -- instead call the planar interpolator the same way the GPU is called.
    spk::interp1d_planar3(log_raw.data(), npix, axis.data(), /*common_axis=*/false,
                          curve.data(), n, cpu.data());

    spk::gpu::FilmingStageDiagnostics d{};
    const bool ok = spk::gpu::filming_develop(log_raw.data(), npix, axis.data(),
                                              curve.data(), n, gpu.data(), &d);
    if (!ok) {
        std::printf("[FAIL] develop refused (%s)\n", d.reason);
        ++g_failures;
        return;
    }
    if (d.engaged) g_engaged = true;
    const Err e = compare(cpu, gpu);
    std::printf("  develop: max_abs %.3e  peak %.4g  bar %.3e  gpu %.1f ms\n",
                e.max_abs, e.peak, 1e-4 * e.peak, d.gpu_ms);
    check(e.max_abs <= 1e-4 * std::max(e.peak, 1.0), "develop matches the CPU interpolator");
}

void refusal_cases() {
    const int npix = 64;
    const std::vector<double> rgb = make_scene(npix);
    const spk::NdArray tc = make_tc_lut(8);
    const std::vector<float> pristine(static_cast<size_t>(npix) * 3, -777.0f);
    std::vector<float> axis(6, 0.0f), curve(6, 0.0f);
    for (int c = 0; c < 3; ++c) { axis[c] = -1.0f; axis[3 + c] = 1.0f; curve[3 + c] = 1.0f; }

    struct Case { const char* what; bool expect_ok; };
    {
        std::vector<float> out = pristine;
        spk::gpu::FilmingStageDiagnostics d{};
        // Both outputs null: the pass cannot know where to write.
        const bool ok = spk::gpu::filming_expose(rgb.data(), nullptr, 1.0, npix,
                                                 tc.data.data(), 8, 1.0,
                                                 nullptr, nullptr, &d);
        std::printf("  refusal 'no destination': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "expose with no destination refused");
    }
    {
        std::vector<float> out = pristine;
        spk::gpu::FilmingStageDiagnostics d{};
        // Both sources given: ambiguous, and silently preferring one would make
        // an f32 caller's gain vanish.
        std::vector<float> rgb32(rgb.size(), 0.5f);
        const bool ok = spk::gpu::filming_expose(rgb.data(), rgb32.data(), 1.0, npix,
                                                 tc.data.data(), 8, 1.0,
                                                 nullptr, out.data(), &d);
        std::printf("  refusal 'two sources': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "expose with two sources refused");
    }
    {
        std::vector<float> out = pristine;
        spk::gpu::FilmingStageDiagnostics d{};
        const bool ok = spk::gpu::filming_expose(rgb.data(), nullptr, 1.0, npix,
                                                 nullptr, 8, 1.0, nullptr,
                                                 out.data(), &d);
        std::printf("  refusal 'null tc_lut': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "expose with null tc_lut refused");
    }
    {
        std::vector<float> out = pristine;
        spk::gpu::FilmingStageDiagnostics d{};
        const bool ok = spk::gpu::filming_develop(nullptr, npix, axis.data(),
                                                  curve.data(), 2, out.data(), &d);
        std::printf("  refusal 'null exposure': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "develop with null source refused");
    }
    {
        std::vector<float> out = pristine;
        spk::gpu::FilmingStageDiagnostics d{};
        std::vector<float> lr(static_cast<size_t>(npix) * 3, 0.0f);
        const bool ok = spk::gpu::filming_develop(lr.data(), npix, axis.data(),
                                                  curve.data(), 1, out.data(), &d);
        std::printf("  refusal 'one curve point': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "develop with a degenerate curve refused");
    }
}

}  // namespace

int main() {
    std::printf("== test_filming_stage_gpu ==\n");
    std::printf("gpu available: %s\n", spk::gpu::available() ? "yes" : "NO");

    expose_case("expose fused (log10 folded), f64 source", false, false, 1.0);
    expose_case("expose unfused (log10 separate), f64 source", true, false, 1.0);
    // gain != 1 so a gain applied in the wrong place cannot hide.
    expose_case("expose fused, f32 source with gain 2^1.5", false, true, 2.8284271247461903);

    develop_case();
    refusal_cases();

    std::printf("engaged=%d\n", g_engaged ? 1 : 0);
    if (g_failures == 0) std::printf("test_filming_stage_gpu: ALL OK\n");
    else std::printf("test_filming_stage_gpu: %d FAIL\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
