/*
 * SpectraFilm for Android — native engine: filming stage (rgb -> raw -> density).
 * Copyright (C) 2026 SpectraFilm Android contributors.
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

}  // namespace

NdArray build_filming_tc_lut(const Profile& film, const NdArray& spectra_lut,
                             const double* illuminant) {
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

    // sens_window[s,c] = sens[s,c] * window[s] / norm[c]
    std::vector<double> sw(static_cast<size_t>(S) * 3);
    for (int s = 0; s < S; ++s)
        for (int c = 0; c < 3; ++c)
            sw[s * 3 + c] = sens[s * 3 + c] * window[s] / norm[c];

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
    for (int p = 0; p < npix; ++p) {
        double in[3] = {rgb[p * 3 + 0], rgb[p * 3 + 1], rgb[p * 3 + 2]};
        Vec2 tc;
        double b;
        prophoto_rgb_to_tc_b(in, &tc, &b);
        double rr[3];
        cubic_interp_lut_at_2d(tc_lut, tc.x * scale, tc.y * scale, rr);
        for (int c = 0; c < 3; ++c) raw[p * 3 + c] = rr[c] * b * exp_mult;
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
