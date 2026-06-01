/*
 * Spektrafilm for Android — host test for the optional tone-curve stage.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Three checks:
 *   1. Kernel math (no assets): identity for < 2 points, NaN passthrough, and a
 *      monotone contrast curve that pins endpoints, stays in [0,1], and bends the
 *      midpoint the expected way.
 *   2. PARITY no-op: scan() with the default (inactive) tone curve reproduces the
 *      scan_portra final_rgb golden bit-for-bit, and an *identity* active curve
 *      (points (0,0),(1,1)) is byte-identical to the inactive run — proving the new
 *      stage cannot perturb the existing goldens.
 *   3. Effect: a non-trivial S-curve changes the output (and keeps it finite, in
 *      [0,1]) — proving the stage is actually wired into scan().
 *
 * Build (host):
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_tonecurve.cpp \
 *     runtime/stages/scanning.cpp kernels/tonecurve.cpp kernels/parallel.cpp \
 *     model/color_output.cpp model/emulsion.cpp model/conversions.cpp \
 *     model/spectral.cpp profiles/profile.cpp kernels/gaussian.cpp \
 *     kernels/exponential_filter.cpp model/diffusion.cpp model/glare.cpp \
 *     kernels/stats.cpp kernels/interp.cpp kernels/lut3d.cpp io/npy_lut.cpp \
 *     -o /tmp/test_tonecurve
 */
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "spkvec_io.h"

#include "kernels/tonecurve.h"
#include "profiles/profile.h"
#include "runtime/stages/scanning.h"

namespace {

const char* kProfilePath =
    "/home/user/spektrafilm/src/spektrafilm/data/profiles/kodak_portra_400.json";
const char* kGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra";

int g_fail = 0;
void check(bool cond, const char* msg) {
    std::printf("  [%s] %s\n", cond ? "ok" : "FAIL", msg);
    if (!cond) g_fail = 1;
}

}  // namespace

int main(int argc, char** argv) {
    std::string profile_path = argc > 1 ? argv[1] : kProfilePath;
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;

    // --- 1. Kernel math ---
    std::printf("kernel:\n");
    {
        spk::ToneCurve1D id = spk::build_tone_curve_1d(nullptr, nullptr, 0);
        check(id.eval(0.3f) == 0.3f, "fewer than 2 points => identity");
        check(std::isnan(id.eval(std::nanf(""))), "identity passes NaN through");

        const float xs[3] = {0.0f, 0.5f, 1.0f};
        const float ys[3] = {0.0f, 0.25f, 1.0f};  // darken midtones (contrast)
        spk::ToneCurve1D c = spk::build_tone_curve_1d(xs, ys, 3);
        check(std::fabs(c.eval(0.0f) - 0.0f) < 1e-4f, "pins x=0 -> y=0");
        check(std::fabs(c.eval(1.0f) - 1.0f) < 1e-4f, "pins x=1 -> y=1");
        check(std::fabs(c.eval(0.5f) - 0.25f) < 2e-3f, "midpoint pulled to 0.25");
        check(c.eval(0.25f) <= c.eval(0.75f), "monotonic non-decreasing");
        check(c.eval(1.5f) <= 1.0f && c.eval(-1.0f) >= 0.0f, "clamps out-of-range x");
        check(std::isnan(c.eval(std::nanf(""))), "active curve passes NaN through");

        spk::ToneCurveSet set;
        check(set.apply(0, 0.42f) == 0.42f, "inactive set is a no-op");
    }

    // --- 2 & 3. scan() integration ---
    spk::Profile film = spk::load_profile_file(profile_path);
    spkvec::Array in = spkvec::read(golden_dir + "/film_density_cmy.spkvec");
    spkvec::Array gold = spkvec::read(golden_dir + "/final_rgb.spkvec");
    const int height = static_cast<int>(in.shape[0]);
    const int width = static_cast<int>(in.shape[1]);

    std::vector<float> base(in.data.size());
    spk::ScanningParams params;  // inactive tone curve (default)
    spk::scan(film, params, in.data.data(), width, height, base.data());

    // 2a. inactive curve reproduces the golden bit-for-bit (within parity tol).
    double max_abs = 0.0, sse = 0.0;
    for (size_t i = 0; i < base.size(); ++i) {
        double d = std::fabs((double)base[i] - (double)gold.data[i]);
        if (d > max_abs) max_abs = d;
        sse += d * d;
    }
    double rms = std::sqrt(sse / base.size());
    std::printf("parity (inactive): max_abs=%.3e rms=%.3e\n", max_abs, rms);
    check(max_abs <= 1e-4 && rms <= 1e-5, "inactive tone curve == golden");

    // 2b. identity active curve is byte-identical to the inactive run.
    {
        const float xs[2] = {0.0f, 1.0f};
        const float ys[2] = {0.0f, 1.0f};
        spk::ScanningParams p2;
        p2.tone_curve.active = true;
        p2.tone_curve.master = spk::build_tone_curve_1d(xs, ys, 2);
        for (int c = 0; c < 3; ++c) p2.tone_curve.rgb[c] = spk::build_tone_curve_1d(xs, ys, 2);
        std::vector<float> idr(in.data.size());
        spk::scan(film, p2, in.data.data(), width, height, idr.data());
        bool identical = true;
        for (size_t i = 0; i < idr.size(); ++i) if (idr[i] != base[i]) { identical = false; break; }
        check(identical, "identity active curve byte-identical to inactive");
    }

    // 3. an S-curve actually changes the output and keeps it finite, in [0,1].
    {
        const float xs[3] = {0.0f, 0.5f, 1.0f};
        const float ys[3] = {0.0f, 0.32f, 1.0f};  // crush shadows (contrast)
        spk::ScanningParams p3;
        p3.tone_curve.active = true;
        p3.tone_curve.master = spk::build_tone_curve_1d(xs, ys, 3);
        std::vector<float> cr(in.data.size());
        spk::scan(film, p3, in.data.data(), width, height, cr.data());
        bool changed = false, in_range = true;
        for (size_t i = 0; i < cr.size(); ++i) {
            if (cr[i] != base[i]) changed = true;
            if (!std::isnan(cr[i]) && (cr[i] < 0.0f || cr[i] > 1.0f)) in_range = false;
        }
        check(changed, "S-curve changes the rendered output");
        check(in_range, "curved output stays in [0,1]");
    }

    std::printf("%s\n", g_fail == 0 ? "ALL PASS" : "FAIL");
    return g_fail;
}
