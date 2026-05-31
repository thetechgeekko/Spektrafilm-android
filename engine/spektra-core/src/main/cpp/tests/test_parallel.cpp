/*
 * Spektrafilm for Android — thread-count invariance (determinism) test.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The per-pixel engine stages (expose / scan / print_expose) are parallelized
 * via kernels/parallel (deterministic fork-join over disjoint pixel ranges).
 * Because each output pixel is an independent function of its input pixel, the
 * result MUST be byte-identical regardless of the worker count. This test runs
 * the SAME input through spk_simulate at SPK_NUM_THREADS=1 and =8 and asserts the
 * outputs are bitwise equal (memcmp) — for the scan route, the print route, AND
 * with grain + halation ON (the stochastic/spatial branch, which stays serial and
 * must therefore also be unaffected by the worker count).
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_parallel.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/params.cpp runtime/print_digest.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_parallel
 * Run:
 *   /tmp/test_parallel <asset_dir> <input.f64>
 */
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "spektra.h"

namespace {

const char* kAssetDir = "/home/user/spektrafilm/src/spektrafilm/data";
const char* kInputF64 =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "scan_portra_input_rgb.f64";

// Run spk_simulate with a forced worker count; copy the output into `out`.
// Returns false on engine error.
bool simulate_with_threads(spk_engine* eng, const spk_image* in,
                           const spk_params* p, int nthreads,
                           std::vector<float>* out) {
    char buf[16];
    std::snprintf(buf, sizeof(buf), "%d", nthreads);
    setenv("SPK_NUM_THREADS", buf, /*overwrite=*/1);

    spk_image img{};
    spk_status st = spk_simulate(eng, in, p, &img);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate (threads=%d) failed: %s\n", nthreads,
                     spk_status_str(st));
        return false;
    }
    const size_t n = static_cast<size_t>(img.width) * img.height * 3;
    out->assign(img.data, img.data + n);
    spk_image_free(&img);
    return true;
}

// Assert two run outputs are bitwise identical; print + return PASS/FAIL.
bool check_identical(const char* label, const std::vector<float>& a,
                     const std::vector<float>& b) {
    if (a.size() != b.size()) {
        std::printf("[%s] size mismatch %zu vs %zu -> FAIL\n", label, a.size(),
                    b.size());
        return false;
    }
    const bool same = std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
    // Also report the worst absolute difference for diagnostics (0 when identical).
    double max_abs = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        double d = static_cast<double>(a[i]) - static_cast<double>(b[i]);
        if (d < 0) d = -d;
        if (d > max_abs) { max_abs = d; argmax = i; }
    }
    std::printf("[%s] 1-thread vs 8-thread: max_abs=%.3e (worst idx=%zu) -> %s\n",
                label, max_abs, argmax, same ? "PASS" : "FAIL");
    return same;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir  = argc > 1 ? argv[1] : kAssetDir;
    std::string input_path = argc > 2 ? argv[2] : kInputF64;

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    // The fixture is a 64x64x3 float64 image (matches make_test_image(64)).
    const int width = 64, height = 64;
    const int npix = width * height;
    std::vector<double> rgb64(static_cast<size_t>(npix) * 3);
    {
        std::ifstream in(input_path, std::ios::binary);
        if (!in) { std::fprintf(stderr, "cannot open %s\n", input_path.c_str()); return 2; }
        in.read(reinterpret_cast<char*>(rgb64.data()),
                static_cast<std::streamsize>(rgb64.size() * sizeof(double)));
        if (in.gcount() != static_cast<std::streamsize>(rgb64.size() * sizeof(double))) {
            std::fprintf(stderr, "input size mismatch\n");
            return 2;
        }
    }
    std::vector<float> rgb32(rgb64.begin(), rgb64.end());
    spk_image in_img{rgb32.data(), width, height, /*color_space=*/SPK_CS_PROPHOTO};

    // Base parity-style params (deterministic). Each case toggles from here.
    spk_params base{};
    base.film_profile = "kodak_portra_400";
    base.print_profile = "kodak_portra_endura";
    spk_default_params(&base);
    base.exposure_compensation_ev = 0.0f;
    base.auto_exposure = 0;
    base.density_curve_gamma = 1.0f;
    base.grain_active = 0;
    base.halation_active = 0;
    base.dir_couplers_active = 1;
    base.glare_active = 0;
    base.output_color_space = SPK_CS_SRGB;
    base.output_cctf_encoding = 1;
    base.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    base.preview_max_size = 640;

    bool ok = true;
    std::vector<float> r1, r8;

    // 1) Scan route, pointwise (the threaded expose + scan hot loops).
    {
        spk_params p = base;
        p.scan_film = 1;
        ok &= simulate_with_threads(eng, &in_img, &p, 1, &r1);
        ok &= simulate_with_threads(eng, &in_img, &p, 8, &r8);
        ok &= check_identical("scan_film", r1, r8);
    }

    // 2) Print route (adds the threaded print_expose hot loop).
    {
        spk_params p = base;
        p.scan_film = 0;
        ok &= simulate_with_threads(eng, &in_img, &p, 1, &r1);
        ok &= simulate_with_threads(eng, &in_img, &p, 8, &r8);
        ok &= check_identical("print", r1, r8);
    }

    // 3) Scan route with grain + halation ON: the stochastic + spatial branch.
    //    Grain walks a seeded RNG in pixel order and the spatial blurs run serial,
    //    so this must ALSO be byte-identical across worker counts (only the
    //    pointwise stages are threaded).
    {
        spk_params p = base;
        p.scan_film = 1;
        p.grain_active = 1;
        p.halation_active = 1;
        ok &= simulate_with_threads(eng, &in_img, &p, 1, &r1);
        ok &= simulate_with_threads(eng, &in_img, &p, 8, &r8);
        ok &= check_identical("scan_film+grain+halation", r1, r8);
    }

    spk_engine_destroy(eng);
    std::printf("%s\n", ok ? "ALL PASS" : "FAIL");
    return ok ? 0 : 1;
}
