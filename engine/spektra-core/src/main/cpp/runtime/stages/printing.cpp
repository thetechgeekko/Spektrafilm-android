/*
 * Spektrafilm for Android — native engine: printing (enlarger) stage.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 */
#include "runtime/stages/printing.h"

#include <cmath>
#include <vector>

#include "kernels/exp10.h"
#include "kernels/lut3d.h"
#include "kernels/parallel.h"
#include "model/density_curves.h"
#include "model/diffusion.h"

namespace spk {

namespace {

// Context + adapter for the OPT-IN enlarger 3D-LUT. Samples the SAME
// _film_cmy_to_print_log_raw transform the direct path evaluates: from a film
// CMY density triple, the spectral integral against the (precomputed) print
// sensitivity and filtered illuminant, times the midgray exposure factor, then
// log10. Scalar libm exp10 here (the LUT is an approximation anyway; the direct
// per-pixel path keeps its byte-exact exp10_vec SIMD untouched).
struct EnlargerLutCtx {
    const float* channel_density;     // film dyes, (S*3,) row-major [l*3+k]
    const float* base_density;        // film base, (S,)
    const double* sens;               // print sensitivity, (S*3,) [l*3+k]
    const double* filtered_illuminant; // enlarger illuminant * dichroic, (S,)
    int S;
    double exposure_factor_midgray;
    // Constant per-print-channel preflash raw 3-vector (already scaled by
    // preflash_exposure), added to raw AFTER the midgray factor. {0,0,0} when
    // preflash is off, so the LUT samples the exact same transform as the direct
    // path (printing.py::_film_cmy_to_print_log_raw, raw += _compute_raw_preflash).
    double preflash_raw[3];
};

void cmy_to_print_log_raw_fn(const double in[3], double out[3], void* vctx) {
    const EnlargerLutCtx& c = *static_cast<const EnlargerLutCtx*>(vctx);
    double raw0 = 0.0, raw1 = 0.0, raw2 = 0.0;
    for (int l = 0; l < c.S; ++l) {
        const float* cd = c.channel_density + static_cast<size_t>(l) * 3;
        const double spectral = in[0] * static_cast<double>(cd[0]) +
                                in[1] * static_cast<double>(cd[1]) +
                                in[2] * static_cast<double>(cd[2]) +
                                static_cast<double>(c.base_density[l]);
        double light = std::pow(10.0, -spectral) * c.filtered_illuminant[l];
        if (std::isnan(light)) light = 0.0;
        const double* sl = c.sens + static_cast<size_t>(l) * 3;
        raw0 += light * sl[0];
        raw1 += light * sl[1];
        raw2 += light * sl[2];
    }
    raw0 *= c.exposure_factor_midgray;
    raw1 *= c.exposure_factor_midgray;
    raw2 *= c.exposure_factor_midgray;
    raw0 += c.preflash_raw[0];  // raw += _compute_raw_preflash (0 when off)
    raw1 += c.preflash_raw[1];
    raw2 += c.preflash_raw[2];
    out[0] = std::log10(std::fmax(raw0, 0.0) + 1e-10);
    out[1] = std::log10(std::fmax(raw1, 0.0) + 1e-10);
    out[2] = std::log10(std::fmax(raw2, 0.0) + 1e-10);
}

}  // namespace

void print_expose(const Profile& film, const Profile& print_profile,
                  const PrintingParams& params, const float* density_cmy,
                  int width, int height, float* log_raw_print_out) {
    const int npix = width * height;
    const int S = film.n_samples;  // 81 for bundled profiles.
    const bool diffusion = params.diffusion_filter.active;
    // When the enlarger diffusion filter is active the spatial pass runs on the
    // float64 print irradiance (raw = 10^log_raw * print_exposure * bw) BEFORE
    // the final log10, mirroring printing.py::expose. Otherwise the pointwise
    // round trip below writes directly into log_raw_print_out and `raw_buf`
    // is unused, keeping the default (no-op) path byte-identical.
    std::vector<double> raw_buf;
    if (diffusion) raw_buf.resize(static_cast<size_t>(npix) * 3);

    // print sensitivity = nan_to_num(10**log_sensitivity) on the working shape.
    // Precompute once: print_profile.log_sensitivity is (S*3,) row-major [s*3+k].
    std::vector<double> sens(static_cast<size_t>(S) * 3);
    for (int l = 0; l < S; ++l) {
        for (int k = 0; k < 3; ++k) {
            double v = std::pow(10.0, static_cast<double>(
                          print_profile.log_sensitivity[static_cast<size_t>(l) * 3 + k]));
            if (std::isnan(v)) v = 0.0;  // np.nan_to_num
            sens[static_cast<size_t>(l) * 3 + k] = v;
        }
    }

    // PREFLASH (printing.py::_compute_raw_preflash): a uniform pre-exposure flash
    // of the print paper. When enlarger.preflash_exposure > 0 the print raw gets a
    // constant per-channel term added (after the midgray factor, before log10):
    //   light_preflash[l] = 10^-base_density[l] * preflash_illuminant[l]  (NaN->0)
    //   raw_preflash[k]   = sum_l light_preflash[l] * sens[l,k]
    //   preflash_raw[k]   = raw_preflash[k] * preflash_exposure
    // The term is constant across pixels (it depends only on the film base density,
    // the preflash-filtered enlarger illuminant, and the print sensitivity), so it
    // is computed once here. preflash_exposure == 0 (the default / no-preflash
    // guard `if preflash_exposure > 0`) => preflash_raw stays {0,0,0}, a STRICT
    // no-op that keeps the default path byte-identical.
    double preflash_raw[3] = {0.0, 0.0, 0.0};
    if (params.preflash_exposure > 0.0) {
        for (int l = 0; l < S; ++l) {
            double light = std::pow(10.0, -static_cast<double>(film.base_density[l])) *
                           params.preflash_illuminant[l];
            if (std::isnan(light)) light = 0.0;  // density_to_light NaN -> 0
            const double* sl = sens.data() + static_cast<size_t>(l) * 3;
            preflash_raw[0] += light * sl[0];
            preflash_raw[1] += light * sl[1];
            preflash_raw[2] += light * sl[2];
        }
        preflash_raw[0] *= params.preflash_exposure;
        preflash_raw[1] *= params.preflash_exposure;
        preflash_raw[2] *= params.preflash_exposure;
    }

    // OPT-IN enlarger 3D-LUT acceleration (params.use_enlarger_lut, default
    // false). Mirrors printing.py::expose routing _film_cmy_to_print_log_raw
    // through SpectralLUTService.spectral_compute_enlarger(use_lut=...): when on,
    // a per-channel uniform PCHIP 3D LUT is built over the film-density domain
    // [data_min, data_max] = [-grain.density_min, nanmax(film.density_curves)] at
    // params.lut_resolution steps (utils.lut.compute_with_lut) and the film
    // density image is interpolated to log_raw_print instead of evaluating the
    // spectral integral per pixel. The LUT covers EXACTLY the cmy ->
    // _film_cmy_to_print_log_raw step; the 10^lr * print_exposure * bw tail
    // (+ optional diffusion) below is shared with the direct path. Interpolation
    // is NOT bit-exact vs the direct evaluation (~5e-5), so it is OPT-IN and the
    // default path (use_enlarger_lut == false) never even constructs the LUT,
    // staying byte-identical to the per-pixel exp10_vec integral.
    std::vector<double> lut_lr;  // (npix*3) when use_enlarger_lut, else empty
    if (params.use_enlarger_lut) {
        // Per-channel domain bounds (printing.py::expose):
        //   data_min = -film_render.grain.density_min
        //   data_max =  np.nanmax(film.data.density_curves, axis=0)
        double xmin[3], xmax[3];
        const int N = film.n_density_pts;
        double cmax[3] = {-INFINITY, -INFINITY, -INFINITY};
        for (int nrow = 0; nrow < N; ++nrow) {
            const float* dc =
                film.density_curves.data() + static_cast<size_t>(nrow) * 3;
            for (int c = 0; c < 3; ++c) {
                double v = static_cast<double>(dc[c]);
                if (!std::isnan(v) && v > cmax[c]) cmax[c] = v;  // np.nanmax
            }
        }
        for (int c = 0; c < 3; ++c) {
            xmin[c] = -params.grain_density_min[c];
            xmax[c] = cmax[c];
        }

        int steps = params.lut_resolution;
        if (steps < 2) steps = 2;
        if (steps > 192) steps = 192;

        EnlargerLutCtx ctx;
        ctx.channel_density = film.channel_density.data();
        ctx.base_density = film.base_density.data();
        ctx.sens = sens.data();
        ctx.filtered_illuminant = params.filtered_illuminant;
        ctx.S = S;
        ctx.exposure_factor_midgray = params.exposure_factor_midgray;
        ctx.preflash_raw[0] = preflash_raw[0];
        ctx.preflash_raw[1] = preflash_raw[1];
        ctx.preflash_raw[2] = preflash_raw[2];
        Lut3D lut =
            build_lut_3d(xmin, xmax, steps, {}, &cmy_to_print_log_raw_fn, &ctx);

        lut_lr.resize(static_cast<size_t>(npix) * 3);
        std::vector<double> dens_d(static_cast<size_t>(npix) * 3);
        for (size_t i = 0; i < dens_d.size(); ++i)
            dens_d[i] = static_cast<double>(density_cmy[i]);
        apply_lut_3d_pchip(lut, dens_d.data(), width, height, lut_lr.data());
    }

    // The Python reference runs the whole spectral chain in float64 and stores
    // float32 only at the final write. Mirror that exactly.
    parallel_for(0, npix, [&](int lo, int hi) {
    for (int p = lo; p < hi; ++p) {
        // log_raw_print = _film_cmy_to_print_log_raw(cmy). Either interpolated
        // from the opt-in enlarger LUT or evaluated directly (the default,
        // byte-exact path — its exp10_vec SIMD is left untouched).
        double lr0, lr1, lr2;
        if (params.use_enlarger_lut) {
            const double* lr = lut_lr.data() + static_cast<size_t>(p) * 3;
            lr0 = lr[0];
            lr1 = lr[1];
            lr2 = lr[2];
        } else {
        const float* dcmy = density_cmy + static_cast<size_t>(p) * 3;
        const double c0 = static_cast<double>(dcmy[0]);
        const double c1 = static_cast<double>(dcmy[1]);
        const double c2 = static_cast<double>(dcmy[2]);

        // raw[k] = sum_l light[l] * sens[l,k], where
        //   spectral[l] = c.channel_density[l] + base_density[l]   (film dyes)
        //   light[l]    = 10^-spectral[l] * filtered_illuminant[l] (NaN -> 0)
        // SIMD: 10^(-spectral) (the dominant per-band cost) is evaluated kExp10Lanes
        // at a time with the vector exp10 (kernels/exp10; <=4 ULP, byte-identical
        // after the float32 cast). The raw accumulation stays in band order.
        double raw0 = 0.0, raw1 = 0.0, raw2 = 0.0;
        int l = 0;
        for (; l + kExp10Lanes <= S; l += kExp10Lanes) {
            exp10_vd negspec;
            for (int q = 0; q < kExp10Lanes; ++q) {
                const float* cd =
                    film.channel_density.data() + static_cast<size_t>(l + q) * 3;
                negspec[q] = -(c0 * static_cast<double>(cd[0]) +
                               c1 * static_cast<double>(cd[1]) +
                               c2 * static_cast<double>(cd[2]) +
                               static_cast<double>(film.base_density[l + q]));
            }
            exp10_vd ev = exp10_vec(negspec);
            for (int q = 0; q < kExp10Lanes; ++q) {
                double light = ev[q] * params.filtered_illuminant[l + q];
                if (std::isnan(light)) light = 0.0;
                const double* sl = sens.data() + static_cast<size_t>(l + q) * 3;
                raw0 += light * sl[0];
                raw1 += light * sl[1];
                raw2 += light * sl[2];
            }
        }
        for (; l < S; ++l) {  // odd-band tail (same exp10 arithmetic, scalar).
            const float* cd =
                film.channel_density.data() + static_cast<size_t>(l) * 3;
            const double spectral = c0 * static_cast<double>(cd[0]) +
                                    c1 * static_cast<double>(cd[1]) +
                                    c2 * static_cast<double>(cd[2]) +
                                    static_cast<double>(film.base_density[l]);
            double light = exp10_scalar(-spectral) * params.filtered_illuminant[l];
            if (std::isnan(light)) light = 0.0;
            const double* sl = sens.data() + static_cast<size_t>(l) * 3;
            raw0 += light * sl[0];
            raw1 += light * sl[1];
            raw2 += light * sl[2];
        }

        // raw *= exposure_factor_midgray (midgray normalisation), then
        // raw += _compute_raw_preflash (the constant preflash 3-vector; {0,0,0}
        // when preflash is off, so this is a strict no-op on the default path).
        raw0 *= params.exposure_factor_midgray;
        raw1 *= params.exposure_factor_midgray;
        raw2 *= params.exposure_factor_midgray;
        raw0 += preflash_raw[0];
        raw1 += preflash_raw[1];
        raw2 += preflash_raw[2];

        // _film_cmy_to_print_log_raw returns log10(max(raw,0) + 1e-10).
        lr0 = std::log10(std::fmax(raw0, 0.0) + 1e-10);
        lr1 = std::log10(std::fmax(raw1, 0.0) + 1e-10);
        lr2 = std::log10(std::fmax(raw2, 0.0) + 1e-10);
        }  // end direct (non-LUT) path

        // expose(): raw = 10^log_raw; raw *= print_exposure * bw_correction;
        // then the optical diffusion filter (if active) runs on `raw`; finally
        // return log10(max(raw,0) + 1e-10). The 10^/log10 round trip is
        // reproduced verbatim so float rounding matches the reference.
        const double mult = params.print_exposure * params.bw_exposure_correction;
        double r0 = std::pow(10.0, lr0) * mult;
        double r1 = std::pow(10.0, lr1) * mult;
        double r2 = std::pow(10.0, lr2) * mult;

        if (diffusion) {
            double* rb = raw_buf.data() + static_cast<size_t>(p) * 3;
            rb[0] = r0; rb[1] = r1; rb[2] = r2;
        } else {
            float* out = log_raw_print_out + static_cast<size_t>(p) * 3;
            out[0] = static_cast<float>(std::log10(std::fmax(r0, 0.0) + 1e-10));
            out[1] = static_cast<float>(std::log10(std::fmax(r1, 0.0) + 1e-10));
            out[2] = static_cast<float>(std::log10(std::fmax(r2, 0.0) + 1e-10));
        }
    }
    });

    if (diffusion) {
        apply_diffusion_filter_um(raw_buf.data(), width, height,
                                  params.diffusion_filter, params.pixel_size_um);
        const size_t total = static_cast<size_t>(npix) * 3;
        for (size_t i = 0; i < total; ++i) {
            log_raw_print_out[i] = static_cast<float>(
                std::log10(std::fmax(raw_buf[i], 0.0) + 1e-10));
        }
    }
}

void print_develop(const Profile& print_profile, const PrintingParams& params,
                   const float* log_raw_print, int npix,
                   float* density_cmy_out) {
    // develop_simple: interpolate against the RAW print density curves (no nanmin
    // normalisation, no DIR couplers), gamma broadcast to all channels.
    interpolate_exposure_to_density(log_raw_print, npix,
                                    print_profile.density_curves.data(),
                                    print_profile.log_exposure.data(),
                                    print_profile.n_density_pts,
                                    params.density_curve_gamma, density_cmy_out);
}

}  // namespace spk
