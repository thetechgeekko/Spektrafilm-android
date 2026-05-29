/*
 * SpectraFilm for Android — host parity test for the crop/resize geometry stage.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Exercises the NON-default crop + cubic-upscale preprocess (the scan_portra_crop
 * case): crop_center=(0.45,0.55), crop_size=(0.6,0.5), upscale_factor=1.75 on the
 * scan_film route (spatial/grain off). It:
 *   1) loads the deterministic 64x64 scan_portra_input_rgb.f64 fixture,
 *   2) runs the WHOLE scan_film pipeline through spk_simulate() with crop ON,
 *   3) asserts the output GEOMETRY (56x66) matches the golden, and that every
 *      pixel matches goldens/scan_portra_crop/{film_density_cmy,final_rgb}.spkvec
 *      within the standard parity tolerance (max_abs <= 1e-4, rms <= 1e-5).
 *
 * The crop_and_rescale port (crop integer-slice + skimage rescale(order=3) ==
 * scipy.ndimage.zoom cubic + clip) is bit-exact (fp round-off ~1e-15) vs the
 * Python oracle, so the residual here is dominated by the downstream pipeline,
 * exactly as in test_simulate_e2e.
 *
 * Build (host): full source set per tests/README, e.g.
 *   g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
 *     tests/test_crop_resize.cpp spektra.cpp kernels/*.cpp io/*.cpp \
 *     model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_crop_resize
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "spkvec_io.h"
#include "spektra.h"

namespace {

const char* kAssetDir  = "/home/user/spektrafilm/src/spektrafilm/data";
const char* kGoldenDir =
    "/home/user/wt-engine/tools/parity/goldens/scan_portra_crop";
const char* kInputF64  =
    "/home/user/wt-engine/engine/spektra-core/src/main/cpp/tests/"
    "scan_portra_input_rgb.f64";

bool check(const char* label, const float* got, const std::vector<float>& gold) {
    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < gold.size(); ++i) {
        double d = std::fabs(static_cast<double>(got[i]) -
                             static_cast<double>(gold[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    double rms = std::sqrt(sse / static_cast<double>(gold.size()));
    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass = (max_abs <= tol_max_abs) && (rms <= tol_rms);
    std::printf("[%s] max_abs=%.6e (tol %.0e) rms=%.6e (tol %.0e) "
                "worst idx=%zu got=%.8f gold=%.8f -> %s\n",
                label, max_abs, tol_max_abs, rms, tol_rms, argmax,
                got[argmax], gold[argmax], pass ? "PASS" : "FAIL");
    return pass;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir  = argc > 1 ? argv[1] : kAssetDir;
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    // The input fixture is the 64x64 synthetic image (matches the golden's input).
    const int in_w = 64, in_h = 64, in_npix = in_w * in_h;
    std::vector<double> rgb64(static_cast<size_t>(in_npix) * 3);
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
    spk_image in_img{rgb32.data(), in_w, in_h, /*color_space=*/SPK_CS_PROPHOTO};

    spk_params p{};
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    spk_default_params(&p);
    p.exposure_compensation_ev = 0.0f;
    p.auto_exposure = 0;
    p.density_curve_gamma = 1.0f;
    p.grain_active = 0;
    p.halation_active = 0;
    p.dir_couplers_active = 1;
    p.glare_active = 0;
    p.scan_film = 1;
    p.output_color_space = SPK_CS_SRGB;
    p.output_cctf_encoding = 1;
    p.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p.preview_max_size = 640;
    // --- NON-default crop/resize geometry (scan_portra_crop case) ------------
    p.crop = 1;
    p.crop_center[0] = 0.45f; p.crop_center[1] = 0.55f;  // (x, y)
    p.crop_size[0]   = 0.6f;  p.crop_size[1]   = 0.5f;   // (x, y), frac of long side
    p.upscale_factor = 1.75f;

    // Expected output geometry from the golden.
    spkvec::Array gold_cmy = spkvec::read(golden_dir + "/film_density_cmy.spkvec");
    spkvec::Array gold_rgb = spkvec::read(golden_dir + "/final_rgb.spkvec");
    const int oh = static_cast<int>(gold_rgb.shape[0]);
    const int ow = static_cast<int>(gold_rgb.shape[1]);
    std::printf("Input %dx%d -> expected cropped/upscaled %dx%d (%d pixels)\n",
                in_w, in_h, ow, oh, ow * oh);

    bool pass_geom = false, pass_cmy = false, pass_rgb = false;

    // Final RGB through the full scan_film pipeline.
    spk_image out{};
    st = spk_simulate(eng, &in_img, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }
    pass_geom = (out.width == ow && out.height == oh);
    std::printf("[geometry] got %dx%d, golden %dx%d -> %s\n",
                out.width, out.height, ow, oh, pass_geom ? "PASS" : "FAIL");
    if (pass_geom && out.width * out.height * 3 == static_cast<int>(gold_rgb.data.size()))
        pass_rgb = check("scan_portra_crop final_rgb", out.data, gold_rgb.data);
    else
        std::printf("[scan_portra_crop final_rgb] SKIPPED (geometry mismatch)\n");
    spk_image_free(&out);

    // film_density_cmy tap (verifies the cropped/upscaled negative directly).
    spk_image tap{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_density_cmy", &tap);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate_tap failed: %s\n", spk_status_str(st));
    } else if (tap.width == ow && tap.height == oh &&
               tap.width * tap.height * 3 == static_cast<int>(gold_cmy.data.size())) {
        pass_cmy = check("scan_portra_crop film_density_cmy", tap.data, gold_cmy.data);
        spk_image_free(&tap);
    } else {
        std::printf("[scan_portra_crop film_density_cmy] geometry mismatch %dx%d\n",
                    tap.width, tap.height);
        spk_image_free(&tap);
    }

    spk_engine_destroy(eng);
    bool all = pass_geom && pass_cmy && pass_rgb;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
