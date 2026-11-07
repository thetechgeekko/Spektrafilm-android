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
    // Pre-compensate the gain bound so a base kContrastMaskReach stops past the
    // core moves by exactly `amount` (closed form), then never past the core.
    const double L = kContrastMaskGainLimit;
    const double a = std::min(amount, L * 0.999);
    const double pre = a * L / std::sqrt(L * L - a * a);
    const double raw = pre * shape(D) / shape(kContrastMaskReach);
    const double d = std::min(raw * L / std::sqrt(L * L + raw * raw), D);
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

void contrast_mask_apply(double* y, int width, int height, const double preflash[3],
                         double mult, const ContrastMaskParams& m) {
    if (!m.engaged() || width <= 0 || height <= 0 || !(mult > 0.0)) return;
    int gw = 0, gh = 0;
    contrast_mask_grid_size(width, height, m.scale, &gw, &gh);
    const int cells = std::max(gw, gh);

    // 1. Cell means of x and x^2. Cells partition the frame, so every pixel counts once.
    std::vector<double> grid(static_cast<size_t>(gw) * gh);
    std::vector<double> grid2(grid.size());
    parallel_for(0, gh, [&](int lo, int hi) {
        for (int cy = lo; cy < hi; ++cy) {
            const int y0 = static_cast<int>(static_cast<long long>(cy) * height / gh);
            const int y1 = std::min(height, std::max(
                static_cast<int>(static_cast<long long>(cy + 1) * height / gh), y0 + 1));
            for (int cx = 0; cx < gw; ++cx) {
                const int x0 = static_cast<int>(static_cast<long long>(cx) * width / gw);
                const int x1 = std::min(width, std::max(
                    static_cast<int>(static_cast<long long>(cx + 1) * width / gw), x0 + 1));
                double acc = 0.0, acc2 = 0.0;
                for (int py = y0; py < y1; ++py)
                    for (int px = x0; px < x1; ++px) {
                        const double xv = pixel_x(
                            y + (static_cast<size_t>(py) * width + px) * 3, preflash, mult);
                        acc += xv;
                        acc2 += xv * xv;
                    }
                const double inv = 1.0 / static_cast<double>((x1 - x0) * (y1 - y0));
                grid[static_cast<size_t>(cy) * gw + cx] = acc * inv;
                grid2[static_cast<size_t>(cy) * gw + cx] = acc2 * inv;
            }
        }
    });

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

    // 3. Per pixel: bilinear base, bounded shift, gain on the image exposure
    //    only (the pre-flash is re-added unscaled).
    parallel_for(0, height, [&](int lo, int hi) {
        for (int py = lo; py < hi; ++py) {
            const double v = std::clamp((py + 0.5) * gh / height - 0.5, 0.0,
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
                auto bilerp = [&](const std::vector<double>& g) {
                    return (g[r0 + u0] * (1.0 - tu) + g[r0 + u1] * tu) * (1.0 - tv) +
                           (g[r1 + u0] * (1.0 - tu) + g[r1 + u1] * tu) * tv;
                };
                double* yp = y + (static_cast<size_t>(py) * width + px) * 3;
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

}  // namespace spk
