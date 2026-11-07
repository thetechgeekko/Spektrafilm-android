/*
 * Spektrafilm for Android — native engine: date-back imprint (opt-in).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * See model/date_stamp.h for the model and its provenance.
 */
#include "model/date_stamp.h"

#include <algorithm>
#include <cmath>

namespace spk {

namespace {

constexpr double kMidGrey = 0.184;
constexpr double kYr = 0.2880402, kYg = 0.7118741, kYb = 0.0000857;  // ProPhoto D50 Y row

// Glyph geometry in "em" units: a digit is 1.0 tall. Segments are capsules
// (line segments with round caps), which is how LED date backs look on film.
constexpr double kDigitW = 0.55;   // digit cell width
constexpr double kGap = 0.16;      // space between glyphs
constexpr double kStroke = 0.11;   // segment thickness
constexpr double kSkew = 0.10;     // italic lean, em of x per em of height
constexpr double kR = kStroke / 2.0;

struct Capsule { double x0, y0, x1, y1; };

struct Glyph {
    double advance = 0.0;
    std::vector<Capsule> parts;
};

// Seven segments, a..g, in the usual order.
Capsule segment(int s) {
    const double L = kR, R = kDigitW - kR, T = kR, M = 0.5, B = 1.0 - kR, g = 0.035;
    switch (s) {
        case 0: return {L + g, T, R - g, T};      // a
        case 1: return {R, T + g, R, M - g};      // b
        case 2: return {R, M + g, R, B - g};      // c
        case 3: return {L + g, B, R - g, B};      // d
        case 4: return {L, M + g, L, B - g};      // e
        case 5: return {L, T + g, L, M - g};      // f
        default: return {L + g, M, R - g, M};     // g
    }
}

constexpr int kDigitSegments[10] = {
    0b0111111,  // 0: abcdef
    0b0000110,  // 1: bc
    0b1011011,  // 2: abdeg
    0b1001111,  // 3: abcdg
    0b1100110,  // 4: bcfg
    0b1101101,  // 5: acdfg
    0b1111101,  // 6: acdefg
    0b0000111,  // 7: abc
    0b1111111,  // 8
    0b1101111,  // 9: abcdfg
};

bool glyph_for(char c, Glyph* out) {
    out->parts.clear();
    if (c >= '0' && c <= '9') {
        out->advance = kDigitW;
        const int bits = kDigitSegments[c - '0'];
        for (int s = 0; s < 7; ++s)
            if (bits & (1 << s)) out->parts.push_back(segment(s));
        return true;
    }
    switch (c) {
        case ' ': out->advance = 0.32; return true;
        case '-': out->advance = kDigitW; out->parts.push_back(segment(6)); return true;
        case '\'':
            out->advance = 0.14;
            out->parts.push_back({0.08, kR, 0.06, 0.24});
            return true;
        case '.':
            out->advance = 0.14;
            out->parts.push_back({0.06, 1.0 - kR, 0.06, 1.0 - kR});
            return true;
        case ':':
            out->advance = 0.14;
            out->parts.push_back({0.06, 0.3, 0.06, 0.3});
            out->parts.push_back({0.06, 0.72, 0.06, 0.72});
            return true;
        case '/':
            out->advance = 0.34;
            out->parts.push_back({0.04, 1.0 - kR, 0.28, kR});
            return true;
        default:
            return false;  // unknown characters are skipped
    }
}

double dist_to_capsule(double u, double v, const Capsule& c) {
    const double dx = c.x1 - c.x0, dy = c.y1 - c.y0;
    const double len2 = dx * dx + dy * dy;
    double t = len2 > 0.0 ? ((u - c.x0) * dx + (v - c.y0) * dy) / len2 : 0.0;
    t = std::clamp(t, 0.0, 1.0);
    const double px = c.x0 + t * dx - u, py = c.y0 + t * dy - v;
    return std::sqrt(px * px + py * py);
}

}  // namespace

bool DateStampParams::engaged() const {
    if (!active || !(height > 0.0) || !std::isfinite(height) || !std::isfinite(exposure_ev))
        return false;
    const double y = kYr * color[0] + kYg * color[1] + kYb * color[2];
    if (!(y > 0.0) || !std::isfinite(y)) return false;
    Glyph g;
    for (char c : text)
        if (glyph_for(c, &g) && !g.parts.empty()) return true;
    return false;
}

DateStampMask build_date_stamp_mask(const DateStampParams& p, int width, int height,
                                    double exposure_compensation_ev) {
    DateStampMask m;
    if (!p.engaged() || width <= 0 || height <= 0) return m;

    // Lay the text out in em units.
    std::vector<std::pair<double, Glyph>> laid;
    double pen = 0.0;
    for (char c : p.text) {
        Glyph g;
        if (!glyph_for(c, &g)) continue;
        if (!laid.empty()) pen += kGap;
        laid.emplace_back(pen, g);
        pen += g.advance;
    }
    const double text_w_em = pen + kSkew;  // the lean widens the top edge

    const double short_edge = static_cast<double>(std::min(width, height));
    const double S = std::max(4.0, std::clamp(p.height, 0.0, 0.5) * short_edge);  // px per em
    const double margin = std::clamp(std::isfinite(p.margin) ? p.margin : 0.05, 0.0, 0.45) *
                          short_edge;
    const double tw = text_w_em * S, th = S;
    const bool right = p.corner == 0 || p.corner == 2;
    const bool bottom = p.corner == 0 || p.corner == 1;
    const double ox = right ? width - margin - tw : margin;
    const double oy = bottom ? height - margin - th : margin;

    // Edge softness: at least a pixel, more on a big frame so the strokes have the
    // soft LED edge rather than a vector-sharp one.
    const double aa = std::max(1.0, 0.05 * S);
    const double pad = aa + 2.0;
    const int x0 = std::max(0, static_cast<int>(std::floor(ox - pad)));
    const int y0 = std::max(0, static_cast<int>(std::floor(oy - pad)));
    const int x1 = std::min(width, static_cast<int>(std::ceil(ox + tw + pad)));
    const int y1 = std::min(height, static_cast<int>(std::ceil(oy + th + pad)));
    if (x1 <= x0 || y1 <= y0) return m;

    m.x0 = x0;
    m.y0 = y0;
    m.w = x1 - x0;
    m.h = y1 - y0;
    m.coverage.assign(static_cast<size_t>(m.w) * m.h, 0.0f);
    for (int y = y0; y < y1; ++y) {
        const double v = (y + 0.5 - oy) / S;
        for (int x = x0; x < x1; ++x) {
            // Undo the lean: the top of a glyph is shifted right by kSkew em.
            const double u = (x + 0.5 - ox) / S - (1.0 - v) * kSkew;
            double best = 0.0;
            for (const auto& [gx, g] : laid) {
                const double lu = u - gx;
                if (lu < -0.5 || lu > g.advance + 0.5) continue;
                for (const Capsule& c : g.parts) {
                    const double d_px = (dist_to_capsule(lu, v, c) - kR) * S;
                    best = std::max(best, std::clamp(0.5 - d_px / aa, 0.0, 1.0));
                }
            }
            m.coverage[static_cast<size_t>(y - y0) * m.w + (x - x0)] = static_cast<float>(best);
        }
    }

    const double ylum = kYr * p.color[0] + kYg * p.color[1] + kYb * p.color[2];
    const double ec = std::isfinite(exposure_compensation_ev) ? exposure_compensation_ev : 0.0;
    const double k = kMidGrey * std::exp2(p.exposure_ev - ec) / ylum;
    for (int c = 0; c < 3; ++c) m.light[c] = std::max(0.0, p.color[c]) * k;
    return m;
}

}  // namespace spk
