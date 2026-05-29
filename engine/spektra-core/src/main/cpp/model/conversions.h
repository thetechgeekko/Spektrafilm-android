/*
 * SpectraFilm for Android — native engine: density<->light & color matrices.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/utils/conversions.py (density_to_light and the
 * embedded color matrices used by the ACES IDT path).
 */
#ifndef SPK_MODEL_CONVERSIONS_H
#define SPK_MODEL_CONVERSIONS_H

namespace spk {

// --- density <-> light --------------------------------------------------------

// density_to_light(density, light): transmittance = 10^(-density); * light.
// NaN results are set to 0 (conversions.py: transmitted[isnan]=0).
// Operates element-wise over `count` elements; out may alias in/light.
void density_to_light(const float* density, const float* light, int count,
                      float* out);

// Scalar density_to_light: 10^(-density) * light, NaN -> 0.
float density_to_light_scalar(float density, float light);

// Scalar form of density_to_light for a unit incident light (light == 1).
inline float density_to_transmittance(float density) {
    // 10^(-density); std::pow handles the exp10 identically to numpy.
    return density_to_light_scalar(density, 1.0f);
}

// Inverse: light -> optical density = -log10(light). Values <= 0 are clamped to
// a small floor before the log to avoid -inf (matches how the pipeline treats
// non-positive transmittance). `floor` is the minimum light used inside the log.
void light_to_density(const float* light, int count, float* out,
                      float floor = 1e-10f);
float light_to_density_scalar(float light, float floor = 1e-10f);

// --- color matrices -----------------------------------------------------------
// Row-major 3x3. Embedded from colour-science 0.4.7 (the same library
// conversions.py uses via colour.RGB_to_RGB / matrix_idt).
extern const float kSRGB_to_XYZ[9];   // sRGB linear RGB -> CIE XYZ (D65)
extern const float kXYZ_to_SRGB[9];   // CIE XYZ (D65) -> sRGB linear RGB
extern const float kACES_to_XYZ[9];   // ACES2065-1 -> CIE XYZ (D60)
extern const float kXYZ_to_ACES[9];   // CIE XYZ (D60) -> ACES2065-1

// Apply a row-major 3x3 matrix M to an (npix,3) row-major buffer:
//   out[i] = M . in[i]   (contract 'ijk,lk->ijl' with a single image == per-pixel
//   matrix-vector product). out may alias in.
void apply_matrix3(const float* in, int npix, const float* m3x3, float* out);

// Single-vector matrix-vector helper: out[3] = M(3x3) . v[3].
void matvec3(const float* m3x3, const float* v3, float* out3);

}  // namespace spk

#endif  // SPK_MODEL_CONVERSIONS_H
