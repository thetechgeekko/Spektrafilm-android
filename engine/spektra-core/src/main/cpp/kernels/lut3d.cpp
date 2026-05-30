/*
 * Spektrafilm for Android — native engine: opt-in 3D LUT acceleration.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements kernels/lut3d.h — a faithful port of the oracle's
 * PCHIP 3D LUT path (spektrafilm/utils/fast_interp_lut.py: prepare_lut_pchip_3d
 * + _apply_lut_pchip_3d_prepared, the apply_lut_3d default method='pchip') and
 * the LUT build (spektrafilm/utils/lut.py: _create_lut_3d / compute_with_lut).
 */
#include "kernels/lut3d.h"

#include <cmath>

namespace spk {

namespace {

// fast_interp_lut.clamp_coordinate.
inline double clamp_coordinate(double coord, int L) {
    if (coord <= 0.0) return 0.0;
    double upper = static_cast<double>(L - 1);
    if (coord >= upper) return upper;
    return coord;
}

// fast_interp_lut.cubic_coordinate_base_fraction: maps a clamped coordinate onto
// a cubic interpolation cell base in [0, L-2] and its fraction.
inline void cubic_coordinate_base_fraction(double coord, int L, int* base,
                                           double* frac) {
    coord = clamp_coordinate(coord, L);
    if (coord >= static_cast<double>(L - 1)) {
        *base = L - 2;
        *frac = 1.0;
        return;
    }
    int b = static_cast<int>(std::floor(coord));
    *base = b;
    *frac = coord - static_cast<double>(b);
}

// fast_interp_lut._fill_monotone_slopes_1d: PCHIP-style monotone slopes for a
// uniformly sampled 1D signal. `values`/`slopes` length == size.
void fill_monotone_slopes_1d(const double* values, int size, double* slopes) {
    if (size == 1) {
        slopes[0] = 0.0;
        return;
    }
    // deltas[i] = values[i+1] - values[i], i in [0, size-2].
    std::vector<double> deltas(static_cast<size_t>(size - 1));
    for (int i = 0; i < size - 1; ++i) deltas[i] = values[i + 1] - values[i];

    if (size == 2) {
        slopes[0] = deltas[0];
        slopes[1] = deltas[0];
        return;
    }

    double left = 0.5 * (3.0 * deltas[0] - deltas[1]);
    if (left * deltas[0] <= 0.0) {
        left = 0.0;
    } else if (deltas[0] * deltas[1] < 0.0 &&
               std::fabs(left) > std::fabs(3.0 * deltas[0])) {
        left = 3.0 * deltas[0];
    }
    slopes[0] = left;

    for (int i = 1; i < size - 1; ++i) {
        double delta_prev = deltas[i - 1];
        double delta_next = deltas[i];
        if (delta_prev == 0.0 || delta_next == 0.0 ||
            delta_prev * delta_next <= 0.0) {
            slopes[i] = 0.0;
        } else {
            slopes[i] =
                2.0 * delta_prev * delta_next / (delta_prev + delta_next);
        }
    }

    double right = 0.5 * (3.0 * deltas[size - 2] - deltas[size - 3]);
    if (right * deltas[size - 2] <= 0.0) {
        right = 0.0;
    } else if (deltas[size - 2] * deltas[size - 3] < 0.0 &&
               std::fabs(right) > std::fabs(3.0 * deltas[size - 2])) {
        right = 3.0 * deltas[size - 2];
    }
    slopes[size - 1] = right;
}

// fast_interp_lut._hermite_value.
inline double hermite_value(double y0, double y1, double m0, double m1,
                            double t) {
    double t2 = t * t;
    double t3 = t2 * t;
    double h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    double h10 = t3 - 2.0 * t2 + t;
    double h01 = -2.0 * t3 + 3.0 * t2;
    double h11 = t3 - t2;
    return h00 * y0 + h10 * m0 + h01 * y1 + h11 * m1;
}

inline double linear_mix(double v0, double v1, double t) {
    return v0 + t * (v1 - v0);
}

inline double bilinear_mix(double v00, double v10, double v01, double v11,
                           double tx, double ty) {
    double vx0 = linear_mix(v00, v10, tx);
    double vx1 = linear_mix(v01, v11, tx);
    return linear_mix(vx0, vx1, ty);
}

inline double clamp_to_range(double value, double mn, double mx) {
    if (value < mn) return mn;
    if (value > mx) return mx;
    return value;
}

// Prepared PCHIP state for a LUT (mirrors prepare_lut_pchip_3d's outputs).
struct PchipPrep {
    int size = 0;
    const double* lut = nullptr;          // [i][j][k][c]
    std::vector<double> slope_x, slope_y, slope_z;  // size^3 * 3
    std::vector<double> cell_min, cell_max;         // (size-1)^3 * 3

    inline size_t idx(int i, int j, int k, int c) const {
        return (((static_cast<size_t>(i) * size + j) * size + k) * 3) +
               static_cast<size_t>(c);
    }
    inline size_t cidx(int i, int j, int k, int c) const {
        const int s1 = size - 1;
        return (((static_cast<size_t>(i) * s1 + j) * s1 + k) * 3) +
               static_cast<size_t>(c);
    }
};

// Port of _prepare_lut_pchip_3d_impl.
PchipPrep prepare_pchip(const Lut3D& L) {
    PchipPrep p;
    const int size = L.steps;
    p.size = size;
    p.lut = L.data.data();
    const size_t n = static_cast<size_t>(size) * size * size * 3;
    p.slope_x.assign(n, 0.0);
    p.slope_y.assign(n, 0.0);
    p.slope_z.assign(n, 0.0);
    const int s1 = size - 1;
    p.cell_min.assign(static_cast<size_t>(s1) * s1 * s1 * 3, 0.0);
    p.cell_max.assign(static_cast<size_t>(s1) * s1 * s1 * 3, 0.0);

    std::vector<double> line(static_cast<size_t>(size));
    std::vector<double> slopes(static_cast<size_t>(size));

    // slope_x: lines along axis i (first input).
    for (int j = 0; j < size; ++j)
        for (int k = 0; k < size; ++k)
            for (int c = 0; c < 3; ++c) {
                for (int i = 0; i < size; ++i) line[i] = L.data[L.index(i, j, k, c)];
                fill_monotone_slopes_1d(line.data(), size, slopes.data());
                for (int i = 0; i < size; ++i) p.slope_x[p.idx(i, j, k, c)] = slopes[i];
            }
    // slope_y: lines along axis j.
    for (int i = 0; i < size; ++i)
        for (int k = 0; k < size; ++k)
            for (int c = 0; c < 3; ++c) {
                for (int j = 0; j < size; ++j) line[j] = L.data[L.index(i, j, k, c)];
                fill_monotone_slopes_1d(line.data(), size, slopes.data());
                for (int j = 0; j < size; ++j) p.slope_y[p.idx(i, j, k, c)] = slopes[j];
            }
    // slope_z: lines along axis k.
    for (int i = 0; i < size; ++i)
        for (int j = 0; j < size; ++j)
            for (int c = 0; c < 3; ++c) {
                for (int k = 0; k < size; ++k) line[k] = L.data[L.index(i, j, k, c)];
                fill_monotone_slopes_1d(line.data(), size, slopes.data());
                for (int k = 0; k < size; ++k) p.slope_z[p.idx(i, j, k, c)] = slopes[k];
            }
    // cell min/max over the 8 corners of each cell.
    for (int i = 0; i < s1; ++i)
        for (int j = 0; j < s1; ++j)
            for (int k = 0; k < s1; ++k)
                for (int c = 0; c < 3; ++c) {
                    double mn = L.data[L.index(i, j, k, c)];
                    double mx = mn;
                    for (int di = 0; di < 2; ++di)
                        for (int dj = 0; dj < 2; ++dj)
                            for (int dk = 0; dk < 2; ++dk) {
                                double s = L.data[L.index(i + di, j + dj, k + dk, c)];
                                if (s < mn) mn = s;
                                else if (s > mx) mx = s;
                            }
                    p.cell_min[p.cidx(i, j, k, c)] = mn;
                    p.cell_max[p.cidx(i, j, k, c)] = mx;
                }
    return p;
}

// Port of _pchip_interp_lut_at_3d_prepared. r/g/b are in [0, size-1] grid coords.
void pchip_interp_at(const PchipPrep& p, double r, double g, double b,
                     double out[3]) {
    const int size = p.size;
    int i, j, k;
    double tr, tg, tb;
    cubic_coordinate_base_fraction(r, size, &i, &tr);
    cubic_coordinate_base_fraction(g, size, &j, &tg);
    cubic_coordinate_base_fraction(b, size, &k, &tb);

    for (int c = 0; c < 3; ++c) {
        const double* lut = p.lut;
        auto LV = [&](int ii, int jj, int kk) { return lut[p.idx(ii, jj, kk, c)]; };
        auto SX = [&](int ii, int jj, int kk) { return p.slope_x[p.idx(ii, jj, kk, c)]; };
        auto SY = [&](int ii, int jj, int kk) { return p.slope_y[p.idx(ii, jj, kk, c)]; };
        auto SZ = [&](int ii, int jj, int kk) { return p.slope_z[p.idx(ii, jj, kk, c)]; };

        double v000 = hermite_value(LV(i, j, k), LV(i + 1, j, k),
                                    SX(i, j, k), SX(i + 1, j, k), tr);
        double v010 = hermite_value(LV(i, j + 1, k), LV(i + 1, j + 1, k),
                                    SX(i, j + 1, k), SX(i + 1, j + 1, k), tr);
        double v001 = hermite_value(LV(i, j, k + 1), LV(i + 1, j, k + 1),
                                    SX(i, j, k + 1), SX(i + 1, j, k + 1), tr);
        double v011 = hermite_value(LV(i, j + 1, k + 1), LV(i + 1, j + 1, k + 1),
                                    SX(i, j + 1, k + 1), SX(i + 1, j + 1, k + 1), tr);

        double sy00 = linear_mix(SY(i, j, k), SY(i + 1, j, k), tr);
        double sy10 = linear_mix(SY(i, j + 1, k), SY(i + 1, j + 1, k), tr);
        double sy01 = linear_mix(SY(i, j, k + 1), SY(i + 1, j, k + 1), tr);
        double sy11 = linear_mix(SY(i, j + 1, k + 1), SY(i + 1, j + 1, k + 1), tr);

        double vz0 = hermite_value(v000, v010, sy00, sy10, tg);
        double vz1 = hermite_value(v001, v011, sy01, sy11, tg);

        double sz0 = bilinear_mix(SZ(i, j, k), SZ(i + 1, j, k),
                                  SZ(i, j + 1, k), SZ(i + 1, j + 1, k), tr, tg);
        double sz1 = bilinear_mix(SZ(i, j, k + 1), SZ(i + 1, j, k + 1),
                                  SZ(i, j + 1, k + 1), SZ(i + 1, j + 1, k + 1), tr, tg);

        double interpolated = hermite_value(vz0, vz1, sz0, sz1, tb);
        out[c] = clamp_to_range(interpolated, p.cell_min[p.cidx(i, j, k, c)],
                                p.cell_max[p.cidx(i, j, k, c)]);
    }
}

}  // namespace

Lut3D build_lut_3d(const double xmin[3], const double xmax[3], int steps,
                   const std::vector<double>& /*fn_inputs_unused*/,
                   void (*fn)(const double in[3], double out[3], void* ctx),
                   void* ctx) {
    Lut3D lut;
    lut.steps = steps;
    for (int c = 0; c < 3; ++c) {
        lut.xmin[c] = xmin[c];
        lut.xmax[c] = xmax[c];
    }
    lut.data.assign(static_cast<size_t>(steps) * steps * steps * 3, 0.0);

    // Per-axis linspace (endpoint=True). step = (stop-start)/(steps-1); the last
    // sample is set to the exact endpoint (numpy's endpoint guarantee).
    std::vector<double> grid[3];
    for (int c = 0; c < 3; ++c) {
        grid[c].resize(static_cast<size_t>(steps));
        if (steps == 1) {
            grid[c][0] = xmin[c];
            continue;
        }
        double step = (xmax[c] - xmin[c]) / static_cast<double>(steps - 1);
        for (int s = 0; s < steps; ++s) {
            grid[c][s] = (s == steps - 1) ? xmax[c]
                                          : (xmin[c] + step * static_cast<double>(s));
        }
    }

    double in[3], out[3];
    for (int i = 0; i < steps; ++i)
        for (int j = 0; j < steps; ++j)
            for (int k = 0; k < steps; ++k) {
                in[0] = grid[0][i];
                in[1] = grid[1][j];
                in[2] = grid[2][k];
                fn(in, out, ctx);
                for (int c = 0; c < 3; ++c) lut.data[lut.index(i, j, k, c)] = out[c];
            }
    return lut;
}

void apply_lut_3d_pchip_normalized(const Lut3D& L, const double* norm, int w,
                                   int h, double* out) {
    if (L.steps <= 0) return;
    // size==1 -> constant LUT value everywhere (_apply_lut_constant_3d).
    if (L.steps == 1) {
        const double v0 = L.data[L.index(0, 0, 0, 0)];
        const double v1 = L.data[L.index(0, 0, 0, 1)];
        const double v2 = L.data[L.index(0, 0, 0, 2)];
        const size_t n = static_cast<size_t>(w) * h;
        for (size_t p = 0; p < n; ++p) {
            out[p * 3 + 0] = v0;
            out[p * 3 + 1] = v1;
            out[p * 3 + 2] = v2;
        }
        return;
    }
    const PchipPrep prep = prepare_pchip(L);
    const double scale = static_cast<double>(L.steps - 1);
    const size_t n = static_cast<size_t>(w) * h;
    for (size_t p = 0; p < n; ++p) {
        double r = norm[p * 3 + 0] * scale;
        double g = norm[p * 3 + 1] * scale;
        double b = norm[p * 3 + 2] * scale;
        double v[3];
        pchip_interp_at(prep, r, g, b, v);
        out[p * 3 + 0] = v[0];
        out[p * 3 + 1] = v[1];
        out[p * 3 + 2] = v[2];
    }
}

void apply_lut_3d_pchip(const Lut3D& L, const double* data, int w, int h,
                        double* out) {
    // compute_with_lut: data_normalized = (data - xmin)/(xmax - xmin).
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<double> norm(n * 3);
    for (size_t p = 0; p < n; ++p)
        for (int c = 0; c < 3; ++c) {
            double denom = L.xmax[c] - L.xmin[c];
            norm[p * 3 + c] = (data[p * 3 + c] - L.xmin[c]) / denom;
        }
    apply_lut_3d_pchip_normalized(L, norm.data(), w, h, out);
}

}  // namespace spk
