/*
 * Spektrafilm for Android -- native engine: virtual contrast mask at the print.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * NOT an upstream spektrafilm stage, so there is no oracle for it. The idea is
 * SpektraLab's RFC-024 "virtual contrast mask" (JamesQiu2005/SpektraLab,
 * GPL-3.0-or-later); this is an independent implementation for the Android
 * engine.
 *
 * A darkroom contrast mask is a soft, unsharp positive sandwiched with the
 * negative in the enlarger: it holds back light where the negative is thin and
 * adds none where it is dense, so large bright and dark areas of the print move
 * toward the middle while local contrast (edges, texture) survives. Virtually:
 *
 *   A  = the paper's image exposure per channel (before the pre-flash)
 *   x  = mean over channels of log2(A)      0 = mid-grey through this enlarger
 *   b  = x blurred over the frame (Gaussian, sigma = scale * long edge)
 *   d  = a bounded pull of b toward [-core, +core]
 *   A' = 2^d * A                             then the pre-flash is added as usual
 *
 * The blur runs on a frame-normalised grid (cells per sigma, not per pixel), so a
 * 640 px preview and a 24 MP export derive the same field from their own frames.
 */
#ifndef SPK_MODEL_CONTRAST_MASK_H
#define SPK_MODEL_CONTRAST_MASK_H

namespace spk {

struct ContrastMaskParams {
    bool active = false;
    // Stops a print highlight (low paper exposure) kContrastMaskReach stops
    // beyond the core is pulled toward mid-grey, i.e. darkened: highlight detail.
    double highlights = 0.0;
    // Stops a print shadow (high paper exposure) the same distance out is pulled
    // toward mid-grey, i.e. lightened: shadow detail.
    double shadows = 0.0;
    // Half-width, in stops of print exposure around mid-grey, that is left alone.
    double core = 1.0;
    // Blur sigma as a fraction of the frame's long edge.
    double scale = 0.03;

    bool engaged() const {
        return active && (highlights > 0.0 || shadows > 0.0) && scale > 0.0;
    }
};

// The bounded exposure shift, in stops, for a blurred base `b` (stops from
// mid-grey). Exactly 0 inside [-core, core] and on a branch whose amount is 0.
// Positive (more light, a darker print) where the base exposure is low (a print
// highlight), negative where it is high (a print shadow), so both move toward
// the middle; |result| < kContrastMaskGainLimit always.
double contrast_mask_delta(double b, const ContrastMaskParams& m);

constexpr double kContrastMaskReach = 4.0;
constexpr double kContrastMaskGainLimit = 6.0;

// Grid size the analysis uses for a w x h frame at `scale`.
void contrast_mask_grid_size(int w, int h, double scale, int* gw, int* gh);

// Apply the mask in place to the print exposure plane `y` (npix * 3 doubles, row
// major), which holds max(A + preflash, 0) + 1e-10 per channel, as print_expose
// computes it before the print_exposure/bw multiplier `mult`. `preflash` is the
// constant preflash 3-vector already inside `y`. Deterministic for any worker
// count: every output is written once by one thread, every sum in a fixed order.
void contrast_mask_apply(double* y, int width, int height, const double preflash[3],
                         double mult, const ContrastMaskParams& m);

}  // namespace spk

#endif  // SPK_MODEL_CONTRAST_MASK_H
