/*
 * Spektrafilm for Android — end-to-end host parity test for spk_simulate.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Constructs an spk_params for the scan_portra case + an spk_image from the
 * deterministic scan_portra_input_rgb.f64 fixture, runs the WHOLE scan_film
 * pipeline through one spk_simulate() call, and compares the output to
 * final_rgb.spkvec — printing max_abs/rms and PASS/FAIL against the manifest
 * tolerances (max_abs <= 1e-4, rms <= 1e-5).
 *
 * It then exercises the PRINT (enlarger) route on the same fixture for TWO
 * (film, paper) pairs through the generalized native digest (neutral dichroic
 * CC resolved from neutral_print_filters.json + midgray exposure factor computed
 * natively — no baked per-pair constants):
 *   - print_portra: kodak_portra_400 -> kodak_portra_endura
 *   - print_ektar:  kodak_ektar_100  -> kodak_supra_endura
 * Each compares print_density_cmy (spk_simulate_tap) and final_rgb (spk_simulate)
 * to the committed goldens, proving the generalized print path is bit-exact for
 * an additional pair, not just the original portra case.
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
 *     tests/test_simulate_e2e.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/params.cpp runtime/print_digest.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_simulate_e2e
 * Run (golden dirs default to the repo-root /home/user/Spectrafilmandroid path;
 * argv[4] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_simulate_e2e <asset_dir> <scan_portra_golden_dir> <input.f64> \
 *     [goldens_root]
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

const char* kAssetDir   = "/home/user/spektrafilm/src/spektrafilm/data";
const char* kGoldenDir  =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra";
const char* kPrintGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_portra";
// Second (film, paper) pair exercising the GENERALIZED print path (native
// neutral-CC + midgray digest, no baked portra/ektar special-case): the
// kodak_ektar_100 negative printed on kodak_supra_endura paper. Driven by the
// SAME deterministic input fixture (scan_portra_input_rgb.f64 == make_test_image(64))
// the print_ektar golden was generated from.
const char* kPrintEktarGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_ektar";
const char* kInputF64   =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "scan_portra_input_rgb.f64";

// Compare a flat float buffer against a golden, print + return PASS/FAIL.
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
    // Optional argv[4] = goldens ROOT (the dir containing print_portra/,
    // print_ektar/, ...). When given, the print-route golden dirs are taken from
    // it; otherwise the repo-root defaults are used (CI / installed repo). This
    // lets the test run from a git worktree before the goldens land in the repo.
    std::string print_portra_dir = kPrintGoldenDir;
    std::string print_ektar_dir  = kPrintEktarGoldenDir;
    if (argc > 4) {
        std::string root = argv[4];
        print_portra_dir = root + "/print_portra";
        print_ektar_dir  = root + "/print_ektar";
    }

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    spkvec::Array gold = spkvec::read(golden_dir + "/final_rgb.spkvec");
    const int height = static_cast<int>(gold.shape[0]);
    const int width  = static_cast<int>(gold.shape[1]);
    const int npix   = width * height;
    std::printf("Image: %dx%dx3 (%d pixels)\n", width, height, npix);

    // Load the deterministic float64 input fixture, promote to float32 for the
    // C API's linear-RGB spk_image buffer.
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

    spk_params p{};
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    spk_default_params(&p);  // physical defaults; parity case overrides below.
    p.exposure_compensation_ev = 0.0f;
    p.auto_exposure = 0;
    p.density_curve_gamma = 1.0f;
    p.grain_active = 0;       // deterministic goldens: stochastic + spatial off.
    p.halation_active = 0;
    p.dir_couplers_active = 1;
    p.glare_active = 0;
    p.scan_film = 1;
    p.output_color_space = SPK_CS_SRGB;
    p.output_cctf_encoding = 1;
    p.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p.preview_max_size = 640;

    spk_image out{};
    st = spk_simulate(eng, &in_img, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }

    const size_t n = static_cast<size_t>(npix) * 3;
    if (n != gold.data.size()) {
        std::fprintf(stderr, "size mismatch: got %zu, golden %zu\n", n, gold.data.size());
        spk_image_free(&out);
        spk_engine_destroy(eng);
        return 2;
    }

    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < n; ++i) {
        double d = std::fabs(static_cast<double>(out.data[i]) -
                             static_cast<double>(gold.data[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    double rms = std::sqrt(sse / static_cast<double>(n));

    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass = (max_abs <= tol_max_abs) && (rms <= tol_rms);
    std::printf("[scan_portra final_rgb] max_abs = %.6e (tol %.0e)\n",
                max_abs, tol_max_abs);
    std::printf("[scan_portra final_rgb] rms     = %.6e (tol %.0e)\n",
                rms, tol_rms);
    std::printf("worst idx=%zu: got=%.8f golden=%.8f -> %s\n", argmax,
                out.data[argmax], gold.data[argmax], pass ? "PASS" : "FAIL");
    spk_image_free(&out);

    // --- Print (enlarger) route: same input fixture, scan_film off. ----------
    std::string print_dir = print_portra_dir;
    spkvec::Array gold_print_cmy = spkvec::read(print_dir + "/print_density_cmy.spkvec");
    spkvec::Array gold_print_rgb = spkvec::read(print_dir + "/final_rgb.spkvec");

    p.scan_film = 0;  // negative -> print -> scan route.

    bool pass_print_cmy = false, pass_print_rgb = false;

    spk_image tap_cmy{};
    st = spk_simulate_tap(eng, &in_img, &p, "print_density_cmy", &tap_cmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate_tap(print_density_cmy) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_print_cmy = check("print_portra print_density_cmy", tap_cmy.data,
                               gold_print_cmy.data);
        spk_image_free(&tap_cmy);
    }

    spk_image print_out{};
    st = spk_simulate(eng, &in_img, &p, &print_out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(print) failed: %s\n", spk_status_str(st));
    } else {
        pass_print_rgb = check("print_portra final_rgb", print_out.data,
                               gold_print_rgb.data);
        spk_image_free(&print_out);
    }

    // --- Generalized print path: a SECOND (film, paper) pair --------------
    // kodak_ektar_100 -> kodak_supra_endura through the SAME C API. The neutral
    // dichroic CC + midgray exposure factor are computed natively for this pair
    // (no baked constants), proving the generalized print route matches the
    // oracle bit-exact under the same 1e-4/1e-5 tolerances.
    std::string ektar_dir = print_ektar_dir;
    spkvec::Array gold_ektar_cmy = spkvec::read(ektar_dir + "/print_density_cmy.spkvec");
    spkvec::Array gold_ektar_rgb = spkvec::read(ektar_dir + "/final_rgb.spkvec");

    spk_params pe{};
    pe.film_profile = "kodak_ektar_100";
    pe.print_profile = "kodak_supra_endura";
    spk_default_params(&pe);  // preserves film_profile/print_profile set above.
    pe.exposure_compensation_ev = 0.0f;
    pe.auto_exposure = 0;
    pe.density_curve_gamma = 1.0f;
    pe.grain_active = 0;
    pe.halation_active = 0;
    pe.dir_couplers_active = 1;
    pe.glare_active = 0;
    pe.scan_film = 0;  // negative -> print -> scan route.
    pe.output_color_space = SPK_CS_SRGB;
    pe.output_cctf_encoding = 1;
    pe.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    pe.preview_max_size = 640;

    bool pass_ektar_cmy = false, pass_ektar_rgb = false;

    spk_image ektar_cmy{};
    st = spk_simulate_tap(eng, &in_img, &pe, "print_density_cmy", &ektar_cmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate_tap(print_ektar cmy) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_ektar_cmy = check("print_ektar print_density_cmy", ektar_cmy.data,
                               gold_ektar_cmy.data);
        spk_image_free(&ektar_cmy);
    }

    spk_image ektar_out{};
    st = spk_simulate(eng, &in_img, &pe, &ektar_out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(print_ektar) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_ektar_rgb = check("print_ektar final_rgb", ektar_out.data,
                               gold_ektar_rgb.data);
        spk_image_free(&ektar_out);
    }

    spk_engine_destroy(eng);
    bool all = pass && pass_print_cmy && pass_print_rgb &&
               pass_ektar_cmy && pass_ektar_rgb;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
