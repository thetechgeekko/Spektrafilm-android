/*
 * Spektrafilm for Android — host parity test for the filming stage.
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
 * Regenerates the scan_portra synthetic input (the float64 output of
 * gen_goldens.py::make_test_image, seed 20260529, 64x64x3, ProPhoto linear —
 * shipped here as scan_portra_input_rgb.f64), runs the C++ filming stage
 * (expose -> develop) and compares to BOTH goldens film_log_raw.spkvec and
 * film_density_cmy.spkvec, reporting max_abs / rms and PASS/FAIL against the
 * manifest tolerances (max_abs <= 1e-4, rms <= 1e-5).
 *
 * Build (host):
 *   g++ -std=c++17 -O2 \
 *     -I <cpp_root> -I <tools/parity> \
 *     tests/test_filming.cpp \
 *     runtime/stages/filming.cpp runtime/params.cpp \
 *     model/couplers.cpp model/density_curves.cpp \
 *     kernels/spectral_upsampling.cpp kernels/interp.cpp \
 *     io/npy_lut.cpp profiles/profile.cpp \
 *     -o /tmp/test_filming
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "spkvec_io.h"

#include "io/npy_lut.h"
#include "profiles/profile.h"
#include "runtime/params.h"
#include "runtime/stages/filming.h"

namespace {

const char* kProfilePath =
    "/home/user/spektrafilm/src/spektrafilm/data/profiles/kodak_portra_400.json";
const char* kGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra";
const char* kSpectraLut =
    "/home/user/spektrafilm/src/spektrafilm/data/luts/spectral_upsampling/"
    "irradiance_xy_tc.npy";
const char* kInputF64 =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "scan_portra_input_rgb.f64";

// D55 standard illuminant (colour-science SDS_ILLUMINANTS['D55'] aligned to the
// 380..780 @5nm working shape, normalised by mean), the film reference
// illuminant for the bundled negative profiles. Baked at full double precision
// from the Python oracle (standard_illuminant('D55')).
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

struct Metrics {
    double max_abs;
    double rms;
    size_t argmax;
};

Metrics compare(const std::vector<float>& got, const std::vector<float>& gold) {
    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < got.size(); ++i) {
        double d = std::fabs(static_cast<double>(got[i]) - static_cast<double>(gold[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    return {max_abs, std::sqrt(sse / static_cast<double>(got.size())), argmax};
}

bool report(const char* name, const Metrics& m, const std::vector<float>& got,
            const std::vector<float>& gold) {
    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass = (m.max_abs <= tol_max_abs) && (m.rms <= tol_rms);
    std::printf("[%s] max_abs = %.6e (tol %.0e)\n", name, m.max_abs, tol_max_abs);
    std::printf("[%s] rms     = %.6e (tol %.0e)\n", name, m.rms, tol_rms);
    std::printf("[%s] worst idx=%zu: got=%.8f golden=%.8f\n", name, m.argmax,
                got[m.argmax], gold[m.argmax]);
    std::printf("[%s] %s\n", name, pass ? "PASS" : "FAIL");
    return pass;
}

}  // namespace

int main(int argc, char** argv) {
    std::string profile_path = argc > 1 ? argv[1] : kProfilePath;
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;
    std::string spectra_path = argc > 3 ? argv[3] : kSpectraLut;
    std::string input_path = argc > 4 ? argv[4] : kInputF64;

    spk::Profile film = spk::load_profile_file(profile_path);
    std::printf("Loaded profile: type=%s ref_illuminant=%s samples=%d density_pts=%d\n",
                film.type.c_str(), film.reference_illuminant.c_str(), film.n_samples,
                film.n_density_pts);
    if (film.log_sensitivity.empty() || film.log_exposure.empty() ||
        film.window_params.size() < 4) {
        std::fprintf(stderr, "profile missing filming fields\n");
        return 2;
    }

    spk::NdArray spectra_lut;
    try {
        spectra_lut = spk::load_npy(spectra_path);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "failed to load spectra LUT %s: %s\n",
                     spectra_path.c_str(), e.what());
        return 2;
    }
    std::printf("spectra LUT shape: %dx%dx%d\n", spectra_lut.shape[0],
                spectra_lut.shape[1], spectra_lut.shape[2]);

    spkvec::Array gold_log = spkvec::read(golden_dir + "/film_log_raw.spkvec");
    spkvec::Array gold_cmy = spkvec::read(golden_dir + "/film_density_cmy.spkvec");
    const int height = static_cast<int>(gold_log.shape[0]);
    const int width = static_cast<int>(gold_log.shape[1]);
    const int npix = height * width;
    std::printf("Image: %dx%dx3 (%d pixels)\n", width, height, npix);

    // Read the deterministic float64 synthetic input (make_test_image output).
    std::vector<double> rgb(static_cast<size_t>(npix) * 3);
    {
        std::ifstream in(input_path, std::ios::binary);
        if (!in) { std::fprintf(stderr, "cannot open input %s\n", input_path.c_str()); return 2; }
        in.read(reinterpret_cast<char*>(rgb.data()),
                static_cast<std::streamsize>(rgb.size() * sizeof(double)));
        if (in.gcount() != static_cast<std::streamsize>(rgb.size() * sizeof(double))) {
            std::fprintf(stderr, "input size mismatch\n");
            return 2;
        }
    }

    spk::FilmingParams params = spk::digest_filming_params(film.is_negative());

    spk::NdArray tc_lut = spk::build_filming_tc_lut(film, spectra_lut, kD55Illuminant);
    std::printf("tc_lut shape: %dx%dx%d\n", tc_lut.shape[0], tc_lut.shape[1],
                tc_lut.shape[2]);

    std::vector<float> log_raw(static_cast<size_t>(npix) * 3);
    spk::expose(rgb.data(), width, height, params, tc_lut, log_raw.data());

    std::vector<float> density_cmy(static_cast<size_t>(npix) * 3);
    spk::develop(log_raw.data(), width, height, film, params, density_cmy.data());

    if (log_raw.size() != gold_log.data.size() ||
        density_cmy.size() != gold_cmy.data.size()) {
        std::fprintf(stderr, "size mismatch vs goldens\n");
        return 2;
    }

    Metrics m_log = compare(log_raw, gold_log.data);
    Metrics m_cmy = compare(density_cmy, gold_cmy.data);
    bool pass_log = report("film_log_raw", m_log, log_raw, gold_log.data);
    bool pass_cmy = report("film_density_cmy", m_cmy, density_cmy, gold_cmy.data);

    return (pass_log && pass_cmy) ? 0 : 1;
}
