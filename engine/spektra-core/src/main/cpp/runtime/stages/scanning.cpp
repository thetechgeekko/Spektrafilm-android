/*
 * SpectraFilm for Android — native engine: scanning stage.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 */
#include "runtime/stages/scanning.h"

#include <cmath>
#include <vector>

#include "model/color_output.h"
#include "model/conversions.h"
#include "model/emulsion.h"
#include "model/spectral.h"

namespace spk {

void scan(const Profile& film, const ScanningParams& params,
          const float* density_cmy, int width, int height, float* rgb_out) {
    const int npix = width * height;
    const int S = film.n_samples;  // == kSpectralSamples (81) for bundled profiles

    // Scan illuminant + constants. For the scan_film route the scan illuminant is
    // the film's viewing illuminant (D50 here). These mirror scanning.py:
    //   normalization = sum(scan_illuminant * ybar)
    const float* illum = kIlluminantD50;
    const double norm = kNormD50;

    // The Python reference computes the whole chain in float64 (NumPy default for
    // the profile arrays) and only stores float32 at the very end. To reproduce it
    // bit-for-bit we mirror that: spectral density, light, the XYZ integral, the
    // matrix product and the CCTF are all done in double; only the final write is
    // float32.
    for (int p = 0; p < npix; ++p) {
        const float* dcmy = density_cmy + static_cast<size_t>(p) * 3;
        const double c0 = static_cast<double>(dcmy[0]);
        const double c1 = static_cast<double>(dcmy[1]);
        const double c2 = static_cast<double>(dcmy[2]);

        // 1. density_cmy -> spectral density (emulsion.compute_density_spectral):
        //    spectral[l] = sum_k dcmy[k] * channel_density[l,k] + base_density[l].
        //    NaN channel_density / base entries propagate as NaN here.
        // 2. light = density_to_light(spectral, illuminant): 10^(-D) * illuminant,
        //    NaN -> 0 (utils/conversions.density_to_light).
        // 3. xyz = sum_l light[l] * CMF[l] / normalization. scanning.py uses a
        //    plain einsum over wavelengths with NO 5 nm interval factor, so we
        //    integrate without dlambda (the interval cancels against the
        //    normalization's own missing interval).
        double X = 0.0, Y = 0.0, Z = 0.0;
        for (int l = 0; l < S; ++l) {
            const float* cd = film.channel_density.data() + static_cast<size_t>(l) * 3;
            double spectral = c0 * static_cast<double>(cd[0]) +
                              c1 * static_cast<double>(cd[1]) +
                              c2 * static_cast<double>(cd[2]) +
                              static_cast<double>(film.base_density[l]);
            double w = std::pow(10.0, -spectral) * static_cast<double>(illum[l]);
            if (std::isnan(w)) w = 0.0;
            X += w * kCieCmf1931[l][0];
            Y += w * kCieCmf1931[l][1];
            Z += w * kCieCmf1931[l][2];
        }
        // 4. log_xyz = log10(max(xyz,0) + 1e-10); xyz = 10^log_xyz. The log/exp
        //    round-trip just floors at 1e-10 and clamps negatives; reproduce that
        //    exactly so float rounding matches the reference.
        const double inv_norm = 1.0 / norm;
        double xyz[3];
        xyz[0] = std::pow(10.0, std::log10(std::fmax(X * inv_norm, 0.0) + 1e-10));
        xyz[1] = std::pow(10.0, std::log10(std::fmax(Y * inv_norm, 0.0) + 1e-10));
        xyz[2] = std::pow(10.0, std::log10(std::fmax(Z * inv_norm, 0.0) + 1e-10));

        // (black/white XYZ correction skipped: negative film scan route.)
        // (add_glare skipped: glare is None on the scan_film route.)

        // 5. XYZ -> output RGB (sRGB linear), CAT to D65 baked into the matrix.
        double rgb[3];
        for (int c = 0; c < 3; ++c) {
            rgb[c] = static_cast<double>(kXYZ_to_sRGB_D50[c * 3 + 0]) * xyz[0] +
                     static_cast<double>(kXYZ_to_sRGB_D50[c * 3 + 1]) * xyz[1] +
                     static_cast<double>(kXYZ_to_sRGB_D50[c * 3 + 2]) * xyz[2];
        }

        // (blur + unsharp skipped: spatial effects deactivated.)

        // 6. CCTF encode path mirrors scanning._apply_cctf_encoding_and_clip,
        //    which calls colour.RGB_to_RGB(rgb, sRGB, sRGB, encode=True). That
        //    helper *always* applies matrix_RGB_to_RGB(sRGB, sRGB, CAT02) — a
        //    near-identity round-trip matrix with ~1e-5 residuals — BEFORE the
        //    sRGB CCTF encode. Reproduce it exactly, then encode + clip.
        float* out = rgb_out + static_cast<size_t>(p) * 3;
        double rgb_adapted[3];
        for (int c = 0; c < 3; ++c) {
            rgb_adapted[c] = kSRGB_to_sRGB_CAT02[c * 3 + 0] * rgb[0] +
                             kSRGB_to_sRGB_CAT02[c * 3 + 1] * rgb[1] +
                             kSRGB_to_sRGB_CAT02[c * 3 + 2] * rgb[2];
        }
        for (int c = 0; c < 3; ++c) {
            double v = rgb_adapted[c];
            if (params.output_cctf_encoding) {
                v = (v <= 0.0031308) ? 12.92 * v
                                     : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055;
            }
            if (v < 0.0) v = 0.0;
            if (v > 1.0) v = 1.0;
            out[c] = static_cast<float>(v);
        }
    }
}

}  // namespace spk
