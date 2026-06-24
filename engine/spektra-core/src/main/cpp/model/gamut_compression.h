/*
 * Spektrafilm for Android — native engine: output gamut compression.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports utils/gamut_compression.py::reinhard_knee and
 * compress_rgb_aces_rgc (ACES Reference Gamut Compression v1.3, the native
 * per-channel form) for the output side.
 *
 * OPT-IN / DEFAULT-OFF. The selector defaults to kLegacyClip — the engine's
 * existing behavior (no compression; scanning's final np.clip(0,1)) — so every
 * pre-existing golden stays byte-identical. kAcesRgc opts into the ACES RGC knee
 * in the linear output space (gated by tests/test_gamut_out_aces.cpp against an
 * upstream gamut_compression.py golden). reinhard_knee is the shared knee the
 * input/perceptual gamut items (the P2 follow-up) reuse. The perceptual
 * algorithms (oklch/oklrab/jzazbz/cam16ucs) are reserved here, not yet ported.
 */
#ifndef SPK_MODEL_GAMUT_COMPRESSION_H
#define SPK_MODEL_GAMUT_COMPRESSION_H

namespace spk {

// Output gamut compression algorithm selector (mirrors
// gamut_compression.py::OutputGamutCompressSpec.algorithm, plus the kLegacyClip
// sentinel that is local to this engine — the oracle has no clip, the engine does).
//   kLegacyClip — DEFAULT. No gamut compression; the scanning stage keeps its
//                 existing final np.clip(0,1). Byte-identical to every golden.
//   kOff        — oracle "off": pass output RGB through unchanged (no compression,
//                 and no implied clip). Reserved; not selected by default.
//   kAcesRgc    — ACES Reference Gamut Compression v1.3 (per-channel knee on the
//                 achromatic distance), applied in the linear output space.
//   kOklch/kOklrab/kJzazbz/kCam16ucs — perceptual chroma reduction. RESERVED
//                 (P2 follow-up); not implemented yet.
enum class OutputGamutCompress {
    kLegacyClip = 0,
    kOff        = 1,
    kAcesRgc    = 2,
    kOklch      = 3,
    kOklrab     = 4,
    kJzazbz     = 5,
    kCam16ucs   = 6,
};

// Input gamut compression algorithm selector (mirrors
// gamut_compression.py::InputGamutCompressSpec.algorithm, plus the kOff sentinel that
// is the engine default — the oracle spec defaults active=True, the engine defaults
// OFF so every pre-existing golden stays byte-identical, exactly like the output side
// defaults to kLegacyClip).
//   kOff   — DEFAULT. No input compression; the Hanatos filming tc_lut is built and
//            used exactly as before (oracle InputGamutCompressSpec.active == False).
//            Byte-identical to every golden.
//   kXy    — ACES-RGC-style radial compression in CIE 1931 chromaticity from the film
//            reference illuminant toward the visible spectral locus (oracle
//            algorithm="xy", the production default), baked into the tc_lut.
//   kOklch — perceptual chroma reduction. RESERVED (needs the OkLab C_max bisection
//            table); not implemented yet.
enum class InputGamutCompress {
    kOff   = 0,
    kXy    = 1,
    kOklch = 2,
};

// Reinhard knee on a normalized distance d (gamut_compression.py::reinhard_knee):
// identity at/below `threshold`, smoothly asymptotic to `limit` above it.
//   d <= threshold      -> d
//   else  scale = limit - threshold;  x = (d - threshold) / scale;
//         y = x / (1 + x^power)^(1/power);  return threshold + scale * y.
double reinhard_knee(double d, double threshold, double limit, double power);

// ACES RGC v1.3 on one linear-RGB triple
// (gamut_compression.py::compress_rgb_aces_rgc), writing the compressed triple to
// out[3]. ach = max(R,G,B); for ach <= 1e-12 (near-black) the pixel passes through
// unchanged. Otherwise each channel's distance d = (ach - c) / ach is passed through
// reinhard_knee and reconstructed as c' = ach * (1 - d'). The achromatic max is
// preserved; with limit=1 negative channels (d>1) are pulled to exactly 0.
void compress_pixel_aces_rgc(const double rgb[3], double threshold, double limit,
                             double power, double out[3]);

// In-place ACES RGC over an interleaved (npix*3) row-major linear-RGB image.
void compress_rgb_aces_rgc(double* rgb, int npix, double threshold, double limit,
                           double power);

// ---- Input-side: radial xy compression toward the visible spectral locus --------
// (gamut_compression.py input path: spectral_locus_xy + compress_xy_radial.) These
// operate on CIE 1931 chromaticity xy (2 components) around a reference white, NOT on
// output RGB. They are pure double math; the spectral-locus polygon is baked as a
// captured constant (see the .cpp) so the result matches the oracle bit-for-bit.

// The closed CIE 1931 2 deg visible spectral locus polygon in xy
// (gamut_compression.py::spectral_locus_xy: CMFs at 380..700 nm @ 5 nm, normalized by
// X+Y+Z, first vertex repeated). Returns the vertex count N (66) and sets *out_xy to
// a static flat array of N*2 doubles (x0,y0,x1,y1,...). The first and last vertices
// are identical (the polygon is closed) so it is directly usable for ray-polygon
// intersection.
int spectral_locus_xy(const double** out_xy);

// ACES-RGC-style radial xy compression of one chromaticity toward the spectral locus
// (gamut_compression.py::compress_xy_radial, per point). `white_xy` is the achromatic
// axis (the film reference illuminant chromaticity). Computes the normalized radial
// distance d = |xy - white| / boundary (boundary = ray-to-locus distance along the
// xy->white direction), passes d through reinhard_knee, and rescales along the same
// ray — preserving hue (dominant wavelength). At-white points (|xy - white| < 1e-9)
// pass through unchanged. Writes the compressed xy to out[2].
void compress_pixel_xy(const double xy[2], const double white_xy[2], double threshold,
                       double limit, double power, double out[2]);

// In-place radial xy compression over an interleaved (npix*2) array of chromaticities.
void compress_xy_radial(double* xy, int npix, const double white_xy[2],
                        double threshold, double limit, double power);

}  // namespace spk

#endif  // SPK_MODEL_GAMUT_COMPRESSION_H
