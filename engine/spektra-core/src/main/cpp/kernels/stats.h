/*
 * Spektrafilm for Android — native engine: per-element Poisson + Binomial samplers.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports the per-element samplers of spektrafilm/utils/fast_stats.py
 * (fast_poisson, fast_binomial) used by the AgX particle grain model.
 *
 * RNG choice: exact stream parity with numpy's PCG64 / Numba is NOT a goal (the
 * grain milestone is verified statistically). We use C++ <random> std::mt19937
 * seeded deterministically per channel, and reproduce the SAME branch structure
 * as fast_stats so the produced *distributions* match:
 *   - Poisson: lambda < 30 -> Knuth product algorithm; else Normal approximation
 *              N(lambda, sqrt(lambda)), rounded, clamped at >= 0.
 *   - Binomial: n < 25 -> direct Bernoulli trials; else if n*p*(1-p) > 10 ->
 *               Normal approximation N(np, sqrt(npq)), rounded, clamped to [0,n];
 *               else inversion by CDF accumulation.
 * The thresholds (lam_threshold=30, n_threshold=25, var>10) match fast_stats.py
 * exactly so the same regime is selected for the same parameters.
 */
#ifndef SPK_KERNELS_STATS_H
#define SPK_KERNELS_STATS_H

#include <cstdint>
#include <random>

namespace spk {

// Deterministic per-channel RNG wrapper. We expose uniform [0,1) and standard
// normal draws so grain.cpp can mirror fast_stats' algorithm element by element.
class StatsRng {
   public:
    explicit StatsRng(uint64_t seed) : engine_(static_cast<uint32_t>(seed)) {}

    // Uniform [0, 1) — analogue of numba's np.random.rand().
    double uniform() { return uni_(engine_); }
    // Standard normal — analogue of numba's np.random.randn().
    double normal() { return nrm_(engine_); }

   private:
    std::mt19937 engine_;
    std::uniform_real_distribution<double> uni_{0.0, 1.0};
    std::normal_distribution<double> nrm_{0.0, 1.0};
};

// Single Poisson variate with rate lam, matching fast_poisson's branch structure.
int64_t fast_poisson_one(double lam, StatsRng& rng);

// Single Binomial(n, p) variate, matching fast_binomial's branch structure.
int64_t fast_binomial_one(int64_t n, double p, StatsRng& rng);

// Single lognormal variate from underlying-normal (mu, sigma): exp(mu+sigma*z).
// Mirrors fast_stats.py::fast_lognormal: if sigma < 1e-6, returns exp(mu)
// (no normal draw is consumed in that branch, matching the Numba code path).
double fast_lognormal_one(double mu, double sigma, StatsRng& rng);

// Single lognormal variate parameterised by the LINEAR-space mean/std (m, s),
// mirroring fast_stats.py::fast_lognormal_from_mean_std:
//   if m <= 0: mu = 0, sigma = 0  -> exp(0) = 1
//   else: sigma^2 = ln(1 + s^2/m^2); sigma = sqrt(sigma^2); mu = ln(m) - sigma^2/2
// then draws fast_lognormal_one(mu, sigma). The sigma<1e-6 short-circuit inside
// fast_lognormal_one matches the Numba two-stage structure.
double fast_lognormal_from_mean_std_one(double m, double s, StatsRng& rng);

}  // namespace spk

#endif  // SPK_KERNELS_STATS_H
