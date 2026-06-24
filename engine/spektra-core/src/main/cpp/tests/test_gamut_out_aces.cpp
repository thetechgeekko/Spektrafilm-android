/*
 * Spektrafilm for Android — host parity test for the ACES RGC v1.3 output gamut
 * compression (model/gamut_compression.cpp::compress_rgb_aces_rgc / reinhard_knee).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The oracle's per-channel ACES Reference Gamut Compression v1.3
 * (utils/gamut_compression.py::compress_rgb_aces_rgc) IS the spec for the native
 * port. This checks the C++ port against tests/gamut_aces_cases.bin — pixels spanning
 * in/out-of-gamut, achromatic, super-white and near-black across several knee triples,
 * each output captured directly from the oracle (tools/parity/gen_gamut_aces_golden.py).
 * The port is the same double-precision formula, so the match is essentially exact
 * (well inside parity tolerance). Also pins the shared reinhard_knee helper against
 * hand-captured oracle values. This gates ONLY the opt-in kAcesRgc path; the engine
 * default (kLegacyClip) applies no compression and stays byte-identical.
 *
 * Binary format (little-endian): int32 num_cases; per case f64 threshold, f64 limit,
 * f64 power, int32 npix, f64 rgb_in[npix*3], f64 expected_out[npix*3].
 *
 * Build (host): full source set per tests/README, e.g.
 *   g++ -std=c++17 -O2 -pthread -I. -I <tools/parity> -DSPK_TEST_DIR=... \
 *     tests/test_gamut_out_aces.cpp spektra.cpp kernels/*.cpp io/*.cpp \
 *     model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp -o /tmp/test_gamut_out_aces
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "model/gamut_compression.h"

#ifndef SPK_TEST_DIR
#define SPK_TEST_DIR "."
#endif

namespace {

template <typename T>
bool read_n(std::ifstream& in, T* dst, size_t count) {
    in.read(reinterpret_cast<char*>(dst), static_cast<std::streamsize>(count * sizeof(T)));
    return static_cast<size_t>(in.gcount()) == count * sizeof(T);
}

int g_fail = 0;
void chk(bool c, const char* m) {
    std::printf("  [%s] %s\n", c ? "ok" : "FAIL", m);
    if (!c) g_fail = 1;
}

}  // namespace

int main(int argc, char** argv) {
    std::string path = argc > 1 ? argv[1] : (std::string(SPK_TEST_DIR) + "/gamut_aces_cases.bin");
    std::ifstream in(path, std::ios::binary);
    if (!in) { std::fprintf(stderr, "cannot open %s\n", path.c_str()); return 2; }

    int32_t num_cases = 0;
    if (!read_n(in, &num_cases, 1) || num_cases <= 0) {
        std::fprintf(stderr, "bad case count\n"); return 2;
    }

    double max_abs = 0.0;
    int worst_case = -1;
    size_t total_pixels = 0;
    for (int c = 0; c < num_cases; ++c) {
        double t = 0.0, l = 0.0, p = 0.0;
        int32_t npix = 0;
        if (!read_n(in, &t, 1) || !read_n(in, &l, 1) || !read_n(in, &p, 1) ||
            !read_n(in, &npix, 1) || npix < 1) {
            std::fprintf(stderr, "truncated header at case %d\n", c); return 2;
        }
        std::vector<double> rgb_in(static_cast<size_t>(npix) * 3);
        std::vector<double> expected(static_cast<size_t>(npix) * 3);
        if (!read_n(in, rgb_in.data(), rgb_in.size()) ||
            !read_n(in, expected.data(), expected.size())) {
            std::fprintf(stderr, "truncated payload at case %d\n", c); return 2;
        }
        for (int px = 0; px < npix; ++px) {
            const double* in_px = rgb_in.data() + static_cast<size_t>(px) * 3;
            double got[3];
            spk::compress_pixel_aces_rgc(in_px, t, l, p, got);
            for (int ch = 0; ch < 3; ++ch) {
                double d = std::fabs(got[ch] - expected[static_cast<size_t>(px) * 3 + ch]);
                if (d > max_abs) { max_abs = d; worst_case = c; }
            }
        }
        total_pixels += static_cast<size_t>(npix);
    }

    const double tol = 1e-9;  // same double-precision formula as the oracle -> ~exact
    chk(max_abs <= tol, "compress_rgb_aces_rgc matches the oracle golden");
    std::printf("    %d cases, %zu pixels -> max_abs=%.3e (tol %.0e) worst case=%d\n",
                num_cases, total_pixels, max_abs, tol, worst_case);

    // Pin the shared reinhard_knee helper against hand-captured oracle values
    // (gamut_compression.py::reinhard_knee, default knee threshold=0, limit=1, power=6).
    {
        struct { double d, want; } probes[] = {
            {0.0, 0.0}, {0.5, 0.498709652323}, {1.0, 0.89089871814},
            {1.5, 0.98607297601}, {3.0, 0.999771559074},
        };
        double knee_max = 0.0;
        for (auto& pr : probes)
            knee_max = std::fmax(knee_max, std::fabs(spk::reinhard_knee(pr.d, 0.0, 1.0, 6.0) - pr.want));
        chk(knee_max <= 1e-9, "reinhard_knee matches the oracle at probe points");
        std::printf("    reinhard_knee probe max_abs=%.3e\n", knee_max);
        // Identity at/below threshold (oracle mask is strict d > threshold).
        chk(spk::reinhard_knee(0.2, 0.2, 1.0, 6.0) == 0.2, "reinhard_knee identity at threshold");
        chk(spk::reinhard_knee(-0.3, 0.0, 1.0, 6.0) == -0.3, "reinhard_knee identity below threshold");
    }

    std::printf("%s\n", g_fail == 0 ? "ALL PASS" : "FAIL");
    return g_fail;
}
