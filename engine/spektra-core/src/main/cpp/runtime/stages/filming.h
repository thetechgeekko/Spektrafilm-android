/*
 * Spektrafilm for Android — native engine: filming stage (rgb -> raw -> density).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/runtime/stages/filming.py's expose + develop
 * for the parity toggles (auto-exposure off, spatial + stochastic effects off):
 *
 *   expose:  raw = rgb_to_raw_hanatos2025(rgb, sensitivity, tc_lut);
 *            log_raw = log10(fmax(raw, 0) + 1e-10)
 *            (boost/diffusion/blur/halation are no-ops with sigmas/boost == 0,
 *             and the negative-film black/white exposure correction is 1.0)
 *   develop: normalized_curves = curves - nanmin(curves, axis=0)
 *            density_cmy = interpolate_exposure_to_density(log_raw, curves)
 *            density_cmy = apply_density_correction_dir_couplers(...)
 *            (grain inactive => identity)
 *
 * The Hanatos2025 sensitivity-adaptation tc_lut is built here from the spectra
 * LUT, the profile sensitivity (10**log_sensitivity) and the erf4 spectral
 * band-pass window (apply_window=True, apply_surface=False, no spectral blur),
 * matching utils/spectral_upsampling.py::compute_hanatos2025_tc_lut.
 */
#ifndef SPK_RUNTIME_STAGES_FILMING_H
#define SPK_RUNTIME_STAGES_FILMING_H

#include "io/npy_lut.h"
#include "profiles/profile.h"
#include "runtime/params.h"

namespace spk {

// Build the Hanatos2025 filming tc_lut (shape {L, L, 3}) from the spectra LUT
// and the film profile, mirroring compute_hanatos2025_tc_lut with
// apply_window=True, apply_surface=False, spectral_gaussian_blur=0:
//   sensitivity = nan_to_num(10**log_sensitivity)              (S,3)
//   window      = eval_erf4_spectral_bandpass(window_params)   (S,3)
//   norm[c]     = sum_s sens[s,c]*illu[s]*window[s,c] / sum_s sens[s,c]*illu[s]
//   tc_lut[i,j,c] = sum_s spectra_lut[i,j,s] * sens[s,c] * window[s,c]/norm[c]
//
// `illuminant` is the film reference illuminant on the 81-band working shape
// (D55 for the bundled negative profiles). `spectra_lut` is irradiance_xy_tc.npy.
NdArray build_filming_tc_lut(const Profile& film, const NdArray& spectra_lut,
                             const double* illuminant);

// expose(): rgb (npix,3, linear ProPhoto, double — the pipeline runs the image
// as float64) -> log_raw (npix,3). Reuses the project's verified cubic-2D LUT
// path. `tc_lut` is from build_filming_tc_lut.
//
// When params.spatial_effects is true, the float64 pre-log irradiance `raw` is
// passed through apply_halation_um (in-emulsion scatter + back-reflection
// halation) before the log10, using params.halation and params.pixel_size_um.
// The 2D geometry (width, height) drives the spatial kernels; npix == width*height.
void expose(const double* rgb, int width, int height, const FilmingParams& params,
            const NdArray& tc_lut, float* log_raw_out);

// develop(): log_raw (npix,3) -> density_cmy (npix,3). Normalises the profile's
// density curves, interpolates, then applies the DIR-coupler correction. When
// params.spatial_effects is true the coupler inhibitor correction is spatially
// diffused (Gaussian + exponential tail) using params.pixel_size_um and the
// 2D geometry; otherwise the correction is pointwise. npix == width*height.
void develop(const float* log_raw, int width, int height, const Profile& film,
             const FilmingParams& params, float* density_cmy_out);

}  // namespace spk

#endif  // SPK_RUNTIME_STAGES_FILMING_H
