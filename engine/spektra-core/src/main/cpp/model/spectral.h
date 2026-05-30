/*
 * Spektrafilm for Android — native engine: spectral constants + CIE CMFs.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Mirrors spektrafilm/config.py (SPECTRAL_SHAPE + the CIE 1931 2deg
 * CMFs) onto the C API's spectral working shape declared in spektra.h.
 */
#ifndef SPK_MODEL_SPECTRAL_H
#define SPK_MODEL_SPECTRAL_H

namespace spk {

// --- Spectral working shape ---------------------------------------------------
// Mirrors spektrafilm/config.py exactly: SPECTRAL_SHAPE = SpectralShape(380,780,5),
// i.e. 380..780 nm @ 5 nm -> 81 samples. Equals the macros in spektra.h
// (SPK_SPECTRAL_MIN_NM=380, STEP=5, SAMPLES=81). This governs every spectral
// buffer's layout across JNI.
constexpr int   kSpectralMinNm   = 380;
constexpr int   kSpectralStepNm  = 5;
constexpr int   kSpectralSamples = 81;           // == SPK_SPECTRAL_SAMPLES
constexpr int   kSpectralMaxNm   = kSpectralMinNm + (kSpectralSamples - 1) * kSpectralStepNm;  // 780

// Wavelength (nm) of sample index i, i in [0, kSpectralSamples).
inline int spectral_wavelength_nm(int i) {
    return kSpectralMinNm + i * kSpectralStepNm;
}

// --- CIE 1931 2-degree Standard Observer CMFs --------------------------------
// Row-major [kSpectralSamples][3] = {xbar, ybar, zbar}, aligned to the spectral
// shape above. Source: colour-science,
// MSDS_CMFS["CIE 1931 2 Degree Standard Observer"], aligned to
// SpectralShape(380, 780, 5). These are the exact values used by spektrafilm.
extern const float kCieCmf1931[kSpectralSamples][3];

// --- XYZ from a radiance spectrum --------------------------------------------
struct XYZ {
    float X, Y, Z;
};

// xyz_from_spectrum: integrate a radiance spectrum against the CMFs.
//
//   X = sum_i radiance[i] * xbar[i] * dlambda
//   Y = sum_i radiance[i] * ybar[i] * dlambda
//   Z = sum_i radiance[i] * zbar[i] * dlambda
//
// `radiance` is a spectrum sampled on the working shape (kSpectralSamples).
// dlambda = kSpectralStepNm. (colour applies the same rectangular-rule sum with
// its spectral interval; no normalising k is applied here — that is the caller's
// choice of absolute vs. relative colorimetry.)
XYZ xyz_from_spectrum(const float* radiance);

}  // namespace spk

#endif  // SPK_MODEL_SPECTRAL_H
