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
 * compress_rgb_aces_rgc. All math is in double precision to match the oracle
 * (NumPy float64); the knee uses std::pow, the same transcendental NumPy calls.
 */
#include "model/gamut_compression.h"

#include <cmath>

namespace spk {

double reinhard_knee(double d, double threshold, double limit, double power) {
    // gamut_compression.py::reinhard_knee: identity at/below threshold (the oracle's
    // `mask = d > threshold` is strict, so d == threshold returns d unchanged), and a
    // smooth Reinhard roll-off above it that asymptotes to `limit`.
    if (!(d > threshold)) return d;  // (!(>) also leaves NaN untouched, as np.where would)
    const double scale = limit - threshold;
    const double x = (d - threshold) / scale;
    const double y = x / std::pow(1.0 + std::pow(x, power), 1.0 / power);
    return threshold + scale * y;
}

void compress_pixel_aces_rgc(const double rgb[3], double threshold, double limit,
                             double power, double out[3]) {
    // gamut_compression.py::compress_rgb_aces_rgc, per pixel.
    //   ach = max(R,G,B)
    //   safe_ach = ach if ach > 1e-12 else 1.0
    //   d = (ach - c) / safe_ach   (per channel; d >= 0, and d > 1 iff c < 0)
    //   c' = ach * (1 - reinhard_knee(d))
    //   pixels with ach <= 1e-12 keep their original (near-black) values.
    const double ach = std::fmax(rgb[0], std::fmax(rgb[1], rgb[2]));
    if (!(ach > 1e-12)) {  // near-black (or non-finite ach) -> identity, matching np.where
        out[0] = rgb[0];
        out[1] = rgb[1];
        out[2] = rgb[2];
        return;
    }
    for (int c = 0; c < 3; ++c) {
        const double d = (ach - rgb[c]) / ach;  // safe_ach == ach here (ach > 1e-12)
        const double dc = reinhard_knee(d, threshold, limit, power);
        out[c] = ach * (1.0 - dc);
    }
}

void compress_rgb_aces_rgc(double* rgb, int npix, double threshold, double limit,
                           double power) {
    for (int p = 0; p < npix; ++p) {
        double* px = rgb + static_cast<long>(p) * 3;
        const double in[3] = {px[0], px[1], px[2]};
        compress_pixel_aces_rgc(in, threshold, limit, power, px);
    }
}

}  // namespace spk
