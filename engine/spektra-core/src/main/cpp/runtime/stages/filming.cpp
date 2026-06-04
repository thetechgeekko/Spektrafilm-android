/*
 * Spektrafilm for Android — native engine: filming stage (rgb -> raw -> density).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements runtime/stages/filming.h.
 */
#include "runtime/stages/filming.h"

#include <cmath>
#include <vector>

#include "kernels/exponential_filter.h"
#include "kernels/parallel.h"
#include "kernels/spectral_upsampling.h"
#include "model/couplers.h"
#include "model/density_curves.h"
#include "model/diffusion.h"
#include "model/grain.h"
#include "model/spectral.h"

namespace spk {

namespace {

// ProPhoto RGB (linear) -> XYZ with CAT02 chromatic adaptation to the film's
// reference illuminant (D55), apply_cctf_decoding=False. Baked from
// colour.RGB_to_XYZ at full double precision (see oracle dump). Matches
// _rgb_to_tc_b's fixed linear transform for color_space='ProPhoto RGB'.
// Row-major 3x3: xyz[r] = sum_c M[r][c] * rgb[c].
constexpr double kProPhotoToXyzD55[3][3] = {
    {0.7815775876144749, 0.12427353211547089, 0.05084064074531416},
    {0.28106991658512925, 0.7111246050020191, 0.0078043503519031375},
    {0.0008785229438793953, 0.0012166783269637077, 0.9190442562432091},
};

inline double clip01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

// _rgb_to_tc_b for color_space='ProPhoto RGB', apply_cctf_decoding=False,
// reference_illuminant='D55'. Mirrors kernels rgb_to_tc_b but with the
// ProPhoto matrix.
void prophoto_rgb_to_tc_b(const double rgb[3], Vec2* out_tc, double* out_b) {
    double xyz[3];
    for (int r = 0; r < 3; ++r) {
        xyz[r] = kProPhotoToXyzD55[r][0] * rgb[0] + kProPhotoToXyzD55[r][1] * rgb[1] +
                 kProPhotoToXyzD55[r][2] * rgb[2];
    }
    double b = xyz[0] + xyz[1] + xyz[2];
    double denom = std::fmax(b, 1e-10);
    Vec2 xy{clip01(xyz[0] / denom), clip01(xyz[1] / denom)};
    *out_tc = tri2quad(xy);
    *out_b = std::isnan(b) ? 0.0 : b;
}

// eval_erf4_spectral_bandpass(window_params): common band-pass replicated across
// the 3 channels. params = [c_uv, sigma_uv, c_ir, sigma_ir].
//   edge_uv = 0.5*(1 + erf((wl - c_uv)/(sigma_uv*sqrt2)))
//   edge_ir = 0.5*(1 - erf((wl - c_ir)/(sigma_ir*sqrt2)))
//   window  = edge_uv * edge_ir
void eval_erf4_window(const double* window_params, const std::vector<float>& wavelengths,
                      std::vector<double>* window /* (S,) common */) {
    const double sqrt2 = std::sqrt(2.0);
    double c_uv = window_params[0], sigma_uv = window_params[1];
    double c_ir = window_params[2], sigma_ir = window_params[3];
    int S = static_cast<int>(wavelengths.size());
    window->resize(S);
    for (int s = 0; s < S; ++s) {
        double wl = static_cast<double>(wavelengths[s]);
        double edge_uv = 0.5 * (1.0 + std::erf((wl - c_uv) / (sigma_uv * sqrt2)));
        double edge_ir = 0.5 * (1.0 - std::erf((wl - c_ir) / (sigma_ir * sqrt2)));
        (*window)[s] = edge_uv * edge_ir;
    }
}

// ---- hanatos2025 log-exposure-correction surface (poly4 model) ----
// Mirrors spectral_upsampling.py::eval_poly4_log_exposure_surface +
// compute_hanatos2025_tc_lut's `raw_lut *= 2**surface`. The surface is a per-LUT-
// cell, per-channel log-exposure correction evaluated over the same (L, L) tc grid
// as the spectra LUT's first two axes, multiplied into the tc_lut as 2**surface.

constexpr double kHanatos2025MaxCorrectionStops = 2.0;  // _HANATOS2025_MAX_CORRECTION_STOPS

// _tri2quad: triangular chromaticity -> square coords (scalar).
inline void tri2quad(double tx, double ty, double* qx, double* qy) {
    double y = ty / std::fmax(1.0 - tx, 1e-10);
    double x = (1.0 - tx) * (1.0 - tx);
    *qx = x < 0.0 ? 0.0 : (x > 1.0 ? 1.0 : x);
    *qy = y < 0.0 ? 0.0 : (y > 1.0 ? 1.0 : y);
}

// hanika_sigmoid: algebraic sigmoid bounded to [-max_val, max_val].
inline double hanika_sigmoid(double z, double max_val) {
    return z / std::sqrt(1.0 + (z / max_val) * (z / max_val));
}

// poly2d_deg4(x, y; params, center): degree-4 2D polynomial. `params` is the
// per-channel coefficient row [c0..c14]; c0 is unused (the centre is the zero-
// correction point). Mirrors spectral_upsampling.py::poly2d_deg4.
inline double poly2d_deg4(double tcx, double tcy, const double* params, double cx,
                          double cy) {
    double x = tcx - cx;
    double y = tcy - cy;
    double x2 = x * x, y2 = y * y;
    double xy_term = x * y;
    double x3 = x2 * x, y3 = y2 * y;
    double c1 = params[1], c2 = params[2], c3 = params[3], c4 = params[4],
           c5 = params[5], c6 = params[6], c7 = params[7], c8 = params[8],
           c9 = params[9], c10 = params[10], c11 = params[11], c12 = params[12],
           c13 = params[13], c14 = params[14];
    return c1 * x + c2 * y + c3 * x2 + c4 * y2 + c5 * xy_term + c6 * x3 + c7 * y3 +
           c8 * (x2 * y) + c9 * (x * y2) + c10 * (x2 * x2) + c11 * (y2 * y2) +
           c12 * (x3 * y) + c13 * (x2 * y2) + c14 * (x * y3);
}

// eval_poly4_log_exposure_surface over the (L, L) tc grid (tc_base = linspace(0,1,L),
// tc[i,j] = (tc_base[i], tc_base[j]) with indexing='ij'). Writes surface[(i*L+j)*3+c].
// center_tc = tri2quad(illuminant_xy). Stores the per-cell log-exposure correction
// (in stops) — the caller multiplies the tc_lut by 2**surface.
void eval_poly4_surface(const double* surface_params, int cols, int L,
                        double illu_xy_x, double illu_xy_y,
                        std::vector<double>* surface /* (L*L*3,) */) {
    double cx, cy;
    tri2quad(illu_xy_x, illu_xy_y, &cx, &cy);
    surface->assign(static_cast<size_t>(L) * L * 3, 0.0);
    for (int i = 0; i < L; ++i) {
        double tcx = (L > 1) ? static_cast<double>(i) / (L - 1) : 0.0;
        for (int j = 0; j < L; ++j) {
            double tcy = (L > 1) ? static_cast<double>(j) / (L - 1) : 0.0;
            double* o = &(*surface)[(static_cast<size_t>(i) * L + j) * 3];
            for (int c = 0; c < 3; ++c) {
                const double* pp = &surface_params[static_cast<size_t>(c) * cols];
                double raw = poly2d_deg4(tcx, tcy, pp, cx, cy);
                o[c] = hanika_sigmoid(raw, kHanatos2025MaxCorrectionStops);
            }
        }
    }
}

}  // namespace

namespace {

// 1-D Gaussian blur of the spectra LUT along its spectral axis (axis 2), in
// float64, mirroring scipy.ndimage.gaussian_filter(lut, sigma=(0,0,sigma)) with
// the SciPy defaults order=0, mode='reflect', truncate=4.0. The first two LUT
// axes get sigma 0 (no blur). Ports compute_hanatos2025_tc_lut's optional blur.
//
// SciPy's gaussian_filter1d: radius lw = int(truncate*sigma + 0.5); the symmetric
// weights w[k] = exp(-0.5*(k/sigma)^2) for k in [-lw,lw], normalised; the result
// is correlate1d with mode='reflect' (d c b a | a b c d | d c b a). The kernel is
// symmetric so correlate == convolve. All arithmetic stays in double to match
// the oracle's np.double LUT path.
NdArray blur_spectra_lut_spectral(const NdArray& lut, double sigma) {
    const int ni = lut.shape[0];
    const int nj = lut.shape[1];
    const int ns = lut.shape[2];  // spectral axis length

    // SciPy: lw = int(truncate*sd + 0.5), truncate=4.0.
    int lw = static_cast<int>(4.0 * sigma + 0.5);
    if (lw < 0) lw = 0;
    if (lw == 0) return lut;  // degenerate kernel {1.0} -> identity

    // Weights: exp(-0.5/sigma^2 * x^2) over x in [-lw, lw], then normalise.
    const int ksize = 2 * lw + 1;
    std::vector<double> w(ksize);
    double wsum = 0.0;
    const double inv2s2 = 0.5 / (sigma * sigma);
    for (int k = 0; k < ksize; ++k) {
        double x = static_cast<double>(k - lw);
        double v = std::exp(-inv2s2 * x * x);
        w[k] = v;
        wsum += v;
    }
    for (int k = 0; k < ksize; ++k) w[k] /= wsum;

    NdArray out;
    out.shape = lut.shape;
    out.data.assign(lut.data.size(), 0.0);

    // reflect(i, n): scipy.ndimage mode='reflect'.
    auto reflect = [](int i, int n) -> int {
        if (i >= 0 && i < n) return i;
        if (i >= -n && i < 0) return -i - 1;
        if (i >= n && i < 2 * n) return 2 * n - 1 - i;
        int period = 2 * n;
        i = i % period;
        if (i < 0) i += period;
        if (i >= n) i = period - 1 - i;
        return i;
    };

    for (int i = 0; i < ni; ++i) {
        for (int j = 0; j < nj; ++j) {
            const double* in = &lut.data[(static_cast<size_t>(i) * nj + j) * ns];
            double* o = &out.data[(static_cast<size_t>(i) * nj + j) * ns];
            for (int s = 0; s < ns; ++s) {
                double acc = 0.0;
                for (int k = -lw; k <= lw; ++k) {
                    acc += w[k + lw] * in[reflect(s + k, ns)];
                }
                o[s] = acc;
            }
        }
    }
    return out;
}

}  // namespace

NdArray build_filming_tc_lut(const Profile& film, const NdArray& spectra_lut_in,
                             const double* illuminant,
                             float spectral_gaussian_blur, bool apply_window,
                             bool apply_surface) {
    // Optional spectral-domain blur of the spectra LUT (default 0 -> no-op). Done
    // up front, before the sensitivity contraction, matching upstream
    // compute_hanatos2025_tc_lut.
    NdArray spectra_lut_blurred;
    const bool do_blur = spectral_gaussian_blur > 0.0f;
    if (do_blur) {
        spectra_lut_blurred =
            blur_spectra_lut_spectral(spectra_lut_in,
                                      static_cast<double>(spectral_gaussian_blur));
    }
    const NdArray& spectra_lut = do_blur ? spectra_lut_blurred : spectra_lut_in;

    int S = film.n_samples;  // 81
    // sensitivity = nan_to_num(10**log_sensitivity)  (S,3)
    std::vector<double> sens(static_cast<size_t>(S) * 3);
    for (int s = 0; s < S; ++s)
        for (int c = 0; c < 3; ++c) {
            double ls = static_cast<double>(film.log_sensitivity[s * 3 + c]);
            double v = std::pow(10.0, ls);
            if (std::isnan(v) || std::isinf(v)) v = 0.0;  // np.nan_to_num
            sens[s * 3 + c] = v;
        }

    // Per-channel sensitivity weights folded into the spectra contraction. When
    // apply_window is on, this is sens[s,c]*window[s]/norm[c] (white-balance-
    // preserving erf4 band-pass); otherwise it is the bare sensitivity. Mirrors
    // compute_hanatos2025_tc_lut's apply_window branch.
    std::vector<double> sw(static_cast<size_t>(S) * 3);
    if (apply_window) {
        // window (common across channels) via erf4, then per-channel white-balance
        // normalisation: window[s,c] /= norm[c].
        std::vector<double> window;
        eval_erf4_window(film.window_params.data(), film.wavelengths, &window);

        double num[3] = {0, 0, 0}, den[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            for (int c = 0; c < 3; ++c) {
                double se = sens[s * 3 + c];
                num[c] += se * ill * window[s];
                den[c] += se * ill;
            }
        }
        double norm[3];
        for (int c = 0; c < 3; ++c) norm[c] = num[c] / den[c];

        for (int s = 0; s < S; ++s)
            for (int c = 0; c < 3; ++c)
                sw[s * 3 + c] = sens[s * 3 + c] * window[s] / norm[c];
    } else {
        for (int s = 0; s < S; ++s)
            for (int c = 0; c < 3; ++c) sw[s * 3 + c] = sens[s * 3 + c];
    }

    // tc_lut[i,j,c] = sum_s spectra_lut[i,j,s] * sens_window[s,c]
    int L = spectra_lut.shape[0];
    int Ssl = spectra_lut.shape[2];
    NdArray tc_lut;
    tc_lut.shape = {L, L, 3};
    tc_lut.data.assign(static_cast<size_t>(L) * L * 3, 0.0);
    for (int i = 0; i < L; ++i) {
        for (int j = 0; j < L; ++j) {
            const double* spec = &spectra_lut.data[(static_cast<size_t>(i) * L + j) * Ssl];
            double acc[3] = {0, 0, 0};
            for (int s = 0; s < Ssl; ++s) {
                double sp = spec[s];
                acc[0] += sp * sw[s * 3 + 0];
                acc[1] += sp * sw[s * 3 + 1];
                acc[2] += sp * sw[s * 3 + 2];
            }
            double* o = &tc_lut.data[(static_cast<size_t>(i) * L + j) * 3];
            o[0] = acc[0];
            o[1] = acc[1];
            o[2] = acc[2];
        }
    }

    // Optional log-exposure-correction surface: raw_lut *= 2**surface, where
    // surface is the per-cell, per-channel poly4 correction over the (L, L) tc grid
    // evaluated at the film's reference illuminant chromaticity. Mirrors
    // compute_hanatos2025_tc_lut's apply_surface branch (model='poly4'). The
    // reference illuminant chromaticity is computed from the SAME illuminant SPD
    // used for the window normalisation, integrated against the CIE 1931 2deg CMFs
    // (xy is scale-invariant, so the SPD's normalisation is irrelevant).
    if (apply_surface && film.surface_params_cols > 0 &&
        static_cast<int>(film.surface_params.size()) == 3 * film.surface_params_cols) {
        double xyz[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            xyz[0] += ill * static_cast<double>(kCieCmf1931[s][0]);
            xyz[1] += ill * static_cast<double>(kCieCmf1931[s][1]);
            xyz[2] += ill * static_cast<double>(kCieCmf1931[s][2]);
        }
        double sum_xyz = xyz[0] + xyz[1] + xyz[2];
        double illu_xy_x = xyz[0] / sum_xyz;
        double illu_xy_y = xyz[1] / sum_xyz;

        std::vector<double> surface;
        eval_poly4_surface(film.surface_params.data(), film.surface_params_cols, L,
                           illu_xy_x, illu_xy_y, &surface);
        size_t ncell = static_cast<size_t>(L) * L * 3;
        for (size_t k = 0; k < ncell; ++k) {
            tc_lut.data[k] *= std::pow(2.0, surface[k]);
        }
    }
    return tc_lut;
}

void expose(const double* rgb, int width, int height, const FilmingParams& params,
            const NdArray& tc_lut, float* log_raw_out) {
    const int npix = width * height;
    int L = tc_lut.shape[0];
    double scale = static_cast<double>(L - 1);
    double exp_mult = std::pow(2.0, params.exposure_compensation_ev);  // 1.0 under goldens

    // Compute the float64 pre-log irradiance `raw` for every pixel.
    // raw = rgb_to_raw_hanatos2025(rgb) * brightness * 2^exposure_comp_ev.
    // (boost_ev==0 -> highlight boost identity; diffusion_filter/lens_blur off;
    //  black/white exposure correction == 1.0 under the goldens.)
    std::vector<double> raw(static_cast<size_t>(npix) * 3);
    parallel_for(0, npix, [&](int lo, int hi) {
        for (int p = lo; p < hi; ++p) {
            double in[3] = {rgb[p * 3 + 0], rgb[p * 3 + 1], rgb[p * 3 + 2]};
            Vec2 tc;
            double b;
            prophoto_rgb_to_tc_b(in, &tc, &b);
            double rr[3];
            cubic_interp_lut_at_2d(tc_lut, tc.x * scale, tc.y * scale, rr);
            for (int c = 0; c < 3; ++c) raw[p * 3 + c] = rr[c] * b * exp_mult;
        }
    });

    // Camera optical diffusion filter (Black Pro-Mist family), applied on the
    // float64 irradiance AFTER the highlight boost and BEFORE lens blur /
    // halation — matching filming.py::expose, which calls
    // apply_diffusion_filter_um(raw, camera.diffusion_filter, pixel_size_um)
    // before apply_gaussian_blur_um / apply_halation_um. Gated on
    // spatial_effects: the oracle's digest_params zeroes
    // camera.diffusion_filter.active when deactivate_spatial_effects is True
    // (params_builder.py), so the diffusion filter is part of the spatial branch.
    // No-op unless diffusion_filter.active (schema default false), so default
    // params stay bit-exact.
    if (params.spatial_effects && params.diffusion_filter.active) {
        apply_diffusion_filter_um(raw.data(), width, height,
                                  params.diffusion_filter, params.pixel_size_um);
    }

    // Camera lens blur (camera.lens_blur_um), applied on the float64 irradiance
    // AFTER the diffusion filter and BEFORE halation — matching filming.py::expose,
    // which calls apply_gaussian_blur_um(raw, camera.lens_blur_um, pixel_size_um)
    // between apply_diffusion_filter_um and apply_halation_um. apply_gaussian_blur_um
    // gates on sigma = lens_blur_um / pixel_size_um > 0 and blurs every channel with
    // the same scalar sigma (fast_gaussian_filter broadcasts the scalar across the
    // 3 channels). Gated on spatial_effects: the oracle's digest_params zeroes
    // camera.lens_blur_um under deactivate_spatial_effects=True, so the lens blur is
    // part of the spatial branch. Default lens_blur_um == 0 => no-op (bit-exact).
    if (params.spatial_effects && params.lens_blur_um > 0.0 &&
        params.pixel_size_um > 0.0) {
        double sigma = params.lens_blur_um / params.pixel_size_um;
        if (sigma > 0.0) {
            double sg[3] = {sigma, sigma, sigma};
            gaussian_blur_per_channel_d(raw.data(), width, height, 3, sg);
        }
    }

    // Spatial branch: in-emulsion scatter + back-reflection halation, applied to
    // the float64 irradiance before the log10 (matching filming.py::expose, which
    // calls apply_halation_um on `raw`). Skipped under the spatial-OFF goldens.
    if (params.spatial_effects && params.halation.active) {
        apply_halation_um(raw.data(), width, height, params.halation,
                          params.pixel_size_um);
    }

    // log_raw = log10(fmax(raw, 0) + 1e-10).
    for (int i = 0; i < npix * 3; ++i) {
        double lr = std::log10(std::fmax(raw[i], 0.0) + 1e-10);
        log_raw_out[i] = static_cast<float>(lr);
    }
}

void develop(const float* log_raw, int width, int height, const Profile& film,
             const FilmingParams& params, float* density_cmy_out) {
    int n = film.n_density_pts;
    const int npix = width * height;

    // normalized_density_curves = density_curves - nanmin(density_curves, axis=0)
    std::vector<float> ndc(static_cast<size_t>(n) * 3);
    normalize_density_curves(film.density_curves.data(), n, ndc.data());

    // density_cmy = interpolate_exposure_to_density(log_raw, ndc, le, gamma)
    interpolate_exposure_to_density(log_raw, npix, ndc.data(),
                                    film.log_exposure.data(), n,
                                    params.density_curve_gamma, density_cmy_out);

    // apply_density_correction_dir_couplers. The spatial variant diffuses the
    // inhibitor correction (Gaussian + exponential tail) when spatial effects are
    // on; it delegates to the pointwise path when diffusion_size_um == 0.
    apply_density_correction_dir_couplers_spatial(
        density_cmy_out, width, height, log_raw, film.log_exposure.data(),
        ndc.data(), n, params.dir_couplers, film.is_positive(),
        params.density_curve_gamma,
        params.spatial_effects ? params.pixel_size_um : 0.0, density_cmy_out);

    // Stochastic grain (AgX particle model). Identity unless grain.active (set by
    // digest_grain_params when grain_active && stochastic effects are on). The
    // model operates on the post-coupler density_cmy, mirroring emulsion.py::
    // develop, which calls apply_grain after apply_density_correction_dir_couplers.
    if (params.grain.active) {
        GrainParams grain = params.grain;
        // The sublayer path is selected when sublayers_active AND the profile
        // actually carries density_curves_layers; otherwise fall back to the
        // non-sublayer path (mirrors apply_grain's structure, where the layers
        // are required for the sublayer branch).
        const bool have_layers =
            static_cast<int>(film.density_curves_layers.size()) == n * 9;
        if (grain.sublayers_active && have_layers) {
            // interp_density_cmy_layers(density_cmy, normalized_density_curves,
            //                           density_curves_layers_RAW, positive_film).
            // NOTE: apply_grain passes the RAW (un-normalized) density_curves_layers
            // here, while density_curves is the normalized curve axis.
            std::vector<float> layers(static_cast<size_t>(npix) * 9);
            interp_density_cmy_layers(density_cmy_out, npix, ndc.data(),
                                      film.density_curves_layers.data(), n,
                                      film.is_positive(), layers.data());
            // density_max_layers[sl,c] = nanmax over the log-exposure axis of the
            // RAW density_curves_layers (NOT normalized).
            double density_max_layers[9];
            for (int sl = 0; sl < 3; ++sl) {
                for (int c = 0; c < 3; ++c) {
                    double mx = -1.0;
                    bool any = false;
                    for (int k = 0; k < n; ++k) {
                        float v = film.density_curves_layers[
                            static_cast<size_t>(k) * 9 + sl * 3 + c];
                        if (std::isnan(v)) continue;
                        if (!any || v > mx) { mx = v; any = true; }
                    }
                    density_max_layers[sl * 3 + c] = any ? mx : 2.2;
                }
            }
            apply_grain_to_density_layers(layers.data(), npix, width, height,
                                          density_max_layers, params.pixel_size_um,
                                          grain, density_cmy_out);
        } else {
            // Non-sublayer path: density_max_curves[c] = nanmax(ndc[:,c]).
            for (int c = 0; c < 3; ++c) {
                double mx = -1.0;
                bool any = false;
                for (int k = 0; k < n; ++k) {
                    float v = ndc[static_cast<size_t>(k) * 3 + c];
                    if (std::isnan(v)) continue;
                    if (!any || v > mx) { mx = v; any = true; }
                }
                grain.density_max_curves[c] = any ? mx : 2.2;
            }
            apply_grain_to_density(density_cmy_out, npix, width, height,
                                   params.pixel_size_um, grain, density_cmy_out);
        }
    }
}

}  // namespace spk
