/*
 * Spektrafilm for Android — host parity test for np_interp_array
 * (model/couplers.cpp), the numpy.interp port used by the DIR-coupler
 * non-monotonic interpolation.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The DIR-coupler axis le0 = le - silver_curve @ M can be NON-MONOTONIC, where a
 * plain ascending binary search diverges from numpy.interp. np_interp_array ports
 * numpy's order-dependent binary_search_with_guess so it reproduces the oracle.
 * This checks it against tests/np_interp_cases.bin — 82 cases (77 non-monotonic),
 * each np.interp(x, xp, fp) captured directly from numpy
 * (tools/parity/gen_np_interp_golden.py). The port is the same algorithm as
 * numpy, so the match is essentially exact (well inside parity tolerance).
 *
 * Binary format (little-endian): int32 num_cases; per case int32 n, int32 nx,
 * f64 xp[n], f64 fp[n], f64 x[nx], f64 expected[nx].
 *
 * Build (host): full source set per tests/README, e.g.
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_np_interp.cpp spektra.cpp kernels/*.cpp io/*.cpp \
 *     model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_np_interp
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "model/couplers.h"

namespace {

const char* kCasesBin =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "np_interp_cases.bin";

template <typename T>
bool read_n(std::ifstream& in, T* dst, size_t count) {
    in.read(reinterpret_cast<char*>(dst), static_cast<std::streamsize>(count * sizeof(T)));
    return static_cast<size_t>(in.gcount()) == count * sizeof(T);
}

}  // namespace

int main(int argc, char** argv) {
    std::string path = argc > 1 ? argv[1] : kCasesBin;
    std::ifstream in(path, std::ios::binary);
    if (!in) { std::fprintf(stderr, "cannot open %s\n", path.c_str()); return 2; }

    int32_t num_cases = 0;
    if (!read_n(in, &num_cases, 1) || num_cases <= 0) {
        std::fprintf(stderr, "bad case count\n"); return 2;
    }

    double max_abs = 0.0;
    int worst_case = -1;
    size_t total_points = 0;
    for (int c = 0; c < num_cases; ++c) {
        int32_t n = 0, nx = 0;
        if (!read_n(in, &n, 1) || !read_n(in, &nx, 1) || n < 1 || nx < 1) {
            std::fprintf(stderr, "truncated header at case %d\n", c); return 2;
        }
        std::vector<double> xp(n), fp(n), x(nx), expected(nx);
        if (!read_n(in, xp.data(), n) || !read_n(in, fp.data(), n) ||
            !read_n(in, x.data(), nx) || !read_n(in, expected.data(), nx)) {
            std::fprintf(stderr, "truncated payload at case %d\n", c); return 2;
        }
        std::vector<double> got(nx);
        spk::np_interp_array(x.data(), nx, xp.data(), fp.data(), n, got.data());
        for (int i = 0; i < nx; ++i) {
            double d = std::fabs(got[i] - expected[i]);
            if (d > max_abs) { max_abs = d; worst_case = c; }
        }
        total_points += static_cast<size_t>(nx);
    }

    const double tol = 1e-9;  // same algorithm as numpy -> essentially exact
    bool pass = max_abs <= tol;
    std::printf("[np_interp_array] %d cases, %zu points -> max_abs=%.3e (tol %.0e) "
                "worst case=%d -> %s\n",
                num_cases, total_points, max_abs, tol, worst_case,
                pass ? "PASS" : "FAIL");
    std::printf("%s\n", pass ? "ALL PASS" : "FAIL");
    return pass ? 0 : 1;
}
