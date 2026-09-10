// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Statistical gate for StatsRng::normal() itself.
//
// The existing stochastic gates (test_grain, test_grain_sublayer,
// test_glare_sampler) check the DISTRIBUTIONS the engine builds on top of the
// normal — particle counts, a lognormal field. None of them looks at the normal
// generator directly, so when the Fast path's normal was swapped from a
// Marsaglia polar to a Marsaglia-Tsang ziggurat there was nothing gating the
// thing that actually changed.
//
// That swap is exactly the kind of change that fails quietly. A ziggurat whose
// tables are normalised against the wrong power of two produces a clean,
// symmetric, perfectly plausible bell curve with the WRONG standard deviation —
// it read sd 0.50 on the first attempt here, and every downstream mean check
// still passed. So this file gates the moments AND the shape.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "kernels/stats.h"

namespace {

int failures = 0;

void check(bool ok, const std::string& what) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) ++failures;
}

// Normal CDF via erfc, for the shape comparison.
double phi(double x) { return 0.5 * std::erfc(-x * M_SQRT1_2); }

struct Summary {
    double mean, sd, skew, kurt;
    std::vector<double> counts;   // per histogram bin
};

Summary summarise(spk::StatsRng::Generator generator, uint64_t seed, long n,
                  const std::vector<double>& edges) {
    spk::StatsRng rng(seed, generator);
    Summary s{};
    s.counts.assign(edges.size() + 1, 0.0);
    double m1 = 0, m2 = 0, m3 = 0, m4 = 0;
    for (long i = 0; i < n; ++i) {
        const double v = rng.normal();
        m1 += v;
        m2 += v * v;
        m3 += v * v * v;
        m4 += v * v * v * v;
        size_t bin = 0;
        while (bin < edges.size() && v >= edges[bin]) ++bin;
        s.counts[bin] += 1.0;
    }
    const double N = static_cast<double>(n);
    s.mean = m1 / N;
    const double var = m2 / N - s.mean * s.mean;
    s.sd = std::sqrt(var);
    s.skew = (m3 / N - 3.0 * s.mean * var - s.mean * s.mean * s.mean) /
             (var * s.sd);
    s.kurt = (m4 / N - 4.0 * s.mean * (m3 / N) + 6.0 * s.mean * s.mean * (m2 / N) -
              3.0 * s.mean * s.mean * s.mean * s.mean) /
             (var * var);
    return s;
}

}  // namespace

int main() {
    const long N = 4000000;   // 4 M draws: se(mean) = 5e-4, se(sd) = 3.5e-4
    // Bin edges spanning +/-4 sigma, deliberately including the tails the
    // ziggurat handles by a separate code path from the body.
    const std::vector<double> edges = {-4.0, -3.0, -2.0, -1.5, -1.0, -0.5, 0.0,
                                       0.5,  1.0,  1.5,  2.0,  3.0,  4.0};

    for (int fast = 0; fast < 2; ++fast) {
        const auto gen = fast ? spk::StatsRng::Generator::Fast
                              : spk::StatsRng::Generator::Exact;
        const char* name = fast ? "fast" : "exact";
        const Summary s = summarise(gen, 20260909u, N, edges);

        std::printf("%-5s mean %+.5f  sd %.5f  skew %+.4f  kurt %.4f\n", name,
                    s.mean, s.sd, s.skew, s.kurt);

        // 4 M draws: the sample mean's standard error is 1/sqrt(N) = 5.0e-4, so
        // 4e-3 is 8 sigma. Loose enough never to flake, tight enough that the
        // 0.50-sd bug would miss by a factor of 1000.
        check(std::fabs(s.mean) < 4e-3,
              std::string(name) + " normal has zero mean");
        check(std::fabs(s.sd - 1.0) < 4e-3,
              std::string(name) + " normal has unit standard deviation");
        check(std::fabs(s.skew) < 0.02,
              std::string(name) + " normal is symmetric (skew ~ 0)");
        // Excess kurtosis 0 => kurt 3. A normal mixture or a truncated tail
        // shows up here even when mean and sd are perfect.
        check(std::fabs(s.kurt - 3.0) < 0.05,
              std::string(name) + " normal has Gaussian kurtosis (~3)");

        // Shape: every bin's frequency against the exact normal CDF. This is
        // what catches a generator that is right on average and wrong in
        // profile, and it covers the ziggurat's separate tail path.
        double worst = 0.0;
        int worst_bin = -1;
        for (size_t b = 0; b < s.counts.size(); ++b) {
            const double lo = (b == 0) ? -INFINITY : edges[b - 1];
            const double hi = (b == edges.size()) ? INFINITY : edges[b];
            const double expected =
                (std::isinf(hi) ? 1.0 : phi(hi)) - (std::isinf(lo) ? 0.0 : phi(lo));
            const double observed = s.counts[b] / static_cast<double>(N);
            const double diff = std::fabs(observed - expected);
            if (diff > worst) {
                worst = diff;
                worst_bin = static_cast<int>(b);
            }
        }
        std::printf("%-5s worst bin deviation %.5f (bin %d)\n", name, worst,
                    worst_bin);
        // Largest bin holds ~0.19 of the mass; its binomial se at 4 M draws is
        // about 2.0e-4, so 3e-3 is ~15 sigma on the worst case.
        check(worst < 3e-3,
              std::string(name) + " normal matches the normal CDF bin by bin");

        // The tails must actually be reachable. A ziggurat with a broken tail
        // branch truncates silently, and truncation barely moves the sd.
        check(s.counts[0] > 0.0 && s.counts[s.counts.size() - 1] > 0.0,
              std::string(name) + " normal reaches beyond +/-4 sigma");
    }

    // Same seed, same generator, same sequence: the Fast GPU route has to be
    // same-device deterministic, and the ziggurat consumes a VARIABLE number of
    // raw words per draw, so this is not automatic.
    {
        spk::StatsRng a(12345u, spk::StatsRng::Generator::Fast);
        spk::StatsRng b(12345u, spk::StatsRng::Generator::Fast);
        bool same = true;
        for (int i = 0; i < 100000; ++i) same = same && (a.normal() == b.normal());
        check(same, "fast normal is reproducible from the same seed");

        spk::StatsRng c(12345u, spk::StatsRng::Generator::Fast);
        spk::StatsRng d(99999u, spk::StatsRng::Generator::Fast);
        bool differs = false;
        for (int i = 0; i < 1000; ++i) differs = differs || (c.normal() != d.normal());
        check(differs, "fast normal depends on the seed");
    }

    std::printf(failures == 0 ? "test_normal_generator: ALL OK\n"
                              : "test_normal_generator: FAILURES\n");
    return failures == 0 ? 0 : 1;
}
