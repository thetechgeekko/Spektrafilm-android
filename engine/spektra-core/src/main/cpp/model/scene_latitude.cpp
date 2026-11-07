/*
 * Spektrafilm for Android — native engine: scene latitude mapping (opt-in).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * See model/scene_latitude.h for the model and its provenance.
 */
#include "model/scene_latitude.h"

#include <algorithm>
#include <cmath>

namespace spk {

namespace {

// Metered mid-grey: auto-exposure scales the frame so the meter reads this
// (spektra.cpp: ev = -log2(metered / 0.184)).
constexpr double kMidGrey = 0.184;

// ProPhoto RGB (D50) -> XYZ, the Y row. The engine's only input space.
constexpr double kYr = 0.2880402;
constexpr double kYg = 0.7118741;
constexpr double kYb = 0.0000857;

constexpr double kMaxStops = 24.0;

bool finite_pos(double v) { return std::isfinite(v) && v > 0.0; }

// The compressed distance past a knee: D * H / (H^m + D^m)^(1/m). Monotone in D,
// equal to D near 0 (slope 1 at the knee) and approaching H as D grows.
double compress(double D, double H, double m) {
    if (!(D > 0.0)) return D;
    if (m == 2.0) return D * H / std::sqrt(H * H + D * D);
    if (m == 1.0) return D * H / (H + D);
    return D * H / std::pow(std::pow(H, m) + std::pow(D, m), 1.0 / m);
}

double clamp_rolloff(double m) { return std::clamp(std::isfinite(m) ? m : 2.0, 1.0, 4.0); }

}  // namespace

bool SceneLatitudeParams::engaged() const {
    if (!active) return false;
    const bool hi = finite_pos(highlight_room) && std::isfinite(highlight_knee);
    const bool lo = finite_pos(shadow_room) && std::isfinite(shadow_knee);
    return hi || lo;
}

double scene_latitude_map_stops(const SceneLatitudeParams& p, double E) {
    if (!p.engaged() || !std::isfinite(E)) return E;
    const double m = clamp_rolloff(p.rolloff);
    if (finite_pos(p.highlight_room) && E > p.highlight_knee) {
        return p.highlight_knee + compress(E - p.highlight_knee, p.highlight_room, m);
    }
    if (finite_pos(p.shadow_room) && E < p.shadow_knee) {
        return p.shadow_knee - compress(p.shadow_knee - E, p.shadow_room, m);
    }
    return E;
}

double scene_latitude_gain(const SceneLatitudeParams& p, const double rgb[3]) {
    const double y = kYr * std::max(rgb[0], 0.0) + kYg * std::max(rgb[1], 0.0) +
                     kYb * std::max(rgb[2], 0.0);
    if (!finite_pos(y)) return 1.0;
    const double E = std::clamp(std::log2(y / kMidGrey), -kMaxStops, kMaxStops);
    double d = scene_latitude_map_stops(p, E) - E;
    if (d == 0.0) return 1.0;
    if (d > 0.0) {
        const double L = finite_pos(p.max_lift) ? p.max_lift : 4.0;
        d = d * L / std::sqrt(L * L + d * d);
    }
    return std::exp2(d);
}

void scene_latitude_apply(const SceneLatitudeParams& p, double rgb[3]) {
    const double k = scene_latitude_gain(p, rgb);
    if (k == 1.0) return;
    if (k > 1.0) {
        rgb[0] *= k;
        rgb[1] *= k;
        rgb[2] *= k;
        return;
    }
    // Same Y as the gain (finite and positive, or the gain would be 1).
    const double y = kYr * std::max(rgb[0], 0.0) + kYg * std::max(rgb[1], 0.0) +
                     kYb * std::max(rgb[2], 0.0);
    for (int c = 0; c < 3; ++c) rgb[c] = k * (y + k * (rgb[c] - y));
}

}  // namespace spk
