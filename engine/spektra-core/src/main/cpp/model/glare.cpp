/*
 * SpectraFilm for Android — native engine: lognormal viewing glare.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/glare.py +
 * spektrafilm/utils/fast_stats.py::fast_lognormal[_from_mean_std].
 */
#include "glare.h"

#include <cmath>
#include <random>
#include <vector>

#include "../kernels/gaussian.h"

// NaN / Inf behavior — VERIFIED against the oracle (spektrafilm/model/glare.py
// + spektrafilm/utils/fast_stats.py). add_glare / compute_random_glare_amount do
// NO NaN/Inf sanitization: there is no np.nan_to_num / np.isnan / np.isfinite on
// this path. The glare amount is a lognormal random field (always finite for
// finite mu/sigma) blurred and scaled by 1/100, then ADDED to the XYZ image, so
// numpy would simply propagate any pre-existing NaN/Inf in the input. This port
// matches that behavior DELIBERATELY to stay bit-exact with the oracle; the
// engine's other isnan guards each mirror a specific oracle np.nan_to_num/isnan
// call, and the glare path has none, so adding one here would DIVERGE.
namespace spk {

namespace {

// fast_stats.py::fast_lognormal element: exp(mu + sigma*z), with the
// sigma < 1e-6 short-circuit returning exp(mu).
inline float lognormal_sample(double mu, double sigma, double z) {
    const double sigma_threshold = 1e-6;
    if (sigma < sigma_threshold) {
        return static_cast<float>(std::exp(mu));
    }
    return static_cast<float>(std::exp(mu + sigma * z));
}

}  // namespace

void compute_random_glare_amount(float amount, float roughness, float blur,
                                 int w, int h, uint64_t seed, float* out) {
    const size_t n = static_cast<size_t>(w) * h;
    // fast_lognormal_from_mean_std: mean m = amount, std s = roughness*amount,
    // uniform over the plane (amount*ones / (roughness*amount)*ones).
    //   sigma2 = ln(1 + (s*s)/(m*m)); sigma = sqrt(sigma2); mu = ln(m) - sigma2/2.
    // With a constant m,s these are the same for every pixel.
    const double m = static_cast<double>(amount);
    const double s = static_cast<double>(roughness) * static_cast<double>(amount);
    double mu, sigma;
    if (m <= 0.0) {
        mu = 0.0;
        sigma = 0.0;
    } else {
        double sigma2 = std::log(1.0 + (s * s) / (m * m));
        sigma = std::sqrt(sigma2);
        mu = std::log(m) - sigma2 / 2.0;
    }

    // Deterministic standard-normal stream (numpy uses np.random.randn per pixel;
    // here we use a seedable mt19937 + normal_distribution for reproducibility).
    std::mt19937 rng(static_cast<uint32_t>(seed ^ (seed >> 32)));
    std::normal_distribution<double> randn(0.0, 1.0);
    for (size_t i = 0; i < n; ++i) {
        double z = randn(rng);
        out[i] = lognormal_sample(mu, sigma, z);
    }

    // fast_gaussian_filter(random, blur) — single-plane blur, sigma = blur px.
    gaussian_blur_plane(out, w, h, blur);

    // random /= 100
    for (size_t i = 0; i < n; ++i) out[i] *= 0.01f;
}

void add_glare(float* xyz, int w, int h, const float illuminant_xyz[3],
               float percent, float roughness, float blur, uint64_t seed) {
    // glare.py::add_glare gate: active and percent > 0.
    if (!(percent > 0.0f)) return;
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> glare(n);
    compute_random_glare_amount(percent, roughness, blur, w, h, seed,
                                glare.data());
    // xyz += glare[:,:,None] * illuminant_xyz[None,None,:]
    for (size_t i = 0; i < n; ++i) {
        float g = glare[i];
        float* px = xyz + i * 3;
        px[0] += g * illuminant_xyz[0];
        px[1] += g * illuminant_xyz[1];
        px[2] += g * illuminant_xyz[2];
    }
}

}  // namespace spk
