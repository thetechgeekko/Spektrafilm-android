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

#include <cmath>
#include <cstdint>
#include <random>

namespace spk {

// Deterministic per-channel RNG wrapper. We expose uniform [0,1) and standard
// normal draws so grain.cpp can mirror fast_stats' algorithm element by element.
//
// TWO GENERATORS, ONE SET OF DISTRIBUTIONS. `Exact` is the shipped mt19937 and
// is what the Strict Exact route and every committed golden use; it must not
// change. `Fast` is a xoshiro256++ with a hand-written uniform and a cached
// Marsaglia polar normal, selected only on the Fast GPU export route (owner
// decision, issue #180: that route may carry a different noise realisation
// provided the distribution is gated statistically).
//
// Why the generator and not the distribution: libstdc++'s normal_distribution
// already caches its polar pair, so the cost is not the transform. It is that
// every uniform is a mt19937 draw -- large state, tempering, and two 32-bit
// words per double -- and the grain sampler pulls ~190 M normals per 12.5 MP
// export. xoshiro256++ is one 64-bit word of the same statistical quality for
// this use, and needs no tempering.
//
// Determinism: integer state plus IEEE double arithmetic, no library
// distribution objects, so one device reproduces itself exactly. Grain seeds
// per fixed 8192-pixel block, not per worker, so output stays byte-identical
// for any worker count on either generator.
class StatsRng {
   public:
    enum class Generator { Exact, Fast };

    explicit StatsRng(uint64_t seed, Generator generator = Generator::Exact)
        : generator_(generator), engine_(static_cast<uint32_t>(seed)) {
        // SplitMix64 expansion, the reference seeding for xoshiro.
        uint64_t z = seed;
        for (uint64_t& word : s_) {
            z += 0x9E3779B97F4A7C15ull;
            uint64_t x = z;
            x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ull;
            x = (x ^ (x >> 27)) * 0x94D049BB133111EBull;
            word = x ^ (x >> 31);
        }
    }

    // Uniform [0, 1) — analogue of numba's np.random.rand().
    double uniform() {
        if (generator_ == Generator::Exact) return uni_(engine_);
        // 53 significant bits, the same resolution as the exact path.
        return static_cast<double>(next() >> 11) * 0x1.0p-53;
    }

    // Standard normal — analogue of numba's np.random.randn().
    double normal() {
        if (generator_ == Generator::Exact) return nrm_(engine_);
        if (has_spare_) {
            has_spare_ = false;
            return spare_;
        }
        // Marsaglia polar: one log and one sqrt per PAIR of variates.
        double u, v, sq;
        do {
            u = 2.0 * uniform() - 1.0;
            v = 2.0 * uniform() - 1.0;
            sq = u * u + v * v;
        } while (sq >= 1.0 || sq == 0.0);
        const double f = std::sqrt(-2.0 * std::log(sq) / sq);
        spare_ = v * f;
        has_spare_ = true;
        return u * f;
    }

   private:
    static uint64_t rotl(uint64_t x, int k) { return (x << k) | (x >> (64 - k)); }

    uint64_t next() {  // xoshiro256++
        const uint64_t result = rotl(s_[0] + s_[3], 23) + s_[0];
        const uint64_t t = s_[1] << 17;
        s_[2] ^= s_[0];
        s_[3] ^= s_[1];
        s_[1] ^= s_[2];
        s_[0] ^= s_[3];
        s_[2] ^= t;
        s_[3] = rotl(s_[3], 45);
        return result;
    }

    Generator generator_ = Generator::Exact;
    std::mt19937 engine_;
    std::uniform_real_distribution<double> uni_{0.0, 1.0};
    std::normal_distribution<double> nrm_{0.0, 1.0};
    uint64_t s_[4] = {0, 0, 0, 0};
    double spare_ = 0.0;
    bool has_spare_ = false;
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
