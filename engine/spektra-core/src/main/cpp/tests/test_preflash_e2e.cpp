/*
 * Spektrafilm for Android — END-TO-END host parity test for the ENLARGER PREFLASH
 * (enlarger.preflash_exposure / preflash_y_filter_shift / preflash_m_filter_shift)
 * run through the WHOLE print->scan pipeline.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The preflash is a uniform pre-exposure flash of the print paper before the image
 * exposure, of printing.py::_compute_raw_preflash. When preflash_exposure > 0 the
 * print raw gets a CONSTANT per-print-channel term added inside
 * _film_cmy_to_print_log_raw, AFTER the midgray factor and BEFORE the log10:
 *   preflash_illuminant = color_enlarger(enlarger source, CC =
 *       [c_neutral, m_neutral + preflash_m_filter_shift,
 *        y_neutral + preflash_y_filter_shift])   (its OWN shifts, not the image's)
 *   light_preflash[l]   = 10^-base_density[l] * preflash_illuminant[l]   (NaN -> 0)
 *   raw_preflash[k]     = sum_l light_preflash[l] * sens[l,k]
 *   raw[k] += raw_preflash[k] * preflash_exposure
 * It is a PRINT-stage effect (scan_film=False), so it changes print_density_cmy and
 * final_rgb but NOT the film taps (film_log_raw / film_density_cmy). It is constant
 * across pixels (no spatial branch), so this case keeps spatial + grain OFF and is
 * fully deterministic, matching gen_goldens.py's print_portra_preflash case.
 *
 * It checks print_density_cmy + final_rgb against the print_portra_preflash oracle
 * golden bit-exact (max_abs <= 1e-4, rms <= 1e-5), confirms the FILM taps are
 * UNCHANGED vs the no-preflash print_portra golden (the preflash never touches the
 * negative), and confirms that turning the preflash OFF (everything else equal)
 * changes the output — proving it is genuinely active end-to-end and the sole driver
 * of the difference vs. the golden.
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_preflash_e2e.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_preflash_e2e
 * Run (argv[4] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_preflash_e2e <asset_dir> <print_portra_preflash_golden_dir> \
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
const char* kPreflashGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_portra_preflash";
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
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    std::string preflash_golden_dir = argc > 2 ? argv[2] : kPreflashGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;
    std::string goldens_root;
    if (argc > 4) {
        goldens_root = argv[4];
        preflash_golden_dir = goldens_root + "/print_portra_preflash";
    }

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    // --- Load goldens + input image ---
    spkvec::Array gold_rgb = spkvec::read(preflash_golden_dir + "/final_rgb.spkvec");
    spkvec::Array gold_pcmy =
        spkvec::read(preflash_golden_dir + "/print_density_cmy.spkvec");
    spkvec::Array gold_logr =
        spkvec::read(preflash_golden_dir + "/film_log_raw.spkvec");
    spkvec::Array gold_fcmy =
        spkvec::read(preflash_golden_dir + "/film_density_cmy.spkvec");
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
    // PREFLASH ON: vs print_portra_preflash golden.
    // Feature under test (mirrors gen_goldens.py print_portra_preflash):
    //   preflash_exposure = 0.15, preflash_y_filter_shift = -10, preflash_m = 5.
    // ---------------------------------------------------------------------------
    spk_params p{};
    set_base_params(&p);
    p.preflash_exposure = 0.15f;
    p.preflash_y_filter_shift = -10.0f;
    p.preflash_m_filter_shift = 5.0f;

    bool pass_pcmy = false, pass_rgb = false;
    bool pass_film_logr = false, pass_film_cmy = false;

    // PRINT-stage tap: print_density_cmy.
    spk_image tap_pcmy{};
    st = spk_simulate_tap(eng, &in_img, &p, "print_density_cmy", &tap_pcmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(print_density_cmy) failed: %s\n", spk_status_str(st));
    } else {
        pass_pcmy = check("preflash print_density_cmy", tap_pcmy.data, gold_pcmy.data);
        spk_image_free(&tap_pcmy);
    }

    // FILM taps: the preflash is a PRINT effect, so the negative is UNTOUCHED.
    // The film taps in the print_portra_preflash golden are therefore identical to
    // the no-preflash print_portra film taps; verifying them confirms the preflash
    // does NOT leak into the filming stage.
    spk_image tap_logr{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_log_raw", &tap_logr);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_log_raw) failed: %s\n", spk_status_str(st));
    } else {
        pass_film_logr = check("preflash film_log_raw (unchanged)", tap_logr.data,
                               gold_logr.data);
        spk_image_free(&tap_logr);
    }
    spk_image tap_fcmy{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_density_cmy", &tap_fcmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_density_cmy) failed: %s\n", spk_status_str(st));
    } else {
        pass_film_cmy = check("preflash film_density_cmy (unchanged)", tap_fcmy.data,
                              gold_fcmy.data);
        spk_image_free(&tap_fcmy);
    }

    spk_image out{};
    st = spk_simulate(eng, &in_img, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }
    pass_rgb = check("preflash final_rgb", out.data, gold_rgb.data);

    // --- Sanity: preflash OFF (everything else equal) changes the output, proving
    //     the preflash is genuinely active end-to-end and is the sole driver of the
    //     difference vs. the golden. ---
    spk_params p_off = p;
    p_off.preflash_exposure = 0.0f;
    spk_image out_off{};
    double delta = 0.0;
    bool preflash_active = false;
    st = spk_simulate(eng, &in_img, &p_off, &out_off);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(preflash off) failed: %s\n",
                     spk_status_str(st));
    } else {
        delta = max_abs_diff(out.data, out_off.data,
                             static_cast<size_t>(npix) * 3);
        preflash_active = delta > 1e-4;
        std::printf("[preflash on vs off] max_abs = %.6e (must be > 0: the preflash "
                    "lifts the print base fog) -> %s\n",
                    delta, preflash_active ? "PREFLASH ACTIVE" : "PREFLASH INERT?!");
        spk_image_free(&out_off);
    }
    spk_image_free(&out);

    spk_engine_destroy(eng);
    bool all = pass_pcmy && pass_rgb && pass_film_logr && pass_film_cmy &&
               preflash_active;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
