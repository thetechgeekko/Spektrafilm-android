/*
 * Spektrafilm for Android — native engine: print density-curve morph (s023).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. See morph_curves.h for the parity contract.
 */
#include "model/morph_curves.h"

#include <algorithm>
#include <array>
#include <cfloat>
#include <cmath>
#include <functional>
#include <numeric>
#include <vector>

namespace spk {
namespace {

constexpr double kSigmaFloor = 0.05;  // NormCdfsFitConfig.sigma_floor
// _GUMBEL_LOCATION = -log(log(2)); _GUMBEL_WIDTH = 0.5*log(2)*sqrt(2*pi).
const double kGumbelLocation = -std::log(std::log(2.0));
const double kGumbelWidth = 0.5 * std::log(2.0) * std::sqrt(2.0 * M_PI);

inline double signed_z(double z, bool positive) { return positive ? -z : z; }

// scipy.stats.norm.cdf via the complementary error function (ndtr-equivalent):
// Phi(x) = 0.5 * erfc(-x / sqrt(2)).
inline double norm_cdf(double x) { return 0.5 * std::erfc(-x * M_SQRT1_2); }

inline double gumbel_matched_cdf(double z) {
    return std::exp(-std::exp(-(z / kGumbelWidth + kGumbelLocation)));
}

inline double layer_cdf(double z, bool positive, double gumbel_mix) {
    const double sz = signed_z(z, positive);
    double cdf = norm_cdf(sz);
    if (gumbel_mix > 0.0)
        cdf = (1.0 - gumbel_mix) * cdf + gumbel_mix * gumbel_matched_cdf(sz);
    return cdf;
}

// _evaluate_channel_density at a single log-exposure x for one channel's layers.
double eval_channel_density(double x, const double* centers, const double* amps,
                            const double* sigmas, int n_layers, bool positive,
                            const double* gumbel_mix) {
    double total = 0.0;
    for (int i = 0; i < n_layers; ++i) {
        const double z = (x - centers[i]) / sigmas[i];
        total += amps[i] * layer_cdf(z, positive, gumbel_mix ? gumbel_mix[i] : 0.0);
    }
    return total;
}

// (i_fast, i_mid, i_slow) by ascending center (grain-speed order). Stable on
// ties to match numpy argsort on the distinct centers used in practice.
std::array<int, 3> speed_layer_indices(const double* centers, int n_layers) {
    std::vector<int> order(n_layers);
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(),
                     [&](int a, int b) { return centers[a] < centers[b]; });
    return {order.front(), order[n_layers / 2], order.back()};
}

inline double channel_gamma_factor(const PrintCurvesMorphParams& p, int c) {
    switch (c) {
        case 0: return p.gamma_factor_red;
        case 1: return p.gamma_factor_green;
        default: return p.gamma_factor_blue;
    }
}

// Faithful port of scipy.optimize.brentq (Brent's zeroin) for the D(0) offset
// root, xtol=1e-10, rtol=4*eps. The root is found to xtol, and the residual is
// smooth, so the resulting density tracks the oracle far inside parity tolerance.
double brentq(const std::function<double(double)>& f, double xa, double xb,
              double xtol, double rtol, int maxiter) {
    double xpre = xa, xcur = xb;
    double fpre = f(xpre), fcur = f(xcur);
    if (fpre == 0.0) return xpre;
    if (fcur == 0.0) return xcur;
    double xblk = 0.0, fblk = 0.0, spre = 0.0, scur = 0.0;
    for (int i = 0; i < maxiter; ++i) {
        if (fpre * fcur < 0.0) {
            xblk = xpre; fblk = fpre; spre = scur = xcur - xpre;
        }
        if (std::fabs(fblk) < std::fabs(fcur)) {
            xpre = xcur; xcur = xblk; xblk = xpre;
            fpre = fcur; fcur = fblk; fblk = fpre;
        }
        const double delta = (xtol + rtol * std::fabs(xcur)) / 2.0;
        const double sbis = (xblk - xcur) / 2.0;
        if (fcur == 0.0 || std::fabs(sbis) < delta) return xcur;
        if (std::fabs(spre) > delta && std::fabs(fcur) < std::fabs(fpre)) {
            double stry;
            if (xpre == xblk) {  // secant
                stry = -fcur * (xcur - xpre) / (fcur - fpre);
            } else {  // inverse quadratic interpolation
                const double dpre = (fpre - fcur) / (xpre - xcur);
                const double dblk = (fblk - fcur) / (xblk - xcur);
                stry = -fcur * (fblk * dblk - fpre * dpre) /
                       (dblk * dpre * (fblk - fpre));
            }
            if (2.0 * std::fabs(stry) <
                std::min(std::fabs(spre), 3.0 * std::fabs(sbis) - delta)) {
                spre = scur; scur = stry;
            } else {
                spre = sbis; scur = sbis;
            }
        } else {
            spre = sbis; scur = sbis;
        }
        xpre = xcur; fpre = fcur;
        if (std::fabs(scur) > delta) xcur += scur;
        else xcur += (sbis > 0.0 ? delta : -delta);
        fcur = f(xcur);
    }
    return xcur;
}

// _developer_exhaustion_center_offset: the common per-channel center shift that
// keeps D(0) fixed when the Gumbel blend is engaged.
double developer_exhaustion_center_offset(const double* centers, const double* amps,
                                          const double* sigmas, int n_layers,
                                          bool positive, const double* gumbel_mix) {
    bool all_zero = true;
    for (int i = 0; i < n_layers; ++i)
        if (gumbel_mix[i] != 0.0) { all_zero = false; break; }
    if (all_zero) return 0.0;

    const double target_d0 =
        eval_channel_density(0.0, centers, amps, sigmas, n_layers, positive, nullptr);

    std::vector<double> shifted(n_layers);
    auto residual = [&](double offset) -> double {
        for (int i = 0; i < n_layers; ++i) shifted[i] = centers[i] + offset;
        return eval_channel_density(0.0, shifted.data(), amps, sigmas, n_layers,
                                    positive, gumbel_mix) - target_d0;
    };

    const double r_zero = residual(0.0);
    if (std::fabs(r_zero) <= 1e-12) return 0.0;

    double lo = -0.25, hi = 0.25;
    double r_lo = residual(lo), r_hi = residual(hi);
    for (int it = 0; it < 12; ++it) {
        if (r_lo == 0.0) return lo;
        if (r_hi == 0.0) return hi;
        if (r_lo * r_hi < 0.0)
            return brentq(residual, lo, hi, /*xtol=*/1e-10,
                          /*rtol=*/4.0 * DBL_EPSILON, /*maxiter=*/100);
        lo *= 2.0; hi *= 2.0;
        r_lo = residual(lo); r_hi = residual(hi);
    }
    return 0.0;
}

// _morph_channel_params: morph one channel's (centers, sigmas) by coupled gamma
// and compute its developer-exhaustion gumbel mix + center offset.
void morph_channel_params(const double* centers_in, const double* amps_in,
                          const double* sigmas_in, int n_layers,
                          const PrintCurvesMorphParams& p, int channel,
                          bool positive, std::vector<double>* centers_out,
                          std::vector<double>* sigmas_out,
                          std::vector<double>* gumbel_mix_out) {
    std::vector<double>& mc = *centers_out;
    std::vector<double>& ms = *sigmas_out;
    std::vector<double>& mix = *gumbel_mix_out;
    mc.assign(centers_in, centers_in + n_layers);
    ms.assign(sigmas_in, sigmas_in + n_layers);
    mix.assign(static_cast<size_t>(n_layers), p.developer_exhaustion);

    const auto idx = speed_layer_indices(mc.data(), n_layers);
    const int i_fast = idx[0], i_mid = idx[1], i_slow = idx[2];

    const double gf = p.gamma_factor;
    const double cg = channel_gamma_factor(p, channel);
    const double g_fast = gf * cg * p.gamma_factor_fast;
    const double g_mid = gf * cg * p.gamma_factor_slow;   // mid uses the slow factor
    const double g_slow = gf * cg * p.gamma_factor_slow;

    // Defensive: a non-positive effective gamma is rejected by the oracle; skip
    // the coupled-gamma scaling rather than emit NaN/inf in the engine.
    if (g_fast > 0.0 && g_mid > 0.0 && g_slow > 0.0) {
        ms[i_fast] = std::max(ms[i_fast] / g_fast, kSigmaFloor);
        mc[i_fast] = mc[i_fast] / g_fast;
        ms[i_mid] = std::max(ms[i_mid] / g_mid, kSigmaFloor);
        mc[i_mid] = mc[i_mid] / g_mid;
        ms[i_slow] = std::max(ms[i_slow] / g_slow, kSigmaFloor);
        mc[i_slow] = mc[i_slow] / g_slow;
    }

    const double offset = developer_exhaustion_center_offset(
        mc.data(), amps_in, ms.data(), n_layers, positive, mix.data());
    for (int i = 0; i < n_layers; ++i) mc[i] += offset;
}

}  // namespace

void apply_print_curves_morph(const double* centers, const double* amplitudes,
                              const double* sigmas, int n_layers,
                              const PrintCurvesMorphParams& params,
                              bool profile_positive,
                              const float* log_exposure, int n,
                              float* out) {
    std::vector<double> mc, ms, mix;
    for (int c = 0; c < 3; ++c) {
        const double* centers_c = centers + static_cast<size_t>(c) * n_layers;
        const double* amps_c = amplitudes + static_cast<size_t>(c) * n_layers;
        const double* sigmas_c = sigmas + static_cast<size_t>(c) * n_layers;
        morph_channel_params(centers_c, amps_c, sigmas_c, n_layers, params, c,
                             profile_positive, &mc, &ms, &mix);
        for (int k = 0; k < n; ++k) {
            const double x = static_cast<double>(log_exposure[k]);
            const double d = eval_channel_density(x, mc.data(), amps_c, ms.data(),
                                                  n_layers, profile_positive,
                                                  mix.data());
            out[static_cast<size_t>(k) * 3 + c] = static_cast<float>(d);
        }
    }
}

}  // namespace spk
