/*
 * Spektrafilm for Android — END-TO-END host parity test for the Hanatos2025
 * sensitivity-adaptation WINDOW / SURFACE toggles
 * (settings.apply_hanatos2025_adaptation_window / _surface) run through the WHOLE
 * scan_film pipeline.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The surface is the apply_surface branch of compute_hanatos2025_tc_lut: after the
 * window-weighted spectra contraction, the filming tc_lut is multiplied per-cell,
 * per-channel by 2**surface, where
 *   surface = eval_poly4_log_exposure_surface(profile.surface_params,
 *               illuminant_xy=_illuminant_to_xy(film.reference_illuminant),
 *               model='poly4')
 * a degree-4 2D polynomial over the (L,L) tc grid centred at tri2quad(illuminant_xy)
 * passed through hanika_sigmoid(raw, max=2.0). It therefore changes the filming
 * tc_lut and every downstream tap (film_log_raw / film_density_cmy / final_rgb).
 * The surface lives in the LUT-build path, NOT the per-pixel spatial branch, so
 * this case keeps spatial + grain OFF and is fully deterministic, matching
 * gen_goldens.py's scan_portra_surface case.
 *
 * It checks film_log_raw + film_density_cmy + final_rgb against the
 * scan_portra_surface oracle golden bit-exact (max_abs <= 1e-4, rms <= 1e-5), and
 * confirms that turning the surface OFF (everything else equal) changes the output —
 * proving the surface is genuinely active end-to-end and the sole driver of the
 * difference vs. the golden.
 *
 * It additionally checks the WINDOW-OFF case (apply_window=False) against the
 * scan_portra_nowindow golden, proving the window toggle is wired too (its
 * default-on path is the existing scan_portra golden).
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_hanatos_surface_e2e.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_hanatos_surface_e2e
 * Run (argv[4] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_hanatos_surface_e2e <asset_dir> <scan_portra_surface_golden_dir> \
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
const char* kSurfaceGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra_surface";
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

// Base scan_portra parameter set (spatial/grain/glare off), shared by all toggle
// variants. Mirrors gen_goldens.py's scan_portra_* cases.
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
    std::string surface_golden_dir = argc > 2 ? argv[2] : kSurfaceGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;
    std::string nowindow_golden_dir =
        "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra_nowindow";
    if (argc > 4) {
        std::string root = argv[4];
        surface_golden_dir = root + "/scan_portra_surface";
        nowindow_golden_dir = root + "/scan_portra_nowindow";
    }

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    // --- Load input image ---
    spkvec::Array gold_rgb = spkvec::read(surface_golden_dir + "/final_rgb.spkvec");
    spkvec::Array gold_logr = spkvec::read(surface_golden_dir + "/film_log_raw.spkvec");
    spkvec::Array gold_cmy = spkvec::read(surface_golden_dir + "/film_density_cmy.spkvec");
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
    // SURFACE ON (window also on): vs scan_portra_surface golden.
    // ---------------------------------------------------------------------------
    spk_params p{};
    set_base_params(&p);
    p.apply_hanatos_window = 1;
    p.apply_hanatos_surface = 1;  // feature under test

    bool pass_logr = false, pass_cmy = false, pass_rgb = false;

    spk_image tap_logr{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_log_raw", &tap_logr);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_log_raw) failed: %s\n", spk_status_str(st));
    } else {
        pass_logr = check("hanatos_surface film_log_raw", tap_logr.data, gold_logr.data);
        spk_image_free(&tap_logr);
    }

    spk_image tap_cmy{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_density_cmy", &tap_cmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_density_cmy) failed: %s\n", spk_status_str(st));
    } else {
        pass_cmy = check("hanatos_surface film_density_cmy", tap_cmy.data, gold_cmy.data);
        spk_image_free(&tap_cmy);
    }

    spk_image out{};
    st = spk_simulate(eng, &in_img, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }
    pass_rgb = check("hanatos_surface final_rgb", out.data, gold_rgb.data);

    // --- Sanity: surface OFF (everything else equal) changes the output, proving
    //     the surface is genuinely active end-to-end and is the sole driver of the
    //     difference vs. the golden. ---
    spk_params p_surf_off = p;
    p_surf_off.apply_hanatos_surface = 0;
    spk_image out_surf_off{};
    double surf_delta = 0.0;
    bool surface_active = false;
    st = spk_simulate(eng, &in_img, &p_surf_off, &out_surf_off);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(surface off) failed: %s\n",
                     spk_status_str(st));
    } else {
        surf_delta = max_abs_diff(out.data, out_surf_off.data,
                                  static_cast<size_t>(npix) * 3);
        surface_active = surf_delta > 1e-4;
        std::printf("[surface on vs off] max_abs = %.6e (must be > 0: the "
                    "log-exposure surface warps the filming tc_lut) -> %s\n",
                    surf_delta, surface_active ? "SURFACE ACTIVE" : "SURFACE INERT?!");
        spk_image_free(&out_surf_off);
    }
    spk_image_free(&out);

    // ---------------------------------------------------------------------------
    // WINDOW OFF (surface also off): vs scan_portra_nowindow golden.
    // ---------------------------------------------------------------------------
    spkvec::Array gnw_rgb = spkvec::read(nowindow_golden_dir + "/final_rgb.spkvec");
    spkvec::Array gnw_logr = spkvec::read(nowindow_golden_dir + "/film_log_raw.spkvec");
    spkvec::Array gnw_cmy = spkvec::read(nowindow_golden_dir + "/film_density_cmy.spkvec");

    spk_params pnw{};
    set_base_params(&pnw);
    pnw.apply_hanatos_window = 0;   // feature under test: window OFF
    pnw.apply_hanatos_surface = 0;

    bool pass_nw_logr = false, pass_nw_cmy = false, pass_nw_rgb = false;

    spk_image nw_logr{};
    st = spk_simulate_tap(eng, &in_img, &pnw, "film_log_raw", &nw_logr);
    if (st == SPK_OK) {
        pass_nw_logr = check("hanatos_nowindow film_log_raw", nw_logr.data, gnw_logr.data);
        spk_image_free(&nw_logr);
    } else {
        std::fprintf(stderr, "tap(nowindow film_log_raw) failed: %s\n", spk_status_str(st));
    }

    spk_image nw_cmy{};
    st = spk_simulate_tap(eng, &in_img, &pnw, "film_density_cmy", &nw_cmy);
    if (st == SPK_OK) {
        pass_nw_cmy = check("hanatos_nowindow film_density_cmy", nw_cmy.data, gnw_cmy.data);
        spk_image_free(&nw_cmy);
    } else {
        std::fprintf(stderr, "tap(nowindow film_density_cmy) failed: %s\n", spk_status_str(st));
    }

    spk_image nw_out{};
    st = spk_simulate(eng, &in_img, &pnw, &nw_out);
    bool window_active = false;
    if (st == SPK_OK) {
        pass_nw_rgb = check("hanatos_nowindow final_rgb", nw_out.data, gnw_rgb.data);
        // Sanity: window OFF differs from the window-ON default (= scan_portra).
        spk_params p_win_on = pnw;
        p_win_on.apply_hanatos_window = 1;
        spk_image win_on{};
        if (spk_simulate(eng, &in_img, &p_win_on, &win_on) == SPK_OK) {
            double win_delta = max_abs_diff(nw_out.data, win_on.data,
                                            static_cast<size_t>(npix) * 3);
            window_active = win_delta > 1e-4;
            std::printf("[window off vs on] max_abs = %.6e (must be > 0: the erf4 "
                        "band-pass + WB normalisation changes the tc_lut) -> %s\n",
                        win_delta, window_active ? "WINDOW ACTIVE" : "WINDOW INERT?!");
            spk_image_free(&win_on);
        }
        spk_image_free(&nw_out);
    } else {
        std::fprintf(stderr, "spk_simulate(nowindow) failed: %s\n", spk_status_str(st));
    }

    spk_engine_destroy(eng);
    bool all = pass_logr && pass_cmy && pass_rgb && surface_active &&
               pass_nw_logr && pass_nw_cmy && pass_nw_rgb && window_active;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
