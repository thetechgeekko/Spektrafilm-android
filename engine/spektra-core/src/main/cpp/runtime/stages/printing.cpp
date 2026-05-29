/*
 * SpectraFilm for Android — native engine: printing (enlarger) stage.
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
#include "runtime/stages/printing.h"

#include <cmath>
#include <vector>

#include "model/density_curves.h"

namespace spk {

void print_expose(const Profile& film, const Profile& print_profile,
                  const PrintingParams& params, const float* density_cmy,
                  int npix, float* log_raw_print_out) {
    const int S = film.n_samples;  // 81 for bundled profiles.

    // print sensitivity = nan_to_num(10**log_sensitivity) on the working shape.
    // Precompute once: print_profile.log_sensitivity is (S*3,) row-major [s*3+k].
    std::vector<double> sens(static_cast<size_t>(S) * 3);
    for (int l = 0; l < S; ++l) {
        for (int k = 0; k < 3; ++k) {
            double v = std::pow(10.0, static_cast<double>(
                          print_profile.log_sensitivity[static_cast<size_t>(l) * 3 + k]));
            if (std::isnan(v)) v = 0.0;  // np.nan_to_num
            sens[static_cast<size_t>(l) * 3 + k] = v;
        }
    }

    // The Python reference runs the whole spectral chain in float64 and stores
    // float32 only at the final write. Mirror that exactly.
    for (int p = 0; p < npix; ++p) {
        const float* dcmy = density_cmy + static_cast<size_t>(p) * 3;
        const double c0 = static_cast<double>(dcmy[0]);
        const double c1 = static_cast<double>(dcmy[1]);
        const double c2 = static_cast<double>(dcmy[2]);

        // raw[k] = sum_l light[l] * sens[l,k], where
        //   spectral[l] = c.channel_density[l] + base_density[l]   (film dyes)
        //   light[l]    = 10^-spectral[l] * filtered_illuminant[l] (NaN -> 0)
        double raw0 = 0.0, raw1 = 0.0, raw2 = 0.0;
        for (int l = 0; l < S; ++l) {
            const float* cd =
                film.channel_density.data() + static_cast<size_t>(l) * 3;
            const double spectral = c0 * static_cast<double>(cd[0]) +
                                    c1 * static_cast<double>(cd[1]) +
                                    c2 * static_cast<double>(cd[2]) +
                                    static_cast<double>(film.base_density[l]);
            double light = std::pow(10.0, -spectral) * params.filtered_illuminant[l];
            if (std::isnan(light)) light = 0.0;
            const double* sl = sens.data() + static_cast<size_t>(l) * 3;
            raw0 += light * sl[0];
            raw1 += light * sl[1];
            raw2 += light * sl[2];
        }

        // raw *= exposure_factor_midgray (midgray normalisation; preflash += 0).
        raw0 *= params.exposure_factor_midgray;
        raw1 *= params.exposure_factor_midgray;
        raw2 *= params.exposure_factor_midgray;

        // _film_cmy_to_print_log_raw returns log10(max(raw,0) + 1e-10).
        double lr0 = std::log10(std::fmax(raw0, 0.0) + 1e-10);
        double lr1 = std::log10(std::fmax(raw1, 0.0) + 1e-10);
        double lr2 = std::log10(std::fmax(raw2, 0.0) + 1e-10);

        // expose(): raw = 10^log_raw; raw *= print_exposure * bw_correction;
        // diffusion off; return log10(max(raw,0) + 1e-10). The 10^/log10 round
        // trip is reproduced verbatim so float rounding matches the reference.
        const double mult = params.print_exposure * params.bw_exposure_correction;
        double r0 = std::pow(10.0, lr0) * mult;
        double r1 = std::pow(10.0, lr1) * mult;
        double r2 = std::pow(10.0, lr2) * mult;

        float* out = log_raw_print_out + static_cast<size_t>(p) * 3;
        out[0] = static_cast<float>(std::log10(std::fmax(r0, 0.0) + 1e-10));
        out[1] = static_cast<float>(std::log10(std::fmax(r1, 0.0) + 1e-10));
        out[2] = static_cast<float>(std::log10(std::fmax(r2, 0.0) + 1e-10));
    }
}

void print_develop(const Profile& print_profile, const PrintingParams& params,
                   const float* log_raw_print, int npix,
                   float* density_cmy_out) {
    // develop_simple: interpolate against the RAW print density curves (no nanmin
    // normalisation, no DIR couplers), gamma broadcast to all channels.
    interpolate_exposure_to_density(log_raw_print, npix,
                                    print_profile.density_curves.data(),
                                    print_profile.log_exposure.data(),
                                    print_profile.n_density_pts,
                                    params.density_curve_gamma, density_cmy_out);
}

}  // namespace spk
