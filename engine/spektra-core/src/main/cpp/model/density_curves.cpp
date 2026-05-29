/*
 * SpectraFilm for Android — native engine: density characteristic curves.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/density_curves.py and the curve
 * normalisation in spektrafilm/model/emulsion.py::develop.
 */
#include "density_curves.h"

#include <cmath>
#include <vector>

#include "../kernels/interp.h"

namespace spk {

void interpolate_exposure_to_density(const float* log_exposure_rgb, int npix,
                                     const float* density_curves,
                                     const float* log_exposure, int n,
                                     const float gamma_factor[3],
                                     float* density_cmy_out) {
    // density_curves.py::interpolate_exposure_to_density
    //   density_cmy = fast_interp(log_exposure_rgb,
    //                             log_exposure[:,None]/gamma_factor[None,:],
    //                             density_curves)
    // Build the channel-specific axis xp (n,3): xp[k,c] = log_exposure[k]/gamma[c].
    std::vector<float> xp(static_cast<size_t>(n) * 3);
    for (int k = 0; k < n; ++k) {
        for (int c = 0; c < 3; ++c) {
            float g = gamma_factor[c];
            xp[k * 3 + c] = (g != 0.0f) ? (log_exposure[k] / g) : log_exposure[k];
        }
    }
    // fp is density_curves (n,3); channel-specific axis -> common_axis = false.
    interp1d_planar3(log_exposure_rgb, npix, xp.data(), /*common_axis=*/false,
                     density_curves, n, density_cmy_out);
}

void interpolate_exposure_to_density(const float* log_exposure_rgb, int npix,
                                     const float* density_curves,
                                     const float* log_exposure, int n,
                                     float gamma_factor,
                                     float* density_cmy_out) {
    // np.size(gamma_factor)==1 -> broadcast to [g,g,g].
    const float g3[3] = {gamma_factor, gamma_factor, gamma_factor};
    interpolate_exposure_to_density(log_exposure_rgb, npix, density_curves,
                                    log_exposure, n, g3, density_cmy_out);
}

void normalize_density_curves(const float* in, int n, float* out) {
    // emulsion.py::develop: density_curves - np.nanmin(density_curves, axis=0)
    float mn[3];
    for (int c = 0; c < 3; ++c) {
        float m = 0.0f;
        bool found = false;
        for (int k = 0; k < n; ++k) {
            float v = in[k * 3 + c];
            if (std::isnan(v)) continue;
            if (!found || v < m) { m = v; found = true; }
        }
        mn[c] = found ? m : 0.0f;
    }
    for (int k = 0; k < n; ++k) {
        for (int c = 0; c < 3; ++c) {
            float v = in[k * 3 + c];
            out[k * 3 + c] = std::isnan(v) ? v : (v - mn[c]);
        }
    }
}

void interp_density_cmy_layers(const float* density_cmy, int npix,
                               const float* density_curves,
                               const float* density_curves_layers, int n,
                               bool positive_film,
                               float* out) {
    // density_curves.py::interp_density_cmy_layers
    // For each channel ch and each layer L:
    //   out[pix, L, ch] = interp(density_cmy[pix, ch],
    //                            density_curves[:, ch],
    //                            density_curves_layers[:, L, ch])
    // positive_film negates the query and the axis (interp on -x against -axis),
    // which preserves the monotonic-ascending requirement of interp1d.
    std::vector<float> axis(n);
    std::vector<float> fp(n);
    for (int ch = 0; ch < 3; ++ch) {
        // Per-channel density axis (negated for positive film).
        for (int k = 0; k < n; ++k) {
            float a = density_curves[k * 3 + ch];
            axis[k] = positive_film ? -a : a;
        }
        for (int L = 0; L < 3; ++L) {
            // Layer L curve for this channel: layers[k, L, ch] = k*9 + L*3 + ch.
            for (int k = 0; k < n; ++k) {
                fp[k] = density_curves_layers[k * 9 + L * 3 + ch];
            }
            for (int p = 0; p < npix; ++p) {
                float x = density_cmy[p * 3 + ch];
                if (positive_film) x = -x;
                float v = interp1d_scalar(x, axis.data(), fp.data(), n);
                out[p * 9 + L * 3 + ch] = v;
            }
        }
    }
}

}  // namespace spk
