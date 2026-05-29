/*
 * SpectraFilm for Android — native engine: Hanatos2025 RGB->spectrum upsampling.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spectrafilm/utils/spectral_upsampling.py (Hanatos2025
 * spectral upsampling) and the cubic LUT interpolation it relies on from
 * spektrafilm/utils/fast_interp_lut.py.
 */
#ifndef SPK_KERNELS_SPECTRAL_UPSAMPLING_H
#define SPK_KERNELS_SPECTRAL_UPSAMPLING_H

#include "io/npy_lut.h"
#include "model/spectral.h"

namespace spk {

// --- Triangular <-> square chromaticity coordinate mapping -------------------
// Ports _tri2quad / _quad2tri from spectral_upsampling.py. The Hanatos LUTs are
// indexed in triangular coordinates for better sampling of the visible locus.
struct Vec2 {
    double x, y;
};

Vec2 tri2quad(Vec2 tc);   // triangular -> square (LUT) coordinates
Vec2 quad2tri(Vec2 xy);   // square -> triangular coordinates

// --- RGB -> (tc, b) ----------------------------------------------------------
// Ports _rgb_to_tc_b for the default arguments used in the spectra path:
//   color_space='ITU-R BT.2020', apply_cctf_decoding=False, reference_illuminant='D55'.
// With cctf decoding off, colour.RGB_to_XYZ(...CAT02...) is a fixed linear 3x3
// matrix, baked here at full double precision (kRgbToXyzBt2020D55).
//   xyz = M @ rgb;  b = sum(xyz);  xy = clip(xyz[:2]/max(b,1e-10), 0, 1);
//   tc  = tri2quad(xy).
// Returns tc in `out_tc` and the irradiance scale b in `out_b`.
void rgb_to_tc_b(const double rgb[3], Vec2* out_tc, double* out_b);

// --- Cubic 2D LUT interpolation (Mitchell-Netravali, reflected boundaries) ---
// Ports fast_interp_lut.py::cubic_interp_lut_at_2d (the per-pixel core of
// apply_lut_cubic_2d). `lut` is a 2D LUT of shape {L, L, channels} indexed
// [x][y][c]; (x, y) are coordinates already scaled into [0, L-1]. Writes
// `channels` interpolated values to `out`. Mirrors B=C=1/3 Mitchell weights,
// symmetric index reflection, and the weight-sum renormalisation.
void cubic_interp_lut_at_2d(const NdArray& lut, double x, double y, double* out);

// --- RGB -> 81-band spectrum (Hanatos2025 spectra LUT) -----------------------
// The canonical RGB->spectrum upsampling. Given a linear RGB triple and the
// loaded spectra LUT (irradiance_xy_tc.npy, shape {192, 192, 81}), produces a
// spectrum on the working shape (kSpectralSamples == 81):
//   (tc, b) = rgb_to_tc_b(rgb);
//   spectrum = cubic_interp_lut_at_2d(spectra_lut, tc * (L-1)) * b.
// This matches the project's cubic 2D LUT path (apply_lut_cubic_2d, used by
// rgb_to_raw_hanatos2025) applied directly to the spectra LUT.
//
// `out` receives kSpectralSamples doubles.
void rgb_to_spectrum_hanatos2025(const double rgb[3],
                                 const NdArray& spectra_lut,
                                 double out[kSpectralSamples]);

// --- Degree-4 2D polynomial surface ------------------------------------------
// Ports poly2d_deg4 from spectral_upsampling.py (the log-exposure correction
// surface). `params` is the 15-element coefficient vector [c0..c14]; c0 is unused
// (center_tc is zero correction). `center` is center_tc. Returns the scalar
// surface value at (x, y).
double poly2d_deg4(double x, double y, const double params[15], Vec2 center);

// --- Coefficient-LUT cubic fetch + spectrum synthesis ------------------------
// Ports _fetch_coeffs: cubic interpolation of the 4 polynomial coefficients from
// the coeffs LUT (hanatos_irradiance_xy_coeffs_*.lut, shape {512, 512, 4}) at a
// triangular coordinate `tc` in [0,1]^2. Writes 4 coefficients to `out_coeffs`.
//
// NOTE on interpolation order: _fetch_coeffs uses scipy's
// RegularGridInterpolator(method='cubic') (a tensor cubic spline), whereas the
// spectra path (apply_lut_cubic_2d) uses the project's Mitchell-Netravali cubic.
// These differ in kernel; this routine reproduces the *Mitchell* cubic, which is
// the one the C++ runtime ships. It is provided for completeness of the coeffs
// decode and is not on the verified spectra parity path. See the test notes.
void fetch_coeffs(const NdArray& coeffs_lut, Vec2 tc, double out_coeffs[4]);

}  // namespace spk

#endif  // SPK_KERNELS_SPECTRAL_UPSAMPLING_H
