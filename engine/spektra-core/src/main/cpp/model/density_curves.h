/*
 * Spektrafilm for Android — native engine: density characteristic curves.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/density_curves.py
 * (interpolate_exposure_to_density, interp_density_cmy_layers) and the density
 * curve normalisation from spektrafilm/model/emulsion.py::develop.
 */
#ifndef SPK_MODEL_DENSITY_CURVES_H
#define SPK_MODEL_DENSITY_CURVES_H

namespace spk {

// interpolate_exposure_to_density(log_exposure_rgb, density_curves, log_exposure,
//                                 gamma_factor)
//
//   axis_c[k] = log_exposure[k] / gamma_factor[c]        (per channel c)
//   density_cmy[:,:,c] = interp(log_exposure_rgb[:,:,c], axis_c, density_curves[:,c])
//
// Buffers:
//   log_exposure_rgb : (npix, 3) row-major log10 RGB exposure (the image).
//   density_curves   : (n, 3) row-major CMY density characteristic curves.
//   log_exposure     : (n,) shared log-exposure axis.
//   gamma_factor     : 3 entries (caller broadcasts a scalar to {g,g,g}).
//   density_cmy_out  : (npix, 3) row-major output; may alias log_exposure_rgb.
//
// Endpoint clamping follows fast_interp (clamp to first/last density).
void interpolate_exposure_to_density(const float* log_exposure_rgb, int npix,
                                     const float* density_curves,
                                     const float* log_exposure, int n,
                                     const float gamma_factor[3],
                                     float* density_cmy_out);

// Convenience overload: scalar gamma applied to all three channels.
void interpolate_exposure_to_density(const float* log_exposure_rgb, int npix,
                                     const float* density_curves,
                                     const float* log_exposure, int n,
                                     float gamma_factor,
                                     float* density_cmy_out);

// Normalise density curves by subtracting the per-channel minimum, matching
// emulsion.py::develop: density_curves - nanmin(density_curves, axis=0).
// `in`/`out` are (n,3) row-major; out may alias in. NaN entries are ignored when
// computing the per-channel minimum and are passed through unchanged.
void normalize_density_curves(const float* in, int n, float* out);

// interp_density_cmy_layers(density_cmy, density_curves, density_curves_layers,
//                           positive_film)
//
// Splits each channel's CMY density into 3 sublayer densities by interpolating
// the per-channel density against that channel's 3 layer curves:
//   negative: layers[:,:,ch] = interp(density_cmy[:,:,ch], density_curves[:,ch],
//                                      density_curves_layers[:,:,ch])
//   positive: same with negated x and axis (interp on -density / -curve).
//
// Buffers:
//   density_cmy           : (npix, 3) row-major CMY density.
//   density_curves        : (n, 3) row-major monotonic per-channel density axis.
//   density_curves_layers : (n, 3, 3) row-major = [point][layer][channel], i.e.
//                           index n*9 + layer*3 + ch.
//   out                   : (npix, 3, 3) row-major = [pixel][layer][channel],
//                           index pix*9 + layer*3 + ch.
void interp_density_cmy_layers(const float* density_cmy, int npix,
                               const float* density_curves,
                               const float* density_curves_layers, int n,
                               bool positive_film,
                               float* out);

}  // namespace spk

#endif  // SPK_MODEL_DENSITY_CURVES_H
