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

// RFC-023's m = 2 branch: slope 1 at the knee, asymptote h.
inline double soft(double d, double h) { return d * h / std::sqrt(h * h + d * d); }

// The branch room `h` such that, after the gain bound, a base kContrastMaskReach
// stops beyond the knee moves by exactly `amount` stops (closed form both ways).
double room_for(double amount) {
    const double L = kContrastMaskGainLimit, R = kContrastMaskReach;
    const double a = std::min(amount, L * 0.999);
    const double delta = a * L / std::sqrt(L * L - a * a);
    const double t = std::max(R - delta, 1e-3);
    return R * t / std::sqrt(R * R - t * t);
}

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
    double d = 0.0;
    if (m.highlights > 0.0 && b < -core) {
        const double D = -core - b;
        d = D - soft(D, room_for(m.highlights));
    } else if (m.shadows > 0.0 && b > core) {
        const double D = b - core;
        d = soft(D, room_for(m.shadows)) - D;
    }
    const double L = kContrastMaskGainLimit;
    return d * L / std::sqrt(L * L + d * d);
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

    // 1. Cell means of x. Cells partition the frame, so every pixel counts once.
    std::vector<double> grid(static_cast<size_t>(gw) * gh);
    parallel_for(0, gh, [&](int lo, int hi) {
        for (int cy = lo; cy < hi; ++cy) {
            const int y0 = static_cast<int>(static_cast<long long>(cy) * height / gh);
            const int y1 = std::min(height, std::max(
                static_cast<int>(static_cast<long long>(cy + 1) * height / gh), y0 + 1));
            for (int cx = 0; cx < gw; ++cx) {
                const int x0 = static_cast<int>(static_cast<long long>(cx) * width / gw);
                const int x1 = std::min(width, std::max(
                    static_cast<int>(static_cast<long long>(cx + 1) * width / gw), x0 + 1));
                double acc = 0.0;
                for (int py = y0; py < y1; ++py)
                    for (int px = x0; px < x1; ++px)
                        acc += pixel_x(y + (static_cast<size_t>(py) * width + px) * 3,
                                       preflash, mult);
                grid[static_cast<size_t>(cy) * gw + cx] =
                    acc / static_cast<double>((x1 - x0) * (y1 - y0));
            }
        }
    });

    // 2. The unsharp base.
    gaussian_grid(grid, gw, gh, std::max(0.5, m.scale * cells));

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
                const double* g0 = grid.data() + static_cast<size_t>(v0) * gw;
                const double* g1 = grid.data() + static_cast<size_t>(v1) * gw;
                const double b = (g0[u0] * (1.0 - tu) + g0[u1] * tu) * (1.0 - tv) +
                                 (g1[u0] * (1.0 - tu) + g1[u1] * tu) * tv;
                const double d = contrast_mask_delta(b, m);
                if (d == 0.0) continue;  // inside the core: untouched, byte for byte
                const double k = std::exp2(d);
                double* yp = y + (static_cast<size_t>(py) * width + px) * 3;
                for (int c = 0; c < 3; ++c) {
                    const double a = std::max(yp[c] - 1e-10 - preflash[c], 0.0);
                    yp[c] = std::max(k * a + preflash[c], 0.0) + 1e-10;
                }
            }
        }
    });
}

}  // namespace spk
