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

}  // namespace spk

#endif  // SPK_MODEL_GAMUT_COMPRESSION_H
