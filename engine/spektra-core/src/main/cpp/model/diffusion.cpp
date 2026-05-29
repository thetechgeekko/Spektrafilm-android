/*
 * SpectraFilm for Android — native engine: in-emulsion scatter + halation.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements model/diffusion.h (apply_halation_um).
 */
#include "model/diffusion.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "kernels/exponential_filter.h"

// M_PI is not in standard C++ <cmath>; some toolchains gate it behind
// _USE_MATH_DEFINES / _GNU_SOURCE. Provide the IEEE-754 double value (identical
// to numpy.pi) so the PSF normalisation is bit-exact on every compiler.
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace spk {

namespace {
inline double max_eps(double v) { return v > 1e-6 ? v : 1e-6; }
}  // namespace

void apply_halation_um(double* raw, int w, int h, const HalationParams& params,
                       double pixel_size_um) {
    if (!params.active) return;
    if (w <= 0 || h <= 0 || pixel_size_um <= 0.0) return;

    const size_t total = static_cast<size_t>(w) * h * 3;

    // Step 1 (highlight boost): boost_ev==0 -> identity. Not applied here.

    // Step 2: in-emulsion scatter.
    //   scattered = (1 - w_s) * G(sigma_c) * raw  +  w_s * Exp(lambda_t) * raw
    //   raw       = (1 - s) * raw  +  s * scattered
    const double s_amount = params.scatter_amount;
    const double s_scale = params.scatter_spatial_scale;
    double sigma_c_px[3], lambda_t_px[3], w_s[3];
    bool any_scatter_sigma = false;
    for (int c = 0; c < 3; ++c) {
        sigma_c_px[c] = params.scatter_core_um[c] * s_scale / pixel_size_um;
        lambda_t_px[c] = params.scatter_tail_um[c] * s_scale / pixel_size_um;
        w_s[c] = params.scatter_tail_weight[c];
        if (sigma_c_px[c] > 0.0 || lambda_t_px[c] > 0.0) any_scatter_sigma = true;
    }
    if (s_amount > 0.0 && any_scatter_sigma) {
        // core = fast_gaussian_filter(raw, max(sigma_c, 1e-6))
        std::vector<double> core(raw, raw + total);
        double sc[3] = {max_eps(sigma_c_px[0]), max_eps(sigma_c_px[1]),
                        max_eps(sigma_c_px[2])};
        gaussian_blur_per_channel_d(core.data(), w, h, 3, sc);
        // tail = fast_exponential_filter(raw, max(lambda_t, 1e-6))
        std::vector<double> tail(total);
        double lt[3] = {max_eps(lambda_t_px[0]), max_eps(lambda_t_px[1]),
                        max_eps(lambda_t_px[2])};
        exponential_filter_per_channel_d(raw, w, h, 3, lt, tail.data());
        // scattered = (1-w_s)*core + w_s*tail ; raw = (1-s)*raw + s*scattered
        const size_t plane = static_cast<size_t>(w) * h;
        for (size_t p = 0; p < plane; ++p) {
            for (int c = 0; c < 3; ++c) {
                size_t idx = p * 3 + c;
                double scattered = (1.0 - w_s[c]) * core[idx] + w_s[c] * tail[idx];
                raw[idx] = (1.0 - s_amount) * raw[idx] + s_amount * scattered;
            }
        }
    }

    // Step 3: back-reflection halation.
    //   a_tot = halation_strength * halation_amount
    //   decay_k = rho^(k-1) / sum_j rho^(j-1)   (k = 1..N)
    //   halation_blur = sum_k decay_k * G(sigma_h * sqrt(k)) * raw
    //   raw = raw + a_tot * halation_blur ; if renormalize: raw /= (1 + a_tot)
    const double h_amount = params.halation_amount;
    const double h_scale = params.halation_spatial_scale;
    double a_tot[3], sigma_h_px[3];
    bool any_a = false, any_sigma_h = false;
    for (int c = 0; c < 3; ++c) {
        a_tot[c] = params.halation_strength[c] * h_amount;
        sigma_h_px[c] = params.halation_first_sigma_um[c] * h_scale / pixel_size_um;
        if (a_tot[c] > 0.0) any_a = true;
        if (sigma_h_px[c] > 0.0) any_sigma_h = true;
    }
    const int N = params.halation_n_bounces;
    const double rho = params.halation_bounce_decay;
    if (N >= 1 && any_a && any_sigma_h) {
        std::vector<double> decay(N);
        double dsum = 0.0;
        for (int k = 1; k <= N; ++k) {
            decay[k - 1] = std::pow(rho, static_cast<double>(k - 1));
            dsum += decay[k - 1];
        }
        for (int k = 0; k < N; ++k) decay[k] /= dsum;

        std::vector<double> halation_blur(total, 0.0);
        std::vector<double> comp(total);
        for (int k = 1; k <= N; ++k) {
            double sk[3];
            for (int c = 0; c < 3; ++c)
                sk[c] = max_eps(sigma_h_px[c] * std::sqrt(static_cast<double>(k)));
            for (size_t i = 0; i < total; ++i) comp[i] = raw[i];
            gaussian_blur_per_channel_d(comp.data(), w, h, 3, sk);
            double wk = decay[k - 1];
            for (size_t i = 0; i < total; ++i) halation_blur[i] += wk * comp[i];
        }
        const size_t plane = static_cast<size_t>(w) * h;
        for (size_t p = 0; p < plane; ++p) {
            for (int c = 0; c < 3; ++c) {
                size_t idx = p * 3 + c;
                double v = raw[idx] + a_tot[c] * halation_blur[idx];
                if (params.halation_renormalize) v = v / (1.0 + a_tot[c]);
                raw[idx] = v;
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Optical diffusion filter (Black Pro-Mist family) — port of
// spektrafilm/model/diffusion.py::apply_diffusion_filter_um and the PSF helpers.
// ---------------------------------------------------------------------------
namespace {

// One {core|halo|bloom} group configuration, mirroring the per-family dicts in
// _DIFFUSION_FILTER_SHAPES.
struct GroupCfg {
    double lambda_um;
    double spread;
    int n_components;
    double alpha;  // only used for bloom (kind == bloom).
};

struct FamilyCfg {
    GroupCfg core;
    GroupCfg halo;
    GroupCfg bloom;
    double w_c, w_h, w_b;
    double halo_warmth_base;
    double total_gain;  // _DIFFUSION_FAMILY_TOTAL_GAIN[family].
};

// _DIFFUSION_FILTER_SHAPES + _DIFFUSION_FAMILY_TOTAL_GAIN, transcribed verbatim.
FamilyCfg family_cfg(DiffusionFamily fam) {
    switch (fam) {
        case DiffusionFamily::kGlimmerglass:
            return {{10.0, 1.5, 2, 0.0}, {50.0, 2.0, 3, 0.0},
                    {260.0, 2.5, 4, 3.2}, 0.60, 0.30, 0.10, 0.0, 0.65};
        case DiffusionFamily::kBlackProMist:
            return {{16.0, 1.5, 2, 0.0}, {95.0, 2.0, 3, 0.0},
                    {380.0, 2.5, 4, 3.5}, 0.40, 0.47, 0.13, 0.65, 0.75};
        case DiffusionFamily::kProMist:
            return {{14.0, 1.5, 2, 0.0}, {150.0, 2.0, 3, 0.0},
                    {650.0, 2.5, 4, 2.9}, 0.28, 0.42, 0.30, 0.40, 1.05};
        case DiffusionFamily::kCinebloom:
        default:
            return {{20.0, 1.5, 2, 0.0}, {200.0, 2.0, 3, 0.0},
                    {1000.0, 2.5, 4, 2.5}, 0.22, 0.30, 0.48, 0.85, 1.00};
    }
}

// _DIFFUSION_STRENGTH_BREAKPOINTS / _DIFFUSION_STRENGTH_TOTAL_FRACTION.
constexpr double kStrengthBreaks[5] = {0.125, 0.25, 0.5, 1.0, 2.0};
constexpr double kStrengthFraction[5] = {0.10, 0.20, 0.35, 0.55, 0.75};

// _HALO_CHANNEL_WARMTH_AXIS (R warm, G mild, B cool).
constexpr double kHaloWarmthAxis[3] = {+1.30, +0.15, -1.45};

// np.interp(x, xp, fp) for monotonically increasing xp, with end clamping
// (numpy returns fp[0] / fp[-1] outside the range).
double np_interp(double x, const double* xp, const double* fp, int n) {
    if (x <= xp[0]) return fp[0];
    if (x >= xp[n - 1]) return fp[n - 1];
    int i = 1;
    while (i < n && x > xp[i]) ++i;
    double t = (x - xp[i - 1]) / (xp[i] - xp[i - 1]);
    return fp[i - 1] + t * (fp[i] - fp[i - 1]);
}

// _strength_to_scatter(strength, family).
double strength_to_scatter(double strength, const FamilyCfg& cfg) {
    if (strength <= 0.0) return 0.0;
    double s = strength < 1e-6 ? 1e-6 : strength;  // np.clip(strength, 1e-6, None)
    double log_strength = std::log2(s);
    double log_breaks[5];
    for (int i = 0; i < 5; ++i) log_breaks[i] = std::log2(kStrengthBreaks[i]);
    double base_total = np_interp(log_strength, log_breaks, kStrengthFraction, 5);
    double v = base_total * cfg.total_gain;
    if (v < 0.0) v = 0.0;
    if (v > 0.99) v = 0.99;  // np.clip(..., 0.0, 0.99)
    return v;
}

// _expand_group: returns (lambdas, weights) summing to 1 inside the group.
void expand_group(const GroupCfg& g, bool is_bloom, std::vector<double>* lambdas,
                  std::vector<double>* weights) {
    double lambda_center = g.lambda_um;
    double spread = g.spread;
    int n = g.n_components < 1 ? 1 : g.n_components;
    if (n == 1 || spread <= 1.0) {
        lambdas->assign(1, lambda_center);
        weights->assign(1, 1.0);
        return;
    }
    double log_lo = std::log(lambda_center / spread);
    double log_hi = std::log(lambda_center * spread);
    lambdas->resize(n);
    weights->resize(n);
    // np.linspace(log_lo, log_hi, n): endpoints exact; interior via step.
    double step = (log_hi - log_lo) / static_cast<double>(n - 1);
    for (int k = 0; k < n; ++k) {
        double lg = (k == n - 1) ? log_hi : (log_lo + step * static_cast<double>(k));
        (*lambdas)[k] = std::exp(lg);
    }
    double wsum = 0.0;
    for (int k = 0; k < n; ++k) {
        double wk = is_bloom ? std::pow((*lambdas)[k], 2.0 - g.alpha) : 1.0;
        (*weights)[k] = wk;
        wsum += wk;
    }
    for (int k = 0; k < n; ++k) (*weights)[k] /= wsum;
}

// _halo_channel_weights: returns a (3, n) per-channel halo weight set,
// energy-conserving (each row sums to sum(weights)).
void halo_channel_weights(const std::vector<double>& weights, double warmth,
                          std::vector<std::vector<double>>* out /* [3][n] */) {
    int n = static_cast<int>(weights.size());
    out->assign(3, std::vector<double>(n));
    if (n < 2) {
        for (int c = 0; c < 3; ++c) (*out)[c] = weights;
        return;
    }
    double w = warmth;
    if (w < -1.5) w = -1.5;
    if (w > 1.5) w = 1.5;  // np.clip(warmth, -1.5, 1.5)
    // g = linspace(-1, 1, n); g -= average(g, weights=weights)
    std::vector<double> gv(n);
    double step = 2.0 / static_cast<double>(n - 1);
    for (int k = 0; k < n; ++k)
        gv[k] = (k == n - 1) ? 1.0 : (-1.0 + step * static_cast<double>(k));
    double wsum = 0.0, gwsum = 0.0;
    for (int k = 0; k < n; ++k) { wsum += weights[k]; gwsum += gv[k] * weights[k]; }
    double gavg = gwsum / wsum;  // np.average(g, weights=weights)
    for (int k = 0; k < n; ++k) gv[k] -= gavg;
    double target_total = wsum;  // np.sum(weights)
    for (int c = 0; c < 3; ++c) {
        std::vector<double> rawv(n);
        double s = 0.0;
        for (int k = 0; k < n; ++k) {
            double r = weights[k] * (1.0 + w * kHaloWarmthAxis[c] * gv[k]);
            if (r < 0.0) r = 0.0;  // np.maximum(raw, 0.0)
            rawv[k] = r;
            s += r;
        }
        if (s > 0.0) {
            for (int k = 0; k < n; ++k) (*out)[c][k] = rawv[k] * (target_total / s);
        } else {
            (*out)[c] = weights;
        }
    }
}

// Resolve the family cfg with the per-group multiplier overrides applied
// (mirrors _resolve_family_cfg). Returns the renormalized w_c/w_h/w_b and the
// scaled group lambda centres.
FamilyCfg resolve_family_cfg(DiffusionFamily fam, const DiffusionFilterParams& p) {
    FamilyCfg base = family_cfg(fam);
    double ci = p.core_intensity, hi = p.halo_intensity, bi = p.bloom_intensity;
    double cs = p.core_size, hs = p.halo_size, bs = p.bloom_size;
    if (ci == 1.0 && hi == 1.0 && bi == 1.0 && cs == 1.0 && hs == 1.0 && bs == 1.0)
        return base;
    double w_c = base.w_c * (ci > 0.0 ? ci : 0.0);
    double w_h = base.w_h * (hi > 0.0 ? hi : 0.0);
    double w_b = base.w_b * (bi > 0.0 ? bi : 0.0);
    double total = w_c + w_h + w_b;
    if (total <= 0.0) return base;
    FamilyCfg cfg = base;
    cfg.core.lambda_um = base.core.lambda_um * (cs > 1e-6 ? cs : 1e-6);
    cfg.halo.lambda_um = base.halo.lambda_um * (hs > 1e-6 ? hs : 1e-6);
    cfg.bloom.lambda_um = base.bloom.lambda_um * (bs > 1e-6 ? bs : 1e-6);
    cfg.w_c = w_c / total;
    cfg.w_h = w_h / total;
    cfg.w_b = w_b / total;
    return cfg;
}

// _bloom_max_lambda_um: largest lambda in the bloom progression (image-plane µm).
double bloom_max_lambda_um(const FamilyCfg& cfg) {
    return cfg.bloom.lambda_um * cfg.bloom.spread;
}

}  // namespace

void apply_diffusion_filter_um(double* raw, int w, int h,
                               const DiffusionFilterParams& params,
                               double pixel_size_um) {
    if (!params.active) return;
    if (params.strength <= 0.0 || params.spatial_scale <= 0.0) return;
    if (w <= 0 || h <= 0 || pixel_size_um <= 0.0) return;

    const FamilyCfg cfg = resolve_family_cfg(params.family, params);
    const double p_s = strength_to_scatter(params.strength, cfg);
    if (p_s <= 0.0) return;

    const double spatial_scale = params.spatial_scale > 1e-6 ? params.spatial_scale : 1e-6;

    // Kernel radius (matches apply_diffusion_filter_um):
    //   radius = ceil(max(8 * bloom_max_lambda_px, 5))
    //   radius = min(radius, max(min(h,w)//2 - 1, 1))
    const double bloom_max_lambda_px =
        bloom_max_lambda_um(cfg) * params.spatial_scale / pixel_size_um;
    double rd = 8.0 * bloom_max_lambda_px;
    if (rd < 5.0) rd = 5.0;
    int radius = static_cast<int>(std::ceil(rd));
    int min_hw = (h < w ? h : w);
    int cap = min_hw / 2 - 1;
    if (cap < 1) cap = 1;
    if (radius > cap) radius = cap;

    const int ks = 2 * radius + 1;
    // effective_warmth = halo_warmth_base + halo_warmth.
    const double effective_warmth = cfg.halo_warmth_base + params.halo_warmth;

    // Expand groups (lambdas in µm -> pixel; the channel-independent core/bloom
    // exp-sums and the per-channel halo weights).
    std::vector<double> core_lambdas, core_weights;
    std::vector<double> halo_lambdas, halo_weights;
    std::vector<double> bloom_lambdas, bloom_weights;
    expand_group(cfg.core, /*is_bloom=*/false, &core_lambdas, &core_weights);
    expand_group(cfg.halo, /*is_bloom=*/false, &halo_lambdas, &halo_weights);
    expand_group(cfg.bloom, /*is_bloom=*/true, &bloom_lambdas, &bloom_weights);

    std::vector<std::vector<double>> halo_per_ch;  // [3][N_halo]
    halo_channel_weights(halo_weights, effective_warmth, &halo_per_ch);

    auto to_px = [&](std::vector<double>& v) {
        for (double& x : v) x = x * spatial_scale / pixel_size_um;
    };
    to_px(core_lambdas);
    to_px(halo_lambdas);
    to_px(bloom_lambdas);

    // _exp_sum: sum_k w_k * exp(-r/lk) / (2*pi*lk^2), lk >= 1e-6.
    auto exp_sum = [](double r, const std::vector<double>& lambdas_px,
                      const std::vector<double>& weights) {
        double total = 0.0;
        for (size_t k = 0; k < lambdas_px.size(); ++k) {
            double lk = lambdas_px[k] > 1e-6 ? lambdas_px[k] : 1e-6;
            total += weights[k] * std::exp(-r / lk) / (2.0 * M_PI * lk * lk);
        }
        return total;
    };

    // Build the per-channel PSF on the (ks x ks) grid. r is measured from the
    // grid centre (cy, cx) = (ks//2, ks//2) (same as np.ogrid + the integer
    // centre in diffusion_filter_psf). Then sum-normalise each channel.
    const int cy = ks / 2, cx = ks / 2;
    std::vector<std::vector<double>> psf(3, std::vector<double>(
                                                static_cast<size_t>(ks) * ks));
    double psf_sum[3] = {0.0, 0.0, 0.0};
    for (int yy = 0; yy < ks; ++yy) {
        for (int xx = 0; xx < ks; ++xx) {
            double dx = static_cast<double>(xx - cx);
            double dy = static_cast<double>(yy - cy);
            double r = std::sqrt(dx * dx + dy * dy);
            double core = cfg.w_c * exp_sum(r, core_lambdas, core_weights);
            double bloom = cfg.w_b * exp_sum(r, bloom_lambdas, bloom_weights);
            size_t idx = static_cast<size_t>(yy) * ks + xx;
            for (int c = 0; c < 3; ++c) {
                double halo = cfg.w_h * exp_sum(r, halo_lambdas, halo_per_ch[c]);
                double v = core + halo + bloom;
                psf[c][idx] = v;
                psf_sum[c] += v;
            }
        }
    }
    for (int c = 0; c < 3; ++c)
        for (size_t i = 0; i < psf[c].size(); ++i) psf[c][i] /= psf_sum[c];

    // Reflect-pad the image (numpy mode='reflect': mirror WITHOUT edge repeat),
    // convolve each channel directly in double precision, slice back to the
    // original window. blurred = K_s * image (mode='same').
    const int pw = w + 2 * radius;
    const int ph = h + 2 * radius;
    // numpy 'reflect' index map for a coordinate p in [-radius, w-1+radius].
    auto reflect = [](int p, int n) {
        if (n == 1) return 0;
        int period = 2 * (n - 1);
        int m = p % period;
        if (m < 0) m += period;
        return m < n ? m : period - m;
    };

    std::vector<double> blurred(static_cast<size_t>(w) * h * 3);
    // Build the padded plane for one channel, convolve, write back.
    std::vector<double> padded(static_cast<size_t>(pw) * ph);
    for (int c = 0; c < 3; ++c) {
        for (int yy = 0; yy < ph; ++yy) {
            int sy = reflect(yy - radius, h);
            for (int xx = 0; xx < pw; ++xx) {
                int sx = reflect(xx - radius, w);
                padded[static_cast<size_t>(yy) * pw + xx] =
                    raw[(static_cast<size_t>(sy) * w + sx) * 3 + c];
            }
        }
        const std::vector<double>& kern = psf[c];
        // mode='same' direct convolution: out[y,x] = sum_{i,j} padded[y+i, x+j]
        // * flip(kern)[i,j], centred. For a symmetric centred kernel the centre
        // offsets (ks-1)/2 == radius, so out[y,x] over the original window maps
        // to padded[y .. y+ks-1, x .. x+ks-1] convolved with the flipped kernel.
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                double acc = 0.0;
                for (int i = 0; i < ks; ++i) {
                    const double* prow = &padded[static_cast<size_t>(y + i) * pw + x];
                    // flip(kern) row index (ks-1-i), reversed columns.
                    const double* krow = &kern[static_cast<size_t>(ks - 1 - i) * ks];
                    for (int j = 0; j < ks; ++j) {
                        acc += prow[j] * krow[ks - 1 - j];
                    }
                }
                blurred[(static_cast<size_t>(y) * w + x) * 3 + c] = acc;
            }
        }
    }

    // E_out = (1 - p_s) * E_in + p_s * blurred.
    const size_t total = static_cast<size_t>(w) * h * 3;
    for (size_t i = 0; i < total; ++i)
        raw[i] = (1.0 - p_s) * raw[i] + p_s * blurred[i];
}

}  // namespace spk
