/*
 * Spektrafilm for Android — host parity test for the small_preview (auto-exposure
 * metering downscale) anti-aliasing prefilter.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * ResizingService.small_preview(image, max_size=256) downscales the image the
 * auto-exposure stage meters on with skimage.transform.rescale(scale,
 * channel_axis=2, order=0). For a float image whose long edge exceeds 256,
 * skimage 0.26 leaves anti_aliasing at its default (None) which resolves to True
 * on a downscale and runs scipy.ndimage.gaussian_filter (sigma = max(0,
 * (in/out-1)/2) per spatial axis, mode='mirror', truncate=4.0) BEFORE the nearest
 * resample. Without that prefilter the metered luminance — and thus the global
 * 2**ev gain applied to EVERY pixel of the full-resolution image — diverges from
 * the oracle on every real (>256px) import. The 64px end-to-end autoexposure
 * golden never exercises this path (long edge 64 <= 256 -> small_preview is a
 * no-op), so this focused stage test covers it directly.
 *
 * It:
 *   1) loads a deterministic NON-square 200x384 input fixture
 *      (tests/small_preview_aa_input_rgb.f64 = make_test_image(384)[:200]),
 *   2) calls spk::small_preview(in, w=384, h=200, max_size=256, ...),
 *   3) asserts the output GEOMETRY (133x256) and every pixel match the
 *      goldens/small_preview_aa/preview_rgb.spkvec golden generated directly from
 *      the oracle's skimage call, within parity tolerance (max_abs <= 1e-4,
 *      rms <= 1e-5).
 *
 * The golden is STAGE-LOCAL (skimage only), so it is independent of the
 * spektrafilm pipeline oracle SHA. Regenerate: tools/parity/gen_small_preview_golden.py.
 *
 * Build (host): full source set per tests/README, e.g.
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_small_preview_aa.cpp spektra.cpp kernels/*.cpp io/*.cpp \
 *     model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_small_preview_aa
 */
#include <cmath>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "spkvec_io.h"
#include "runtime/stages/autoexposure.h"

namespace {

const char* kGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/small_preview_aa";
const char* kInputF64 =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "small_preview_aa_input_rgb.f64";

// Geometry of the committed input fixture (make_test_image(384)[:200]).
constexpr int kInW = 384;
constexpr int kInH = 200;
constexpr int kMaxSize = 256;

bool check(const char* label, const std::vector<double>& got,
           const std::vector<float>& gold) {
    if (got.size() != gold.size()) {
        std::printf("[%s] size mismatch got=%zu gold=%zu -> FAIL\n",
                    label, got.size(), gold.size());
        return false;
    }
    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < gold.size(); ++i) {
        double d = std::fabs(got[i] - static_cast<double>(gold[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    double rms = std::sqrt(sse / static_cast<double>(gold.size()));
    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass = (max_abs <= tol_max_abs) && (rms <= tol_rms);
    std::printf("[%s] max_abs=%.6e (tol %.0e) rms=%.6e (tol %.0e) "
                "worst idx=%zu got=%.8f gold=%.8f -> %s\n",
                label, max_abs, tol_max_abs, rms, tol_rms, argmax,
                got[argmax], static_cast<double>(gold[argmax]),
                pass ? "PASS" : "FAIL");
    return pass;
}

}  // namespace

int main(int argc, char** argv) {
    // argv: [1]=asset_dir (unused; small_preview needs no engine), [2]=golden dir,
    // [3]=input.f64, [4]=goldens root override (git worktree). Keeping the same
    // arg shape as the other stage tests lets CI invoke it uniformly.
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;
    if (argc > 4) golden_dir = std::string(argv[4]) + "/small_preview_aa";

    std::vector<double> rgb64(static_cast<size_t>(kInW) * kInH * 3);
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

    spkvec::Array gold;
    try {
        gold = spkvec::read(golden_dir + "/preview_rgb.spkvec");
    } catch (const std::exception& e) {
        std::fprintf(stderr, "cannot read golden: %s\n", e.what());
        return 2;
    }
    if (gold.shape.size() != 3) {
        std::fprintf(stderr, "golden ndim %zu != 3\n", gold.shape.size());
        return 2;
    }
    const int gold_h = static_cast<int>(gold.shape[0]);
    const int gold_w = static_cast<int>(gold.shape[1]);

    std::vector<double> out;
    int ow = 0, oh = 0;
    spk::small_preview(rgb64.data(), kInW, kInH, kMaxSize, &out, &ow, &oh);
    std::printf("Input %dx%d (max_size %d) -> preview %dx%d, golden %dx%d\n",
                kInW, kInH, kMaxSize, ow, oh, gold_w, gold_h);

    bool pass_geom = (ow == gold_w && oh == gold_h);
    std::printf("[geometry] got %dx%d, golden %dx%d -> %s\n",
                ow, oh, gold_w, gold_h, pass_geom ? "PASS" : "FAIL");

    bool pass_px = false;
    if (pass_geom)
        pass_px = check("small_preview_aa preview_rgb", out, gold.data);
    else
        std::printf("[small_preview_aa preview_rgb] SKIPPED (geometry mismatch)\n");

    bool all = pass_geom && pass_px;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
