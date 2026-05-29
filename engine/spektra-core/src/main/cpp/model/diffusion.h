/*
 * SpectraFilm for Android — native engine: in-emulsion scatter + halation.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/diffusion.py::apply_halation_um (the
 * spatial-effects branch of the filming expose step). The diffusion-filter
 * family (Black Pro-Mist etc.) is NOT ported: camera/enlarger diffusion_filter
 * defaults active=False and is confirmed OFF under the parity goldens.
 *
 * apply_halation_um runs on the float64 pre-log irradiance `raw` (the image is
 * carried as float64 by the spektrafilm pipeline), in the order:
 *   1. highlight boost (boost_ev==0 under the goldens -> identity, not ported here)
 *   2. in-emulsion scatter: energy-preserving mix of a Gaussian core and an
 *      exponential tail, blended with the identity by scatter_amount.
 *   3. back-reflection halation: additive sum of N Gaussians with sqrt(k)-spaced
 *      widths and a geometric bounce decay, optionally renormalised by 1+a_tot.
 */
#ifndef SPK_MODEL_DIFFUSION_H
#define SPK_MODEL_DIFFUSION_H

namespace spk {

// Halation parameters, mirroring the digested
// spektrafilm.runtime.params_schema.HalationParams fields consumed by
// apply_halation_um. Per-channel arrays are RGB-ordered.
struct HalationParams {
    bool active = false;

    // Highlight boost (boost_ev==0 default -> identity; carried for completeness).
    double boost_ev = 0.0;
    double boost_range = 0.3;
    double protect_ev = 4.0;

    // In-emulsion scatter (core Gaussian + exponential tail).
    double scatter_amount = 1.0;
    double scatter_spatial_scale = 1.0;
    double scatter_core_um[3] = {0.0, 0.0, 0.0};
    double scatter_tail_um[3] = {0.0, 0.0, 0.0};
    double scatter_tail_weight[3] = {0.0, 0.0, 0.0};

    // Back-reflection halation (multi-bounce Gaussian sum).
    double halation_amount = 1.0;
    double halation_spatial_scale = 1.0;
    double halation_strength[3] = {0.0, 0.0, 0.0};
    double halation_first_sigma_um[3] = {0.0, 0.0, 0.0};
    int halation_n_bounces = 0;
    double halation_bounce_decay = 0.5;
    bool halation_renormalize = true;
};

// apply_halation_um: in-place on the float64 raw irradiance image `raw`, shape
// (h, w, 3) row-major channel-interleaved. `pixel_size_um` converts the µm
// spatial parameters to pixel sigmas (sigma_px = um * spatial_scale /
// pixel_size_um). If `params.active` is false the image is left untouched.
//
// Highlight boost is treated as identity (boost_ev==0 under the goldens); if a
// non-zero boost is ever required it must be added before the scatter pass.
void apply_halation_um(double* raw, int w, int h, const HalationParams& params,
                       double pixel_size_um);

}  // namespace spk

#endif  // SPK_MODEL_DIFFUSION_H
