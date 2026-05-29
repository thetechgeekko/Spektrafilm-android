/*
 * SpectraFilm for Android — golden-vector parity harness.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * --------------------------------------------------------------------------------
 * Standalone host comparator for the golden-vector parity harness. Reads a Python
 * golden (.spkvec) and an engine-produced buffer (also .spkvec) and reports
 * max-abs / RMS error against a tolerance. This is the seed of the on-host/CI
 * parity test: the C++ engine writes its spk_simulate_tap output to a .spkvec
 * (using spkvec_io.h), and CI runs:
 *
 *     spkvec_compare golden.spkvec engine.spkvec --max-abs 1e-4 --rms 1e-5
 *
 * Exit code 0 = within tolerance, 1 = out of tolerance / shape mismatch,
 * 2 = usage / IO error. `--selftest` round-trips the spkvec writer/reader to
 * keep spkvec_io.h byte-compatible with spkvec.py.
 * --------------------------------------------------------------------------------
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "spkvec_io.h"

namespace {

struct Stats {
    double max_abs = 0.0;
    double rms = 0.0;
    size_t n = 0;
    size_t worst_index = 0;
    double worst_a = 0.0;
    double worst_b = 0.0;
    size_t nonfinite = 0;
};

Stats compute_stats(const spkvec::Array& a, const spkvec::Array& b) {
    Stats s;
    s.n = a.data.size();
    double sumsq = 0.0;
    for (size_t i = 0; i < s.n; ++i) {
        const double av = static_cast<double>(a.data[i]);
        const double bv = static_cast<double>(b.data[i]);
        if (!std::isfinite(av) || !std::isfinite(bv)) {
            ++s.nonfinite;
            continue;
        }
        const double d = std::fabs(av - bv);
        if (d > s.max_abs) {
            s.max_abs = d;
            s.worst_index = i;
            s.worst_a = av;
            s.worst_b = bv;
        }
        sumsq += d * d;
    }
    const size_t finite = s.n - s.nonfinite;
    s.rms = finite ? std::sqrt(sumsq / static_cast<double>(finite)) : 0.0;
    return s;
}

bool same_shape(const spkvec::Array& a, const spkvec::Array& b) {
    return a.shape == b.shape;
}

std::string shape_str(const std::vector<uint32_t>& shape) {
    std::string out = "(";
    for (size_t i = 0; i < shape.size(); ++i) {
        out += std::to_string(shape[i]);
        if (i + 1 < shape.size()) out += ", ";
    }
    out += ")";
    return out;
}

int run_selftest() {
    const char* path = "spkvec_selftest.tmp";
    std::vector<uint32_t> shape = {2, 3, 4};
    std::vector<float> data(2 * 3 * 4);
    for (size_t i = 0; i < data.size(); ++i)
        data[i] = static_cast<float>(i) * 0.5f - 3.0f;

    spkvec::write(path, shape, data.data(), data.size());
    spkvec::Array back = spkvec::read(path);
    std::remove(path);

    if (back.shape != shape) {
        std::fprintf(stderr, "selftest FAIL: shape mismatch\n");
        return 1;
    }
    if (back.data.size() != data.size()) {
        std::fprintf(stderr, "selftest FAIL: count mismatch\n");
        return 1;
    }
    for (size_t i = 0; i < data.size(); ++i) {
        if (back.data[i] != data[i]) {
            std::fprintf(stderr, "selftest FAIL: data[%zu] %g != %g\n", i,
                         back.data[i], data[i]);
            return 1;
        }
    }
    std::printf("selftest OK: spkvec write/read round-trips %zu floats\n",
                data.size());
    return 0;
}

void usage(const char* argv0) {
    std::fprintf(stderr,
        "usage: %s GOLDEN.spkvec ENGINE.spkvec [--max-abs T] [--rms T]\n"
        "       %s --selftest\n"
        "\n"
        "Compares two .spkvec arrays element-wise. Exit 0 if within tolerance.\n"
        "Defaults: --max-abs 1e-4  --rms 1e-5\n",
        argv0, argv0);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc >= 2 && std::strcmp(argv[1], "--selftest") == 0)
        return run_selftest();

    if (argc < 3) {
        usage(argv[0]);
        return 2;
    }

    const std::string golden_path = argv[1];
    const std::string engine_path = argv[2];
    double tol_max_abs = 1e-4;
    double tol_rms = 1e-5;

    for (int i = 3; i < argc; ++i) {
        if (std::strcmp(argv[i], "--max-abs") == 0 && i + 1 < argc) {
            tol_max_abs = std::atof(argv[++i]);
        } else if (std::strcmp(argv[i], "--rms") == 0 && i + 1 < argc) {
            tol_rms = std::atof(argv[++i]);
        } else {
            std::fprintf(stderr, "unknown/incomplete arg: %s\n", argv[i]);
            usage(argv[0]);
            return 2;
        }
    }

    spkvec::Array golden, engine;
    try {
        golden = spkvec::read(golden_path);
        engine = spkvec::read(engine_path);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "IO error: %s\n", e.what());
        return 2;
    }

    if (!same_shape(golden, engine)) {
        std::fprintf(stderr,
            "FAIL shape mismatch: golden %s vs engine %s\n",
            shape_str(golden.shape).c_str(), shape_str(engine.shape).c_str());
        return 1;
    }

    const Stats s = compute_stats(golden, engine);

    std::printf("golden : %s %s\n", golden_path.c_str(),
                shape_str(golden.shape).c_str());
    std::printf("engine : %s %s\n", engine_path.c_str(),
                shape_str(engine.shape).c_str());
    std::printf("count  : %zu\n", s.n);
    std::printf("max_abs: %.6g  (tol %.6g)  at idx %zu  golden=%.6g engine=%.6g\n",
                s.max_abs, tol_max_abs, s.worst_index, s.worst_a, s.worst_b);
    std::printf("rms    : %.6g  (tol %.6g)\n", s.rms, tol_rms);
    if (s.nonfinite)
        std::printf("WARNING: %zu non-finite element(s) skipped\n", s.nonfinite);

    const bool ok = (s.max_abs <= tol_max_abs) && (s.rms <= tol_rms) &&
                    (s.nonfinite == 0);
    std::printf("result : %s\n", ok ? "PASS" : "FAIL");
    return ok ? 0 : 1;
}
