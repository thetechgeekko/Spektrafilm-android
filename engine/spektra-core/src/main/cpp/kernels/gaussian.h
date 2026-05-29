/*
 * SpectraFilm for Android — native engine: separable 2D Gaussian blur kernel.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/utils/fast_gaussian_filter.py.
 */
#ifndef SPK_KERNELS_GAUSSIAN_H
#define SPK_KERNELS_GAUSSIAN_H

namespace spk {

// SciPy default FIR truncate (kernel half-width = round(truncate*sigma)).
// fast_gaussian_filter.py uses truncate=3.0 by default.
constexpr float kGaussianTruncate = 3.0f;

// Sigma dispatch threshold. fast_gaussian_filter.py: SMALL_SIGMA_MAX = 3.0.
// sigma >= 3 -> Young-van Vliet IIR; sigma < 3 -> fused FIR.
constexpr float kSmallSigmaMax = 3.0f;

// Single-plane 2D Gaussian blur (mode='reflect'), in place.
// `img` is row-major h*w. Auto-dispatches small FIR / large IIR by sigma.
// Port of fast_gaussian_filter._dispatch_2d.
void gaussian_blur_plane(float* img, int w, int h, float sigma,
                         float truncate = kGaussianTruncate);

// Interleaved per-channel 2D Gaussian blur, in place.
// `img` is row-major h*w*channels (channel-interleaved). All channels share the
// same sigma. Mirrors fast_gaussian_filter on a 3D (H,W,C) array.
void gaussian_blur(float* img, int w, int h, int channels, float sigma,
                   float truncate = kGaussianTruncate);

// Per-channel-sigma variant: `sigmas` has `channels` entries.
// Mirrors fast_gaussian_filter's per-channel sigma path.
void gaussian_blur_per_channel(float* img, int w, int h, int channels,
                               const float* sigmas,
                               float truncate = kGaussianTruncate);

// Convert a spatial blur expressed in micrometres to a sigma in pixels.
// Spatial effects in spektrafilm are parameterised in physical microns and
// converted via the per-pixel size (pixel_size_um); sigma_px = um / pixel_size_um.
inline float um_to_pixels(float um, float pixel_size_um) {
    return (pixel_size_um > 0.0f) ? (um / pixel_size_um) : 0.0f;
}

}  // namespace spk

#endif  // SPK_KERNELS_GAUSSIAN_H
