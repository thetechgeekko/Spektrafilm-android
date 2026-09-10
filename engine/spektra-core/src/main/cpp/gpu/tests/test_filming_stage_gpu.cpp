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
#include "model/density_curves.h"
#include "model/diffusion.h"
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
    const bool ok = spk::gpu::filming_develop(log_raw.data(), npix, npix, 1,
                                              axis.data(), curve.data(), n,
                                              gpu.data(), &d);
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
        const bool ok = spk::gpu::filming_expose(rgb.data(), nullptr, 1.0, npix, npix, 1,
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
        const bool ok = spk::gpu::filming_expose(rgb.data(), rgb32.data(), 1.0, npix, npix, 1,
                                                 tc.data.data(), 8, 1.0,
                                                 nullptr, out.data(), &d);
        std::printf("  refusal 'two sources': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "expose with two sources refused");
    }
    {
        std::vector<float> out = pristine;
        spk::gpu::FilmingStageDiagnostics d{};
        const bool ok = spk::gpu::filming_expose(rgb.data(), nullptr, 1.0, npix, npix, 1,
                                                 nullptr, 8, 1.0, nullptr,
                                                 out.data(), &d);
        std::printf("  refusal 'null tc_lut': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "expose with null tc_lut refused");
    }
    {
        std::vector<float> out = pristine;
        spk::gpu::FilmingStageDiagnostics d{};
        const bool ok = spk::gpu::filming_develop(nullptr, npix, npix, 1, axis.data(),
                                                  curve.data(), 2, out.data(), &d);
        std::printf("  refusal 'null exposure': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "develop with null source refused");
    }
    {
        std::vector<float> out = pristine;
        spk::gpu::FilmingStageDiagnostics d{};
        std::vector<float> lr(static_cast<size_t>(npix) * 3, 0.0f);
        const bool ok = spk::gpu::filming_develop(lr.data(), npix, npix, 1, axis.data(),
                                                  curve.data(), 1, out.data(), &d);
        std::printf("  refusal 'one curve point': ok=%d reason=%s untouched=%d\n",
                    ok ? 1 : 0, d.reason, out == pristine ? 1 : 0);
        check(!ok && out == pristine, "develop with a degenerate curve refused");
    }
}

// FRAME RESIDENCY (#220). The mechanism, not a stage: open a frame, run the
// develop pass against it, close, and check the answer is the one the
// non-resident call gives.
//
// The point of the check is that residency must be INVISIBLE to the result. It
// changes where the bytes live and nothing else -- same kernel, same tables,
// same f32 arithmetic -- so a difference here is a plumbing bug (a descriptor
// still pointing at the mapped pair, a ping-pong half not flipped, a missing
// barrier), not a numerical one. Anything but an exact match is a failure, which
// is a stronger bar than the tolerance the rest of this file uses, and it can be
// because nothing about the arithmetic has changed.
void residency_case() {
    const int w = 129, h = 7;                  // not a multiple of the workgroup
    const int npix = w * h;
    const int n = 21;
    std::vector<float> axis(static_cast<size_t>(n) * 3);
    std::vector<float> curve(static_cast<size_t>(n) * 3);
    for (int k = 0; k < n; ++k) {
        const double t = static_cast<double>(k) / (n - 1);
        for (int c = 0; c < 3; ++c) {
            axis[k * 3 + c] = static_cast<float>(-3.0 + 5.0 * t * t + 0.2 * c);
            curve[k * 3 + c] = static_cast<float>(0.1 + 2.0 * std::pow(t, 1.0 + 0.3 * c));
        }
    }
    std::vector<double> src(static_cast<size_t>(npix) * 3);
    for (size_t i = 0; i < src.size(); ++i)
        src[i] = -4.0 + 8.0 * (static_cast<double>(i) / src.size());
    std::vector<float> src32(src.size());
    for (size_t i = 0; i < src.size(); ++i) src32[i] = static_cast<float>(src[i]);

    // Non-resident: the ordinary path, host in and host out.
    std::vector<float> plain(src.size(), -1.0f);
    spk::gpu::FilmingStageDiagnostics d0{};
    if (!spk::gpu::filming_develop(src32.data(), npix, w, h, axis.data(),
                                   curve.data(), n, plain.data(), &d0)) {
        std::printf("[FAIL] residency: non-resident develop refused (%s)\n", d0.reason);
        ++g_failures;
        return;
    }
    check(!d0.resident, "a develop with no frame open is not resident");

    // Resident: one upload, the pass, one readback.
    spk::gpu::FrameDiagnostics fo{}, fc{};
    if (!spk::gpu::frame_open(src.data(), w, h, &fo)) {
        std::printf("[FAIL] residency: frame_open refused (%s)\n", fo.reason);
        ++g_failures;
        return;
    }
    check(spk::gpu::frame_active(w, h), "frame_active reports the open frame");
    check(!spk::gpu::frame_active(w + 1, h), "frame_active rejects a different geometry");

    spk::gpu::FilmingStageDiagnostics d1{};
    const bool ok = spk::gpu::filming_develop(nullptr, npix, w, h, axis.data(),
                                              curve.data(), n, nullptr, &d1);
    check(ok && d1.resident, "develop runs resident with null host pointers");

    std::vector<double> back(src.size(), -1.0);
    const bool closed = spk::gpu::frame_close(back.data(), &fc);
    check(closed, "frame_close reads the plane back");
    check(!spk::gpu::frame_active(w, h), "the frame is closed afterwards");

    if (!ok || !closed) return;
    double worst = 0.0;
    for (size_t i = 0; i < plain.size(); ++i)
        worst = std::max(worst, std::fabs(static_cast<double>(plain[i]) - back[i]));
    std::printf("  residency: worst |resident - non-resident| = %.3e\n", worst);
    check(worst == 0.0, "residency changes where the bytes live and nothing else");

    // And it must fail closed: a second open without a close is a caller bug,
    // and silently reusing the plane would render the previous frame's image.
    spk::gpu::FrameDiagnostics f2{};
    if (spk::gpu::frame_open(src.data(), w, h, &f2)) {
        spk::gpu::FrameDiagnostics f3{};
        const bool again = spk::gpu::frame_open(src.data(), w, h, &f3);
        std::printf("  residency: second open ok=%d reason=%s\n", again ? 1 : 0, f3.reason);
        check(!again, "a second frame_open without a close is refused");
        spk::gpu::frame_discard();
        check(!spk::gpu::frame_active(w, h), "frame_discard closes without a readback");
    }
}

// HIGHLIGHT BOOST (#219). The GPU pass against the engine's own CPU map.
//
// The interesting half is the FRAME MAXIMUM, not the per-element curve. The CPU
// scans the whole w*h*3 plane; the GPU reduces per workgroup and the host takes
// the maximum of the partials. That is only equal if the reduction covers every
// component and the out-of-range lanes contribute nothing -- so the plane here
// is deliberately NOT a multiple of the 64-wide workgroup, and its single
// largest value is parked in the last few components, where a reduction that
// drops its tail would miss it and quietly boost by the wrong scale.
void boost_case(const char* label, double boost_ev, double boost_range,
                double protect_ev, bool expect_gpu) {
    const int w = 101, h = 13;                     // 101*13*3 = 3939 components
    const size_t comps = static_cast<size_t>(w) * h * 3;
    std::vector<double> base(comps);
    for (size_t i = 0; i < comps; ++i) {
        const double t = static_cast<double>(i) / comps;
        base[i] = 0.004 * std::pow(2.0, 9.0 * t);   // ~9 stops
    }
    // The maximum, in the tail, past the last whole workgroup.
    base[comps - 3] = 17.5;

    spk::HalationParams hp;
    hp.boost_ev = boost_ev;
    hp.boost_range = boost_range;
    hp.protect_ev = protect_ev;

    std::vector<double> cpu = base, gpu = base;
    spk::apply_highlight_boost(cpu.data(), w, h, hp, /*allow_gpu=*/false);

    spk::gpu::FilmingStageDiagnostics d{};
    const bool ok = spk::gpu::highlight_boost(gpu.data(), w, h, boost_ev,
                                              boost_range, protect_ev, &d);
    if (!expect_gpu) {
        std::printf("  %s: gpu ok=%d reason=%s (identity, left to the CPU)\n",
                    label, ok ? 1 : 0, d.reason);
        check(!ok, label);
        // A refusal must leave the plane exactly as it was, because the CPU map
        // is about to run over the same memory.
        check(gpu == base, "a refused boost leaves the plane untouched");
        return;
    }
    if (!ok) {
        std::printf("[FAIL] %s: gpu refused (%s)\n", label, d.reason);
        ++g_failures;
        return;
    }
    if (d.engaged) g_engaged = true;

    double max_abs = 0.0, peak = 0.0;
    bool moved = false;
    for (size_t i = 0; i < comps; ++i) {
        max_abs = std::max(max_abs, std::fabs(cpu[i] - gpu[i]));
        peak = std::max(peak, std::fabs(cpu[i]));
        if (cpu[i] != base[i]) moved = true;
    }
    std::printf("  %s: max_abs %.3e  peak %.4g  bar %.3e\n",
                label, max_abs, peak, 1e-4 * peak);
    check(moved, "the CPU reference actually boosted something");
    check(max_abs <= 1e-4 * std::max(peak, 1.0), label);
}

// TWO-INPUT BLENDS (#222). The diffusion resolve and the unsharp mask.
//
// Both are one multiply-add per component, so the arithmetic is not what needs
// checking -- the ALIASING is. In both real call sites the destination IS the
// first source (`raw` and `lin_rgb` are updated in place), and the unsharp form
// reads its destination as well as writing it. A pass that bound the destination
// writeonly, or that ping-ponged the resident pair under an in-place caller,
// would produce a plausible-looking image built from the wrong half.
void blend_case(const char* label, bool unsharp, double amount) {
    const int w = 37, h = 11;
    const size_t comps = static_cast<size_t>(w) * h * 3;
    std::vector<double> a(comps), b(comps);
    for (size_t i = 0; i < comps; ++i) {
        const double t = static_cast<double>(i) / comps;
        a[i] = 0.05 + 2.0 * t;
        b[i] = 0.30 + 1.1 * std::sin(9.0 * t);   // the "filtered" plane
    }
    std::vector<double> cpu(comps);
    for (size_t i = 0; i < comps; ++i)
        cpu[i] = unsharp ? a[i] + amount * (a[i] - b[i])
                         : (1.0 - amount) * a[i] + amount * b[i];

    // IN PLACE, exactly as both callers do it.
    std::vector<double> gpu = a;
    spk::gpu::FilmingStageDiagnostics d{};
    const bool ok = spk::gpu::blend_mix(gpu.data(), b.data(), gpu.data(), w, h,
                                        amount, unsharp, &d);
    if (!ok) {
        std::printf("[FAIL] %s: refused (%s)\n", label, d.reason);
        ++g_failures;
        return;
    }
    if (d.engaged) g_engaged = true;
    double max_abs = 0.0, peak = 0.0;
    for (size_t i = 0; i < comps; ++i) {
        max_abs = std::max(max_abs, std::fabs(cpu[i] - gpu[i]));
        peak = std::max(peak, std::fabs(cpu[i]));
    }
    std::printf("  %s: max_abs %.3e  peak %.4g  bar %.3e\n",
                label, max_abs, peak, 1e-4 * peak);
    check(max_abs <= 1e-4 * std::max(peak, 1.0), label);

    // And the blend must actually be a blend: amount 0 has to reproduce `a`
    // exactly, which catches a mode that ignored its amount or swapped its
    // inputs -- both of which still look like a smooth image.
    std::vector<double> ident = a;
    spk::gpu::FilmingStageDiagnostics d0{};
    if (spk::gpu::blend_mix(ident.data(), b.data(), ident.data(), w, h, 0.0,
                            unsharp, &d0)) {
        double worst = 0.0;
        for (size_t i = 0; i < comps; ++i)
            worst = std::max(worst, std::fabs(ident[i] - a[i]));
        std::printf("  %s at amount 0: worst %.3e\n", label, worst);
        check(worst <= 1e-6, "amount 0 reproduces the first input");
    }
}

// THE EXPOSURE BRIDGE (#222). log10(max(raw, 0) + 1e-10).
//
// The "expose unfused" case above already exercises this indirectly, but only
// as a fallback would too: if the GPU pass REFUSED, the CPU loop would run and
// that case would still pass. So this one calls it directly and insists it
// engaged, and feeds it the values that decide the formula -- exact zero and a
// negative, where the +1e-10 floor is the whole point and a max() in the wrong
// place gives -inf or NaN instead of -10.
void log10_case() {
    const int w = 71, h = 5;
    const size_t comps = static_cast<size_t>(w) * h * 3;
    std::vector<double> raw(comps);
    for (size_t i = 0; i < comps; ++i) {
        const double t = static_cast<double>(i) / comps;
        raw[i] = 1e-6 * std::pow(10.0, 8.0 * t);
    }
    raw[0] = 0.0;            // the floor
    raw[1] = -3.5;           // clamped to 0 first, then floored
    raw[comps - 1] = 0.0;

    std::vector<float> cpu(comps), gpu(comps, -1.0f);
    for (size_t i = 0; i < comps; ++i)
        cpu[i] = static_cast<float>(std::log10(std::fmax(raw[i], 0.0) + 1e-10));

    spk::gpu::FilmingStageDiagnostics d{};
    const bool ok = spk::gpu::exposure_log10(raw.data(), gpu.data(), w, h, &d);
    if (!ok) {
        std::printf("[FAIL] log10: refused (%s)\n", d.reason);
        ++g_failures;
        return;
    }
    check(d.engaged, "the log10 bridge engaged rather than falling back");
    if (d.engaged) g_engaged = true;
    double max_abs = 0.0;
    bool finite = true;
    for (size_t i = 0; i < comps; ++i) {
        max_abs = std::max(max_abs, std::fabs(static_cast<double>(cpu[i]) - gpu[i]));
        finite = finite && std::isfinite(gpu[i]);
    }
    std::printf("  log10 bridge: max_abs %.3e (log10 units)  zero -> %.4f\n",
                max_abs, gpu[0]);
    check(finite, "no non-finite output at zero or negative input");
    check(max_abs <= 1e-4, "log10 bridge matches the CPU");
}

// THE PER-SUBLAYER DENSITIES (#223), against the engine's own interpolator.
//
// Run for BOTH film polarities, because positive film negates the query AND the
// axis to keep interp1d's ascending requirement, and the negation is split
// between host and shader -- the host flips the axis, the shader flips the
// query. Get one of the two wrong and negative film still passes while positive
// film silently reads the curve backwards.
//
// The axis is deliberately non-uniform and the queries run off both ends, so the
// clamped edges and the binary search are both exercised; and the nine outputs
// per pixel carry DIFFERENT curves, so a transposed (sublayer, channel) index
// shows up rather than cancelling.
void layers_case(bool positive) {
    const int w = 53, h = 9;
    const int npix = w * h;
    const int n = 29;
    std::vector<float> ndc(static_cast<size_t>(n) * 3);
    std::vector<float> curves(static_cast<size_t>(n) * 9);
    for (int k = 0; k < n; ++k) {
        const double t = static_cast<double>(k) / (n - 1);
        for (int ch = 0; ch < 3; ++ch)
            ndc[k * 3 + ch] = static_cast<float>(0.05 + 2.6 * t * t + 0.11 * ch * t);
        for (int L = 0; L < 3; ++L)
            for (int ch = 0; ch < 3; ++ch)
                curves[static_cast<size_t>(k) * 9 + L * 3 + ch] =
                    static_cast<float>(0.02 + (0.7 + 0.35 * L + 0.13 * ch) * std::pow(t, 1.0 + 0.2 * L));
    }
    std::vector<float> density(static_cast<size_t>(npix) * 3);
    for (int p = 0; p < npix; ++p)
        for (int ch = 0; ch < 3; ++ch)
            density[p * 3 + ch] = static_cast<float>(
                -0.5 + 4.0 * (static_cast<double>(p) / npix) + 0.07 * ch);

    std::vector<float> cpu(static_cast<size_t>(npix) * 9, -1.0f);
    spk::interp_density_cmy_layers(density.data(), npix, ndc.data(), curves.data(),
                                   n, positive, cpu.data());

    std::vector<float> gpu(static_cast<size_t>(npix) * 9, -2.0f);
    spk::gpu::FilmingStageDiagnostics d{};
    const bool ok = spk::gpu::grain_layers(density.data(), npix, w, h, ndc.data(),
                                           curves.data(), n, positive, gpu.data(), &d);
    const char* pol = positive ? "positive film" : "negative film";
    if (!ok) {
        std::printf("[FAIL] grain layers (%s): refused (%s)\n", pol, d.reason);
        ++g_failures;
        return;
    }
    if (d.engaged) g_engaged = true;
    double max_abs = 0.0, peak = 0.0;
    for (size_t i = 0; i < cpu.size(); ++i) {
        max_abs = std::max(max_abs, std::fabs(static_cast<double>(cpu[i]) - gpu[i]));
        peak = std::max(peak, std::fabs(static_cast<double>(cpu[i])));
    }
    std::printf("  grain layers (%s): max_abs %.3e  peak %.4g  bar %.3e\n",
                pol, max_abs, peak, 1e-4 * peak);
    check(max_abs <= 1e-4 * std::max(peak, 1.0),
          positive ? "grain layers match the CPU (positive film)"
                   : "grain layers match the CPU (negative film)");
}

// THE PER-CHANNEL CONSTANT SUBTRACT (#223): grain's `out -= density_min[c]`.
//
// Trivial arithmetic, so the gate is built around the two ways it goes wrong
// rather than around the result:
//
//   - THREE DIFFERENT CONSTANTS, well separated. A pass that used one scalar for
//     all three channels, or that read the push block at the wrong offset, is
//     still perfectly smooth and still shifts the image -- it just shifts two
//     channels by the wrong amount, which reads as a colour cast and survives
//     any mean or noise check.
//   - IN PLACE, because that is how grain calls it (src and dst are both `out`).
void subc_case() {
    const int w = 67, h = 5;
    const int npix = w * h;
    const double k[3] = {0.07, 0.31, 0.94};   // deliberately far apart
    std::vector<float> base(static_cast<size_t>(npix) * 3);
    for (int p = 0; p < npix; ++p)
        for (int c = 0; c < 3; ++c)
            base[p * 3 + c] = static_cast<float>(0.5 + 2.0 * (static_cast<double>(p) / npix)
                                                 + 0.25 * c);

    std::vector<float> cpu(base.size());
    for (int p = 0; p < npix; ++p)
        for (int c = 0; c < 3; ++c)
            cpu[p * 3 + c] = static_cast<float>(
                static_cast<double>(base[p * 3 + c]) - k[c]);

    std::vector<float> gpu = base;             // IN PLACE, as grain does it
    spk::gpu::FilmingStageDiagnostics d{};
    const bool ok = spk::gpu::subtract_per_channel(gpu.data(), gpu.data(), w, h, k, &d);
    if (!ok) {
        std::printf("[FAIL] per-channel subtract: refused (%s)\n", d.reason);
        ++g_failures;
        return;
    }
    if (d.engaged) g_engaged = true;
    double max_abs = 0.0;
    double per_ch[3] = {0.0, 0.0, 0.0};
    for (int p = 0; p < npix; ++p)
        for (int c = 0; c < 3; ++c) {
            const double e = std::fabs(static_cast<double>(cpu[p * 3 + c]) - gpu[p * 3 + c]);
            max_abs = std::max(max_abs, e);
            per_ch[c] = std::max(per_ch[c], e);
        }
    std::printf("  per-channel subtract: max_abs %.3e  per channel %.3e / %.3e / %.3e\n",
                max_abs, per_ch[0], per_ch[1], per_ch[2]);
    check(max_abs <= 1e-6, "per-channel subtract matches the CPU");
    // Reported per channel as well as overall, because the failure this is for
    // lands on ONE channel and an overall maximum says only "something moved".
    check(per_ch[0] <= 1e-6 && per_ch[1] <= 1e-6 && per_ch[2] <= 1e-6,
          "every channel used its own constant");
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
    log10_case();
    layers_case(/*positive=*/false);
    layers_case(/*positive=*/true);
    subc_case();
    // A real boost, then the two identities the CPU short-circuits: a
    // non-positive boost_ev, and a protect point at or above the maximum. Both
    // must be REFUSED rather than reproduced -- an identity is cheaper to skip
    // than to dispatch, and the refusal has to leave the plane untouched.
    boost_case("boost_ev 1.0, range 0.5", 1.0, 0.5, 0.0, true);
    boost_case("boost_ev 2.5, range 0.9, protect +2 EV", 2.5, 0.9, 2.0, true);
    boost_case("boost_ev 0 (identity)", 0.0, 0.5, 0.0, false);
    blend_case("diffusion resolve, p_s 0.35", false, 0.35);
    blend_case("unsharp mask, amount 0.8", true, 0.8);
    boost_case("protect_ev far above the maximum (identity)", 1.0, 0.5, 12.0, false);
    residency_case();
    refusal_cases();

    std::printf("engaged=%d\n", g_engaged ? 1 : 0);
    if (g_failures == 0) std::printf("test_filming_stage_gpu: ALL OK\n");
    else std::printf("test_filming_stage_gpu: %d FAIL\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
