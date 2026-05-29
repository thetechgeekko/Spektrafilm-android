/*
 * SpectraFilm for Android — native engine: in-emulsion scatter + halation +
 * the optical diffusion-filter (Black Pro-Mist etc.) PSF stage.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/diffusion.py:
 *   - apply_halation_um (in-emulsion scatter + back-reflection halation), and
 *   - apply_diffusion_filter_um (the optical diffusion-filter PSF: a per-family
 *     core/halo/bloom sum of 2D isotropic exponentials, energy-conserving convex
 *     blend (1-p_s)*image + p_s*(K_s * image)). The camera/enlarger
 *     diffusion_filter params drive it (issue #6 exposed-but-inert params).
 *
 * apply_halation_um / apply_diffusion_filter_um run on the float64 pre-log
 * irradiance `raw` (the spektrafilm pipeline carries the image as float64).
 *
 * Diffusion-filter convolution note: the oracle uses scipy.signal.fftconvolve
 * (mode='same') on a numpy-'reflect'-padded image. fftconvolve is a plain linear
 * convolution computed in the frequency domain; a direct double-precision spatial
 * convolution is numerically equivalent (verified |Δ| ~ 1e-16 vs the oracle, far
 * under the 1e-4 / 1e-5 parity tolerance), so this port uses a direct convolution
 * in double precision and needs no FFT infrastructure.
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

// Diffusion-filter family selector. Values mirror the keys of
// _DIFFUSION_FILTER_SHAPES in spektrafilm/model/diffusion.py.
enum class DiffusionFamily {
    kGlimmerglass = 0,
    kBlackProMist = 1,   // spektrafilm DiffusionFilterParams default family.
    kProMist = 2,
    kCinebloom = 3,
};

// Optical diffusion-filter params, mirroring the consumed fields of
// spektrafilm.runtime.params_schema.DiffusionFilterParams. Defaults match the
// schema so a default-constructed instance is a strict no-op:
//   active=false  -> apply_diffusion_filter_um returns the image untouched.
struct DiffusionFilterParams {
    bool active = false;                                  // schema default false.
    DiffusionFamily family = DiffusionFamily::kBlackProMist;  // "black_pro_mist".
    double strength = 0.5;        // commercial filter stop (interpolated).
    double spatial_scale = 1.0;   // multiplier on image-plane PSF widths.
    double halo_warmth = 0.0;     // additive bias to the family halo-warmth axis.
    // Per-group fine-tune multipliers (advanced). 1.0 = use the family preset.
    double core_intensity = 1.0;
    double core_size = 1.0;
    double halo_intensity = 1.0;
    double halo_size = 1.0;
    double bloom_intensity = 1.0;
    double bloom_size = 1.0;
};

// apply_diffusion_filter_um: in-place on the float64 raw irradiance image `raw`,
// shape (h, w, 3) row-major channel-interleaved. Implements the energy-conserving
// convex combination
//     E_out = (1 - p_s) * E_in  +  p_s * (K_s * E_in)
// with p_s derived from strength+family and K_s the per-channel diffusion-filter
// PSF (the halo is colour-tinted via an energy-conserving warmth redistribution).
// `pixel_size_um` converts the image-plane µm widths to pixels. No-op (image left
// untouched) when params.active is false, strength<=0, spatial_scale<=0, or the
// derived p_s<=0 — exactly mirroring spektrafilm.model.diffusion.apply_diffusion_filter_um.
void apply_diffusion_filter_um(double* raw, int w, int h,
                               const DiffusionFilterParams& params,
                               double pixel_size_um);

}  // namespace spk

#endif  // SPK_MODEL_DIFFUSION_H
