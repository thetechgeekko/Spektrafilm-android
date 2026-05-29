/*
 * SpectraFilm for Android — native engine: scan color output (XYZ->RGB + CCTF).
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Provides the constants and helpers the scanning stage needs to
 * mirror colour.XYZ_to_RGB(..., illuminant=scan_illuminant_xy) + the sRGB CCTF
 * encode in spektrafilm/runtime/stages/scanning.py.
 *
 * The XYZ->output-RGB matrix in colour bakes in chromatic adaptation from the
 * scan illuminant's xy to the output colourspace whitepoint. For the scan_film
 * route the scan illuminant is the film's viewing illuminant (D50 for
 * kodak_portra_400). The effective matrices below were extracted from
 * colour-science via the parity oracle so they match bit-for-bit.
 */
#ifndef SPK_MODEL_COLOR_OUTPUT_H
#define SPK_MODEL_COLOR_OUTPUT_H

namespace spk {

// --- D50 viewing illuminant (the film's viewing_illuminant) ------------------
// standard_illuminant("D50") on SpectralShape(380,780,5): colour's SDS_ILLUMINANTS
// "D50" aligned to the working shape, then normalised so mean(values) == 1
// (illuminants.py divides by sum/len). 81 samples. NaN-free.
extern const float kIlluminantD50[81];

// normalization = sum(illuminant * ybar) over the 81 samples, for D50.
// Used as the XYZ denominator in scanning's cmy_to_log_xyz.
extern const double kNormD50;

// Effective XYZ -> sRGB(linear) matrix from colour.XYZ_to_RGB with
// illuminant = D50 xy (Bradford CAT to sRGB's D65 whitepoint baked in).
// Row-major 3x3: rgb = M . xyz.
extern const float kXYZ_to_sRGB_D50[9];

// Near-identity round-trip matrix colour applies inside RGB_to_RGB(sRGB, sRGB,
// CAT02) before the CCTF encode in scanning's _apply_cctf_encoding_and_clip.
// matrix_RGB_to_RGB(sRGB, sRGB, "CAT02") is NOT exactly identity (RGB->XYZ->RGB
// round-trip + CAT02 adaptation between identical whitepoints leaves ~1e-5
// residuals); the goldens were generated with it applied, so it must be matched.
// Row-major 3x3: rgb_out = M . rgb_in.
extern const double kSRGB_to_sRGB_CAT02[9];

// --- sRGB CCTF encode (IEC 61966-2-1) ---------------------------------------
// V = (L <= 0.0031308) ? 12.92*L : 1.055*L^(1/2.4) - 0.055.
// Matches colour.models.rgb.transfer_functions.srgb.eotf_inverse_sRGB.
float srgb_cctf_encode(float linear);

}  // namespace spk

#endif  // SPK_MODEL_COLOR_OUTPUT_H
