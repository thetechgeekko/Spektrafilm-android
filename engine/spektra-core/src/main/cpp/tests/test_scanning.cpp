/*
 * Spektrafilm for Android — host parity test for the scanning stage.
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
 * Loads the scan_portra golden input (film_density_cmy.spkvec) and the
 * kodak_portra_400 profile, runs the C++ scanning stage, and compares the result
 * to final_rgb.spkvec, reporting max_abs / rms and PASS/FAIL against the manifest
 * tolerances (max_abs <= 1e-4, rms <= 1e-5).
 *
 * Build (host):
 *   g++ -std=c++17 -O2 \
 *     -I <cpp_root> -I <tools/parity> \
 *     tests/test_scanning.cpp \
 *     runtime/stages/scanning.cpp model/color_output.cpp model/emulsion.cpp \
 *     model/conversions.cpp model/spectral.cpp profiles/profile.cpp \
 *     -o /tmp/test_scanning
 */
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "spkvec_io.h"

#include "profiles/profile.h"
#include "runtime/stages/scanning.h"

namespace {

const char* kProfilePath =
    "/home/user/spektrafilm/src/spektrafilm/data/profiles/kodak_portra_400.json";
const char* kGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra";

}  // namespace

int main(int argc, char** argv) {
    std::string profile_path = argc > 1 ? argv[1] : kProfilePath;
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;

    spk::Profile film = spk::load_profile_file(profile_path);
    std::printf("Loaded profile: type=%s viewing_illuminant=%s samples=%d\n",
                film.type.c_str(), film.viewing_illuminant.c_str(), film.n_samples);

    spkvec::Array in = spkvec::read(golden_dir + "/film_density_cmy.spkvec");
    spkvec::Array gold = spkvec::read(golden_dir + "/final_rgb.spkvec");

    if (in.shape.size() != 3 || in.shape[2] != 3) {
        std::fprintf(stderr, "unexpected input shape\n");
        return 2;
    }
    const int height = static_cast<int>(in.shape[0]);
    const int width = static_cast<int>(in.shape[1]);
    std::printf("Input image: %dx%d x3 (%zu floats)\n", width, height, in.data.size());

    std::vector<float> rgb(in.data.size());

    spk::ScanningParams params;  // scan_film, sRGB, cctf on, spatial off (defaults)
    spk::scan(film, params, in.data.data(), width, height, rgb.data());

    if (rgb.size() != gold.data.size()) {
        std::fprintf(stderr, "size mismatch: got %zu, golden %zu\n",
                     rgb.size(), gold.data.size());
        return 2;
    }

    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < rgb.size(); ++i) {
        double d = std::fabs(static_cast<double>(rgb[i]) -
                             static_cast<double>(gold.data[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    double rms = std::sqrt(sse / static_cast<double>(rgb.size()));

    const double tol_max_abs = 1e-4;
    const double tol_rms = 1e-5;
    bool pass = (max_abs <= tol_max_abs) && (rms <= tol_rms);

    std::printf("max_abs = %.6e (tol %.0e)\n", max_abs, tol_max_abs);
    std::printf("rms     = %.6e (tol %.0e)\n", rms, tol_rms);
    std::printf("worst pixel idx=%zu: got=%.8f golden=%.8f\n",
                argmax, rgb[argmax], gold.data[argmax]);
    std::printf("%s\n", pass ? "PASS" : "FAIL");
    return pass ? 0 : 1;
}
