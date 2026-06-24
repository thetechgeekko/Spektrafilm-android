/*
 * Spektrafilm for Android — host parity test for the INPUT gamut compression
 * (model/gamut_compression.cpp::spectral_locus_xy / compress_pixel_xy and
 * kernels/spectral_upsampling.cpp::remap_tc_lut_for_compression).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The oracle's radial xy gamut compression toward the visible spectral locus
 * (utils/gamut_compression.py::compress_xy_radial, the production algorithm="xy") and
 * its tc_lut remap-resample bake (remap_tc_lut_for_compression) ARE the spec for the
 * native input-side port. This checks the C++ port against tests/gamut_in_cases.bin —
 * three sections captured directly from the oracle (tools/parity/gen_gamut_in_golden.py):
 *   1. spectral_locus_xy() — the 66-vertex closed CIE 1931 2deg locus polygon.
 *   2. compress_xy_radial() — xy in/out across in/out-of-locus + at-white points and
 *      several knee triples, around several reference whites.
 *   3. remap_tc_lut_for_compression() — the full LUT bake on synthetic tc_luts.
 * The port is the same double-precision math, so the match is essentially exact (well
 * inside parity tolerance). This gates ONLY the opt-in input path; the engine default
 * (InputGamutCompress::kOff) applies no bake and stays byte-identical.
 *
 * Binary format (little-endian):
 *   int32 nverts; f64 locus[nverts*2]
 *   int32 num_xy_cases; per case: f64 white_xy[2], f64 t,l,p, int32 npix,
 *                                 f64 xy_in[npix*2], f64 xy_out[npix*2]
 *   int32 num_lut_cases; per case: f64 white_xy[2], f64 t,l,p, int32 H, int32 W,
 *                                  f64 lut_in[H*W*3], f64 lut_out[H*W*3]
 *
 * Build (host): full source set per tests/README, e.g.
 *   g++ -std=c++17 -O2 -pthread -I. -I <tools/parity> -DSPK_TEST_DIR=... \
 *     tests/test_gamut_in_xy.cpp spektra.cpp kernels/*.cpp io/*.cpp \
 *     model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp -o /tmp/test_gamut_in_xy
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "io/npy_lut.h"
#include "kernels/spectral_upsampling.h"
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
    std::string path = argc > 1 ? argv[1] : (std::string(SPK_TEST_DIR) + "/gamut_in_cases.bin");
    std::ifstream in(path, std::ios::binary);
    if (!in) { std::fprintf(stderr, "cannot open %s\n", path.c_str()); return 2; }

    const double tol = 1e-9;  // same double-precision formula as the oracle -> ~exact
    // A NaN diff (got - exp) is silently NOT > max_abs, so track NaN disagreement
    // separately: the oracle and C++ must be NaN at exactly the same cells. The
    // committed golden uses only realistic (>=2 in each axis) LUT shapes and interior
    // whites, so it is NaN-free by construction; this guard catches any future
    // divergence (e.g. a degenerate 1xN LUT where the oracle hits 0/0).
    int nan_mismatch = 0;

    // --- Section 1: spectral locus polygon ---
    int32_t nverts = 0;
    if (!read_n(in, &nverts, 1) || nverts <= 0) {
        std::fprintf(stderr, "bad nverts\n"); return 2;
    }
    std::vector<double> locus(static_cast<size_t>(nverts) * 2);
    if (!read_n(in, locus.data(), locus.size())) {
        std::fprintf(stderr, "truncated locus\n"); return 2;
    }
    {
        const double* cxx = nullptr;
        int n = spk::spectral_locus_xy(&cxx);
        bool count_ok = (n == nverts);
        double lmax = 0.0;
        if (count_ok)
            for (size_t k = 0; k < locus.size(); ++k)
                lmax = std::fmax(lmax, std::fabs(cxx[k] - locus[k]));
        chk(count_ok, "spectral_locus_xy vertex count matches oracle");
        chk(count_ok && lmax <= 1e-12, "spectral_locus_xy matches the oracle polygon");
        std::printf("    %d verts -> max_abs=%.3e\n", nverts, lmax);
    }

    // --- Section 2: compress_xy_radial ---
    int32_t num_xy = 0;
    if (!read_n(in, &num_xy, 1) || num_xy < 0) {
        std::fprintf(stderr, "bad xy case count\n"); return 2;
    }
    double xy_max = 0.0; int xy_worst = -1; size_t xy_points = 0;
    for (int c = 0; c < num_xy; ++c) {
        double white[2]; double t = 0, l = 0, p = 0; int32_t npix = 0;
        if (!read_n(in, white, 2) || !read_n(in, &t, 1) || !read_n(in, &l, 1) ||
            !read_n(in, &p, 1) || !read_n(in, &npix, 1) || npix < 1) {
            std::fprintf(stderr, "truncated xy header at case %d\n", c); return 2;
        }
        std::vector<double> xin(static_cast<size_t>(npix) * 2);
        std::vector<double> xexp(static_cast<size_t>(npix) * 2);
        if (!read_n(in, xin.data(), xin.size()) || !read_n(in, xexp.data(), xexp.size())) {
            std::fprintf(stderr, "truncated xy payload at case %d\n", c); return 2;
        }
        for (int px = 0; px < npix; ++px) {
            double got[2];
            spk::compress_pixel_xy(&xin[static_cast<size_t>(px) * 2], white, t, l, p, got);
            for (int ch = 0; ch < 2; ++ch) {
                double gv = got[ch], ev = xexp[static_cast<size_t>(px) * 2 + ch];
                if (std::isnan(gv) != std::isnan(ev)) ++nan_mismatch;
                double d = std::fabs(gv - ev);
                if (d > xy_max) { xy_max = d; xy_worst = c; }
            }
        }
        xy_points += static_cast<size_t>(npix);
    }
    chk(xy_max <= tol, "compress_pixel_xy matches the oracle golden");
    std::printf("    %d cases, %zu points -> max_abs=%.3e (tol %.0e) worst case=%d\n",
                num_xy, xy_points, xy_max, tol, xy_worst);

    // --- Section 3: remap_tc_lut_for_compression (the bake) ---
    int32_t num_lut = 0;
    if (!read_n(in, &num_lut, 1) || num_lut < 0) {
        std::fprintf(stderr, "bad lut case count\n"); return 2;
    }
    double lut_max = 0.0; int lut_worst = -1; size_t lut_cells = 0;
    for (int c = 0; c < num_lut; ++c) {
        double white[2]; double t = 0, l = 0, p = 0; int32_t H = 0, W = 0;
        if (!read_n(in, white, 2) || !read_n(in, &t, 1) || !read_n(in, &l, 1) ||
            !read_n(in, &p, 1) || !read_n(in, &H, 1) || !read_n(in, &W, 1) ||
            H < 1 || W < 1) {
            std::fprintf(stderr, "truncated lut header at case %d\n", c); return 2;
        }
        size_t n = static_cast<size_t>(H) * W * 3;
        std::vector<double> lin(n), lexp(n);
        if (!read_n(in, lin.data(), n) || !read_n(in, lexp.data(), n)) {
            std::fprintf(stderr, "truncated lut payload at case %d\n", c); return 2;
        }
        spk::NdArray lut;
        lut.shape = {static_cast<int>(H), static_cast<int>(W), 3};
        lut.data = lin;
        spk::remap_tc_lut_for_compression(lut, white, t, l, p);
        for (size_t k = 0; k < n; ++k) {
            if (std::isnan(lut.data[k]) != std::isnan(lexp[k])) ++nan_mismatch;
            double d = std::fabs(lut.data[k] - lexp[k]);
            if (d > lut_max) { lut_max = d; lut_worst = c; }
        }
        lut_cells += static_cast<size_t>(H) * W;
    }
    chk(lut_max <= tol, "remap_tc_lut_for_compression matches the oracle golden");
    std::printf("    %d cases, %zu cells -> max_abs=%.3e (tol %.0e) worst case=%d\n",
                num_lut, lut_cells, lut_max, tol, lut_worst);

    chk(nan_mismatch == 0, "no NaN disagreement between C++ and the oracle");

    std::printf("%s\n", g_fail == 0 ? "ALL PASS" : "FAIL");
    return g_fail;
}
