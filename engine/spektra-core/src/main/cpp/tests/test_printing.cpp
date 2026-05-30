/*
 * Spektrafilm for Android — host parity test for the printing (enlarger) stage.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * Two checks:
 *  1) print_portra parity using NATIVE-computed digest inputs (no baked
 *     constants): resolves the neutral dichroic CC from neutral_print_filters.json
 *     and computes the midgray exposure factor natively, then runs the C++
 *     printing + scanning stages and compares BOTH print_density_cmy and
 *     final_rgb to the committed goldens (max_abs <= 1e-4, rms <= 1e-5).
 *  2) An ADDITIONAL pair (kodak_vision3_250d film + kodak_endura_premier paper,
 *     and fujifilm_pro_400h + kodak_portra_endura): verifies the native neutral
 *     CC + midgray factor match the Python oracle reference values dumped offline.
 *
 * Build (host):
 *   g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
 *     tests/test_printing.cpp \
 *     runtime/stages/printing.cpp runtime/stages/scanning.cpp runtime/params.cpp \
 *     runtime/print_digest.cpp runtime/stages/filming.cpp \
 *     model/color_filters.cpp model/density_curves.cpp model/color_output.cpp \
 *     model/emulsion.cpp model/conversions.cpp model/spectral.cpp model/couplers.cpp \
 *     model/glare.cpp kernels/interp.cpp kernels/gaussian.cpp \
 *     kernels/spectral_upsampling.cpp io/npy_lut.cpp profiles/profile.cpp \
 *     -o /tmp/test_printing
 */
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "spkvec_io.h"

#include "io/npy_lut.h"
#include "profiles/profile.h"
#include "runtime/params.h"
#include "runtime/print_digest.h"
#include "runtime/stages/filming.h"
#include "runtime/stages/printing.h"
#include "runtime/stages/scanning.h"

namespace {

const char* kProfilesDir =
    "/home/user/spektrafilm/src/spektrafilm/data/profiles";
const char* kFiltersJson =
    "/home/user/spektrafilm/src/spektrafilm/data/filters/neutral_print_filters.json";
const char* kSpectraLut =
    "/home/user/spektrafilm/src/spektrafilm/data/luts/spectral_upsampling/"
    "irradiance_xy_tc.npy";
const char* kGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_portra";

const float kDensityCurveGamma = 1.0f;

// D55 standard illuminant (film reference illuminant), 380..780 @5nm, mean-norm.
static const double kD55Illuminant[81] = {
    0.3792826592081565,0.41130471283820924,0.4433384066186183,0.5763969653409493,
    0.7094555240632804,0.7537113757177554,0.7979788675225865,0.8155671347108852,
    0.8331670420495402,0.8118539267472403,0.7905291712945844,0.8934979413460009,
    0.996455071247061,1.0685541625536938,1.1406532538603265,1.1550288395502992,
    1.169404425240272,1.1662033838923025,1.1630023425443328,1.1794498749977185,
    1.1958974074511046,1.1687758571210345,1.141642666640608,1.1567865022540935,
    1.1719303378675792,1.172023459070429,1.1721049401229227,1.167984326896809,
    1.1638637136706955,1.1884360710727462,1.213020068625153,1.2007513501496623,
    1.1884826316741712,1.1935228167784289,1.1985630018826865,1.1812890187540066,
    1.1640150356253267,1.1478119463294223,1.1316088570335177,1.134705137028281,
    1.1378130571734006,1.1010418221979967,1.0642822273729489,1.0816726120051912,
    1.0990513564870772,1.1032534507656848,1.107443904893936,1.1020894357300595,
    1.096734966566183,1.0747816429942894,1.0528283194223955,1.0637817009076298,
    1.0747350823928643,1.054504501073696,1.0342739197545279,1.0427945098153053,
    1.0513034597257263,1.0724419727726824,1.0935921259699946,1.0703467457085567,
    1.047101365447119,0.9872826327663333,0.9274522599351918,0.945855337648428,
    0.9642700555120207,0.9759334861689865,0.9875969168259522,0.9025656184735221,
    0.8175459602714483,0.8703107618363444,0.9230755634012404,0.9562034313151373,
    0.989331299229034,0.9130184734934376,0.8366940076074848,0.72561205275776,
    0.6145184577576788,0.7491600769284603,0.883801696099242,0.8598811871171415,
    0.8359723182853972};

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

std::string profile_path(const std::string& stock) {
    return std::string(kProfilesDir) + "/" + stock + ".json";
}

// Resolve the native digest (neutral CC + midgray factor) for a (film, paper)
// pair, exactly as run_print does.
struct Digest {
    double cc[3];
    double midgray;
    spk::PrintingParams pparams;
};

Digest native_digest(const spk::Profile& film, const spk::Profile& prnt,
                     const spk::NdArray& spectra_lut) {
    Digest d{};
    spk::NdArray tc_lut = spk::build_filming_tc_lut(film, spectra_lut, kD55Illuminant);
    const double* enl = spk::enlarger_illuminant("TH-KG3");
    spk::resolve_neutral_cc(kFiltersJson, prnt.stock, "TH-KG3", film.stock, d.cc);
    d.pparams = spk::digest_printing_params(d.cc, enl, 1.0, kDensityCurveGamma);
    d.midgray = spk::compute_midgray_exposure_factor(
        film, prnt, tc_lut, d.pparams.filtered_illuminant, kDensityCurveGamma);
    d.pparams.exposure_factor_midgray = d.midgray;
    return d;
}

// Verify the native CC + midgray for an additional pair against oracle refs.
bool check_pair(const char* film_stock, const char* print_stock,
                const spk::NdArray& spectra_lut,
                const double cc_ref[3], double midgray_ref) {
    spk::Profile film = spk::load_profile_file(profile_path(film_stock));
    spk::Profile prnt = spk::load_profile_file(profile_path(print_stock));
    Digest d = native_digest(film, prnt, spectra_lut);

    const double tol_cc = 1e-6, tol_mg = 1e-6;
    double cc_err = 0.0;
    for (int k = 0; k < 3; ++k)
        cc_err = std::fmax(cc_err, std::fabs(d.cc[k] - cc_ref[k]));
    double mg_err = std::fabs(d.midgray - midgray_ref);
    bool pass = (cc_err <= tol_cc) && (mg_err <= tol_mg);

    std::printf("[pair %s -> %s]\n", film_stock, print_stock);
    std::printf("   neutral_cc native = {%.12f, %.12f, %.12f}\n",
                d.cc[0], d.cc[1], d.cc[2]);
    std::printf("   neutral_cc oracle = {%.12f, %.12f, %.12f}  (max err %.3e)\n",
                cc_ref[0], cc_ref[1], cc_ref[2], cc_err);
    std::printf("   midgray    native = %.15f\n", d.midgray);
    std::printf("   midgray    oracle = %.15f  (err %.3e)\n", midgray_ref, mg_err);
    std::printf("   -> %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

}  // namespace

int main(int argc, char** argv) {
    std::string golden_dir = argc > 1 ? argv[1] : kGoldenDir;

    spk::NdArray spectra_lut;
    try {
        spectra_lut = spk::load_npy(kSpectraLut);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "failed to load spectra LUT: %s\n", e.what());
        return 2;
    }

    // ---- 1) print_portra parity with NATIVE digest -------------------------
    spk::Profile film = spk::load_profile_file(profile_path("kodak_portra_400"));
    spk::Profile prnt = spk::load_profile_file(profile_path("kodak_portra_endura"));
    std::printf("film:  type=%s stock=%s samples=%d\n",
                film.type.c_str(), film.stock.c_str(), film.n_samples);
    std::printf("print: type=%s stock=%s viewing=%s samples=%d curves=%d\n",
                prnt.type.c_str(), prnt.stock.c_str(),
                prnt.viewing_illuminant.c_str(), prnt.n_samples, prnt.n_density_pts);

    Digest pd = native_digest(film, prnt, spectra_lut);
    std::printf("native neutral_cc = {%.12f, %.12f, %.12f}\n",
                pd.cc[0], pd.cc[1], pd.cc[2]);
    std::printf("native midgray    = %.15f\n", pd.midgray);

    spkvec::Array in   = spkvec::read(golden_dir + "/film_density_cmy.spkvec");
    spkvec::Array gold_cmy = spkvec::read(golden_dir + "/print_density_cmy.spkvec");
    spkvec::Array gold_rgb = spkvec::read(golden_dir + "/final_rgb.spkvec");

    const int height = static_cast<int>(in.shape[0]);
    const int width  = static_cast<int>(in.shape[1]);
    const int npix   = width * height;
    std::printf("input %dx%d x3\n", width, height);

    std::vector<float> log_raw_print(static_cast<size_t>(npix) * 3);
    spk::print_expose(film, prnt, pd.pparams, in.data.data(), width, height,
                      log_raw_print.data());
    std::vector<float> cmy_print(static_cast<size_t>(npix) * 3);
    spk::print_develop(prnt, pd.pparams, log_raw_print.data(), npix,
                       cmy_print.data());

    Stats s_cmy = compare(cmy_print, gold_cmy.data);
    bool pass_cmy = report("print_density_cmy", s_cmy, cmy_print, gold_cmy.data);

    spk::ScanningParams sparams;
    sparams.scan_film = false;
    sparams.output_cctf_encoding = true;
    std::vector<float> rgb(static_cast<size_t>(npix) * 3);
    spk::scan(prnt, sparams, cmy_print.data(), width, height, rgb.data());

    Stats s_rgb = compare(rgb, gold_rgb.data);
    bool pass_rgb = report("final_rgb", s_rgb, rgb, gold_rgb.data);

    // ---- 2) additional pairs: native CC + midgray vs oracle ----------------
    // Oracle reference values dumped from the Python engine (digest_params +
    // PrintingStage._compute_exposure_factor_midgray) — see report.
    const double kV3CC[3]   = {0.0, 46.08844728032178, 46.09645970154355};
    const double kV3Midgray = 0.7256924928935826;
    bool pass_v3 = check_pair("kodak_vision3_250d", "kodak_endura_premier",
                              spectra_lut, kV3CC, kV3Midgray);

    const double kFujiCC[3]   = {0.0, 33.23358746215229, 28.236662042356137};
    const double kFujiMidgray = 0.6935020619264397;
    bool pass_fuji = check_pair("fujifilm_pro_400h", "kodak_portra_endura",
                                spectra_lut, kFujiCC, kFujiMidgray);

    bool pass = pass_cmy && pass_rgb && pass_v3 && pass_fuji;
    std::printf("%s\n", pass ? "ALL PASS" : "FAIL");
    return pass ? 0 : 1;
}
