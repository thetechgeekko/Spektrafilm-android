/*
 * Spektrafilm for Android — native engine: 1D tone curve (kernels/tonecurve).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * A Lightroom-style point tone curve applied to the FINAL display-referred RGB
 * (after CCTF encode + clip), reverse-engineered from Lightroom's parametric/point
 * curve (docs/IMPROVEMENT_BACKLOG.md #2). Control points are interpolated with a
 * monotone cubic (Fritsch–Carlson PCHIP) so the curve is smooth and never
 * overshoots into non-monotonic wiggles, then baked into a dense LUT for O(1)
 * per-pixel lookup.
 *
 * PARITY: the default is identity — a curve with < 2 control points is a strict
 * no-op (eval returns its input unchanged, NaN passes through). The engine's
 * parity goldens run with no tone curve, so they stay bit-exact; this stage only
 * does work when a caller supplies points.
 */
#ifndef SPK_KERNELS_TONECURVE_H
#define SPK_KERNELS_TONECURVE_H

namespace spk {

// One channel's curve, baked to a dense [0,1] LUT. Identity until built from >= 2
// control points.
struct ToneCurve1D {
    static constexpr int kLutSize = 1025;
    bool identity = true;
    float lut[kLutSize];

    // Evaluate at x. Identity returns x; NaN returns NaN; finite x is clamped to
    // [0,1] and linearly sampled from the baked LUT (whose entries are clamped to
    // [0,1]).
    float eval(float x) const;
};

// Master + per-channel (R,G,B) curves. apply() runs the channel curve then the
// master curve (Lightroom order). Inactive => identity for every channel.
struct ToneCurveSet {
    bool active = false;
    ToneCurve1D master;
    ToneCurve1D rgb[3];

    // c in [0,2]. Returns v unchanged when inactive; NaN passes through.
    float apply(int c, float v) const;
};

// Build a curve from n control points (xs/ys in [0,1], xs strictly increasing).
// n < 2 (or null) yields an identity curve. ys are clamped to [0,1] when baked.
ToneCurve1D build_tone_curve_1d(const float* xs, const float* ys, int n);

}  // namespace spk

#endif  // SPK_KERNELS_TONECURVE_H
