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
 *   b  = x smoothed over the frame (sigma = scale * long edge), edge-aware: a
 *        guided filter guided by x, so a hard edge does not leave a halo
 *   d  = a bounded pull of b toward [-core, +core]
 *   A' = 2^d * A                             then the pre-flash is added as usual
 *
 * The blur runs on a frame-normalised grid (cells per sigma, not per pixel), so a
 * 640 px preview and a 24 MP export derive the same field from their own frames.
 */
#ifndef SPK_MODEL_CONTRAST_MASK_H
#define SPK_MODEL_CONTRAST_MASK_H

#include <vector>

namespace spk {

struct ContrastMaskAccum;
struct ContrastMaskGrid;

struct ContrastMaskParams {
    bool active = false;
    // Stops a print highlight (low paper exposure) kContrastMaskReach stops
    // beyond the core is pulled toward mid-grey, i.e. darkened: highlight detail.
    // Nearer the core the pull shrinks in proportion (see contrast_mask_delta).
    double highlights = 0.0;
    // Stops a print shadow (high paper exposure) the same distance out is pulled
    // toward mid-grey, i.e. lightened: shadow detail.
    double shadows = 0.0;
    // Half-width, in stops of print exposure around mid-grey, that is left alone.
    double core = 0.25;
    // Blur sigma as a fraction of the frame's long edge.
    double scale = 0.03;

    bool engaged() const {
        return active && (highlights > 0.0 || shadows > 0.0) && scale > 0.0;
    }

    // Banded renders only (spektra.cpp run_banded); all zero / null otherwise.
    // The mask's grid is a function of the WHOLE frame's exposure, so a band is
    // rendered twice: pass 1 adds its own rows into the frame's cell sums
    // (`accum`) and stops, pass 2 applies the finished frame grid (`grid`).
    // frame_width > 0 marks a band: the plane handed to the mask is rows
    // [frame_y0, frame_y0 + height) of a frame_width x frame_height frame, and
    // only its band-local rows [core_begin, core_end) belong to it.
    int frame_width = 0;
    int frame_height = 0;
    int frame_y0 = 0;
    int core_begin = 0;
    int core_end = 0;
    ContrastMaskAccum* accum = nullptr;
    const ContrastMaskGrid* grid = nullptr;
};

// Per-cell sums of x and x^2 over a frame, filled row by row. Every cell's sums
// grow in raster order (rows ascending, then columns), whether the rows arrive
// in one call or band by band, so a banded frame gets the same sums as a whole
// one from the same pixels.
struct ContrastMaskAccum {
    int frame_width = 0, frame_height = 0, gw = 0, gh = 0;
    std::vector<double> sum, sum2;
};

// The finished mask: per-cell guided-filter coefficients for a frame.
struct ContrastMaskGrid {
    int frame_width = 0, frame_height = 0, gw = 0, gh = 0;
    std::vector<double> a, b;
};

void contrast_mask_accum_init(ContrastMaskAccum* acc, int frame_width, int frame_height,
                              const ContrastMaskParams& m);
// Adds frame rows [frame_y0 + row_begin, frame_y0 + row_end) -- rows
// [row_begin, row_end) of the band-local plane `y` -- to the cell sums.
void contrast_mask_accumulate(const double* y, int row_begin, int row_end, int frame_y0,
                              const double preflash[3], double mult, ContrastMaskAccum* acc);
void contrast_mask_finish(const ContrastMaskAccum& acc, const ContrastMaskParams& m,
                          ContrastMaskGrid* grid);
// Applies `grid` to `height` rows of `y` that start at frame row `frame_y0`.
void contrast_mask_apply_grid(double* y, int height, int frame_y0, const double preflash[3],
                              double mult, const ContrastMaskParams& m,
                              const ContrastMaskGrid& grid);

// The bounded exposure shift, in stops, for a blurred base `b` (stops from
// mid-grey). Exactly 0 inside [-core, core] and on a branch whose amount is 0.
// Positive (more light, a darker print) where the base exposure is low (a print
// highlight), negative where it is high (a print shadow), so both move toward
// the middle; |result| < kContrastMaskGainLimit always.
double contrast_mask_delta(double b, const ContrastMaskParams& m);

// The printable range of a paper is only about +/-2.5 stops of exposure around
// mid-grey (Portra Endura: paper white at about -2.7, black at about +2.7). An
// amount equal to the reach would flatten a base there completely, so the
// 0..3 amount range runs from no mask to a full one; amount 1 takes about a
// third off a large area's distance from the core, like a typical darkroom mask.
constexpr double kContrastMaskReach = 3.0;
constexpr double kContrastMaskGainLimit = 6.0;
// Guided-filter regulariser, in stops^2 of print exposure: windows whose variance is
// well above it (an edge of more than about a stop) keep their edge in the base.
constexpr double kContrastMaskEdgeEps = 0.25;

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
