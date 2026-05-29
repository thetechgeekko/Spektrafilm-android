/*
 * SpectraFilm for Android — native engine: per-element Poisson + Binomial samplers.
 * Copyright (C) 2026 SpectraFilm Android contributors.
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

}  // namespace spk

#endif  // SPK_KERNELS_STATS_H
