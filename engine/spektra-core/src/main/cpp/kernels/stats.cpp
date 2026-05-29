/*
 * SpectraFilm for Android — native engine: Poisson + Binomial samplers.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements kernels/stats.h, mirroring fast_stats.py's
 * fast_poisson / fast_binomial per-element branch structure.
 */
#include "kernels/stats.h"

#include <cmath>

namespace spk {

int64_t fast_poisson_one(double lam, StatsRng& rng) {
    // fast_poisson: lam <= 0 -> 0; lam < 30 -> Knuth; else Normal approx.
    const double lam_threshold = 30.0;
    if (lam <= 0.0) return 0;
    if (lam < lam_threshold) {
        // Knuth: multiply uniforms until the product drops below exp(-lam).
        double L = std::exp(-lam);
        double p = 1.0;
        int64_t k = 0;
        while (p > L) {
            k += 1;
            p *= rng.uniform();
        }
        return k - 1;
    }
    // Normal approximation N(lam, sqrt(lam)), rounded, clamped >= 0.
    double z = rng.normal();
    double sample = lam + std::sqrt(lam) * z;
    int64_t s = static_cast<int64_t>(std::llround(sample));
    if (s < 0) s = 0;
    return s;
}

int64_t fast_binomial_one(int64_t n, double p, StatsRng& rng) {
    // fast_binomial: p<=0 -> 0; p>=1 -> n; n<25 -> Bernoulli trials; else
    // var>10 -> Normal approx; else CDF inversion.
    if (p <= 0.0) return 0;
    if (p >= 1.0) return n;
    const int64_t n_threshold = 25;
    if (n < n_threshold) {
        int64_t count = 0;
        for (int64_t k = 0; k < n; ++k)
            if (rng.uniform() < p) count += 1;
        return count;
    }
    double mean = static_cast<double>(n) * p;
    double var = static_cast<double>(n) * p * (1.0 - p);
    if (var > 10.0) {
        double z = rng.normal();
        double approx = mean + std::sqrt(var) * z;
        int64_t a = static_cast<int64_t>(std::llround(approx));
        if (a < 0) a = 0;
        if (a > n) a = n;
        return a;
    }
    // CDF inversion (matches the Numba while-loop exactly).
    double u = rng.uniform();
    double cdf = 0.0;
    double prob = std::pow(1.0 - p, static_cast<double>(n));
    int64_t k = 0;
    while (cdf < u && k <= n) {
        cdf += prob;
        if (k < n)
            prob = prob * (static_cast<double>(n - k) / (k + 1)) * (p / (1.0 - p));
        k += 1;
    }
    return k - 1;
}

}  // namespace spk
