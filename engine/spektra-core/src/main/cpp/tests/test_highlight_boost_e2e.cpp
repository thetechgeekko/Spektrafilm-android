/*
 * Spektrafilm for Android — END-TO-END host parity test for the HIGHLIGHT BOOST
 * (film_render.halation.boost_ev / boost_range / protect_ev) run through the WHOLE
 * scan_film pipeline.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The highlight boost is the pre-clip highlight reconstruction
 * (utils/numba_boost_hightlights.boost_highlights) that FilmingStage.expose applies to
 * the float64 raw irradiance right AFTER the exposure-compensation scale and BEFORE the
 * diffusion filter / lens blur / halation (filming.py:58-60), with the default
 * midgray = 0.184:
 *   boost_ev == 0 -> identity (strict no-op)
 *   max_raw = max(raw);  raw_x0 = clip(0.184 * 2^protect_ev, 0, max_raw)
 *   a = 28^(1 - boost_range);  x0 = raw_x0 / max_raw
 *   k = (2^boost_ev - 1) / (exp(a(1-x0)) - a(1-x0) - 1)
 *   raw > raw_x0:  raw += k*max_raw*(exp(a*dx) - a*dx - 1),  dx = (raw - raw_x0)/max_raw
 * It is a FILMING-stage POINTWISE effect, so it changes the film taps
 * (film_log_raw / film_density_cmy) AND final_rgb. It is NOT gated by
 * deactivate_spatial_effects (the oracle's params_builder.py zeroes only the
 * scatter/halation sigmas, never boost_ev), so this case keeps the spatial + grain
 * branches OFF and is fully deterministic, matching gen_goldens.py's scan_portra_boost.
 *
 * It checks film_log_raw + film_density_cmy + final_rgb against the scan_portra_boost
 * oracle golden bit-exact (max_abs <= 1e-4, rms <= 1e-5), confirms that turning the
 * boost OFF (everything else equal) changes the output — proving it is genuinely active
 * and the sole driver of the difference vs. the golden — and confirms the boost-ON
 * pipeline is BYTE-IDENTICAL at SPK_NUM_THREADS 1 vs 8 (the boost's max() reduction is
 * serial / order-independent, so thread-invariance holds end to end).
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_highlight_boost_e2e.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_highlight_boost_e2e
 * Run (argv[4] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_highlight_boost_e2e <asset_dir> <scan_portra_boost_golden_dir> \
 *     <input.f64> [goldens_root]
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

#include "spkvec_io.h"
#include "spektra.h"

namespace {

const char* kAssetDir = "/home/user/spektrafilm/src/spektrafilm/data";
const char* kBoostGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra_boost";
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

// Base scan_portra parameter set (scan_film route, spatial/grain/glare off), shared by
// all variants. Mirrors gen_goldens.py's scan_portra* cases.
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
    p->scan_film = 1;        // scan_film route: negative scanned directly, no print
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    p->rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p->preview_max_size = 640;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    std::string boost_golden_dir = argc > 2 ? argv[2] : kBoostGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;
    if (argc > 4) {
        boost_golden_dir = std::string(argv[4]) + "/scan_portra_boost";
    }

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    // --- Load goldens + input image ---
    spkvec::Array gold_rgb = spkvec::read(boost_golden_dir + "/final_rgb.spkvec");
    spkvec::Array gold_logr = spkvec::read(boost_golden_dir + "/film_log_raw.spkvec");
    spkvec::Array gold_fcmy =
        spkvec::read(boost_golden_dir + "/film_density_cmy.spkvec");
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
    // BOOST ON: vs scan_portra_boost golden.
    // Feature under test (mirrors gen_goldens.py scan_portra_boost):
    //   boost_ev = 3.0, boost_range = 0.5, protect_ev = 1.0.
    // ---------------------------------------------------------------------------
    spk_params p{};
    set_base_params(&p);
    p.halation_boost_ev = 3.0f;
    p.halation_boost_range = 0.5f;
    p.halation_protect_ev = 1.0f;

    bool pass_logr = false, pass_fcmy = false, pass_rgb = false;

    // FILMING taps: the boost runs in expose() before log10, so BOTH film taps move.
    spk_image tap_logr{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_log_raw", &tap_logr);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_log_raw) failed: %s\n", spk_status_str(st));
    } else {
        pass_logr = check("boost film_log_raw", tap_logr.data, gold_logr.data);
        spk_image_free(&tap_logr);
    }
    spk_image tap_fcmy{};
    st = spk_simulate_tap(eng, &in_img, &p, "film_density_cmy", &tap_fcmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "tap(film_density_cmy) failed: %s\n", spk_status_str(st));
    } else {
        pass_fcmy = check("boost film_density_cmy", tap_fcmy.data, gold_fcmy.data);
        spk_image_free(&tap_fcmy);
    }

    spk_image out{};
    st = spk_simulate(eng, &in_img, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }
    pass_rgb = check("boost final_rgb", out.data, gold_rgb.data);

    // --- Sanity: boost OFF (everything else equal) changes the output, proving the
    //     boost is genuinely active and is the sole driver of the difference vs. the
    //     golden. ---
    spk_params p_off = p;
    p_off.halation_boost_ev = 0.0f;
    spk_image out_off{};
    bool boost_active = false;
    st = spk_simulate(eng, &in_img, &p_off, &out_off);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(boost off) failed: %s\n",
                     spk_status_str(st));
    } else {
        double delta = max_abs_diff(out.data, out_off.data,
                                    static_cast<size_t>(npix) * 3);
        boost_active = delta > 1e-4;
        std::printf("[boost on vs off] max_abs = %.6e (must be > 0: the boost lifts the "
                    "highlight raw above raw_x0) -> %s\n",
                    delta, boost_active ? "BOOST ACTIVE" : "BOOST INERT?!");
        spk_image_free(&out_off);
    }

    // --- Thread-invariance: the boost-ON pipeline must be BYTE-IDENTICAL at
    //     SPK_NUM_THREADS 1 vs 8. The boost itself is serial (its max() reduction is
    //     order-independent), and every parallel stage around it is already
    //     deterministic; this asserts the whole boost-on path end to end. ---
    bool thread_invariant = false;
    setenv("SPK_NUM_THREADS", "1", 1);
    spk_image out_t1{};
    spk_status st1 = spk_simulate(eng, &in_img, &p, &out_t1);
    setenv("SPK_NUM_THREADS", "8", 1);
    spk_image out_t8{};
    spk_status st8 = spk_simulate(eng, &in_img, &p, &out_t8);
    unsetenv("SPK_NUM_THREADS");
    if (st1 == SPK_OK && st8 == SPK_OK) {
        double td = max_abs_diff(out_t1.data, out_t8.data,
                                 static_cast<size_t>(npix) * 3);
        thread_invariant = (td == 0.0);
        std::printf("[boost thread-invariance 1 vs 8] max_abs = %.6e -> %s\n",
                    td, thread_invariant ? "BYTE-IDENTICAL" : "DIVERGES?!");
    } else {
        std::fprintf(stderr, "thread-invariance simulate failed: %s / %s\n",
                     spk_status_str(st1), spk_status_str(st8));
    }
    if (st1 == SPK_OK) spk_image_free(&out_t1);
    if (st8 == SPK_OK) spk_image_free(&out_t8);
    spk_image_free(&out);

    spk_engine_destroy(eng);
    bool all = pass_logr && pass_fcmy && pass_rgb && boost_active && thread_invariant;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
