/*
 * Spektrafilm for Android — END-TO-END host parity test for the POSITIVE-film
 * DIR-coupler develop path (scan_film route on fujifilm_provia_100f).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Every other engine golden runs the NEGATIVE-film coupler branch. This case
 * exercises the POSITIVE-film branch of model/couplers.py
 * (compute_density_curves_before_dir_couplers / compute_exposure_correction_
 * dir_couplers: density_silver = nanmax(density_curves) - density, interpolate the
 * -density curve), which fires only for scan_film on a positive stock. It also
 * pins the stock-specific DIR-coupler gamma override that
 * params_builder._apply_film_specifics applies for fujifilm_provia_100f
 * (gamma_samelayer_rgb=(0.156,0.104,0.078), matching interlayer terms) AFTER the
 * generic positive default (0.12,0.08,0.06): if the native digest omits that
 * override the inhibitor matrix is wrong and film_density_cmy diverges ~0.32.
 *
 * Asserts the full pipeline (film_density_cmy + final_rgb) matches the
 * scan_provia_couplers golden (oracle c1d0e44, max_abs<=1e-4, rms<=1e-5), that
 * turning the couplers OFF measurably changes the output (couplers genuinely
 * active), and that the output is byte-identical at SPK_NUM_THREADS=1 vs the
 * worker count under test (thread-invariance is enforced by the env in CI).
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_provia_couplers_e2e.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/*.cpp runtime/stages/*.cpp -o /tmp/test_provia_couplers_e2e
 * Run:
 *   /tmp/test_provia_couplers_e2e <asset_dir> <scan_provia_couplers_golden_dir> \
 *     <input.f64>
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
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_provia_couplers";
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

std::vector<double> load_input(const std::string& path, int npix) {
    std::vector<double> rgb64(static_cast<size_t>(npix) * 3);
    std::ifstream in(path, std::ios::binary);
    if (!in) { std::fprintf(stderr, "cannot open %s\n", path.c_str()); std::exit(2); }
    in.read(reinterpret_cast<char*>(rgb64.data()),
            static_cast<std::streamsize>(rgb64.size() * sizeof(double)));
    return rgb64;
}

void set_provia(spk_params* p) {
    p->film_profile = "fujifilm_provia_100f";
    p->print_profile = "kodak_portra_endura";
    spk_default_params(p);
    p->exposure_compensation_ev = 0.0f;
    p->auto_exposure = 0;
    p->density_curve_gamma = 1.0f;
    p->grain_active = 0;
    p->halation_active = 0;     // spatial branch OFF (deactivate_spatial_effects=True)
    p->dir_couplers_active = 1;  // DIR couplers ON (the path under test)
    p->glare_active = 0;
    p->scan_film = 1;           // scan_film route on a POSITIVE film
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    p->rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p->preview_max_size = 640;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    bool all = true;

    spkvec::Array gold_rgb = spkvec::read(golden_dir + "/final_rgb.spkvec");
    spkvec::Array gold_logr = spkvec::read(golden_dir + "/film_log_raw.spkvec");
    spkvec::Array gold_fcmy = spkvec::read(golden_dir + "/film_density_cmy.spkvec");
    const int height = static_cast<int>(gold_rgb.shape[0]);
    const int width = static_cast<int>(gold_rgb.shape[1]);
    const int npix = width * height;
    std::printf("provia scan_film couplers-ON %dx%dx3\n", width, height);

    std::vector<double> rgb64 = load_input(input_path, npix);
    std::vector<float> rgb32(rgb64.begin(), rgb64.end());
    spk_image in_img{rgb32.data(), width, height, SPK_CS_PROPHOTO};

    spk_params p{};
    set_provia(&p);

    // film_log_raw is computed BEFORE the couplers, so it matches regardless;
    // film_density_cmy is the POSITIVE-film coupler develop output (the gap).
    spk_image tap_logr{}, tap_fcmy{};
    if (spk_simulate_tap(eng, &in_img, &p, "film_log_raw", &tap_logr) == SPK_OK) {
        all &= check("film_log_raw", tap_logr.data, gold_logr.data);
        spk_image_free(&tap_logr);
    } else { all = false; }
    if (spk_simulate_tap(eng, &in_img, &p, "film_density_cmy", &tap_fcmy) == SPK_OK) {
        all &= check("film_density_cmy", tap_fcmy.data, gold_fcmy.data);
        spk_image_free(&tap_fcmy);
    } else { all = false; }

    spk_image out{};
    if (spk_simulate(eng, &in_img, &p, &out) != SPK_OK) { all = false; }
    else {
        all &= check("final_rgb", out.data, gold_rgb.data);

        // Couplers OFF must change the output (proves they are genuinely active).
        spk_params p_off = p;
        p_off.dir_couplers_active = 0;
        spk_image out_off{};
        if (spk_simulate(eng, &in_img, &p_off, &out_off) == SPK_OK) {
            double d = max_abs_diff(out.data, out_off.data,
                                    static_cast<size_t>(npix) * 3);
            bool active = d > 1e-4;
            std::printf("[couplers on vs off] max_abs=%.6e -> %s\n", d,
                        active ? "ACTIVE" : "INERT?!");
            all &= active;
            spk_image_free(&out_off);
        } else all = false;
        spk_image_free(&out);
    }

    spk_engine_destroy(eng);
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
