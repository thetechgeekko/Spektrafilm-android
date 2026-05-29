/*
 * SpectraFilm for Android — native engine: emulsion spectral contraction.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/emulsion.py::compute_density_spectral.
 */
#include "emulsion.h"

namespace spk {

void compute_density_spectral(const float* density_cmy, int npix,
                              const float* channel_density, int samples,
                              const float* base_density,
                              float* spectral_out) {
    // spectral[p, l] = sum_k density_cmy[p,k] * channel_density[l,k] + base[l]
    //
    // GEMM-style: (npix x 3) * (3 x samples) with channel_density stored as
    // (samples x 3). We iterate p, then accumulate over the 3 dye channels into
    // each wavelength. Loop order keeps the long `samples` axis innermost and
    // contiguous in spectral_out for cache-friendly writes.
    for (int p = 0; p < npix; ++p) {
        const float c0 = density_cmy[p * 3 + 0];
        const float c1 = density_cmy[p * 3 + 1];
        const float c2 = density_cmy[p * 3 + 2];
        float* out = spectral_out + (long)p * samples;
        if (base_density != nullptr) {
            for (int l = 0; l < samples; ++l) {
                const float* cd = channel_density + (long)l * 3;
                out[l] = c0 * cd[0] + c1 * cd[1] + c2 * cd[2] + base_density[l];
            }
        } else {
            for (int l = 0; l < samples; ++l) {
                const float* cd = channel_density + (long)l * 3;
                out[l] = c0 * cd[0] + c1 * cd[1] + c2 * cd[2];
            }
        }
    }
}

}  // namespace spk
