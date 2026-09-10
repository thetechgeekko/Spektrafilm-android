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
// change. `Fast` is a xoshiro256++ with a hand-written uniform and a
// Marsaglia-Tsang ziggurat normal, selected only on the Fast GPU export route
// (owner decision, issue #180: that route may carry a different noise
// realisation provided the distribution is gated statistically).
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
    //
    // Fast path is a Marsaglia-Tsang ziggurat. It replaced a Marsaglia polar,
    // which cost a rejection loop over two uniforms plus a log, a sqrt and a
    // divide per PAIR. The ziggurat returns on a table lookup and one multiply
    // for 98.8% of draws and touches no transcendental at all on that path.
    // Measured 4.50 ns -> 2.00 ns per draw, 2.25x, at the shipping flags.
    //
    // The grain sampler draws two normals per pixel per sublayer, which is the
    // largest single item in a 12.5 MP export, so this is the hot path and not
    // a micro-optimisation.
    double normal() {
        if (generator_ == Generator::Exact) return nrm_(engine_);
        const ZigguratTables& z = ziggurat_tables();
        for (;;) {
            const uint64_t bits = next();
            const int i = static_cast<int>(bits & (kZigLevels - 1));
            // Signed 53-bit magnitude: [-2^52, 2^52), which is what the tables
            // are normalised against. Getting that scale wrong is silent -- it
            // yields a clean-looking normal with the wrong standard deviation.
            const int64_t hz = static_cast<int64_t>(bits >> 11) - (1LL << 52);
            const double x = static_cast<double>(hz) * z.w[i];
            const uint64_t mag = static_cast<uint64_t>(hz < 0 ? -hz : hz);
            if (mag < z.k[i]) return x;          // the common case
            if (i == 0) {                        // the tail, below ~1 in 10^4
                double xx, yy;
                do {
                    xx = -std::log(1.0 - uniform()) / kZigR;
                    yy = -std::log(1.0 - uniform());
                } while (yy + yy < xx * xx);
                return hz < 0 ? -(kZigR + xx) : (kZigR + xx);
            }
            if (z.f[i] + uniform() * (z.f[i - 1] - z.f[i]) <
                std::exp(-0.5 * x * x))
                return x;
        }
    }

   private:
    static uint64_t rotl(uint64_t x, int k) { return (x << k) | (x >> (64 - k)); }

    // 256-level ziggurat over the standard normal's right half. kZigR is where
    // the tail begins and kZigArea is the common area of each layer; both are
    // the standard Marsaglia-Tsang constants for 256 levels.
    static constexpr int kZigLevels = 256;
    static constexpr double kZigR = 3.6541528853610088;
    static constexpr double kZigArea = 0.00492867323399;
    static constexpr double kZigScale = 4503599627370496.0;  // 2^52, the span of hz

    struct ZigguratTables {
        double w[kZigLevels];
        double f[kZigLevels];
        uint64_t k[kZigLevels];
    };

    // Built once, on first use. A function-local static is initialised exactly
    // once even under concurrent first calls, which matters because the grain
    // stage enters this from every worker at the same moment.
    static const ZigguratTables& ziggurat_tables() {
        static const ZigguratTables tables = [] {
            ZigguratTables t{};
            const double f0 = std::exp(-0.5 * kZigR * kZigR);
            t.k[0] = static_cast<uint64_t>((kZigR * f0 / kZigArea) * kZigScale);
            t.w[0] = kZigArea / f0 / kZigScale;
            t.f[0] = 1.0;
            t.k[kZigLevels - 1] = 0;
            t.w[kZigLevels - 1] = kZigR / kZigScale;
            t.f[kZigLevels - 1] = f0;
            double xp = kZigR;
            for (int i = kZigLevels - 2; i >= 1; --i) {
                const double xn = std::sqrt(
                    -2.0 * std::log(kZigArea / xp + std::exp(-0.5 * xp * xp)));
                t.k[i + 1] = static_cast<uint64_t>((xn / xp) * kZigScale);
                t.w[i] = xn / kZigScale;
                t.f[i] = std::exp(-0.5 * xn * xn);
                xp = xn;
            }
            return t;
        }();
        return tables;
    }

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
