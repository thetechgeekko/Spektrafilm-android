/*
 * SpectraFilm for Android — native engine: in-emulsion scatter + halation.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements model/diffusion.h (apply_halation_um).
 */
#include "model/diffusion.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "kernels/exponential_filter.h"

namespace spk {

namespace {
inline double max_eps(double v) { return v > 1e-6 ? v : 1e-6; }
}  // namespace

void apply_halation_um(double* raw, int w, int h, const HalationParams& params,
                       double pixel_size_um) {
    if (!params.active) return;
    if (w <= 0 || h <= 0 || pixel_size_um <= 0.0) return;

    const size_t total = static_cast<size_t>(w) * h * 3;

    // Step 1 (highlight boost): boost_ev==0 -> identity. Not applied here.

    // Step 2: in-emulsion scatter.
    //   scattered = (1 - w_s) * G(sigma_c) * raw  +  w_s * Exp(lambda_t) * raw
    //   raw       = (1 - s) * raw  +  s * scattered
    const double s_amount = params.scatter_amount;
    const double s_scale = params.scatter_spatial_scale;
    double sigma_c_px[3], lambda_t_px[3], w_s[3];
    bool any_scatter_sigma = false;
    for (int c = 0; c < 3; ++c) {
        sigma_c_px[c] = params.scatter_core_um[c] * s_scale / pixel_size_um;
        lambda_t_px[c] = params.scatter_tail_um[c] * s_scale / pixel_size_um;
        w_s[c] = params.scatter_tail_weight[c];
        if (sigma_c_px[c] > 0.0 || lambda_t_px[c] > 0.0) any_scatter_sigma = true;
    }
    if (s_amount > 0.0 && any_scatter_sigma) {
        // core = fast_gaussian_filter(raw, max(sigma_c, 1e-6))
        std::vector<double> core(raw, raw + total);
        double sc[3] = {max_eps(sigma_c_px[0]), max_eps(sigma_c_px[1]),
                        max_eps(sigma_c_px[2])};
        gaussian_blur_per_channel_d(core.data(), w, h, 3, sc);
        // tail = fast_exponential_filter(raw, max(lambda_t, 1e-6))
        std::vector<double> tail(total);
        double lt[3] = {max_eps(lambda_t_px[0]), max_eps(lambda_t_px[1]),
                        max_eps(lambda_t_px[2])};
        exponential_filter_per_channel_d(raw, w, h, 3, lt, tail.data());
        // scattered = (1-w_s)*core + w_s*tail ; raw = (1-s)*raw + s*scattered
        const size_t plane = static_cast<size_t>(w) * h;
        for (size_t p = 0; p < plane; ++p) {
            for (int c = 0; c < 3; ++c) {
                size_t idx = p * 3 + c;
                double scattered = (1.0 - w_s[c]) * core[idx] + w_s[c] * tail[idx];
                raw[idx] = (1.0 - s_amount) * raw[idx] + s_amount * scattered;
            }
        }
    }

    // Step 3: back-reflection halation.
    //   a_tot = halation_strength * halation_amount
    //   decay_k = rho^(k-1) / sum_j rho^(j-1)   (k = 1..N)
    //   halation_blur = sum_k decay_k * G(sigma_h * sqrt(k)) * raw
    //   raw = raw + a_tot * halation_blur ; if renormalize: raw /= (1 + a_tot)
    const double h_amount = params.halation_amount;
    const double h_scale = params.halation_spatial_scale;
    double a_tot[3], sigma_h_px[3];
    bool any_a = false, any_sigma_h = false;
    for (int c = 0; c < 3; ++c) {
        a_tot[c] = params.halation_strength[c] * h_amount;
        sigma_h_px[c] = params.halation_first_sigma_um[c] * h_scale / pixel_size_um;
        if (a_tot[c] > 0.0) any_a = true;
        if (sigma_h_px[c] > 0.0) any_sigma_h = true;
    }
    const int N = params.halation_n_bounces;
    const double rho = params.halation_bounce_decay;
    if (N >= 1 && any_a && any_sigma_h) {
        std::vector<double> decay(N);
        double dsum = 0.0;
        for (int k = 1; k <= N; ++k) {
            decay[k - 1] = std::pow(rho, static_cast<double>(k - 1));
            dsum += decay[k - 1];
        }
        for (int k = 0; k < N; ++k) decay[k] /= dsum;

        std::vector<double> halation_blur(total, 0.0);
        std::vector<double> comp(total);
        for (int k = 1; k <= N; ++k) {
            double sk[3];
            for (int c = 0; c < 3; ++c)
                sk[c] = max_eps(sigma_h_px[c] * std::sqrt(static_cast<double>(k)));
            for (size_t i = 0; i < total; ++i) comp[i] = raw[i];
            gaussian_blur_per_channel_d(comp.data(), w, h, 3, sk);
            double wk = decay[k - 1];
            for (size_t i = 0; i < total; ++i) halation_blur[i] += wk * comp[i];
        }
        const size_t plane = static_cast<size_t>(w) * h;
        for (size_t p = 0; p < plane; ++p) {
            for (int c = 0; c < 3; ++c) {
                size_t idx = p * 3 + c;
                double v = raw[idx] + a_tot[c] * halation_blur[idx];
                if (params.halation_renormalize) v = v / (1.0 + a_tot[c]);
                raw[idx] = v;
            }
        }
    }
}

}  // namespace spk
