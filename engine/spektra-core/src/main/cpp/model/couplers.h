/*
 * Spektrafilm for Android — native engine: DIR couplers.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports the no-diffusion (spatial-off) DIR-coupler path of
 * spektrafilm/model/couplers.py: compute_dir_couplers_matrix,
 * compute_density_curves_before_dir_couplers, compute_exposure_correction_dir_couplers
 * (with diffusion_size_pixel == 0) and apply_density_correction_dir_couplers.
 *
 * The spatial diffusion of inhibitors (gaussian/exponential filters) is NOT
 * ported here: under the parity goldens deactivate_spatial_effects=True forces
 * dir_couplers.diffusion_size_um == 0, so the inhibitor correction is a purely
 * pointwise per-pixel matrix operation.
 */
#ifndef SPK_MODEL_COUPLERS_H
#define SPK_MODEL_COUPLERS_H

namespace spk {

// DIR-coupler parameters consumed by the develop stage. Field names mirror
// spektrafilm.runtime.params_schema.DirCouplersParams. Defaults are filled by
// runtime/params.h (digest) for the negative-film stock.
struct DirCouplersParams {
    bool active = false;
    double amount = 1.0;

    // Same-layer (diagonal) inhibition.
    double gamma_samelayer_rgb[3] = {0.0, 0.0, 0.0};
    double inhibition_samelayer = 1.0;

    // Inter-layer (off-diagonal) inhibition. Convention: donor row, receiver col.
    // r_to_gb: M[0,1], M[0,2]; g_to_rb: M[1,0], M[1,2]; b_to_rg: M[2,0], M[2,1].
    double gamma_interlayer_r_to_gb[2] = {0.0, 0.0};
    double gamma_interlayer_g_to_rb[2] = {0.0, 0.0};
    double gamma_interlayer_b_to_rg[2] = {0.0, 0.0};
    double inhibition_interlayer = 1.0;

    double high_exposure_couplers_shift = 0.0;

    // Spatial diffusion of the inhibitor correction (the spatial-effects branch).
    // When diffusion_size_um > 0 the (silver @ M) correction is filtered before
    // it is subtracted from log_raw:
    //   correction = (1 - tail_weight) * G(diffusion_size_px) * correction
    //              + tail_weight       * Exp(diffusion_tail_px) * correction
    // with diffusion_size_px = diffusion_size_um / pixel_size_um (and likewise for
    // the tail). Forced to 0 (pointwise) when deactivate_spatial_effects is True.
    double diffusion_size_um = 0.0;
    double diffusion_tail_um = 0.0;
    double diffusion_tail_weight = 0.0;
};

// Faithful port of numpy.interp(x, xp, fp) (numpy compiled_base.c) including the
// order-dependent binary_search_with_guess. Required because the DIR-coupler axis
// le0 can be NON-MONOTONIC: a plain ascending binary search picks a different
// bracket than numpy there. Query points x[:nx] are consumed IN ORDER (the search
// guess carries) so this reproduces numpy's batched np.interp; endpoints clamp to
// fp[0]/fp[n-1]. Exposed for direct host parity testing (tests/test_np_interp).
void np_interp_array(const double* x, int nx, const double* xp, const double* fp,
                     int n, double* out);

// compute_dir_couplers_matrix(params) * params.amount.
// Writes the 3x3 inhibitor matrix (row-major M[donor*3 + receiver]) to `out`.
void compute_dir_couplers_matrix(const DirCouplersParams& params, double out[9]);

// apply_density_correction_dir_couplers, pointwise (diffusion off) negative path.
//
// Given the already-developed density_cmy (npix,3), the log_raw (npix,3), the
// shared log_exposure axis (n,) and the normalised density_curves (n,3), compute
// the corrected density:
//   M       = compute_dir_couplers_matrix(params)         (donor row, receiver col)
//   dc0     = compute_density_curves_before_dir_couplers(density_curves, le, M)
//   silver  = density_cmy (+ high_exposure_shift * silver^2 if set)
//   log_raw_0 = log_raw - silver @ M
//   out     = interpolate_exposure_to_density(log_raw_0, dc0, le, gamma)
//
// `out` (npix,3) may alias density_cmy. If params.active is false, density_cmy
// is copied to out unchanged.
void apply_density_correction_dir_couplers(const float* density_cmy, int npix,
                                           const float* log_raw,
                                           const float* log_exposure,
                                           const float* density_curves, int n,
                                           const DirCouplersParams& params,
                                           bool positive_film,
                                           const float gamma_factor[3],
                                           float* out);

// Spatial variant: same DIR-coupler correction, but the inhibitor correction
// array (silver @ M, shape (h,w,3)) is spatially diffused (Gaussian + exponential
// tail) before it is subtracted from log_raw, matching
// compute_exposure_correction_dir_couplers's diffusion_size_pixel>0 path.
//
// `pixel_size_um` converts diffusion_size_um / diffusion_tail_um to pixel scales.
// When params.diffusion_size_um <= 0 this is identical to the pointwise overload
// above (the gaussian/exponential filters are skipped). `out` (h*w,3) may alias
// density_cmy. Buffers are row-major channel-interleaved (h*w*3).
void apply_density_correction_dir_couplers_spatial(
    const float* density_cmy, int w, int h, const float* log_raw,
    const float* log_exposure, const float* density_curves, int n,
    const DirCouplersParams& params, bool positive_film,
    const float gamma_factor[3], double pixel_size_um, float* out);

}  // namespace spk

#endif  // SPK_MODEL_COUPLERS_H
