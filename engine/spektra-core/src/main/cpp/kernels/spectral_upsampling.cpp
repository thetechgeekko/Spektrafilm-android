/*
 * SpectraFilm for Android — native engine: Hanatos2025 RGB->spectrum upsampling.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements spectral_upsampling.h.
 */
#include "kernels/spectral_upsampling.h"

#include <algorithm>
#include <cmath>

namespace spk {
namespace {

// Baked colour.RGB_to_XYZ('ITU-R BT.2020', apply_cctf_decoding=False,
// illuminant=_illuminant_to_xy('D55'), chromatic_adaptation_transform='CAT02').
// Row-major 3x3: xyz[r] = sum_c M[r][c] * rgb[c]. Extracted from the live engine
// at full double precision; reproduces _rgb_to_tc_b to ~1e-7 (limited only by the
// float16 spectra LUT, far below the 1e-4 tolerance).
constexpr double kRgbToXyzBt2020D55[3][3] = {
    {0.658137318557109, 0.15958705592003872, 0.1390665248572427},
    {0.27148950871919203, 0.6809522455467492, 0.047558245734058965},
    {-0.001031678468774255, 0.02224084734735576, 0.9001582877074864},
};

inline double clip01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

// Mitchell-Netravali kernel weight (B = C = 1/3). Ports mitchell_weight.
double mitchell_weight(double t) {
    const double B = 1.0 / 3.0;
    const double C = 1.0 / 3.0;
    double x = std::fabs(t);
    if (x < 1.0) {
        return (1.0 / 6.0) *
               ((12.0 - 9.0 * B - 6.0 * C) * x * x * x +
                (-18.0 + 12.0 * B + 6.0 * C) * x * x + (6.0 - 2.0 * B));
    } else if (x < 2.0) {
        return (1.0 / 6.0) *
               ((-B - 6.0 * C) * x * x * x + (6.0 * B + 30.0 * C) * x * x +
                (-12.0 * B - 48.0 * C) * x + (8.0 * B + 24.0 * C));
    }
    return 0.0;
}

// Reflect an index into [0, L-1] using symmetric reflection. Ports safe_index.
int safe_index(int idx, int L) {
    if (idx < 0) return -idx;
    if (idx >= L) return 2 * (L - 1) - idx;
    return idx;
}

// Clamp a coordinate into [0, L-1]. Ports clamp_coordinate.
double clamp_coordinate(double coord, int L) {
    if (coord <= 0.0) return 0.0;
    double upper = static_cast<double>(L - 1);
    if (coord >= upper) return upper;
    return coord;
}

// Map a clamped coordinate onto a cubic cell in [0, L-2].
// Ports cubic_coordinate_base_fraction. Returns base in *base, fraction returned.
double cubic_coordinate_base_fraction(double coord, int L, int* base) {
    coord = clamp_coordinate(coord, L);
    if (coord >= static_cast<double>(L - 1)) {
        *base = L - 2;
        return 1.0;
    }
    int b = static_cast<int>(std::floor(coord));
    *base = b;
    return coord - b;
}

}  // namespace

Vec2 tri2quad(Vec2 tc) {
    double tx = tc.x;
    double ty = tc.y;
    double y = ty / std::fmax(1.0 - tx, 1e-10);
    double x = (1.0 - tx) * (1.0 - tx);
    return {clip01(x), clip01(y)};
}

Vec2 quad2tri(Vec2 xy) {
    double sx = std::sqrt(xy.x);
    double tx = 1.0 - sx;
    double ty = xy.y * sx;
    return {tx, ty};
}

void rgb_to_tc_b(const double rgb[3], Vec2* out_tc, double* out_b) {
    double xyz[3];
    for (int r = 0; r < 3; ++r) {
        xyz[r] = kRgbToXyzBt2020D55[r][0] * rgb[0] +
                 kRgbToXyzBt2020D55[r][1] * rgb[1] +
                 kRgbToXyzBt2020D55[r][2] * rgb[2];
    }
    double b = xyz[0] + xyz[1] + xyz[2];
    double denom = std::fmax(b, 1e-10);
    Vec2 xy{clip01(xyz[0] / denom), clip01(xyz[1] / denom)};
    *out_tc = tri2quad(xy);
    // _rgb_to_tc_b applies np.nan_to_num(b); for finite linear RGB b is finite.
    *out_b = std::isnan(b) ? 0.0 : b;
}

void cubic_interp_lut_at_2d(const NdArray& lut, double x, double y, double* out) {
    int L = lut.shape[0];
    int channels = lut.shape[2];

    int x_base, y_base;
    double x_frac = cubic_coordinate_base_fraction(x, L, &x_base);
    double y_frac = cubic_coordinate_base_fraction(y, L, &y_base);

    double wx[4] = {mitchell_weight(x_frac + 1.0), mitchell_weight(x_frac),
                    mitchell_weight(x_frac - 1.0), mitchell_weight(x_frac - 2.0)};
    double wy[4] = {mitchell_weight(y_frac + 1.0), mitchell_weight(y_frac),
                    mitchell_weight(y_frac - 1.0), mitchell_weight(y_frac - 2.0)};

    for (int c = 0; c < channels; ++c) out[c] = 0.0;
    double weight_sum = 0.0;
    for (int i = 0; i < 4; ++i) {
        int xi = safe_index(x_base - 1 + i, L);
        for (int j = 0; j < 4; ++j) {
            int yj = safe_index(y_base - 1 + j, L);
            double weight = wx[i] * wy[j];
            weight_sum += weight;
            for (int c = 0; c < channels; ++c)
                out[c] += weight * lut.at3(xi, yj, c);
        }
    }
    if (weight_sum != 0.0) {
        for (int c = 0; c < channels; ++c) out[c] /= weight_sum;
    }
}

void rgb_to_spectrum_hanatos2025(const double rgb[3],
                                 const NdArray& spectra_lut,
                                 double out[kSpectralSamples]) {
    Vec2 tc;
    double b;
    rgb_to_tc_b(rgb, &tc, &b);
    int L = spectra_lut.shape[0];
    double scale = static_cast<double>(L - 1);
    cubic_interp_lut_at_2d(spectra_lut, tc.x * scale, tc.y * scale, out);
    for (int i = 0; i < kSpectralSamples; ++i) out[i] *= b;
}

double poly2d_deg4(double xc, double yc, const double params[15], Vec2 center) {
    double x = xc - center.x;
    double y = yc - center.y;
    double x2 = x * x;
    double y2 = y * y;
    double xy = x * y;
    double x3 = x2 * x;
    double y3 = y2 * y;
    // params[0] (c0) intentionally unused; center_tc is zero correction.
    double c1 = params[1], c2 = params[2], c3 = params[3], c4 = params[4];
    double c5 = params[5], c6 = params[6], c7 = params[7], c8 = params[8];
    double c9 = params[9], c10 = params[10], c11 = params[11], c12 = params[12];
    double c13 = params[13], c14 = params[14];
    return c1 * x + c2 * y + c3 * x2 + c4 * y2 + c5 * xy + c6 * x3 + c7 * y3 +
           c8 * (x2 * y) + c9 * (x * y2) + c10 * (x2 * x2) + c11 * (y2 * y2) +
           c12 * (x3 * y) + c13 * (x2 * y2) + c14 * (x * y3);
}

void fetch_coeffs(const NdArray& coeffs_lut, Vec2 tc, double out_coeffs[4]) {
    int L = coeffs_lut.shape[0];
    double scale = static_cast<double>(L - 1);
    cubic_interp_lut_at_2d(coeffs_lut, tc.x * scale, tc.y * scale, out_coeffs);
}

}  // namespace spk
