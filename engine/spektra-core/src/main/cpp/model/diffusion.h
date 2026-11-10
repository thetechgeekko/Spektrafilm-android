/*
 * Spektrafilm for Android — native engine: in-emulsion scatter + halation +
 * the optical diffusion-filter (Black Pro-Mist etc.) PSF stage.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/diffusion.py:
 *   - apply_halation_um (in-emulsion scatter + back-reflection halation), and
 *   - apply_diffusion_filter_um (the optical diffusion-filter PSF: a per-family
 *     core/halo/bloom sum of 2D isotropic exponentials, energy-conserving convex
 *     blend (1-p_s)*image + p_s*(K_s * image)). The camera/enlarger
 *     diffusion_filter params drive it (issue #6 exposed-but-inert params).
 *
 * apply_halation_um / apply_diffusion_filter_um run on the float64 pre-log
 * irradiance `raw` (the spektrafilm pipeline carries the image as float64).
 *
 * Diffusion-filter convolution note: the oracle uses scipy.signal.fftconvolve
 * (mode='same') on a numpy-'reflect'-padded image. fftconvolve is a plain linear
 * convolution computed in the frequency domain; a direct double-precision spatial
 * convolution is numerically equivalent (verified |Δ| ~ 1e-16 vs the oracle, far
 * under the 1e-4 / 1e-5 parity tolerance), so this port uses a direct convolution
 * in double precision and needs no FFT infrastructure.
 */
#ifndef SPK_MODEL_DIFFUSION_H
#define SPK_MODEL_DIFFUSION_H

#include <vector>

namespace spk {

struct DiffusionFarField;

// Halation parameters, mirroring the digested
// spektrafilm.runtime.params_schema.HalationParams fields consumed by
// apply_halation_um. Per-channel arrays are RGB-ordered.
struct HalationParams {
    bool active = false;

    // Highlight boost (consumed by apply_highlight_boost; boost_ev==0 default -> no-op).
    double boost_ev = 0.0;
    double boost_range = 0.3;
    double protect_ev = 4.0;
    // Banded renders only (spektra.cpp run_banded). The boost curve is scaled by
    // the brightest pre-boost value in the WHOLE frame, so a band must not derive
    // it from its own rows. > 0: use this as that maximum instead of scanning
    // `raw`. Left at 0 everywhere else, which keeps every other route unchanged.
    double boost_frame_max = 0.0;
    // Banded pre-pass only: when non-null, expose() computes the pre-boost
    // irradiance, stores its maximum here, and stops before the boost (the
    // log_raw output is NOT written). run_banded max-reduces it over the bands.
    double* boost_max_out = nullptr;

    // In-emulsion scatter (core Gaussian + exponential tail).
    double scatter_amount = 1.0;
    double scatter_spatial_scale = 1.0;
    double scatter_core_um[3] = {0.0, 0.0, 0.0};
    double scatter_tail_um[3] = {0.0, 0.0, 0.0};
    double scatter_tail_weight[3] = {0.0, 0.0, 0.0};

    // Back-reflection halation (multi-bounce Gaussian sum).
    double halation_amount = 1.0;
    double halation_spatial_scale = 1.0;
    double halation_strength[3] = {0.0, 0.0, 0.0};
    double halation_first_sigma_um[3] = {0.0, 0.0, 0.0};
    int halation_n_bounces = 0;
    double halation_bounce_decay = 0.5;
    bool halation_renormalize = true;
};

// apply_highlight_boost: in-place on the float64 raw irradiance image `raw`, shape
// (h, w, 3) row-major channel-interleaved. Ports
// spektrafilm/utils/numba_boost_hightlights.py::boost_highlights, which
// FilmingStage.expose calls on `raw` right after the exposure-compensation scale and
// BEFORE the diffusion filter / lens blur / halation (filming.py:58-60), with the
// default midgray = 0.184. The curve lifts highlights above
// raw_x0 = clip(midgray * 2^protect_ev, 0, max(raw)) by an exponential set so the
// brightest pixel gains `boost_ev` stops:
//   a = 28^(1 - boost_range);  x0 = raw_x0 / max_raw
//   k = (2^boost_ev - 1) / (exp(a(1-x0)) - a(1-x0) - 1)
//   raw > raw_x0:  raw += k*max_raw*(exp(a*dx) - a*dx - 1),  dx = (raw - raw_x0)/max_raw
// It is NOT part of the spatial branch (the oracle's digest does not zero boost_ev
// under deactivate_spatial_effects), so it fires whenever boost_ev > 0. boost_ev <= 0
// (schema/UI default 0) is a strict identity, so default params stay bit-exact. The
// global max(raw) reduction is order-independent, so the result is thread-invariant.
// `allow_gpu` offers the pass to gpu::highlight_boost first (#219). The GPU
// route needs a frame-wide maximum, so it is two dispatches with the scalars
// derived on the host in f64 between them; on any refusal -- including the
// identities the CPU short-circuits -- nothing is written and the CPU map runs.
// np.max(raw) over a (h, w, 3) irradiance image, as apply_highlight_boost scales
// by it. Parallel and byte-identical for any worker count (max is associative).
double highlight_boost_max(const double* raw, int w, int h);

void apply_highlight_boost(double* raw, int w, int h, const HalationParams& params,
                           bool allow_gpu = false);

// apply_halation_um: in-place on the float64 raw irradiance image `raw`, shape
// (h, w, 3) row-major channel-interleaved. `pixel_size_um` converts the µm
// spatial parameters to pixel sigmas (sigma_px = um * spatial_scale /
// pixel_size_um). If `params.active` is false the image is left untouched. The
// highlight boost is a separate, earlier pass (apply_highlight_boost) in
// FilmingStage.expose; apply_halation_um covers Step 2 (scatter) + Step 3 (halation).
void apply_halation_um(double* raw, int w, int h, const HalationParams& params,
                       double pixel_size_um);

// Diffusion-filter family selector. Values mirror the keys of
// _DIFFUSION_FILTER_SHAPES in spektrafilm/model/diffusion.py.
enum class DiffusionFamily {
    kGlimmerglass = 0,
    kBlackProMist = 1,   // spektrafilm DiffusionFilterParams default family.
    kProMist = 2,
    kCinebloom = 3,
};

// Optical diffusion-filter params, mirroring the consumed fields of
// spektrafilm.runtime.params_schema.DiffusionFilterParams. Defaults match the
// schema so a default-constructed instance is a strict no-op:
//   active=false  -> apply_diffusion_filter_um returns the image untouched.
struct DiffusionFilterParams {
    bool active = false;                                  // schema default false.
    DiffusionFamily family = DiffusionFamily::kBlackProMist;  // "black_pro_mist".
    double strength = 0.5;        // commercial filter stop (interpolated).
    double spatial_scale = 1.0;   // multiplier on image-plane PSF widths.
    double halo_warmth = 0.0;     // additive bias to the family halo-warmth axis.
    // Per-group fine-tune multipliers (advanced). 1.0 = use the family preset.
    double core_intensity = 1.0;
    double core_size = 1.0;
    double halo_intensity = 1.0;
    double halo_size = 1.0;
    double bloom_intensity = 1.0;
    double bloom_size = 1.0;
    // Banded renders above the whole-frame capacity only (spektra.cpp
    // run_banded). Non-null: the image is one band of a larger frame starting at
    // frame row band_frame_y0, and the filter runs the near/far split below
    // instead of the whole-frame convolution. band_accumulate = this is the
    // pre-pass that feeds the band's rows into the coarse frame instead.
    DiffusionFarField* band_far = nullptr;
    bool band_accumulate = false;
    int band_frame_y0 = 0;
    // Band-local rows [band_core_begin, band_core_end) the accumulating pass
    // feeds (the enlarger pass renders bands with their margins; 0/0 = all rows).
    int band_core_begin = 0, band_core_end = 0;
};

// apply_diffusion_filter_um: in-place on the float64 raw irradiance image `raw`,
// shape (h, w, 3) row-major channel-interleaved. Implements the energy-conserving
// convex combination
//     E_out = (1 - p_s) * E_in  +  p_s * (K_s * E_in)
// with p_s derived from strength+family and K_s the per-channel diffusion-filter
// PSF (the halo is colour-tinted via an energy-conserving warmth redistribution).
// `pixel_size_um` converts the image-plane µm widths to pixels. No-op (image left
// untouched) when params.active is false, strength<=0, spatial_scale<=0, or the
// derived p_s<=0 — exactly mirroring spektrafilm.model.diffusion.apply_diffusion_filter_um.
// Observability for the FFT convolution path (see diffusion.cpp). Counts the
// renders where the cost model chose the FFT but the transform buffers could not
// be allocated, so the stage silently ran the direct O(w*h*ks^2) loop instead --
// correct output, but potentially ~100x slower with nothing in the timing to say
// so. Read it after any measurement of this stage, and especially after changing
// SPK_DIFFUSION_FFT_MAX: a nonzero count means the number you just measured is
// the DIRECT path, not the transform size you thought you were testing.
// Current-thread and monotonic; reset it yourself around a measurement.
unsigned long long diffusion_fft_fallbacks();
void diffusion_reset_fft_fallbacks();

// `allow_gpu_fft` offers the FFT branch to gpu::fft_convolve first (#216) --
// the SAME operator in f32 on the device, not the Gaussian-mixture surrogate
// that rides allow_gpu_diffusion_filter. It is a Fast GPU route: on any refusal
// the pass falls through to the CPU transform with nothing written, so the
// output is either the device's f32 answer or the host's f64 one, never a mix.
void apply_diffusion_filter_um(double* raw, int w, int h,
                               const DiffusionFilterParams& params,
                               double pixel_size_um,
                               bool allow_gpu_fft = false);

// ---------------------------------------------------------------------------
// BANDED DIFFUSION FILTER (frames above the whole-frame capacity).
//
// The filter's kernel reaches ~0.36 of the frame's short side (8 x the widest
// bloom lambda, capped at min(w,h)/2-1), so a band cannot hold it: at 200 MP
// it is ~11,800 px wide. Above the device's whole-frame limit the kernel is
// split by radius instead:
//
//   near(r) = core(r) * gc(r) + (halo+bloom)(r) * g(r)              band
//   far(r)  = core(r) * (1 - gc(r)) + (halo+bloom)(r) * (1 - g(r))  coarse
//   gc(r)   = exp(-(r / (0.8 near radius))^8): flat-topped, so the core stays
//             exact and only the tail of a core widened by core_size /
//             spatial_scale moves to the far part (the split keeps the mass)
//   g(r)    = exp(-(r/rt)^2),  rt = 10 f
//
// The near part is truncated at 3.5 rt (where g is e^-12) and convolved per band at full
// resolution (the band margin covers it). The far part has a hole where the
// kernel's cusp is, so it is smooth at the scale of f: it runs on a copy of the
// whole frame box-downsampled by f (4..8), with the coarse kernel's mass pinned
// to the fine one's, and is read back with a bilinear interpolation. Both are
// normalised by the full fine kernel's sum, as the whole-frame filter is.
//
// NOT bit-exact. Measured against the whole-frame filter (NumPy prototype,
// Black Pro-Mist / Pro-Mist / Cinebloom at 12, 50 and 200 MP pixel sizes):
// worst |log10(banded/whole)| 9.6e-4 (0.22 %), RMS <= 9.4e-5. So it is used
// only where the whole-frame filter cannot run at all; test_band_render gates
// the C++ against apply_diffusion_filter_um.
// ---------------------------------------------------------------------------
struct DiffusionFarField {
    int frame_width = 0, frame_height = 0;
    int factor = 0;        // f: fine pixels per coarse cell side
    int radius = 0;        // R: the whole-frame filter's radius (fine px)
    int near_radius = 0;   // near kernel half-width (fine px)
    int coarse_radius = 0; // R / f
    int pad = 0;           // fine pixels of reflect padding around the frame
    int cells_x = 0, cells_y = 0;  // padded coarse grid
    double p_s = 0.0;
    std::vector<std::vector<double>> near_kernel;    // [3][(2*near_radius+1)^2]
    std::vector<std::vector<double>> coarse_kernel;  // [3][(2*coarse_radius+1)^2]
    std::vector<float> accum;   // pass 0: cells_y*cells_x*3 fine-pixel sums
    int out_w = 0, out_h = 0;
    std::vector<double> field;  // out_h*out_w*3: far term at the coarse centres
    bool ready = false;
};

// The coarse factor diffusion_far_init picks for a frame (0 = auto).
int diffusion_far_factor(int frame_w, int frame_h);

// Near-kernel half-width the banded filter needs for this frame, i.e. the band
// margin it requires; 0 when the filter is inactive.
int diffusion_band_near_radius(const DiffusionFilterParams& params, double pixel_size_um,
                               int frame_w, int frame_h, int factor = 0);

// Set up `far` for a frame: kernels, normalisation and the empty coarse grid.
// Returns false when the filter is inactive (then nothing is banded for it).
bool diffusion_far_init(const DiffusionFilterParams& params, double pixel_size_um,
                        int frame_w, int frame_h, DiffusionFarField* far,
                        int factor = 0);

// Pass 0: add rows [frame_y0, frame_y0 + h) of the frame's pre-filter
// irradiance (w = frame width) to the coarse grid. Bands must partition the
// frame; each is added once, in any order fixed by the caller.
void diffusion_far_accumulate(const double* raw, int w, int h, int frame_y0,
                              DiffusionFarField* far);

// Convolve the coarse grid with the far kernel (frees the grid). `allow_gpu_fft`
// offers each channel to gpu::fft_convolve first (f32); a refused channel runs
// on the CPU transform.
void diffusion_far_finish(DiffusionFarField* far, bool allow_gpu_fft = false);

// Filter one band in place: rows [frame_y0, frame_y0 + h) of the frame, with
// at least near_radius rows of real frame above and below every row the caller
// keeps (or the frame edge). `allow_gpu_fft` offers the near convolution to
// gpu::fft_convolve first, exactly as apply_diffusion_filter_um does.
void apply_diffusion_filter_banded(double* raw, int w, int h, int frame_y0,
                                   const DiffusionFarField& far,
                                   bool allow_gpu_fft = false);


// ---------------------------------------------------------------------------
// Separable Gaussian-mixture approximation of the diffusion PSF (#213).
//
// The exact PSF above is a sum of 2D isotropic EXPONENTIALS,
// sum_k w_k exp(-r/l_k) / (2 pi l_k^2), which is not separable, so
// apply_diffusion_filter_um convolves it with an FFT. That is the single largest
// stage of a real export (13559 ms of 25319 on a 12.5 MP frame) and it is the one
// stage with no GPU implementation anywhere in the repo.
//
// A sum of GAUSSIANS is separable, and a separable blur is what a GPU can do
// cheaply -- and at a cost that stops growing with the radius once the wide
// components are run at a reduced resolution. So each exponential term is replaced
// by `terms` Gaussians and the whole PSF becomes one weighted set of separable
// blurs. The decomposition technique is adapted from chaert-s/spektrafilm-ofx
// (`makeDiffusionComponents`, `shaders/vulkan/SpektraDiffusion.comp`) at
// 86476afc5b077de77e2278e3658d1ba9309892a1, GPL-3.0-only; see
// docs/research/spektrafilm-ofx-port.md. Every PARAMETER here comes from this
// engine's own family configuration, not from OFX's -- only the fit does.
//
// THIS IS AN APPROXIMATION AND IT IS NOT THE EXPORT PATH. Fitting an exponential
// with Gaussians is worst exactly at r = 0, where the exponential has a cusp and
// every Gaussian is flat, and that cusp is the visible core of the bloom. Call
// diffusion_mixture_psf_error() to get the number for a given configuration rather
// than assuming a bound; the export keeps the FFT.
struct DiffusionGaussian {
    double sigma_px = 0.0;    // in PIXELS, already through spatial_scale/pixel_size
    double weight[3] = {0.0, 0.0, 0.0};  // per-channel, carries the halo warmth tint
};

// Build the mixture for `params`. Mirrors apply_diffusion_filter_um's own setup
// exactly: same resolve_family_cfg, same strength_to_scatter, same group expansion,
// same halo warmth redistribution, same lambda -> pixel conversion, and the same
// truncation radius, so the only difference from the exact path is the exp->Gauss
// fit itself.
//
// `terms` is the number of Gaussians per exponential term (>= 1). 3 reproduces the
// upstream OFX fit; more terms cost more blur passes and reduce the cusp error.
//
// Returns false (and leaves the outputs untouched) when the stage is a no-op --
// inactive, strength <= 0, spatial_scale <= 0, or a derived p_s <= 0 -- so a caller
// can use the return value as "is there anything to do at all".
// On success `*scatter_fraction` is p_s and `*radius_px` is the truncation radius.
bool build_diffusion_mixture(const DiffusionFilterParams& params, double pixel_size_um,
                             int w, int h, int terms,
                             std::vector<DiffusionGaussian>* out,
                             double* scatter_fraction, int* radius_px);

// Measure the mixture against the exact PSF on the SAME truncated, sum-normalised
// grid apply_diffusion_filter_um builds, so the number describes the fit and not a
// normalisation mismatch. Writes the per-channel worst absolute and RMS difference
// of the two normalised kernels. Returns false when the stage is a no-op.
bool diffusion_mixture_psf_error(const DiffusionFilterParams& params,
                                 double pixel_size_um, int w, int h, int terms,
                                 double* max_abs, double* rms);

}  // namespace spk

#endif  // SPK_MODEL_DIFFUSION_H
