/*
 * SpectraFilm for Android — host parity test for the printing (enlarger) stage.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * Loads the print_portra golden film_density_cmy.spkvec and the
 * kodak_portra_400 (film) + kodak_portra_endura (print) profiles, runs the C++
 * printing stage (expose + develop) then the scanning stage on the print, and
 * compares BOTH print_density_cmy and final_rgb to the committed goldens,
 * reporting max_abs / rms and PASS/FAIL against the manifest tolerances
 * (max_abs <= 1e-4, rms <= 1e-5).
 *
 * Build (host):
 *   g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
 *     tests/test_printing.cpp \
 *     runtime/stages/printing.cpp runtime/stages/scanning.cpp runtime/params.cpp \
 *     model/color_filters.cpp model/density_curves.cpp model/color_output.cpp \
 *     model/emulsion.cpp model/conversions.cpp model/spectral.cpp \
 *     kernels/interp.cpp profiles/profile.cpp -o /tmp/test_printing
 */
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "spkvec_io.h"

#include "profiles/profile.h"
#include "runtime/params.h"
#include "runtime/stages/printing.h"
#include "runtime/stages/scanning.h"

namespace {

const char* kFilmPath =
    "/home/user/spektrafilm/src/spektrafilm/data/profiles/kodak_portra_400.json";
const char* kPrintPath =
    "/home/user/spektrafilm/src/spektrafilm/data/profiles/kodak_portra_endura.json";
const char* kGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_portra";

// Digested values for the print_portra case, pulled from the Python oracle:
//   neutral_print_filters.json["kodak_portra_endura"]["TH-KG3"]["kodak_portra_400"]
const double kNeutralCC[3] = {0.0, 51.43162770877449, 55.26070894686862};
// PrintingStage._compute_exposure_factor_midgray (= factor_midgray_comp).
const double kExposureFactorMidgray = 0.8530971500678061;
const float kDensityCurveGamma = 1.0f;

struct Stats { double max_abs; double rms; size_t argmax; };

Stats compare(const std::vector<float>& got, const std::vector<float>& gold) {
    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < got.size(); ++i) {
        double d = std::fabs(static_cast<double>(got[i]) -
                             static_cast<double>(gold[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    return {max_abs, std::sqrt(sse / static_cast<double>(got.size())), argmax};
}

bool report(const char* name, const Stats& s,
            const std::vector<float>& got, const std::vector<float>& gold) {
    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass = (s.max_abs <= tol_max_abs) && (s.rms <= tol_rms);
    std::printf("[%s] max_abs = %.6e (tol %.0e)  rms = %.6e (tol %.0e)\n",
                name, s.max_abs, tol_max_abs, s.rms, tol_rms);
    std::printf("       worst idx=%zu: got=%.8f golden=%.8f -> %s\n",
                s.argmax, got[s.argmax], gold[s.argmax], pass ? "PASS" : "FAIL");
    return pass;
}

}  // namespace

int main(int argc, char** argv) {
    std::string film_path  = argc > 1 ? argv[1] : kFilmPath;
    std::string print_path = argc > 2 ? argv[2] : kPrintPath;
    std::string golden_dir = argc > 3 ? argv[3] : kGoldenDir;

    spk::Profile film = spk::load_profile_file(film_path);
    spk::Profile prnt = spk::load_profile_file(print_path);
    std::printf("film:  type=%s samples=%d\n", film.type.c_str(), film.n_samples);
    std::printf("print: type=%s viewing=%s samples=%d curves=%d\n",
                prnt.type.c_str(), prnt.viewing_illuminant.c_str(),
                prnt.n_samples, prnt.n_density_pts);

    spkvec::Array in   = spkvec::read(golden_dir + "/film_density_cmy.spkvec");
    spkvec::Array gold_cmy = spkvec::read(golden_dir + "/print_density_cmy.spkvec");
    spkvec::Array gold_rgb = spkvec::read(golden_dir + "/final_rgb.spkvec");

    const int height = static_cast<int>(in.shape[0]);
    const int width  = static_cast<int>(in.shape[1]);
    const int npix   = width * height;
    std::printf("input %dx%d x3\n", width, height);

    // Digested printing params (enlarger TH-KG3 + neutral CC + midgray factor).
    const double* enl = spk::enlarger_illuminant("TH-KG3");
    if (!enl) { std::fprintf(stderr, "no enlarger illuminant\n"); return 2; }
    spk::PrintingParams pparams = spk::digest_printing_params(
        kNeutralCC, enl, kExposureFactorMidgray, kDensityCurveGamma);

    // expose + develop the print.
    std::vector<float> log_raw_print(static_cast<size_t>(npix) * 3);
    spk::print_expose(film, prnt, pparams, in.data.data(), npix,
                      log_raw_print.data());
    std::vector<float> cmy_print(static_cast<size_t>(npix) * 3);
    spk::print_develop(prnt, pparams, log_raw_print.data(), npix,
                       cmy_print.data());

    Stats s_cmy = compare(cmy_print, gold_cmy.data);
    bool pass_cmy = report("print_density_cmy", s_cmy, cmy_print, gold_cmy.data);

    // scan the print: D50 viewing illuminant, print profile's dyes.
    spk::ScanningParams sparams;
    sparams.scan_film = false;
    sparams.output_cctf_encoding = true;
    std::vector<float> rgb(static_cast<size_t>(npix) * 3);
    spk::scan(prnt, sparams, cmy_print.data(), width, height, rgb.data());

    Stats s_rgb = compare(rgb, gold_rgb.data);
    bool pass_rgb = report("final_rgb", s_rgb, rgb, gold_rgb.data);

    bool pass = pass_cmy && pass_rgb;
    std::printf("%s\n", pass ? "ALL PASS" : "FAIL");
    return pass ? 0 : 1;
}
