/*
 * Spektrafilm for Android — native engine: DIR couplers.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements model/couplers.h (no-diffusion DIR-coupler path).
 */
#include "model/couplers.h"

#include <cmath>
#include <vector>

#include "kernels/exponential_filter.h"

namespace spk {

namespace {

// Linear interpolation matching numpy.interp(x, xp, fp): xp is assumed sorted
// ascending; values outside [xp[0], xp[-1]] clamp to the endpoint fp values.
// Mirrors np.interp's right-biased search via std::upper_bound semantics.
double np_interp(double x, const double* xp, const double* fp, int n) {
    if (x <= xp[0]) return fp[0];
    if (x >= xp[n - 1]) return fp[n - 1];
    // Find first index where xp[idx] > x (np.interp uses searchsorted right).
    int lo = 0, hi = n;
    while (lo < hi) {
        int mid = (lo + hi) / 2;
        if (xp[mid] <= x)
            lo = mid + 1;
        else
            hi = mid;
    }
    int j = lo - 1;  // xp[j] <= x < xp[j+1]
    double x0 = xp[j], x1 = xp[j + 1];
    double dx = x1 - x0;
    double t = dx != 0.0 ? (x - x0) / dx : 0.0;
    return fp[j] + t * (fp[j + 1] - fp[j]);
}

// fast_interp-style interpolation used by interpolate_exposure_to_density:
// per-channel axis xa (n,), clamp to endpoint density values. Matches the
// searchsorted(side='right') indexing in utils/fast_interp.py.
double fast_interp_channel(double x, const double* xa, const double* y, int n) {
    if (x <= xa[0]) return y[0];
    if (x >= xa[n - 1]) return y[n - 1];
    int lo = 0, hi = n;
    while (lo < hi) {
        int mid = (lo + hi) / 2;
        if (xa[mid] <= x)
            lo = mid + 1;
        else
            hi = mid;
    }
    int low = lo - 1;
    double x0 = xa[low];
    double dx = xa[low + 1] - x0;
    double t = dx != 0.0 ? (x - x0) / dx : 0.0;
    return y[low] + t * (y[low + 1] - y[low]);
}

}  // namespace

void compute_dir_couplers_matrix(const DirCouplersParams& params, double out[9]) {
    // M_self = diag(gamma_samelayer_rgb) * inhibition_samelayer
    // M_inter off-diagonal (donor row, receiver col) * inhibition_interlayer
    for (int i = 0; i < 9; ++i) out[i] = 0.0;
    out[0 * 3 + 0] = params.gamma_samelayer_rgb[0] * params.inhibition_samelayer;
    out[1 * 3 + 1] = params.gamma_samelayer_rgb[1] * params.inhibition_samelayer;
    out[2 * 3 + 2] = params.gamma_samelayer_rgb[2] * params.inhibition_samelayer;

    out[0 * 3 + 1] += params.gamma_interlayer_r_to_gb[0] * params.inhibition_interlayer;
    out[0 * 3 + 2] += params.gamma_interlayer_r_to_gb[1] * params.inhibition_interlayer;
    out[1 * 3 + 0] += params.gamma_interlayer_g_to_rb[0] * params.inhibition_interlayer;
    out[1 * 3 + 2] += params.gamma_interlayer_g_to_rb[1] * params.inhibition_interlayer;
    out[2 * 3 + 0] += params.gamma_interlayer_b_to_rg[0] * params.inhibition_interlayer;
    out[2 * 3 + 1] += params.gamma_interlayer_b_to_rg[1] * params.inhibition_interlayer;

    for (int i = 0; i < 9; ++i) out[i] *= params.amount;
}

void apply_density_correction_dir_couplers(const float* density_cmy, int npix,
                                           const float* log_raw,
                                           const float* log_exposure,
                                           const float* density_curves, int n,
                                           const DirCouplersParams& params,
                                           bool positive_film,
                                           const float gamma_factor[3],
                                           float* out) {
    if (!params.active) {
        if (out != density_cmy)
            for (int i = 0; i < npix * 3; ++i) out[i] = density_cmy[i];
        return;
    }

    double M[9];
    compute_dir_couplers_matrix(params, M);

    // log_exposure axis in double.
    std::vector<double> le(n);
    for (int k = 0; k < n; ++k) le[k] = static_cast<double>(log_exposure[k]);

    // ---- compute_density_curves_before_dir_couplers (negative path) ----
    // density_curves_silver = density_curves (negative)
    // For positive film: silver = nanmax(curves,axis=0) - curves (not exercised
    // by the parity goldens, but kept for correctness).
    std::vector<double> dc(static_cast<size_t>(n) * 3);
    for (int i = 0; i < n * 3; ++i) dc[i] = static_cast<double>(density_curves[i]);

    std::vector<double> silver_curve(static_cast<size_t>(n) * 3);
    if (positive_film) {
        double dmax[3] = {-1e300, -1e300, -1e300};
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c) {
                double v = dc[k * 3 + c];
                if (!std::isnan(v) && v > dmax[c]) dmax[c] = v;
            }
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c)
                silver_curve[k * 3 + c] = dmax[c] - dc[k * 3 + c];
    } else {
        silver_curve = dc;
    }

    // couplers_amount_curves[j,m] = sum_k silver_curve[j,k] * M[k,m]
    // log_exposure_0[j,m] = le[j] - couplers_amount_curves[j,m]
    // dc0[:,i] = interp(le, log_exposure_0[:,i], (+/-)dc[:,i])
    std::vector<double> dc0(static_cast<size_t>(n) * 3);
    std::vector<double> le0(static_cast<size_t>(n));   // per-channel x axis buffer
    std::vector<double> ycol(static_cast<size_t>(n));  // per-channel y values
    for (int c = 0; c < 3; ++c) {
        for (int j = 0; j < n; ++j) {
            double amt = 0.0;
            for (int k = 0; k < 3; ++k) amt += silver_curve[j * 3 + k] * M[k * 3 + c];
            le0[j] = le[j] - amt;
            // negative: y = dc[:,c]; positive: interp on -dc, then negate.
            ycol[j] = positive_film ? -dc[j * 3 + c] : dc[j * 3 + c];
        }
        for (int j = 0; j < n; ++j) {
            double v = np_interp(le[j], le0.data(), ycol.data(), n);
            dc0[j * 3 + c] = positive_film ? -v : v;
        }
    }

    // ---- compute_exposure_correction_dir_couplers (diffusion off) ----
    //   density_silver = density_cmy (negative) or density_max - density_cmy
    //                    (positive); += high_exposure_shift * silver^2
    //   log_raw_0 = log_raw - density_silver @ M     (diffusion_size_pixel == 0)
    // For positive film density_max is the per-channel nanmax of density_curves.
    double dmax_cmy[3] = {0.0, 0.0, 0.0};
    if (positive_film) {
        dmax_cmy[0] = dmax_cmy[1] = dmax_cmy[2] = -1e300;
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c) {
                double v = dc[k * 3 + c];
                if (!std::isnan(v) && v > dmax_cmy[c]) dmax_cmy[c] = v;
            }
    }
    const double shift = params.high_exposure_couplers_shift;

    // ---- interpolate_exposure_to_density(log_raw_0, dc0, le, gamma) ----
    // Per-channel x-axis is le / gamma_factor[c]; y is dc0[:,c].
    //
    // NOTE: `out` may alias `density_cmy`, and the inhibitor matmul mixes all
    // three (donor) channels of density_cmy into each (receiver) channel. We
    // therefore snapshot the three silver donor values per pixel BEFORE writing
    // any output channel, matching the vectorised Python (which computes the
    // full correction array first, then interpolates).
    // Per-channel contiguous axis (le/gamma) and curve (dc0[:,c]) arrays.
    std::vector<double> axis_c[3], curve_c[3];
    for (int c = 0; c < 3; ++c) {
        double g = static_cast<double>(gamma_factor[c]);
        axis_c[c].resize(n);
        curve_c[c].resize(n);
        for (int k = 0; k < n; ++k) {
            axis_c[c][k] = le[k] / g;
            curve_c[c][k] = dc0[k * 3 + c];
        }
    }
    for (int p = 0; p < npix; ++p) {
        double silver[3];
        for (int k = 0; k < 3; ++k) {
            double s = static_cast<double>(density_cmy[p * 3 + k]);
            if (positive_film) s = dmax_cmy[k] - s;
            if (shift != 0.0) s += shift * s * s;
            silver[k] = s;
        }
        for (int c = 0; c < 3; ++c) {
            double correction = 0.0;
            for (int k = 0; k < 3; ++k) correction += silver[k] * M[k * 3 + c];
            double log_raw_0 = static_cast<double>(log_raw[p * 3 + c]) - correction;
            out[p * 3 + c] = static_cast<float>(
                fast_interp_channel(log_raw_0, axis_c[c].data(), curve_c[c].data(), n));
        }
    }
}

void apply_density_correction_dir_couplers_spatial(
    const float* density_cmy, int w, int h, const float* log_raw,
    const float* log_exposure, const float* density_curves, int n,
    const DirCouplersParams& params, bool positive_film,
    const float gamma_factor[3], double pixel_size_um, float* out) {
    const int npix = w * h;

    // No spatial diffusion requested -> delegate to the pointwise path.
    if (!params.active || params.diffusion_size_um <= 0.0 || pixel_size_um <= 0.0) {
        apply_density_correction_dir_couplers(density_cmy, npix, log_raw,
                                              log_exposure, density_curves, n,
                                              params, positive_film, gamma_factor,
                                              out);
        return;
    }

    double M[9];
    compute_dir_couplers_matrix(params, M);

    std::vector<double> le(n);
    for (int k = 0; k < n; ++k) le[k] = static_cast<double>(log_exposure[k]);

    // ---- compute_density_curves_before_dir_couplers (same as pointwise) ----
    std::vector<double> dc(static_cast<size_t>(n) * 3);
    for (int i = 0; i < n * 3; ++i) dc[i] = static_cast<double>(density_curves[i]);
    std::vector<double> silver_curve(static_cast<size_t>(n) * 3);
    if (positive_film) {
        double dmax[3] = {-1e300, -1e300, -1e300};
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c) {
                double v = dc[k * 3 + c];
                if (!std::isnan(v) && v > dmax[c]) dmax[c] = v;
            }
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c)
                silver_curve[k * 3 + c] = dmax[c] - dc[k * 3 + c];
    } else {
        silver_curve = dc;
    }
    std::vector<double> dc0(static_cast<size_t>(n) * 3);
    {
        std::vector<double> le0(n), ycol(n);
        for (int c = 0; c < 3; ++c) {
            for (int j = 0; j < n; ++j) {
                double amt = 0.0;
                for (int k = 0; k < 3; ++k) amt += silver_curve[j * 3 + k] * M[k * 3 + c];
                le0[j] = le[j] - amt;
                ycol[j] = positive_film ? -dc[j * 3 + c] : dc[j * 3 + c];
            }
            for (int j = 0; j < n; ++j) {
                double v = np_interp(le[j], le0.data(), ycol.data(), n);
                dc0[j * 3 + c] = positive_film ? -v : v;
            }
        }
    }

    // density_max for the positive-film silver computation.
    double dmax_cmy[3] = {0.0, 0.0, 0.0};
    if (positive_film) {
        dmax_cmy[0] = dmax_cmy[1] = dmax_cmy[2] = -1e300;
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c) {
                double v = dc[k * 3 + c];
                if (!std::isnan(v) && v > dmax_cmy[c]) dmax_cmy[c] = v;
            }
    }
    const double shift = params.high_exposure_couplers_shift;

    // ---- compute_exposure_correction_dir_couplers (diffusion ON) ----
    // correction[p,c] = sum_k silver[p,k] * M[k,c]   (full array, float64)
    std::vector<double> correction(static_cast<size_t>(npix) * 3);
    for (int p = 0; p < npix; ++p) {
        double silver[3];
        for (int k = 0; k < 3; ++k) {
            double s = static_cast<double>(density_cmy[p * 3 + k]);
            if (positive_film) s = dmax_cmy[k] - s;
            if (shift != 0.0) s += shift * s * s;
            silver[k] = s;
        }
        for (int c = 0; c < 3; ++c) {
            double corr = 0.0;
            for (int k = 0; k < 3; ++k) corr += silver[k] * M[k * 3 + c];
            correction[p * 3 + c] = corr;
        }
    }

    // Diffuse the correction:
    //   correction = (1 - tail_w) * G(size_px) * correction
    //              + tail_w       * Exp(tail_px) * correction
    const double size_px = params.diffusion_size_um / pixel_size_um;
    const double tail_px = params.diffusion_tail_um / pixel_size_um;
    const double tail_w = params.diffusion_tail_weight;
    {
        std::vector<double> gauss(correction);  // copy, then blur in place
        double sg[3] = {size_px, size_px, size_px};
        gaussian_blur_per_channel_d(gauss.data(), w, h, 3, sg);
        std::vector<double> tail(static_cast<size_t>(npix) * 3);
        double lt[3] = {tail_px, tail_px, tail_px};
        exponential_filter_per_channel_d(correction.data(), w, h, 3, lt, tail.data());
        const size_t total = static_cast<size_t>(npix) * 3;
        for (size_t i = 0; i < total; ++i)
            correction[i] = (1.0 - tail_w) * gauss[i] + tail_w * tail[i];
    }

    // ---- interpolate_exposure_to_density(log_raw - correction, dc0, le, gamma) ----
    std::vector<double> axis_c[3], curve_c[3];
    for (int c = 0; c < 3; ++c) {
        double g = static_cast<double>(gamma_factor[c]);
        axis_c[c].resize(n);
        curve_c[c].resize(n);
        for (int k = 0; k < n; ++k) {
            axis_c[c][k] = le[k] / g;
            curve_c[c][k] = dc0[k * 3 + c];
        }
    }
    for (int p = 0; p < npix; ++p) {
        for (int c = 0; c < 3; ++c) {
            double log_raw_0 =
                static_cast<double>(log_raw[p * 3 + c]) - correction[p * 3 + c];
            out[p * 3 + c] = static_cast<float>(
                fast_interp_channel(log_raw_0, axis_c[c].data(), curve_c[c].data(), n));
        }
    }
}

}  // namespace spk
