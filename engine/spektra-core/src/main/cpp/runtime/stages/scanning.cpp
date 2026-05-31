/*
 * Spektrafilm for Android — native engine: scanning stage.
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
#include "runtime/stages/scanning.h"

#include <cmath>
#include <vector>

#include "kernels/exponential_filter.h"
#include "kernels/lut3d.h"
#include "kernels/parallel.h"
#include "model/color_output.h"
#include "model/conversions.h"
#include "model/emulsion.h"
#include "model/glare.h"
#include "model/spectral.h"

namespace spk {

namespace {

// Context for the cmy_to_log_xyz spectral integral (scanning.py::cmy_to_log_xyz):
// the closure over the profile's channel_density / base_density, the scan
// illuminant and its normalization. Shared by the per-pixel direct path AND the
// LUT-build callback so the LUT samples EXACTLY the same transform.
struct CmyToLogXyzCtx {
    const float* channel_density;  // (S*3,) row-major [l*3 + k]
    const float* base_density;     // (S,)
    const float* illum;            // (S,) scan illuminant
    const float (*cmf)[3];         // (S,3) CIE 1931 CMFs
    int S;
    double inv_norm;               // 1 / normalization
};

// One cmy triple -> log_xyz (a 3-vector). Mirrors scanning.py::cmy_to_log_xyz for
// a single pixel: spectral density -> 10^-D * illuminant (NaN->0) -> XYZ integral
// / normalization -> log10(fmax(xyz, 0) + 1e-10). The direct path's steps 1-4 are
// byte-for-byte this routine, so the LUT samples the identical transform.
inline void cmy_to_log_xyz(const CmyToLogXyzCtx& ctx, const double cmy[3],
                           double log_xyz[3]) {
    const double c0 = cmy[0], c1 = cmy[1], c2 = cmy[2];
    double X = 0.0, Y = 0.0, Z = 0.0;
    for (int l = 0; l < ctx.S; ++l) {
        const float* cd = ctx.channel_density + static_cast<size_t>(l) * 3;
        double spectral = c0 * static_cast<double>(cd[0]) +
                          c1 * static_cast<double>(cd[1]) +
                          c2 * static_cast<double>(cd[2]) +
                          static_cast<double>(ctx.base_density[l]);
        double w = std::pow(10.0, -spectral) * static_cast<double>(ctx.illum[l]);
        if (std::isnan(w)) w = 0.0;
        X += w * ctx.cmf[l][0];
        Y += w * ctx.cmf[l][1];
        Z += w * ctx.cmf[l][2];
    }
    log_xyz[0] = std::log10(std::fmax(X * ctx.inv_norm, 0.0) + 1e-10);
    log_xyz[1] = std::log10(std::fmax(Y * ctx.inv_norm, 0.0) + 1e-10);
    log_xyz[2] = std::log10(std::fmax(Z * ctx.inv_norm, 0.0) + 1e-10);
}

// build_lut_3d callback adapter.
void cmy_to_log_xyz_fn(const double in[3], double out[3], void* ctx) {
    cmy_to_log_xyz(*static_cast<const CmyToLogXyzCtx*>(ctx), in, out);
}

}  // namespace

void scan(const Profile& film, const ScanningParams& params,
          const float* density_cmy, int width, int height, float* rgb_out) {
    const int npix = width * height;
    const int S = film.n_samples;  // == kSpectralSamples (81) for bundled profiles

    // Linear output-space RGB (pre-unsharp, pre-CAT02, pre-CCTF). Kept in float64
    // to match scanning.py, which carries the whole chain at NumPy double
    // precision and only stores float32 at the very end.
    const bool do_unsharp =
        params.unsharp_sigma > 0.0 && params.unsharp_amount > 0.0;
    std::vector<double> lin_rgb(static_cast<size_t>(npix) * 3);

    // Scan illuminant + constants. For the scan_film route the scan illuminant is
    // the film's viewing illuminant (D50 here). These mirror scanning.py:
    //   normalization = sum(scan_illuminant * ybar)
    const float* illum = kIlluminantD50;
    const double norm = kNormD50;

    // Viewing glare (print route only). scanning.py::_density_to_rgb adds
    //   xyz += glare_amount[:,:,None] * illuminant_xyz[None,None,:]
    // in XYZ space, where illuminant_xyz = contract("k,kl->l", scan_illuminant,
    // CMFS) / normalization. We precompute the per-pixel glare field and the
    // illuminant XYZ once; the field is added to each pixel's XYZ before the
    // XYZ->RGB matrix. Glare is stochastic (model/glare.py draws np.random.randn
    // per pixel) so the result is NOT bit-exact vs the oracle; it is OFF by default
    // (glare_active == false), preserving the deterministic goldens.
    const bool do_glare =
        params.glare_active && !params.scan_film && params.glare_percent > 0.0f;
    std::vector<float> glare_field;
    double illuminant_xyz[3] = {0.0, 0.0, 0.0};
    if (do_glare) {
        for (int l = 0; l < S; ++l) {
            double w = static_cast<double>(illum[l]) / norm;
            illuminant_xyz[0] += w * kCieCmf1931[l][0];
            illuminant_xyz[1] += w * kCieCmf1931[l][1];
            illuminant_xyz[2] += w * kCieCmf1931[l][2];
        }
        glare_field.assign(static_cast<size_t>(npix), 0.0f);
        compute_random_glare_amount(params.glare_percent, params.glare_roughness,
                                    params.glare_blur, width, height,
                                    params.glare_seed, glare_field.data());
    }

    const double inv_norm = 1.0 / norm;

    // OPT-IN scanner 3D-LUT acceleration (params.use_lut, default false). Mirrors
    // scanning.py::_density_to_rgb routing the per-pixel cmy_to_log_xyz spectral
    // integral through SpectralLUTService.spectral_compute_scanner(use_lut=...):
    // when on, a per-channel uniform 3D LUT is built over [data_min, data_max] at
    // params.lut_resolution steps (utils.lut.compute_with_lut / _create_lut_3d) and
    // the density image is interpolated with the PCHIP path (kernels/lut3d,
    // apply_lut_3d default) instead of evaluating the spectral integral per pixel.
    // The LUT covers EXACTLY the density_cmy -> log_xyz step; everything after
    // (10^log_xyz, glare, XYZ->RGB) is shared with the direct path. Interpolation is
    // NOT bit-exact vs the direct evaluation (documented ~5e-5), so it is OPT-IN and
    // the default path (use_lut == false) never even constructs the LUT.
    //
    // Domain bounds (scanning.py::_density_to_rgb):
    //   scan_film : data_min = -film_render.grain.density_min,
    //               data_max =  np.nanmax(film.data.density_curves, axis=0)
    //   print scan: data_min =  np.nanmin(print.data.density_curves, axis=0),
    //               data_max =  np.nanmax(print.data.density_curves, axis=0)
    std::vector<double> lut_log_xyz;  // (npix*3) when use_lut, else empty
    if (params.use_lut) {
        // Per-channel domain bounds from the (passed) profile's density_curves.
        double xmin[3], xmax[3];
        const int N = film.n_density_pts;
        double cmax[3] = {-INFINITY, -INFINITY, -INFINITY};
        double cmin[3] = {INFINITY, INFINITY, INFINITY};
        for (int nrow = 0; nrow < N; ++nrow) {
            const float* dc = film.density_curves.data() + static_cast<size_t>(nrow) * 3;
            for (int c = 0; c < 3; ++c) {
                double v = static_cast<double>(dc[c]);
                if (!std::isnan(v)) {  // np.nanmax / np.nanmin
                    if (v > cmax[c]) cmax[c] = v;
                    if (v < cmin[c]) cmin[c] = v;
                }
            }
        }
        for (int c = 0; c < 3; ++c) {
            if (params.scan_film) {
                xmin[c] = -params.grain_density_min[c];
                xmax[c] = cmax[c];
            } else {
                xmin[c] = cmin[c];
                xmax[c] = cmax[c];
            }
        }

        // Clamp the resolution to a sane band (compute_with_lut needs steps >= 2 to
        // form a non-degenerate grid; cap to bound build cost steps^3).
        int steps = params.lut_resolution;
        if (steps < 2) steps = 2;
        if (steps > 192) steps = 192;

        // Build the LUT by sampling the SAME cmy_to_log_xyz transform the direct
        // path evaluates (shared via cmy_to_log_xyz_fn) over [xmin, xmax].
        CmyToLogXyzCtx ctx;
        ctx.channel_density = film.channel_density.data();
        ctx.base_density = film.base_density.data();
        ctx.illum = illum;
        ctx.cmf = kCieCmf1931;
        ctx.S = S;
        ctx.inv_norm = inv_norm;
        Lut3D lut =
            build_lut_3d(xmin, xmax, steps, {}, &cmy_to_log_xyz_fn, &ctx);

        // Interpolate the whole density image (compute_with_lut: normalize by
        // (data - xmin)/(xmax - xmin) then PCHIP-interpolate) -> per-pixel log_xyz.
        lut_log_xyz.resize(static_cast<size_t>(npix) * 3);
        std::vector<double> dens_d(static_cast<size_t>(npix) * 3);
        for (size_t i = 0; i < dens_d.size(); ++i)
            dens_d[i] = static_cast<double>(density_cmy[i]);
        apply_lut_3d_pchip(lut, dens_d.data(), width, height, lut_log_xyz.data());
    }

    // The Python reference computes the whole chain in float64 (NumPy default for
    // the profile arrays) and only stores float32 at the very end. To reproduce it
    // bit-for-bit we mirror that: spectral density, light, the XYZ integral, the
    // matrix product and the CCTF are all done in double; only the final write is
    // float32.
    parallel_for(0, npix, [&](int lo, int hi) {
    for (int p = lo; p < hi; ++p) {
        double xyz[3];
        if (params.use_lut) {
            // 1-4 replaced by the LUT-interpolated log_xyz (opt-in path). The
            // 10^log_xyz round-trip below is identical to the direct path.
            const double* lx = lut_log_xyz.data() + static_cast<size_t>(p) * 3;
            xyz[0] = std::pow(10.0, lx[0]);
            xyz[1] = std::pow(10.0, lx[1]);
            xyz[2] = std::pow(10.0, lx[2]);
        } else {
            const float* dcmy = density_cmy + static_cast<size_t>(p) * 3;
            const double c0 = static_cast<double>(dcmy[0]);
            const double c1 = static_cast<double>(dcmy[1]);
            const double c2 = static_cast<double>(dcmy[2]);

            // 1. density_cmy -> spectral density (emulsion.compute_density_spectral):
            //    spectral[l] = sum_k dcmy[k] * channel_density[l,k] + base_density[l].
            //    NaN channel_density / base entries propagate as NaN here.
            // 2. light = density_to_light(spectral, illuminant): 10^(-D) * illuminant,
            //    NaN -> 0 (utils/conversions.density_to_light).
            // 3. xyz = sum_l light[l] * CMF[l] / normalization. scanning.py uses a
            //    plain einsum over wavelengths with NO 5 nm interval factor, so we
            //    integrate without dlambda (the interval cancels against the
            //    normalization's own missing interval).
            double X = 0.0, Y = 0.0, Z = 0.0;
            for (int l = 0; l < S; ++l) {
                const float* cd = film.channel_density.data() + static_cast<size_t>(l) * 3;
                double spectral = c0 * static_cast<double>(cd[0]) +
                                  c1 * static_cast<double>(cd[1]) +
                                  c2 * static_cast<double>(cd[2]) +
                                  static_cast<double>(film.base_density[l]);
                double w = std::pow(10.0, -spectral) * static_cast<double>(illum[l]);
                if (std::isnan(w)) w = 0.0;
                X += w * kCieCmf1931[l][0];
                Y += w * kCieCmf1931[l][1];
                Z += w * kCieCmf1931[l][2];
            }
            // 4. log_xyz = log10(max(xyz,0) + 1e-10); xyz = 10^log_xyz. The log/exp
            //    round-trip just floors at 1e-10 and clamps negatives; reproduce that
            //    exactly so float rounding matches the reference.
            xyz[0] = std::pow(10.0, std::log10(std::fmax(X * inv_norm, 0.0) + 1e-10));
            xyz[1] = std::pow(10.0, std::log10(std::fmax(Y * inv_norm, 0.0) + 1e-10));
            xyz[2] = std::pow(10.0, std::log10(std::fmax(Z * inv_norm, 0.0) + 1e-10));
        }

        // (black/white XYZ correction skipped: negative film scan route.)

        // add_glare (print route only): xyz += glare[p] * illuminant_xyz. On the
        // scan_film route glare is None (do_glare false) so this is skipped.
        if (do_glare) {
            double g = static_cast<double>(glare_field[p]);
            xyz[0] += g * illuminant_xyz[0];
            xyz[1] += g * illuminant_xyz[1];
            xyz[2] += g * illuminant_xyz[2];
        }

        // 5. XYZ -> output RGB (linear, in io.output_color_space), with CAT02
        //    from the D50 scan whitepoint to the space whitepoint baked into the
        //    matrix (colour.XYZ_to_RGB(..., illuminant=D50_xy)).
        const double* M = kXYZ_to_RGB[params.output_color_space];
        double* lin = lin_rgb.data() + static_cast<size_t>(p) * 3;
        for (int c = 0; c < 3; ++c) {
            lin[c] = M[c * 3 + 0] * xyz[0] +
                     M[c * 3 + 1] * xyz[1] +
                     M[c * 3 + 2] * xyz[2];
        }
    }
    });

    // Scanner lens blur (scanner.lens_blur, in pixels): a per-channel 2D Gaussian
    // applied in the linear output space BEFORE the unsharp mask, matching
    // scanning.py::_apply_blur_and_unsharp (apply_gaussian_blur then
    // apply_unsharp_mask). apply_gaussian_blur gates on sigma > 0 and uses a scalar
    // sigma broadcast across the 3 channels. Default lens_blur == 0 => skipped, so
    // the existing goldens stay bit-exact.
    if (params.lens_blur > 0.0) {
        double sg[3] = {params.lens_blur, params.lens_blur, params.lens_blur};
        gaussian_blur_per_channel_d(lin_rgb.data(), width, height, 3, sg);
    }

    // Scanner unsharp mask (spatial branch): rgb += amount * (rgb - G(sigma)*rgb),
    // in the linear output space, after the lens blur and before the CAT02
    // round-trip + CCTF. (apply_gaussian_blur / apply_unsharp_mask in
    // model/diffusion.py.)
    if (do_unsharp) {
        const size_t total = static_cast<size_t>(npix) * 3;
        std::vector<double> blur(lin_rgb);
        double sg[3] = {params.unsharp_sigma, params.unsharp_sigma,
                        params.unsharp_sigma};
        gaussian_blur_per_channel_d(blur.data(), width, height, 3, sg);
        const double amt = params.unsharp_amount;
        for (size_t i = 0; i < total; ++i)
            lin_rgb[i] = lin_rgb[i] + amt * (lin_rgb[i] - blur[i]);
    }

    // 6. CCTF encode + clip per pixel (scanning._apply_cctf_encoding_and_clip).
    //    When output_cctf_encoding is on, colour.RGB_to_RGB(cs, cs, "CAT02")
    //    applies the near-identity round-trip matrix *and* the per-space CCTF;
    //    when off, neither is applied (only the clip). The clip preserves NaN
    //    (np.clip semantics), which is load-bearing for Adobe RGB gamut
    //    excursions where the gamma encode yields NaN for negative linear RGB.
    const double* Mc = kRGB_to_RGB_CCTF[params.output_color_space];
    const spk_color_space cs = params.output_color_space;
    parallel_for(0, npix, [&](int lo, int hi) {
    for (int p = lo; p < hi; ++p) {
        const double* lin = lin_rgb.data() + static_cast<size_t>(p) * 3;
        float* out = rgb_out + static_cast<size_t>(p) * 3;
        for (int c = 0; c < 3; ++c) {
            double v;
            if (params.output_cctf_encoding) {
                double adapted = Mc[c * 3 + 0] * lin[0] +
                                 Mc[c * 3 + 1] * lin[1] +
                                 Mc[c * 3 + 2] * lin[2];
                v = output_cctf_encode(cs, adapted);
            } else {
                v = lin[c];
            }
            // np.clip(v, 0, 1): preserve NaN, clamp finite to [0, 1].
            if (v < 0.0) v = 0.0;
            else if (v > 1.0) v = 1.0;
            out[c] = static_cast<float>(v);
        }
    }
    });
}

}  // namespace spk
