// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// BENCHMARK, NOT A GATE. Deliberately absent from the engine-parity table in
// tools/parity/run_engine_parity.sh and from .github/workflows/ci.yml: it
// reports timings, and timings do not belong in a pass/fail gate.
//
// Why it exists (#180). That issue's scope asks for the sampler profiled
// "across default and adversarial density scenes". The device corpus
// (tools/baseline/corpus.json) varies route and effects but renders one image,
// so it cannot answer the question at all. This sweeps the per-pixel
// Poisson/Binomial walk over uniform density planes and times each.
//
// What it found, on an x86-64 host at the shipping flags, 2048x2048,
// n_particles/px = 500, SPK_NUM_THREADS=1:
//
//     density   p        exact ms   fast ms   speedup
//     0.035     0.016       246.9     222.8     1.11x
//     0.042     0.019       251.3     221.7     1.13x
//     0.048     0.022       124.0      85.6     1.45x
//     0.055     0.025       123.9      82.7     1.50x
//
// Reproduced with the sweep run in both directions, so the step follows the
// density and not the position in the loop.
//
// A STEP, not a slope, and it sits exactly where fast_binomial_one switches
// branch. `var = n*p*(1-p) > 10` takes the normal approximation (one normal
// draw); below it the CDF inversion runs, and that branch evaluates
// `std::pow(1-p, n)` ONCE PER PIXEL. With n ~ 500 the crossover is p = 0.0204.
//
// So the adversarial density regime is the CLEAR end of the film, not the
// dense end, and the cost there is a transcendental call rather than the RNG:
// the cheaper #180 sampler buys 1.11-1.14x below the step against 1.45-1.54x
// above it, because `pow` is generator-independent and dominates.
//
// The dense end escapes for a non-obvious reason worth writing down: as p
// rises, `saturation = 1 - p*uniformity` shrinks, so the Poisson mean
// `n_particles/saturation` grows, so `var` climbs back above 10 from the other
// side. Both extremes of p have small p*(1-p); only the low end also has small
// n.
//
// The threshold is in p, so the DENSITY it corresponds to moves with
// n_particles_per_pixel, which scales with pixel area. Coarser pixels push the
// step to lower densities. Re-run this rather than quoting 0.045 as a constant.
//
// Build (from the cpp root, and note it is not part of any test target):
//   g++ -std=c++17 -O3 -ffast-math -fno-finite-math-only -pthread -I. \
//     tests/bench_grain_density.cpp spektra.cpp gpu/*.cpp kernels/*.cpp \
//     io/*.cpp model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp \
//     -o /tmp/bench_grain_density
//   SPK_NUM_THREADS=1 /tmp/bench_grain_density
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "kernels/stats.h"
#include "model/grain.h"

int main() {
    const int W = 2048, H = 2048;
    const int npix = W * H;
    const double density_max = 2.2;
    const double n_particles = 500.0;
    const double uniformity = 0.97;

    // Brackets the predicted var>10 crossover and both regimes around it.
    const double levels[] = {0.010, 0.022, 0.035, 0.042, 0.048, 0.055,
                             0.070, 0.110, 0.550, 1.100, 1.650, 2.090, 2.190};

    std::vector<float> density(static_cast<size_t>(npix));
    std::vector<float> out(static_cast<size_t>(npix));

    // Warm-up: the first call first-touches `out`, so its page faults would
    // otherwise be charged to whichever density happened to run first.
    for (size_t i = 0; i < density.size(); ++i) density[i] = 1.0f;
    spk::layer_particle_model(density.data(), npix, W, H, density_max,
                              n_particles, uniformity, 1u, out.data(),
                              spk::StatsRng::Generator::Exact);

    std::printf("plane %dx%d = %d px, density_max=%.2f, n_particles/px=%.0f\n\n",
                W, H, npix, density_max, n_particles);
    std::printf("%8s %8s  %10s  %10s  %8s   %12s\n", "density", "p", "exact ms",
                "fast ms", "speedup", "mean out");

    for (double d : levels) {
        for (size_t i = 0; i < density.size(); ++i)
            density[i] = static_cast<float>(d);

        double ms[2] = {0.0, 0.0};
        double mean_out = 0.0;
        for (int fast = 0; fast < 2; ++fast) {
            const auto gen = fast ? spk::StatsRng::Generator::Fast
                                  : spk::StatsRng::Generator::Exact;
            const auto t0 = std::chrono::steady_clock::now();
            spk::layer_particle_model(density.data(), npix, W, H, density_max,
                                      n_particles, uniformity, 12345u,
                                      out.data(), gen);
            const auto t1 = std::chrono::steady_clock::now();
            ms[fast] =
                std::chrono::duration<double, std::milli>(t1 - t0).count();
            if (!fast) {
                double s = 0.0;
                for (float v : out) s += static_cast<double>(v);
                mean_out = s / static_cast<double>(out.size());
            }
        }
        // mean_out tracking `density` is the correctness eye-check: the
        // particle model is mean-preserving at every density it is asked for.
        std::printf("%8.3f %8.3f  %10.1f  %10.1f  %7.2fx   %12.4f\n", d,
                    d / density_max, ms[0], ms[1], ms[0] / ms[1], mean_out);
    }
    return 0;
}
