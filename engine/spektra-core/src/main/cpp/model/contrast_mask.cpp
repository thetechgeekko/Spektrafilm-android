/*
 * Spektrafilm for Android -- native engine: virtual contrast mask at the print.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Independent implementation of SpektraLab's RFC-024 idea (GPL-3.0-or-later).
 * See contrast_mask.h for the model.
 */
#include "model/contrast_mask.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "kernels/parallel.h"

namespace spk {

namespace {

// Grid: kCellsPerSigma cells per blur sigma along the long edge, at least
// kGridMinLongEdge, never more than the frame's own pixels.
constexpr double kCellsPerSigma = 4.0;
constexpr int kGridMinLongEdge = 512;

// log2 floor, ~6 densities under mid-grey: a numerical guard, not a signal.
constexpr double kLog2Floor = -20.0;

// Shape of the pull: ~quadratic just past the core (no visible kink where the mask
// starts), ~linear further out, like a darkroom mask of constant contrast.
constexpr double kShapeKnee = 0.25;
inline double shape(double D) { return D * D / (D + kShapeKnee); }

inline double pixel_x(const double* yp, const double pf[3], double mult) {
    double s = 0.0;
    for (int c = 0; c < 3; ++c) {
        const double a = std::max(yp[c] - 1e-10 - pf[c], 0.0) * mult;
        s += a > 0.0 ? std::max(std::log2(a), kLog2Floor) : kLog2Floor;
    }
    return s * (1.0 / 3.0);
}

// Separable Gaussian on the grid with a truncated, renormalised window at the
// edges (no reflection, no padding value).
void gaussian_grid(std::vector<double>& v, int w, int h, double sigma) {
    const int r = std::max(1, static_cast<int>(std::ceil(4.0 * sigma)));
    std::vector<double> k(static_cast<size_t>(2 * r + 1));
    for (int i = -r; i <= r; ++i)
        k[static_cast<size_t>(i + r)] = std::exp(-0.5 * i * i / (sigma * sigma));
    std::vector<double> tmp(v.size());
    auto pass = [&](const std::vector<double>& src, std::vector<double>& dst, bool horiz) {
        const int len = horiz ? w : h, lines = horiz ? h : w;
        parallel_for(0, lines, [&](int lo, int hi) {
            for (int line = lo; line < hi; ++line) {
                for (int c = 0; c < len; ++c) {
                    const int a0 = std::max(0, c - r), a1 = std::min(len - 1, c + r);
                    double acc = 0.0, ws = 0.0;
                    for (int t = a0; t <= a1; ++t) {
                        const double wt = k[static_cast<size_t>(t - c + r)];
                        const size_t idx = horiz ? static_cast<size_t>(line) * w + t
                                                 : static_cast<size_t>(t) * w + line;
                        acc += wt * src[idx];
                        ws += wt;
                    }
                    dst[horiz ? static_cast<size_t>(line) * w + c
                              : static_cast<size_t>(c) * w + line] = acc / ws;
                }
            }
        });
    };
    pass(v, tmp, true);
    pass(tmp, v, false);
}

}  // namespace

double contrast_mask_delta(double b, const ContrastMaskParams& m) {
    const double core = std::max(0.0, m.core);
    double amount = 0.0, D = 0.0, sign = 0.0;
    if (m.highlights > 0.0 && b < -core) {
        amount = m.highlights; D = -core - b; sign = 1.0;   // more light: darker
    } else if (m.shadows > 0.0 && b > core) {
        amount = m.shadows; D = b - core; sign = -1.0;      // less light: lighter
    } else {
        return 0.0;
    }
    // t is the fraction of the reach the amount asks for. The pull blends a soft
    // knee (zero slope at the core) with a straight line, weighted so that
    //   * a base kContrastMaskReach stops past the core moves by exactly `amount`,
    //   * the slope of the pull never exceeds 1, so the corrected exposure
    //     b + delta stays monotone and the mask never reverses tonal order
    //     (amount 3 flattens the reach instead of inverting it), and
    //   * the pull never moves a base past the core (d <= D follows from the
    //     slope bound and d(0) = 0).
    // knee' < 1 and knee(D)/D rises with D, so with K = R / shape(R) ~= 1.08 the
    // slope is at most t(1 - t)K + t^2 <= 1 for every t in [0, 1].
    const double R = kContrastMaskReach;
    const double t = std::min(amount / R, 1.0);
    const double knee = R * shape(D) / shape(R);
    double d = t * ((1.0 - t) * knee + t * D);
    // Belt and braces: the closed form already keeps both bounds; a constant cap
    // keeps the corrected exposure monotone too.
    d = std::min(std::min(d, D), kContrastMaskGainLimit * 0.999);
    return sign * d;
}

void contrast_mask_grid_size(int w, int h, double scale, int* gw, int* gh) {
    const int long_edge = std::max(w, h);
    const int wanted = std::max(
        kGridMinLongEdge,
        static_cast<int>(std::ceil(kCellsPerSigma / std::max(scale, 1e-4))));
    const int cells = std::max(1, std::min(wanted, long_edge));
    *gw = std::max(1, static_cast<int>(std::lround(static_cast<double>(w) * cells / long_edge)));
    *gh = std::max(1, static_cast<int>(std::lround(static_cast<double>(h) * cells / long_edge)));
}

void contrast_mask_accum_init(ContrastMaskAccum* acc, int frame_width, int frame_height,
                              const ContrastMaskParams& m) {
    acc->frame_width = frame_width;
    acc->frame_height = frame_height;
    contrast_mask_grid_size(frame_width, frame_height, m.scale, &acc->gw, &acc->gh);
    acc->sum.assign(static_cast<size_t>(acc->gw) * acc->gh, 0.0);
    acc->sum2.assign(acc->sum.size(), 0.0);
}

namespace {
// Frame rows of cell row cy: [y0, y1).
inline void cell_rows(int cy, int gh, int height, int* y0, int* y1) {
    *y0 = static_cast<int>(static_cast<long long>(cy) * height / gh);
    *y1 = std::min(height, std::max(
        static_cast<int>(static_cast<long long>(cy + 1) * height / gh), *y0 + 1));
}
inline void cell_cols(int cx, int gw, int width, int* x0, int* x1) {
    *x0 = static_cast<int>(static_cast<long long>(cx) * width / gw);
    *x1 = std::min(width, std::max(
        static_cast<int>(static_cast<long long>(cx + 1) * width / gw), *x0 + 1));
}
}  // namespace

void contrast_mask_accumulate(const double* y, int row_begin, int row_end, int frame_y0,
                              const double preflash[3], double mult, ContrastMaskAccum* acc) {
    const int width = acc->frame_width, height = acc->frame_height;
    const int gw = acc->gw, gh = acc->gh;
    const int f0 = frame_y0 + row_begin, f1 = frame_y0 + row_end;
    if (f1 <= f0) return;
    // Cells partition the frame, so every pixel counts once. Parallel over cell
    // rows: each cell is written by one worker, its sums growing row by row.
    parallel_for(0, gh, [&](int lo, int hi) {
        for (int cy = lo; cy < hi; ++cy) {
            int y0 = 0, y1 = 0;
            cell_rows(cy, gh, height, &y0, &y1);
            const int r0 = std::max(y0, f0), r1 = std::min(y1, f1);
            if (r1 <= r0) continue;
            for (int cx = 0; cx < gw; ++cx) {
                int x0 = 0, x1 = 0;
                cell_cols(cx, gw, width, &x0, &x1);
                const size_t ci = static_cast<size_t>(cy) * gw + cx;
                double a = acc->sum[ci], a2 = acc->sum2[ci];
                for (int py = r0; py < r1; ++py)
                    for (int px = x0; px < x1; ++px) {
                        const double xv = pixel_x(
                            y + (static_cast<size_t>(py - frame_y0) * width + px) * 3,
                            preflash, mult);
                        a += xv;
                        a2 += xv * xv;
                    }
                acc->sum[ci] = a;
                acc->sum2[ci] = a2;
            }
        }
    });
}

void contrast_mask_finish(const ContrastMaskAccum& acc, const ContrastMaskParams& m,
                          ContrastMaskGrid* out) {
    const int width = acc.frame_width, height = acc.frame_height;
    const int gw = acc.gw, gh = acc.gh;
    const int cells = std::max(gw, gh);
    out->frame_width = width;
    out->frame_height = height;
    out->gw = gw;
    out->gh = gh;

    // 1. Cell means of x and x^2.
    std::vector<double> grid(static_cast<size_t>(gw) * gh);
    std::vector<double> grid2(grid.size());
    for (int cy = 0; cy < gh; ++cy) {
        int y0 = 0, y1 = 0;
        cell_rows(cy, gh, height, &y0, &y1);
        for (int cx = 0; cx < gw; ++cx) {
            int x0 = 0, x1 = 0;
            cell_cols(cx, gw, width, &x0, &x1);
            const size_t ci = static_cast<size_t>(cy) * gw + cx;
            const double inv = 1.0 / static_cast<double>((x1 - x0) * (y1 - y0));
            grid[ci] = acc.sum[ci] * inv;
            grid2[ci] = acc.sum2[ci] * inv;
        }
    }

    // 2. The base: an edge-aware (guided-filter) blur of x, guided by x itself.
    //    Where a window holds one large area, the base is its smooth mean, exactly
    //    the unsharp mask; where it straddles an edge much stronger than
    //    kContrastMaskEdgeEps, the base follows the edge, so the dark side of a
    //    skyline is lifted as much as the rest of the shadow instead of keeping a
    //    dark band one blur radius wide (the halo of a plain Gaussian base).
    const double sigma = std::max(0.5, m.scale * cells);
    gaussian_grid(grid, gw, gh, sigma);
    gaussian_grid(grid2, gw, gh, sigma);
    std::vector<double>& coef_a = grid2;  // reuse: a, then b in `grid`
    for (size_t i = 0; i < grid.size(); ++i) {
        const double mean = grid[i];
        const double var = std::max(grid2[i] - mean * mean, 0.0);
        const double a = var / (var + kContrastMaskEdgeEps);
        coef_a[i] = a;
        grid[i] = mean - a * mean;  // b
    }
    // The coefficients are averaged over a smaller window than the statistics, so
    // the edge-following `a` is not spread thin across the whole blur radius.
    gaussian_grid(coef_a, gw, gh, std::max(0.5, 0.25 * sigma));
    gaussian_grid(grid, gw, gh, std::max(0.5, 0.25 * sigma));
    out->a = std::move(coef_a);
    out->b = std::move(grid);
}

void contrast_mask_apply_grid(double* y, int height, int frame_y0, const double preflash[3],
                              double mult, const ContrastMaskParams& m,
                              const ContrastMaskGrid& g) {
    const int width = g.frame_width, fh = g.frame_height, gw = g.gw, gh = g.gh;
    const std::vector<double>& coef_a = g.a;
    const std::vector<double>& grid = g.b;
    // 3. Per pixel: bilinear base, bounded shift, gain on the image exposure
    //    only (the pre-flash is re-added unscaled). Coordinates are FRAME rows.
    parallel_for(0, height, [&](int lo, int hi) {
        for (int ly = lo; ly < hi; ++ly) {
            const int py = frame_y0 + ly;
            const double v = std::clamp((py + 0.5) * gh / fh - 0.5, 0.0,
                                        static_cast<double>(gh - 1));
            const int v0 = static_cast<int>(std::floor(v));
            const int v1 = std::min(v0 + 1, gh - 1);
            const double tv = v - v0;
            for (int px = 0; px < width; ++px) {
                const double u = std::clamp((px + 0.5) * gw / width - 0.5, 0.0,
                                            static_cast<double>(gw - 1));
                const int u0 = static_cast<int>(std::floor(u));
                const int u1 = std::min(u0 + 1, gw - 1);
                const double tu = u - u0;
                const size_t r0 = static_cast<size_t>(v0) * gw;
                const size_t r1 = static_cast<size_t>(v1) * gw;
                auto bilerp = [&](const std::vector<double>& gr) {
                    return (gr[r0 + u0] * (1.0 - tu) + gr[r0 + u1] * tu) * (1.0 - tv) +
                           (gr[r1 + u0] * (1.0 - tu) + gr[r1 + u1] * tu) * tv;
                };
                double* yp = y + (static_cast<size_t>(ly) * width + px) * 3;
                const double b = bilerp(coef_a) * pixel_x(yp, preflash, mult) + bilerp(grid);
                const double d = contrast_mask_delta(b, m);
                if (d == 0.0) continue;  // inside the core: untouched, byte for byte
                const double k = std::exp2(d);
                for (int c = 0; c < 3; ++c) {
                    const double a = std::max(yp[c] - 1e-10 - preflash[c], 0.0);
                    yp[c] = std::max(k * a + preflash[c], 0.0) + 1e-10;
                }
            }
        }
    });
}

void contrast_mask_apply(double* y, int width, int height, const double preflash[3],
                         double mult, const ContrastMaskParams& m) {
    if (!m.engaged() || width <= 0 || height <= 0 || !(mult > 0.0)) return;
    ContrastMaskAccum acc;
    contrast_mask_accum_init(&acc, width, height, m);
    contrast_mask_accumulate(y, 0, height, 0, preflash, mult, &acc);
    ContrastMaskGrid grid;
    contrast_mask_finish(acc, m, &grid);
    contrast_mask_apply_grid(y, height, 0, preflash, mult, m, grid);
}

}  // namespace spk
