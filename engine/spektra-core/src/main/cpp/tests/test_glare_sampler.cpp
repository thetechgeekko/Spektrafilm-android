// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Statistical gate for the viewing-glare field's two generators.
//
// Glare is stochastic, so byte goldens cannot cover it (the same reason
// test_grain exists). The owner decision on issue #180 lets the Fast GPU export
// route draw this field from a cheaper generator than the Strict Exact route,
// PROVIDED the distribution is gated — which is what this file is.
//
// compute_random_glare_amount draws one lognormal variate per pixel with mean
// `amount` and standard deviation `roughness * amount`, optionally blurs the
// field, then scales by 1/100. With blur = 0 the field's moments are therefore
// known in closed form, so each generator is checked against the MODEL, not
// merely against the other one — otherwise two identically-wrong generators
// would agree and pass.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "kernels/stats.h"
#include "model/glare.h"

namespace {

int failures = 0;

void check(bool ok, const std::string& what) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) ++failures;
}

void moments(const std::vector<float>& v, double* mean, double* stddev) {
    double s = 0.0;
    for (float x : v) s += static_cast<double>(x);
    const double m = s / static_cast<double>(v.size());
    double ss = 0.0;
    for (float x : v) {
        const double d = static_cast<double>(x) - m;
        ss += d * d;
    }
    *mean = m;
    *stddev = std::sqrt(ss / static_cast<double>(v.size()));
}

// Normalised autocorrelation of the field at a given sample lag. A lag of 1 is
// the horizontal neighbour; a lag of w is the vertical one. For an independent
// draw per pixel this is zero up to sampling error, and the standard error of
// r on n samples is about 1/sqrt(n).
double autocorr(const std::vector<float>& v, size_t lag) {
    if (v.size() <= lag) return 0.0;
    double m = 0.0;
    for (float x : v) m += static_cast<double>(x);
    m /= static_cast<double>(v.size());
    double num = 0.0, den = 0.0;
    for (size_t i = 0; i < v.size(); ++i) {
        const double d = static_cast<double>(v[i]) - m;
        den += d * d;
        if (i + lag < v.size())
            num += d * (static_cast<double>(v[i + lag]) - m);
    }
    return den > 0.0 ? num / den : 0.0;
}

// One field, one generator. Returns the measured mean and relative std of the
// field in its pre-scaling units (the function scales by 1/100 at the end).
void field_stats(float amount, float roughness, float blur, int w, int h,
                 uint64_t seed, spk::StatsRng::Generator generator,
                 double* mean, double* rel_std, std::vector<float>* out) {
    out->assign(static_cast<size_t>(w) * h, 0.0f);
    spk::compute_random_glare_amount(amount, roughness, blur, w, h, seed,
                                     out->data(), generator);
    double m, sd;
    moments(*out, &m, &sd);
    *mean = m * 100.0;                       // undo the /100 the function applies
    *rel_std = (m > 0.0) ? sd / m : 0.0;     // scale-free, so the /100 cancels
}

}  // namespace

int main() {
    const int w = 320, h = 240;
    const uint64_t seed = 20260909u;

    // Unblurred: the per-pixel draws are independent, so the sample moments
    // must match the requested lognormal mean and relative std directly.
    struct Case {
        float amount, roughness;
    } cases[] = {{2.0f, 0.25f}, {5.0f, 0.5f}, {0.5f, 0.1f}, {10.0f, 0.8f}};

    for (const Case& c : cases) {
        for (int fast = 0; fast < 2; ++fast) {
            const auto gen = fast ? spk::StatsRng::Generator::Fast
                                  : spk::StatsRng::Generator::Exact;
            std::vector<float> field;
            double mean = 0.0, rel = 0.0;
            field_stats(c.amount, c.roughness, 0.0f, w, h, seed, gen, &mean, &rel, &field);
            // 76 800 samples: the sample mean of a lognormal with these shapes
            // is well inside 2 % and the relative std inside 5 %.
            const double mean_err = std::fabs(mean / c.amount - 1.0);
            const double rel_err = std::fabs(rel / c.roughness - 1.0);
            std::printf("%s amount=%.1f roughness=%.2f -> mean=%.4f (err %.2f%%) rel_std=%.4f (err %.1f%%)\n",
                        fast ? "fast " : "exact", c.amount, c.roughness, mean,
                        mean_err * 100.0, rel, rel_err * 100.0);
            check(mean_err < 0.02,
                  std::string(fast ? "fast" : "exact") + " sampler preserves the glare mean");
            check(rel_err < 0.05,
                  std::string(fast ? "fast" : "exact") + " sampler preserves the glare relative std");
            bool finite = true;
            for (float x : field) finite = finite && std::isfinite(x) && x >= 0.0f;
            check(finite, std::string(fast ? "fast" : "exact") + " field is finite and non-negative");
        }
    }

    // The two generators must agree in DISTRIBUTION while differing in
    // realisation, and the fast one must reproduce itself: without the second
    // check a no-op flag would pass everything above, and without the third the
    // Fast GPU route would not be same-device deterministic.
    {
        std::vector<float> a, b, b2;
        double ma, ra, mb, rb;
        field_stats(4.0f, 0.4f, 0.0f, w, h, seed, spk::StatsRng::Generator::Exact, &ma, &ra, &a);
        field_stats(4.0f, 0.4f, 0.0f, w, h, seed, spk::StatsRng::Generator::Fast, &mb, &rb, &b);
        field_stats(4.0f, 0.4f, 0.0f, w, h, seed, spk::StatsRng::Generator::Fast, &mb, &rb, &b2);
        const double dmean = std::fabs(mb / ma - 1.0);
        const double dstd = std::fabs(rb / ra - 1.0);
        std::printf("exact vs fast: mean ratio err %.2f%%, rel_std ratio err %.2f%%\n",
                    dmean * 100.0, dstd * 100.0);
        check(dmean < 0.02, "the two generators agree on the glare mean");
        check(dstd < 0.05, "the two generators agree on the glare relative std");
        check(a != b, "the fast generator is a different realisation, not a no-op flag");
        check(b == b2, "the fast generator reproduces itself exactly");
    }

    // A blurred field keeps its mean (the kernel is normalised) while its
    // spatial std falls; this catches a generator wired in after the blur.
    {
        std::vector<float> sharp, soft;
        double m0, r0, m1, r1;
        field_stats(3.0f, 0.5f, 0.0f, w, h, seed, spk::StatsRng::Generator::Fast, &m0, &r0, &sharp);
        field_stats(3.0f, 0.5f, 2.0f, w, h, seed, spk::StatsRng::Generator::Fast, &m1, &r1, &soft);
        std::printf("blur 0 -> rel_std %.4f ; blur 2 px -> rel_std %.4f ; mean %.4f -> %.4f\n",
                    r0, r1, m0, m1);
        check(std::fabs(m1 / m0 - 1.0) < 0.02, "blurring preserves the field mean");
        check(r1 < r0 * 0.75, "blurring reduces the field's spatial variation");
    }

    // Autocorrelation (#180 acceptance). Matching the mean and the variance is
    // not enough to accept a cheaper generator: a weak PRNG can hit both and
    // still leave neighbouring pixels correlated, which on an image reads as
    // visible structure in what should be grain. Both generators are checked
    // at the horizontal neighbour (lags 1..4) and at the vertical one (lag w),
    // on the unblurred field where the draws are independent by construction
    // and the true value is therefore zero.
    //
    // 76 800 samples put the standard error of r near 1/sqrt(n) = 0.0036, so
    // 0.02 is roughly 5.5 sigma -- loose enough not to flake, tight enough that
    // any real neighbour coupling fails it.
    {
        const size_t n = static_cast<size_t>(w) * h;
        const double bound = 0.02;
        for (int fast = 0; fast < 2; ++fast) {
            const auto gen = fast ? spk::StatsRng::Generator::Fast
                                  : spk::StatsRng::Generator::Exact;
            const char* name = fast ? "fast" : "exact";
            std::vector<float> field;
            double mean = 0.0, rel = 0.0;
            field_stats(4.0f, 0.4f, 0.0f, w, h, seed, gen, &mean, &rel, &field);
            double worst = 0.0;
            for (size_t lag : {size_t(1), size_t(2), size_t(3), size_t(4),
                               static_cast<size_t>(w)}) {
                const double r = autocorr(field, lag);
                std::printf("%-5s autocorr lag %-4zu = %+.5f\n", name, lag, r);
                worst = std::fmax(worst, std::fabs(r));
            }
            check(worst < bound,
                  std::string(name) + " field is uncorrelated at neighbouring lags");
            (void)n;
        }
        // A blurred field MUST correlate -- if it does not, the measurement
        // above is not sensitive enough to have proved anything.
        std::vector<float> soft;
        double m1 = 0.0, r1 = 0.0;
        field_stats(4.0f, 0.4f, 2.0f, w, h, seed, spk::StatsRng::Generator::Fast,
                    &m1, &r1, &soft);
        const double r_soft = autocorr(soft, 1);
        std::printf("blurred field autocorr lag 1 = %+.5f (positive control)\n", r_soft);
        check(r_soft > 0.5, "the autocorrelation measure detects a blurred field");
    }

    std::printf(failures == 0 ? "test_glare_sampler: ALL OK\n"
                              : "test_glare_sampler: FAILURES\n");
    return failures == 0 ? 0 : 1;
}
