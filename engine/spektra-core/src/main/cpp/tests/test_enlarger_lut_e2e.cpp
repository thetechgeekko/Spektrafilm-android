/*
 * Spektrafilm for Android — end-to-end host test for the OPT-IN enlarger 3D-LUT.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Proves the WIRED enlarger-LUT path is correct end-to-end (the print-route
 * analogue of tests/test_scanner_lut_e2e.cpp). It runs the WHOLE negative ->
 * print -> scan pipeline through spk_simulate() twice on the same deterministic
 * fixture, with the scanner LUT OFF in both so only the enlarger differs:
 *   (A) use_enlarger_lut = 0  -> the DEFAULT direct spectral print_expose
 *       (bit-exact, the parity-gate path).
 *   (B) use_enlarger_lut = 1  -> the LUT-accelerated path (print_expose builds a
 *       per-channel PCHIP 3D LUT over the film-density domain
 *       [-grain.density_min, nanmax(film.density_curves)] at lut_resolution and
 *       interpolates film density_cmy -> print log_raw instead of the per-pixel
 *       spectral integral).
 *
 * Assertions:
 *  1. The direct (A) output is within the committed print_portra final_rgb
 *     golden's tolerance band (max_abs <= 1e-4), confirming wiring the opt-in
 *     branch did not perturb the default print path.
 *  2. The LUT (B) output is within the documented ACCELERATION tolerance of the
 *     direct (A) output. The enlarger expose integral (_film_cmy_to_print_log_raw)
 *     is less smooth than the scanner's density->log_xyz, so for the SAME step
 *     count the PCHIP error is larger than the scanner LUT's: ~1.1e-4 at
 *     lut_resolution=17, converging to ~2e-6 at 64. This is NOT bit-exact by
 *     design (interpolation) and is preview-only acceleration (the default/export
 *     path stays direct), so it is held to a measured band, not the parity gate.
 *
 * Build (host) — full source set, from the cpp root:
 *   g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
 *     tests/test_enlarger_lut_e2e.cpp <full SRC set> -o /tmp/test_enlarger_lut_e2e
 * Run:
 *   /tmp/test_enlarger_lut_e2e <asset_dir> <print_portra_golden_dir> <input.f64>
 */
#include <cmath>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "spkvec_io.h"
#include "spektra.h"

namespace {

const char* kAssetDir = "/home/user/spektrafilm/src/spektrafilm/data";
const char* kGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_portra";
const char* kInputF64 =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "scan_portra_input_rgb.f64";

struct Metrics { double max_abs; double rms; size_t argmax; };

Metrics compare(const float* a, const float* b, size_t n) {
    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < n; ++i) {
        double d = std::fabs(static_cast<double>(a[i]) - static_cast<double>(b[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    return {max_abs, std::sqrt(sse / static_cast<double>(n)), argmax};
}

// Run the full negative->print->scan pipeline once with the given enlarger-LUT
// settings. Mirrors the print_portra parity toggles (scan_film=0, grain/halation/
// glare off, DIR couplers on). The scanner LUT is OFF so only the enlarger varies.
bool run(spk_engine* eng, const spk_image& in, int use_enl_lut, int lut_res,
         std::vector<float>* out_rgb, int* w, int* h) {
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
    p.scan_film = 0;  // negative -> print -> scan route
    p.output_color_space = SPK_CS_SRGB;
    p.output_cctf_encoding = 1;
    p.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p.preview_max_size = 640;
    p.use_scanner_lut = 0;          // isolate the enlarger LUT
    p.use_enlarger_lut = use_enl_lut;
    p.lut_resolution = lut_res;

    spk_image out{};
    spk_status st = spk_simulate(eng, &in, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        return false;
    }
    *w = out.width; *h = out.height;
    const size_t n = static_cast<size_t>(out.width) * out.height * 3;
    out_rgb->assign(out.data, out.data + n);
    spk_image_free(&out);
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;

    spk_engine* eng = nullptr;
    if (spk_engine_create(asset_dir.c_str(), &eng) != SPK_OK) {
        std::fprintf(stderr, "engine create failed\n");
        return 2;
    }

    spkvec::Array gold = spkvec::read(golden_dir + "/final_rgb.spkvec");
    const int height = static_cast<int>(gold.shape[0]);
    const int width = static_cast<int>(gold.shape[1]);
    const int npix = width * height;
    const size_t n = static_cast<size_t>(npix) * 3;

    std::vector<double> rgb64(n);
    {
        std::ifstream in(input_path, std::ios::binary);
        if (!in) { std::fprintf(stderr, "cannot open %s\n", input_path.c_str()); return 2; }
        in.read(reinterpret_cast<char*>(rgb64.data()),
                static_cast<std::streamsize>(n * sizeof(double)));
        if (in.gcount() != static_cast<std::streamsize>(n * sizeof(double))) {
            std::fprintf(stderr, "input size mismatch\n"); return 2;
        }
    }
    std::vector<float> rgb32(rgb64.begin(), rgb64.end());
    spk_image in_img{rgb32.data(), width, height, SPK_CS_PROPHOTO};

    std::printf("Image: %dx%dx3\n", width, height);

    // (A) Direct path (use_enlarger_lut = 0): the default, bit-exact path.
    std::vector<float> direct; int dw = 0, dh = 0;
    if (!run(eng, in_img, /*use_enl_lut=*/0, /*res=*/17, &direct, &dw, &dh)) return 2;

    Metrics m_gold = compare(direct.data(), gold.data.data(), n);
    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass_direct = (m_gold.max_abs <= tol_max_abs) && (m_gold.rms <= tol_rms);
    std::printf("[enlarger_lut_e2e direct vs golden] max_abs=%.6e (tol %.0e) "
                "rms=%.6e (tol %.0e) -> %s\n",
                m_gold.max_abs, tol_max_abs, m_gold.rms, tol_rms,
                pass_direct ? "PASS" : "FAIL");

    // (B) LUT path at the engine default resolution (17) and a finer one (64).
    bool pass_lut = true;
    // Measured enlarger-LUT accel bands (looser than the scanner LUT at the same
    // step count: the print-expose integral is less smooth -> larger PCHIP error,
    // converging with resolution). res=17 ~1.1e-4, res=64 ~1.9e-6.
    const struct { int res; double band; } cfgs[] = {{17, 2e-4}, {64, 5e-6}};
    for (const auto& cfg : cfgs) {
        std::vector<float> lut; int lw = 0, lh = 0;
        if (!run(eng, in_img, /*use_enl_lut=*/1, cfg.res, &lut, &lw, &lh)) return 2;
        Metrics m = compare(lut.data(), direct.data(), n);
        bool within = m.max_abs <= cfg.band;
        std::printf("[enlarger_lut_e2e LUT(res=%d) vs direct] max_abs=%.6e "
                    "(accel band %.0e, NOT bit-exact by design) rms=%.6e -> %s\n",
                    cfg.res, m.max_abs, cfg.band, m.rms,
                    within ? "WITHIN BAND" : "OUT OF BAND");
        pass_lut = pass_lut && within;
    }

    spk_engine_destroy(eng);
    bool all = pass_direct && pass_lut;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
