/*
 * Spektrafilm for Android — END-TO-END host parity test for the CAMERA UV/IR cut
 * band-pass filter (camera.filter_uv / filter_ir) run through the WHOLE scan_film
 * pipeline.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The band-pass is the camera UV/IR cut filter of FilmingStage._rgb_to_film_raw:
 * before the profile sensitivity is handed to compute_hanatos2025_tc_lut, it is
 * multiplied by compute_band_pass_filter(filter_uv, filter_ir) with a per-channel
 * white-balance normalisation against the film reference illuminant:
 *   band = filter_uv * filter_ir   (each = 1-amp + amp*sigmoid_erf)
 *   norm[c] = sum_s sens[s,c]*band[s]*illu[s] / sum_s sens[s,c]*illu[s]
 *   sens[s,c] *= band[s] / norm[c]
 * It therefore changes the filming tc_lut and every downstream tap (film_log_raw /
 * film_density_cmy / final_rgb). The band-pass lives in the LUT-build path, NOT the
 * per-pixel spatial branch, so this case keeps spatial + grain OFF and is fully
 * deterministic, matching gen_goldens.py's scan_portra_uvir case.
 *
 * It checks film_log_raw + film_density_cmy + final_rgb against the scan_portra_uvir
 * oracle golden bit-exact (max_abs <= 1e-4, rms <= 1e-5), and confirms that turning
 * the band-pass OFF (everything else equal) changes the output — proving the filter
 * is genuinely active end-to-end and the sole driver of the difference vs. the
 * golden.
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_camera_uvir_e2e.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_camera_uvir_e2e
 * Run (argv[4] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_camera_uvir_e2e <asset_dir> <scan_portra_uvir_golden_dir> \
 *     <input.f64> [goldens_root]
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
const char* kUvirGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra_uvir";
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

double max_abs_diff(const float* a, const float* b, size_t n) {
    double m = 0.0;
    for (size_t i = 0; i < n; ++i) {
        double d = std::fabs(static_cast<double>(a[i]) - static_cast<double>(b[i]));
        if (d > m) m = d;
    }
    return m;
}

// Base scan_portra parameter set (spatial/grain/glare off), shared by all variants.
// Mirrors gen_goldens.py's scan_portra_* cases.
void set_base_params(spk_params* p) {
    p->film_profile = "kodak_portra_400";
    p->print_profile = "kodak_portra_endura";
    spk_default_params(p);
    p->exposure_compensation_ev = 0.0f;
    p->auto_exposure = 0;
    p->density_curve_gamma = 1.0f;
    p->grain_active = 0;
    p->halation_active = 0;  // spatial branch OFF (deactivate_spatial_effects=True)
    p->dir_couplers_active = 1;
    p->glare_active = 0;
    p->scan_film = 1;
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    p->rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p->preview_max_size = 640;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    std::string uvir_golden_dir = argc > 2 ? argv[2] : kUvirGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;
    if (argc > 4) {
        std::string root = argv[4];
        uvir_golden_dir = root + "/scan_portra_uvir";
    }

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    // --- Load goldens + input image ---
    spkvec::Array gold_rgb = spkvec::read(uvir_golden_dir + "/final_rgb.spkvec");
    spkvec::Array gold_logr = spkvec::read(uvir_golden_dir + "/film_log_raw.spkvec");
    spkvec::Array gold_cmy = spkvec::read(uvir_golden_dir + "/film_density_cmy.spkvec");
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

    // ---------------------------------------------------------------------------
    // BAND-PASS ON: vs scan_portra_uvir golden.
    // ---------------------------------------------------------------------------
    spk_params p{};
    set_base_params(&p);
    // feature under test: UV/IR cut amplitudes 1.0 (centres/widths at schema default)
    p.camera_filter_uv[0] = 1.0f; p.camera_filter_uv[1] = 410.0f; p.camera_filter_uv[2] = 8.0f;
    p.camera_filter_ir[0] = 1.0f; p.camera_filter_ir[1] = 675.0f; p.camera_filter_ir[2] = 15.0f;

    bool pass_logr = false, pass_cmy = false, pass_rgb = false;

    spk_image tap_logr{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_log_raw", &tap_logr);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_log_raw) failed: %s\n", spk_status_str(st));
    } else {
        pass_logr = check("camera_uvir film_log_raw", tap_logr.data, gold_logr.data);
        spk_image_free(&tap_logr);
    }

    spk_image tap_cmy{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_density_cmy", &tap_cmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_density_cmy) failed: %s\n", spk_status_str(st));
    } else {
        pass_cmy = check("camera_uvir film_density_cmy", tap_cmy.data, gold_cmy.data);
        spk_image_free(&tap_cmy);
    }

    spk_image out{};
    st = spk_simulate(eng, &in_img, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }
    pass_rgb = check("camera_uvir final_rgb", out.data, gold_rgb.data);

    // --- Sanity: band-pass OFF (everything else equal) changes the output, proving
    //     the filter is genuinely active end-to-end and is the sole driver of the
    //     difference vs. the golden. ---
    spk_params p_off = p;
    p_off.camera_filter_uv[0] = 0.0f;
    p_off.camera_filter_ir[0] = 0.0f;
    spk_image out_off{};
    double delta = 0.0;
    bool filter_active = false;
    st = spk_simulate(eng, &in_img, &p_off, &out_off);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(band-pass off) failed: %s\n",
                     spk_status_str(st));
    } else {
        delta = max_abs_diff(out.data, out_off.data,
                             static_cast<size_t>(npix) * 3);
        filter_active = delta > 1e-4;
        std::printf("[band-pass on vs off] max_abs = %.6e (must be > 0: the UV/IR cut "
                    "reshapes the sensitivity -> tc_lut) -> %s\n",
                    delta, filter_active ? "BAND-PASS ACTIVE" : "BAND-PASS INERT?!");
        spk_image_free(&out_off);
    }
    spk_image_free(&out);

    spk_engine_destroy(eng);
    bool all = pass_logr && pass_cmy && pass_rgb && filter_active;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
