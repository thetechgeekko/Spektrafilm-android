// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Build and run (host, no device and no GPU needed):
//   g++ -std=c++17 -O2 tools/gpu_probe/census_grain_branches.cpp -o /tmp/census && /tmp/census
//
// Branch census for the AgX particle sampler (#214), host-only.
//
// Before writing a GLSL sampler I need to know WHICH branch of fast_poisson /
// fast_binomial the real workload lands in, because they have wildly different
// costs and wildly different f32 hazards:
//
//   poisson  lam < 30            -> Knuth loop, exp(-lam) >= exp(-30) = 9e-14, f32-SAFE
//   poisson  lam >= 30           -> Normal approx, f32-safe, O(1)
//   binomial n < 25              -> up to 25 Bernoulli trials, f32-safe
//   binomial var > 10            -> Normal approx, f32-safe, O(1)
//   binomial else                -> CDF inversion: pow(1-p, n). THE ONLY f32 HAZARD,
//                                   and the branch the short-circuit was built for.
//
// No RNG is needed to answer that: branch membership is a deterministic function
// of (density, density_max, n_ppp, uniformity). seeds is random, but it is
// Poisson(lam) with lam >= 26 at export geometry, so its branch is decided by lam
// to many sigma; the census uses the mean and reports how close the boundary is.
#include <cmath>
#include <cstdio>
#include <vector>

namespace {

struct Census {
    double knuth = 0, pois_normal = 0;
    double bern = 0, bin_normal = 0, cdf = 0, degenerate = 0, p_hi = 0;
    double n_max = 0, lam_min = 1e300, lam_max = 0;
};

// One (sublayer, channel) cell of the sublayer path, per grain.py.
void tally(Census& c, double d, double dmax, double n_ppp, double uni, double w) {
    double p = d / dmax;
    if (p < 1e-6) p = 1e-6;
    if (p > 1.0 - 1e-6) p = 1.0 - 1e-6;
    const double saturation = 1.0 - p * uni * (1.0 - 1e-6);
    const double lam = n_ppp / saturation;
    if (lam < c.lam_min) c.lam_min = lam;
    if (lam > c.lam_max) c.lam_max = lam;

    if (lam < 30.0) c.knuth += w; else c.pois_normal += w;

    const double n = lam;              // E[seeds]; see header note
    if (n > c.n_max) c.n_max = n;
    if (p >= 1.0) { c.p_hi += w; return; }
    if (n < 25.0) { c.bern += w; return; }
    const double var = n * p * (1.0 - p);
    if (var > 10.0) { c.bin_normal += w; return; }
    c.cdf += w;
    // f64 underflow of pow(1-p, n) -> the existing short-circuit fires.
    if (n * std::log1p(-p) < std::log(2.2250738585072014e-308)) c.degenerate += w;
}

// Where in the density range does the expensive branch live? Uniform weighting
// above answers "over all densities"; a real image concentrates its pixels, so
// the INTERVAL matters more than the percentage.
//
// The CDF branch is entered when var = n*p*(1-p) <= 10, and that has TWO roots:
// p near 0 (shadows) and p near 1 (highlights). They are disjoint, and reporting
// their hull would claim the branch covers everything in between, which is false.
// So both bands are found and printed separately -- the low one is the one that
// matters, because shadow grain is what a viewer actually looks at.
void print_bands(double dmin, double dmax, double n_ppp, double uni) {
    const int kSteps = 200001;
    bool in = false;
    double lo = 0.0, prev = 0.0;
    int bands = 0;
    for (int s = 0; s < kSteps; ++s) {
        const double d = dmin + (dmax - dmin) * s / (kSteps - 1);
        double pp = d / dmax;
        if (pp < 1e-6) pp = 1e-6;
        if (pp > 1.0 - 1e-6) pp = 1.0 - 1e-6;
        const double n = n_ppp / (1.0 - pp * uni * (1.0 - 1e-6));
        const bool cdf = (n >= 25.0) && (n * pp * (1.0 - pp) <= 10.0);
        if (cdf && !in) { in = true; lo = pp; }
        if (!cdf && in) {
            in = false; ++bands;
            std::printf("    CDF band %d: p in [%.6f, %.6f]  density [%.4f, %.4f]\n",
                        bands, lo, prev, lo * dmax, prev * dmax);
        }
        prev = pp;
    }
    if (in) {
        ++bands;
        std::printf("    CDF band %d: p in [%.6f, %.6f]  density [%.4f, %.4f]\n",
                    bands, lo, prev, lo * dmax, prev * dmax);
    }
    if (bands == 0) std::printf("    CDF bands: none\n");
}

void report(const char* what, const Census& c, double total) {
    auto pc = [&](double v) { return 100.0 * v / total; };
    std::printf("\n== %s ==\n", what);
    std::printf("  lam range [%.2f, %.3g]   max n (E[seeds]) %.3g\n",
                c.lam_min, c.lam_max, c.n_max);
    std::printf("  poisson : knuth   %6.2f%%   normal %6.2f%%\n", pc(c.knuth), pc(c.pois_normal));
    std::printf("  binomial: p>=1    %6.2f%%\n", pc(c.p_hi));
    std::printf("            n<25    %6.2f%%  (Bernoulli loop)\n", pc(c.bern));
    std::printf("            normal  %6.2f%%\n", pc(c.bin_normal));
    std::printf("            CDF     %6.2f%%  of which degenerate (f64) %6.2f%%\n",
                pc(c.cdf), c.cdf > 0 ? 100.0 * c.degenerate / c.cdf : 0.0);
    std::printf("  --> f32-SAFE branches cover %.2f%% of samples\n",
                pc(c.p_hi + c.bern + c.bin_normal));
}

// Sublayer-path constants (grain.h defaults).
const double kScaleC[3] = {0.8, 1.0, 2.0};
const double kScaleL[3] = {2.5, 1.0, 0.5};
const double kUni[3] = {0.97, 0.97, 0.99};
const double kDensityMin[3] = {0.07, 0.08, 0.12};
const double kParticleAreaUm2 = 0.2;

void run(const char* label, double pixel_size_um, double dmax_curve) {
    const double pixel_area = pixel_size_um * pixel_size_um;
    Census c;
    double total = 0.0;

    // Sweep density uniformly over its range. Uniform weighting is deliberate:
    // it is the honest "no assumption about the image" prior, and any real
    // histogram is a reweighting of these same cells.
    const int kSteps = 2001;
    for (int sl = 0; sl < 3; ++sl) {
        for (int ch = 0; ch < 3; ++ch) {
            // density_max_fractions ~ equal thirds (the profile's per-sublayer
            // maxima are close); density_min is split by the same fraction.
            const double frac = 1.0 / 3.0;
            const double dmin_l = frac * kDensityMin[ch];
            const double dmax_l = frac * dmax_curve + dmin_l;
            const double particle_area = kParticleAreaUm2 * kScaleC[ch] * kScaleL[sl];
            const double n_ppp = pixel_area * frac / particle_area;
            for (int s = 0; s < kSteps; ++s) {
                const double d = dmin_l + (dmax_l - dmin_l) * s / (kSteps - 1);
                tally(c, d, dmax_l, n_ppp, kUni[ch], 1.0);
                total += 1.0;
            }
        }
    }
    report(label, c, total);
    // One representative cell (middle sublayer, green).
    {
        const double frac = 1.0 / 3.0;
        const double dmin_l = frac * kDensityMin[1];
        const double dmax_l = frac * dmax_curve + dmin_l;
        const double n_ppp = pixel_area * frac / (kParticleAreaUm2 * kScaleC[1] * kScaleL[1]);
        std::printf("  representative cell sl=1 green, n_ppp %.1f, density [%.4f, %.4f]\n",
                    n_ppp, dmin_l, dmax_l);
        print_bands(dmin_l, dmax_l, n_ppp, kUni[1]);
    }
}

}  // namespace

int main() {
    std::printf("AgX particle sampler: which branch does the work land in? (#214)\n");
    std::printf("density_max_curves 2.2 (profile default)\n");
    // 36 mm across the long edge, as the engine's pixel_size_um is derived.
    run("12.5 MP export   (4080 px, 8.82 um/px)", 36000.0 / 4080.0, 2.2);
    run("1080p video      (1920 px, 18.75 um/px)", 36000.0 / 1920.0, 2.2);
    run("640 px preview   (640 px, 56.25 um/px)", 36000.0 / 640.0, 2.2);
    run("small-pixel edge (4 um/px)", 4.0, 2.2);
    return 0;
}
