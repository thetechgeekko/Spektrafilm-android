/*
 * Spektrafilm for Android — native engine: double-precision spatial filters
 * (Gaussian + exponential-mixture) for the spatial-effects branch.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports the float64 path of spektrafilm/utils/fast_gaussian_filter.py:
 *   - fast_gaussian_filter  (per-channel 2D Gaussian, mode='reflect')
 *   - fast_exponential_filter (2D isotropic exponential via a fixed Gaussian
 *     mixture; n_gaussians=3 by default — amplitudes sum to 0.9999, NOT 1.0).
 *
 * Unlike kernels/gaussian.{h,cpp} (which stores float32 between separable passes
 * for the spatial-OFF path that never invokes a blur), this implementation keeps
 * the image in float64 throughout. The spektrafilm pipeline runs the whole image
 * as float64, so for the spatial branch (halation / scatter / coupler diffusion)
 * bit-exactness requires matching that precision. The kernel construction, the
 * SMALL_SIGMA_MAX=3 FIR/IIR dispatch, the radius=int(truncate*sigma+0.5) rule,
 * the Young-van Vliet coefficients and the scipy 'reflect' boundary are all
 * reproduced identically.
 */
#ifndef SPK_KERNELS_EXPONENTIAL_FILTER_H
#define SPK_KERNELS_EXPONENTIAL_FILTER_H

namespace spk {

// SMALL_SIGMA_MAX in fast_gaussian_filter.py.
constexpr double kSmallSigmaMaxD = 3.0;
// Default FIR truncate for fast_gaussian_filter / fast_exponential_filter.
constexpr double kGaussianTruncateD = 3.0;

// Single-plane 2D Gaussian blur (mode='reflect'), in place, float64.
// `img` is row-major h*w. Auto-dispatches small FIR / large IIR by sigma exactly
// like fast_gaussian_filter._dispatch_2d.
void gaussian_blur_plane_d(double* img, int w, int h, double sigma,
                           double truncate = kGaussianTruncateD);

// Per-channel 2D Gaussian blur (interleaved h*w*channels), in place, float64.
// Each channel gets its own sigma (sigmas[channels]). Mirrors fast_gaussian_filter
// applied to an (H,W,C) array with a per-channel sigma vector.
void gaussian_blur_per_channel_d(double* img, int w, int h, int channels,
                                 const double* sigmas,
                                 double truncate = kGaussianTruncateD);

// Per-channel 2D exponential filter via the fixed Gaussian-mixture surrogate
// (fast_exponential_filter, n_gaussians=3). `decay` has `channels` entries (the
// per-channel decay constant lambda in pixels). Result is written to `out`
// (same layout as `img`); `out` must not alias `img`.
//   out = sum_k amplitude_k * gaussian(img, sigma_ratio_k * decay)
// with the n=3 fit amplitudes [0.1633, 0.6496, 0.1870] (sum 0.9999) and ratios
// [0.5360, 1.5236, 2.7684].
void exponential_filter_per_channel_d(const double* img, int w, int h, int channels,
                                      const double* decay, double* out,
                                      double truncate = kGaussianTruncateD);

}  // namespace spk

#endif  // SPK_KERNELS_EXPONENTIAL_FILTER_H
