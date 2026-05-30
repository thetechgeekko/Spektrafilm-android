/*
 * Spektrafilm for Android — host parity test for the auto-exposure stage.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Exercises a NON-default auto-exposure setting (camera.auto_exposure = ON,
 * method = center_weighted), which is INERT under all the other goldens
 * (gen_goldens.py forces auto_exposure off for determinism). With the param on,
 * pipeline._preprocess meters the small_preview luminance and globally scales the
 * image by 2**(-log2(Y_meter/0.184)) before crop/rescale.
 *
 * Runs the scan_film route through one spk_simulate() call on the deterministic
 * scan_portra_input_rgb.f64 fixture (== make_test_image(64)) and compares the
 * final RGB scan to the scan_portra_autoexp golden generated from the Python
 * oracle, asserting bit-exact match (max_abs <= 1e-4, rms <= 1e-5). Also checks
 * the film_log_raw + film_density_cmy taps so the EV gain is verified upstream of
 * the scan too. For the 64px image small_preview is a no-op (long edge 64 <= 256)
 * so the metering operates on the full image, matching the oracle.
 *
 * A sanity check also confirms that with auto_exposure OFF on the same input the
 * output is the (different) scan_portra baseline, proving the param is the only
 * thing driving the change (i.e. AE is a strict no-op when off).
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
 *     tests/test_autoexposure.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/params.cpp runtime/print_digest.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_autoexposure
 * Run (golden dirs default to the repo-root /home/user/Spectrafilmandroid path;
 * argv[4] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_autoexposure <asset_dir> <autoexp_golden_dir> <input.f64> \
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

const char* kAssetDir = "/home/user/spektrafilm/src/spektrafilm/data";
const char* kAutoexpGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra_autoexp";
const char* kAutoexpMatrixGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra_autoexp_matrix";
const char* kBaselineGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra";
const char* kInputF64 =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
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

// Max element-wise abs difference between two equal-length buffers.
double max_abs_diff(const float* a, const float* b, size_t n) {
    double m = 0.0;
    for (size_t i = 0; i < n; ++i) {
        double d = std::fabs(static_cast<double>(a[i]) - static_cast<double>(b[i]));
        if (d > m) m = d;
    }
    return m;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    std::string golden_dir = argc > 2 ? argv[2] : kAutoexpGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;
    std::string baseline_dir = kBaselineGoldenDir;
    std::string matrix_dir = kAutoexpMatrixGoldenDir;
    if (argc > 4) {
        std::string root = argv[4];
        golden_dir = root + "/scan_portra_autoexp";
        baseline_dir = root + "/scan_portra";
        matrix_dir = root + "/scan_portra_autoexp_matrix";
    }

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    spkvec::Array gold_rgb = spkvec::read(golden_dir + "/final_rgb.spkvec");
    spkvec::Array gold_logr = spkvec::read(golden_dir + "/film_log_raw.spkvec");
    spkvec::Array gold_cmy = spkvec::read(golden_dir + "/film_density_cmy.spkvec");
    const int height = static_cast<int>(gold_rgb.shape[0]);
    const int width = static_cast<int>(gold_rgb.shape[1]);
    const int npix = width * height;
    std::printf("Image: %dx%dx3 (%d pixels)\n", width, height, npix);

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
    spk_image in_img{rgb32.data(), width, height, SPK_CS_PROPHOTO};

    spk_params p{};
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    spk_default_params(&p);
    // NON-default auto-exposure: ON, center_weighted (NULL method => default).
    p.auto_exposure = 1;
    p.auto_exposure_method = nullptr;  // == "center_weighted"
    p.exposure_compensation_ev = 0.0f;
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

    bool pass_logr = false, pass_cmy = false, pass_rgb = false;

    spk_image tap_logr{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_log_raw", &tap_logr);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_log_raw) failed: %s\n", spk_status_str(st));
    } else {
        pass_logr = check("autoexp film_log_raw", tap_logr.data, gold_logr.data);
        spk_image_free(&tap_logr);
    }

    spk_image tap_cmy{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_density_cmy", &tap_cmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_density_cmy) failed: %s\n", spk_status_str(st));
    } else {
        pass_cmy = check("autoexp film_density_cmy", tap_cmy.data, gold_cmy.data);
        spk_image_free(&tap_cmy);
    }

    spk_image out{};
    st = spk_simulate(eng, &in_img, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }
    pass_rgb = check("autoexp final_rgb", out.data, gold_rgb.data);

    // --- Sanity: AE OFF on the same input reproduces the scan_portra baseline
    //     (and differs from the AE-on output), proving the param is a strict
    //     no-op when off and the sole driver of the change when on. -----------
    spkvec::Array gold_base = spkvec::read(baseline_dir + "/final_rgb.spkvec");
    spk_params p_off = p;
    p_off.auto_exposure = 0;
    spk_image out_off{};
    bool pass_off = false;
    double ae_delta = 0.0;
    st = spk_simulate(eng, &in_img, &p_off, &out_off);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(ae off) failed: %s\n", spk_status_str(st));
    } else {
        pass_off = check("ae-off == scan_portra baseline", out_off.data, gold_base.data);
        ae_delta = max_abs_diff(out.data, out_off.data,
                                static_cast<size_t>(npix) * 3);
        std::printf("[ae on vs off] max_abs = %.6e (must be clearly > 0: the EV "
                    "gain changes the image) -> %s\n",
                    ae_delta, ae_delta > 1e-3 ? "AE ACTIVE" : "AE INERT?!");
        spk_image_free(&out_off);
    }
    spk_image_free(&out);

    // --- NON-center_weighted metering: the MATRIX pattern ------------------
    // The scan_portra_autoexp golden only covers center_weighted; this exercises
    // the 5x5 raised-cosine zone-weighted branch end-to-end and asserts the C++
    // pipeline output matches the scan_portra_autoexp_matrix oracle golden
    // bit-exact (film_log_raw + film_density_cmy + final_rgb). It also confirms
    // the matrix EV differs from center_weighted (the metering pattern matters).
    spkvec::Array gm_rgb = spkvec::read(matrix_dir + "/final_rgb.spkvec");
    spkvec::Array gm_logr = spkvec::read(matrix_dir + "/film_log_raw.spkvec");
    spkvec::Array gm_cmy = spkvec::read(matrix_dir + "/film_density_cmy.spkvec");

    spk_params pm = p;
    pm.auto_exposure = 1;
    pm.auto_exposure_method = "matrix";  // NON-center_weighted pattern.

    bool pass_m_logr = false, pass_m_cmy = false, pass_m_rgb = false;
    double matrix_vs_center = 0.0;

    spk_image m_logr{};
    st = spk_simulate_tap(eng, &in_img, &pm, "film_log_raw", &m_logr);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(matrix film_log_raw) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_m_logr = check("autoexp_matrix film_log_raw", m_logr.data, gm_logr.data);
        spk_image_free(&m_logr);
    }

    spk_image m_cmy{};
    st = spk_simulate_tap(eng, &in_img, &pm, "film_density_cmy", &m_cmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(matrix film_density_cmy) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_m_cmy = check("autoexp_matrix film_density_cmy", m_cmy.data, gm_cmy.data);
        spk_image_free(&m_cmy);
    }

    spk_image m_out{};
    st = spk_simulate(eng, &in_img, &pm, &m_out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(matrix) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_m_rgb = check("autoexp_matrix final_rgb", m_out.data, gm_rgb.data);
        // Confirm matrix metering differs from center_weighted (different EV).
        matrix_vs_center = max_abs_diff(m_out.data, gold_rgb.data.data(),
                                        static_cast<size_t>(npix) * 3);
        std::printf("[matrix vs center_weighted] max_abs = %.6e (must be > 0: a "
                    "different metering pattern => a different EV) -> %s\n",
                    matrix_vs_center,
                    matrix_vs_center > 1e-4 ? "DISTINCT" : "SAME?!");
        spk_image_free(&m_out);
    }

    spk_engine_destroy(eng);
    bool all = pass_logr && pass_cmy && pass_rgb && pass_off && (ae_delta > 1e-3) &&
               pass_m_logr && pass_m_cmy && pass_m_rgb && (matrix_vs_center > 1e-4);
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
