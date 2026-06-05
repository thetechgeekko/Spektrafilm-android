/*
 * Spektrafilm for Android — END-TO-END host parity test for the PRINT-route
 * EXPOSURE-COMPENSATION midgray balance (camera.exposure_compensation_ev +
 * enlarger.print_exposure_compensation / normalize_print_exposure) run through
 * the WHOLE print->scan pipeline.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * THE BUG THIS GATES: on the PRINT route the midgray exposure factor must follow
 * PrintingStage._compute_exposure_factor_midgray (printing.py c1d0e44 L104-118):
 *   factor_midgray      = _exposure_factor(...density_spectral_midgray)       (rgb=0.184)
 *   factor_midgray_comp = _exposure_factor(...density_spectral_midgray_comp)  (rgb=0.184*2^ev)
 *                         (only when print_exposure_compensation, else 1.0)
 *   comp && !normalize : factor_midgray_comp / factor_midgray
 *   normalize && comp  : factor_midgray_comp
 *   normalize && !comp : factor_midgray
 *   else               : 1.0
 * The two midgray densities come from
 * FilmingStage._compute_density_spectral_midgray_to_balance_print (filming.py
 * c1d0e44 L125-134), where the COMPENSATED gray is 0.184 * 2^exposure_compensation_ev
 * (the SAME camera EV that scales the filming raw, filming.py L57). The native
 * engine previously hardcoded ev==0 and always returned the UNcompensated factor,
 * mis-balancing the print whenever EV!=0.
 *
 * Two oracle goldens (both EV=1.5, pinned to oracle c1d0e44):
 *   print_portra_evcomp         : comp ON,  normalize ON  -> factor_midgray_comp
 *   print_portra_evcomp_nonorm  : comp ON,  normalize OFF -> factor_midgray_comp / factor_midgray
 * Together they pin both EV-active branches of the 4-case selection.
 *
 * This test:
 *   1) checks film_log_raw + film_density_cmy + print_density_cmy + final_rgb of the
 *      EV-comp (norm) golden bit-exact (max_abs <= 1e-4, rms <= 1e-5),
 *   2) checks print_density_cmy + final_rgb of the EV-comp (no-norm) golden bit-exact,
 *   3) confirms a GENUINE delta vs EV=0 (turning the camera EV to 0, everything else
 *      equal, changes the print) — proving the EV path is active end-to-end,
 *   4) confirms the norm vs no-norm branches DIFFER (the 4-case selection is wired).
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_print_evcomp_e2e.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_print_evcomp_e2e
 * Run (argv[4] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_print_evcomp_e2e <asset_dir> <print_portra_evcomp_golden_dir> \
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
const char* kEvcompGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_portra_evcomp";
const char* kInputF64 =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "scan_portra_input_rgb.f64";

constexpr float kEv = 1.5f;  // matches gen_goldens.py print_portra_evcomp* cases.

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

// Base print_portra parameter set (PRINT route, spatial/grain/glare off), shared by
// all variants. Mirrors gen_goldens.py's print_portra* cases.
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
    p->scan_film = 0;        // PRINT route (negative -> enlarger -> print -> scan)
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    p->rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p->preview_max_size = 640;
    // Enlarger midgray toggles default ON (schema). spk_default_params sets both.
    p->print_exposure_compensation = 1;
    p->normalize_print_exposure = 1;
}

bool tap_check(spk_engine* eng, const spk_image* in_img, spk_params* p,
               const char* tap, const std::vector<float>& gold,
               const char* label) {
    spk_image t{};
    spk_status st = spk_simulate_tap(eng, in_img, p, tap, &t);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(%s) failed: %s\n", tap, spk_status_str(st));
        return false;
    }
    bool ok = check(label, t.data, gold);
    spk_image_free(&t);
    return ok;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    std::string evcomp_golden_dir = argc > 2 ? argv[2] : kEvcompGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;
    std::string goldens_root;
    if (argc > 4) {
        goldens_root = argv[4];
        evcomp_golden_dir = goldens_root + "/print_portra_evcomp";
    } else {
        // Derive the goldens root from the (norm) golden dir for the sibling case.
        const std::string suffix = "/print_portra_evcomp";
        if (evcomp_golden_dir.size() >= suffix.size() &&
            evcomp_golden_dir.compare(evcomp_golden_dir.size() - suffix.size(),
                                      suffix.size(), suffix) == 0) {
            goldens_root =
                evcomp_golden_dir.substr(0, evcomp_golden_dir.size() - suffix.size());
        }
    }
    std::string nonorm_golden_dir = goldens_root + "/print_portra_evcomp_nonorm";

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    // --- Load goldens + input image ---
    spkvec::Array g_rgb = spkvec::read(evcomp_golden_dir + "/final_rgb.spkvec");
    spkvec::Array g_pcmy = spkvec::read(evcomp_golden_dir + "/print_density_cmy.spkvec");
    spkvec::Array g_logr = spkvec::read(evcomp_golden_dir + "/film_log_raw.spkvec");
    spkvec::Array g_fcmy = spkvec::read(evcomp_golden_dir + "/film_density_cmy.spkvec");
    spkvec::Array gn_rgb = spkvec::read(nonorm_golden_dir + "/final_rgb.spkvec");
    spkvec::Array gn_pcmy =
        spkvec::read(nonorm_golden_dir + "/print_density_cmy.spkvec");
    const int height = static_cast<int>(g_rgb.shape[0]);
    const int width = static_cast<int>(g_rgb.shape[1]);
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

    bool ok = true;

    // -------------------------------------------------------------------------
    // CASE A: EV-comp, normalize ON (default toggles) -> factor_midgray_comp.
    // -------------------------------------------------------------------------
    spk_params pa{};
    set_base_params(&pa);
    pa.exposure_compensation_ev = kEv;  // NON-zero camera EV (the bug trigger).
    // print_exposure_compensation=1, normalize_print_exposure=1 (set in base).

    // Film taps: the EV scales the filming raw, so the negative changes too.
    ok &= tap_check(eng, &in_img, &pa, "film_log_raw", g_logr.data,
                    "evcomp film_log_raw");
    ok &= tap_check(eng, &in_img, &pa, "film_density_cmy", g_fcmy.data,
                    "evcomp film_density_cmy");
    ok &= tap_check(eng, &in_img, &pa, "print_density_cmy", g_pcmy.data,
                    "evcomp print_density_cmy");

    spk_image out_a{};
    st = spk_simulate(eng, &in_img, &pa, &out_a);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(case A) failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }
    ok &= check("evcomp final_rgb", out_a.data, g_rgb.data);

    // -------------------------------------------------------------------------
    // CASE B: EV-comp, normalize OFF -> factor_midgray_comp / factor_midgray.
    // -------------------------------------------------------------------------
    spk_params pb = pa;
    pb.normalize_print_exposure = 0;  // the OTHER 4-case branch.
    ok &= tap_check(eng, &in_img, &pb, "print_density_cmy", gn_pcmy.data,
                    "evcomp_nonorm print_density_cmy");
    spk_image out_b{};
    st = spk_simulate(eng, &in_img, &pb, &out_b);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(case B) failed: %s\n", spk_status_str(st));
        spk_image_free(&out_a);
        spk_engine_destroy(eng);
        return 2;
    }
    ok &= check("evcomp_nonorm final_rgb", out_b.data, gn_rgb.data);

    // -------------------------------------------------------------------------
    // SANITY 1: EV=0 (everything else equal to case A) changes the output —
    //   proving the camera EV is genuinely active on the print route.
    // -------------------------------------------------------------------------
    spk_params p_ev0 = pa;
    p_ev0.exposure_compensation_ev = 0.0f;
    spk_image out_ev0{};
    bool ev_active = false;
    st = spk_simulate(eng, &in_img, &p_ev0, &out_ev0);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(EV=0) failed: %s\n", spk_status_str(st));
    } else {
        double d = max_abs_diff(out_a.data, out_ev0.data,
                                static_cast<size_t>(npix) * 3);
        ev_active = d > 1e-4;
        std::printf("[EV=%.1f vs EV=0] max_abs = %.6e (must be > 0: EV re-balances "
                    "the print) -> %s\n",
                    kEv, d, ev_active ? "EV ACTIVE" : "EV INERT?!");
        spk_image_free(&out_ev0);
    }

    // -------------------------------------------------------------------------
    // SANITY 2: norm vs no-norm branches differ (the 4-case selection is wired).
    // -------------------------------------------------------------------------
    double d_branch = max_abs_diff(out_a.data, out_b.data,
                                   static_cast<size_t>(npix) * 3);
    bool branch_active = d_branch > 1e-4;
    std::printf("[normalize ON vs OFF] max_abs = %.6e (must be > 0: the 4-case "
                "branch selects a different midgray factor) -> %s\n",
                d_branch, branch_active ? "BRANCH ACTIVE" : "BRANCH INERT?!");

    spk_image_free(&out_a);
    spk_image_free(&out_b);
    spk_engine_destroy(eng);

    bool all = ok && ev_active && branch_active;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
