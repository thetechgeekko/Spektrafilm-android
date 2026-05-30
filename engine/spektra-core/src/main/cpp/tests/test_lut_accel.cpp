/*
 * Spektrafilm for Android — host parity test for the OPT-IN 3D LUT acceleration.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Exercises kernels/lut3d.h (the native port of the oracle's PCHIP 3D LUT path:
 * compute_with_lut + apply_lut_3d method='pchip'). The settings.use_scanner_lut /
 * use_enlarger_lut / lut_resolution are an OPT-IN acceleration; the default
 * engine path does NOT use a LUT and stays bit-exact, so this test is a separate
 * stage-isolation gate, NOT part of the default-params parity suite.
 *
 * Two assertions:
 *  (1) INTERPOLATOR PARITY: load the oracle-built LUT (lut.f64) and the
 *      normalized input (input_norm.f64), run the native PCHIP interpolator, and
 *      assert the output matches the oracle's pchip LUT output (lut_out.spkvec)
 *      to ~float32 precision (max_abs <= 1e-4, rms <= 1e-5). This proves the
 *      native interpolator reproduces the oracle's PCHIP algorithm.
 *  (2) LUT-BUILD PARITY + ACCELERATION TOLERANCE: rebuild the LUT natively from
 *      the SAME analytic function the generator used (build_lut_3d), confirm it
 *      matches the oracle LUT, and report the native-LUT vs. DIRECT error
 *      (direct.spkvec). The LUT-vs-direct error is the documented acceleration
 *      tolerance: LUT interpolation is NOT bit-exact vs. the direct path (that is
 *      EXPECTED and acceptable for an opt-in accelerator); it is reported and
 *      bounded, not gated at the 1e-4 parity level.
 *
 * Build (host) — from the cpp root:
 *   g++ -std=c++17 -O2 -I . -I <tools/parity> \
 *     tests/test_lut_accel.cpp kernels/lut3d.cpp -o /tmp/test_lut_accel
 * Run (argv[1] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_lut_accel [goldens_root]
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "spkvec_io.h"

#include "kernels/lut3d.h"

namespace {

const char* kGoldensRoot =
    "/home/user/Spectrafilmandroid/tools/parity/goldens";

// Must match tools/parity/gen_lut_golden.py.
constexpr int kSize = 48;
constexpr int kSteps = 17;
const double kXmin[3] = {-0.12, -0.10, -0.08};
const double kXmax[3] = {2.4, 2.2, 2.0};

// EXACT port of gen_lut_golden.analytic_transform (one point at a time).
void analytic_transform(const double in[3], double out[3], void* /*ctx*/) {
    double r = in[0], g = in[1], b = in[2];
    out[0] = -1.0 * r - 0.15 * g - 0.05 * b + 0.10 * std::tanh(r);
    out[1] = -0.10 * r - 1.0 * g - 0.10 * b + 0.10 * std::tanh(g);
    out[2] = -0.05 * r - 0.15 * g - 1.0 * b + 0.10 * std::tanh(b);
}

struct Metrics { double max_abs; double rms; size_t argmax; };

Metrics compare(const std::vector<double>& got, const std::vector<float>& gold) {
    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < gold.size(); ++i) {
        float g32 = static_cast<float>(got[i]);  // store float32 like the golden
        double d = std::fabs(static_cast<double>(g32) -
                             static_cast<double>(gold[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    return {max_abs, std::sqrt(sse / static_cast<double>(gold.size())), argmax};
}

}  // namespace

int main(int argc, char** argv) {
    std::string root = (argc > 1) ? argv[1] : kGoldensRoot;
    std::string dir = root + "/lut_accel";

    const int w = kSize, h = kSize;
    const size_t npix = static_cast<size_t>(w) * h;
    const size_t n = npix * 3;
    const size_t lut_n = static_cast<size_t>(kSteps) * kSteps * kSteps * 3;

    // Load normalized input (float64 (h,w,3)).
    std::vector<double> norm(n);
    {
        std::ifstream in(dir + "/input_norm.f64", std::ios::binary);
        if (!in) { std::fprintf(stderr, "cannot open input_norm.f64\n"); return 2; }
        in.read(reinterpret_cast<char*>(norm.data()),
                static_cast<std::streamsize>(n * sizeof(double)));
        if (in.gcount() != static_cast<std::streamsize>(n * sizeof(double))) {
            std::fprintf(stderr, "input_norm size mismatch\n"); return 2;
        }
    }
    // Load oracle-built LUT (float64 (steps,steps,steps,3)).
    spk::Lut3D oracle_lut;
    oracle_lut.steps = kSteps;
    for (int c = 0; c < 3; ++c) { oracle_lut.xmin[c] = kXmin[c]; oracle_lut.xmax[c] = kXmax[c]; }
    oracle_lut.data.resize(lut_n);
    {
        std::ifstream in(dir + "/lut.f64", std::ios::binary);
        if (!in) { std::fprintf(stderr, "cannot open lut.f64\n"); return 2; }
        in.read(reinterpret_cast<char*>(oracle_lut.data.data()),
                static_cast<std::streamsize>(lut_n * sizeof(double)));
        if (in.gcount() != static_cast<std::streamsize>(lut_n * sizeof(double))) {
            std::fprintf(stderr, "lut.f64 size mismatch\n"); return 2;
        }
    }

    spkvec::Array gold_lut_out = spkvec::read(dir + "/lut_out.spkvec");
    spkvec::Array gold_direct = spkvec::read(dir + "/direct.spkvec");
    if (gold_lut_out.data.size() != n || gold_direct.data.size() != n) {
        std::fprintf(stderr, "golden size mismatch\n"); return 2;
    }

    std::printf("LUT: %d^3x3, image %dx%dx3, bounds [%.3f,%.3f]x[%.3f,%.3f]x[%.3f,%.3f]\n",
                kSteps, w, h, kXmin[0], kXmax[0], kXmin[1], kXmax[1], kXmin[2], kXmax[2]);

    // (1) Interpolator parity: native PCHIP on the ORACLE LUT vs oracle output.
    std::vector<double> native_out(n);
    spk::apply_lut_3d_pchip_normalized(oracle_lut, norm.data(), w, h,
                                       native_out.data());
    Metrics m_interp = compare(native_out, gold_lut_out.data);
    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass_interp = (m_interp.max_abs <= tol_max_abs) && (m_interp.rms <= tol_rms);
    std::printf("[lut_accel interp-parity] max_abs=%.6e (tol %.0e) rms=%.6e (tol %.0e) "
                "worst idx=%zu -> %s\n",
                m_interp.max_abs, tol_max_abs, m_interp.rms, tol_rms, m_interp.argmax,
                pass_interp ? "PASS" : "FAIL");

    // (2a) LUT-build parity: native-built LUT vs oracle LUT (element-wise).
    spk::Lut3D native_lut =
        spk::build_lut_3d(kXmin, kXmax, kSteps, {}, &analytic_transform, nullptr);
    double lut_build_max_abs = 0.0;
    for (size_t i = 0; i < lut_n; ++i) {
        double d = std::fabs(native_lut.data[i] - oracle_lut.data[i]);
        if (d > lut_build_max_abs) lut_build_max_abs = d;
    }
    bool pass_build = lut_build_max_abs <= 1e-9;  // same math in double -> ~exact
    std::printf("[lut_accel build-parity] max_abs=%.6e (tol 1e-9) -> %s\n",
                lut_build_max_abs, pass_build ? "PASS" : "FAIL");

    // (2b) Acceleration tolerance: native LUT(pchip) vs DIRECT (reported, bounded).
    std::vector<double> native_from_built(n);
    spk::apply_lut_3d_pchip_normalized(native_lut, norm.data(), w, h,
                                       native_from_built.data());
    Metrics m_accel = compare(native_from_built, gold_direct.data);
    // Opt-in accelerator: LUT interpolation is NOT bit-exact vs direct. We only
    // require it stays within a sane, documented band for steps=17 on this
    // density-like transform (the oracle's own LUT-vs-direct error is ~5e-5).
    const double accel_band = 5e-3;
    bool accel_ok = m_accel.max_abs <= accel_band;
    std::printf("[lut_accel lut-vs-direct] max_abs=%.6e (acceleration band %.0e, "
                "NOT bit-exact by design) rms=%.6e -> %s\n",
                m_accel.max_abs, accel_band, m_accel.rms,
                accel_ok ? "WITHIN BAND" : "OUT OF BAND");

    bool all = pass_interp && pass_build && accel_ok;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
