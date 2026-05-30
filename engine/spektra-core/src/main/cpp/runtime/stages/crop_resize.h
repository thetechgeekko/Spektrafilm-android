/*
 * Spektrafilm for Android — native engine: crop / resize geometry stage.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports the geometry preprocessing performed by
 * spektrafilm/runtime/services/resize.py::ResizingService.crop_and_rescale,
 * which runs inside pipeline._preprocess BEFORE the filming stage:
 *
 *   pixel_size_um = film_format_mm * 1000 / max(H, W)
 *   if io.crop:
 *       image = crop_image(image, center=io.crop_center, size=io.crop_size)
 *   if io.upscale_factor != 1.0:
 *       pixel_size_um /= io.upscale_factor
 *       image = skimage.transform.rescale(image, upscale_factor,
 *                                         channel_axis=2, order=3)
 *
 * - crop_image (utils/crop_resize.py) is a pure integer-index slice with
 *   np.round rounding of the center/size (size is a fraction of the LONG side)
 *   and edge clamping; no resampling.
 * - rescale(order=3) is scipy.ndimage.zoom(order=3, mode='mirror',
 *   grid_mode=True) over the cubic B-spline prefiltered coefficients, followed
 *   by a clip to the input value range (skimage clip=True). It is implemented
 *   here as a separable per-axis cubic spline interpolation that is bit-exact
 *   (to fp round-off, ~1e-15) vs skimage/scipy — verified against the oracle.
 *
 * The DEFAULT params (crop=false, upscale_factor=1.0) make this stage a strict
 * no-op on the pixels: only pixel_size_um is computed (which it already was at
 * the call sites). This keeps the existing goldens byte-identical.
 *
 * SCOPE NOTE — anti-aliasing. The param is named `upscale_factor` and the
 * physically meaningful / UI-exposed use is upscaling (factor >= 1). skimage's
 * rescale enables a Gaussian anti-aliasing prefilter ONLY when an output
 * dimension shrinks (factor < 1); that path additionally runs
 * scipy.ndimage.gaussian_filter (a different kernel from the engine's
 * fast_gaussian_filter port) before the cubic zoom. This stage reproduces the
 * upscale path bit-exact; a downscale (factor < 1) is performed WITHOUT the
 * anti-aliasing prefilter, so it is NOT bit-exact vs skimage for factor < 1.
 * Bit-exact downscaling would require porting scipy's gaussian_filter and is
 * left for a follow-up if the UI ever exposes sub-unity factors.
 */
#ifndef SPK_RUNTIME_STAGES_CROP_RESIZE_H
#define SPK_RUNTIME_STAGES_CROP_RESIZE_H

#include <vector>

namespace spk {

// Parameters consumed by the crop/resize stage (mirrors IOParams' crop fields).
struct CropResizeParams {
    bool   crop = false;
    double crop_center[2] = {0.5, 0.5};  // (x, y), fractions of (W, H)
    double crop_size[2]   = {0.1, 0.1};  // (x, y), fractions of the LONG side
    double upscale_factor = 1.0;
};

// Apply crop_and_rescale to an interleaved RGB image (`in`, length w*h*3, any
// float-like — passed as double to match the Python float64 pipeline). On
// return `out` holds the processed image and `out_w`/`out_h` its new geometry.
//
// `pixel_size_um` is updated in place: callers pass in
// film_format_mm*1000/max(h,w); this divides it by upscale_factor when a
// rescale happens (matching ResizingService).
//
// With the default params this copies `in` to `out` unchanged and leaves
// out_w/out_h == w/h and pixel_size_um untouched.
void crop_and_rescale(const double* in, int w, int h,
                      const CropResizeParams& params,
                      std::vector<double>* out, int* out_w, int* out_h,
                      double* pixel_size_um);

// Bit-exact port of skimage.transform.rescale(image, factor, channel_axis=2,
// order=3) for an interleaved RGB image: scipy.ndimage.zoom(order=3,
// mode='mirror', grid_mode=True) + clip to the input [min, max]. Output size is
// max(round(factor * dim), 1) per spatial axis; channels preserved. Exposed for
// direct testing.
void rescale_cubic_rgb(const double* in, int w, int h, double factor,
                       std::vector<double>* out, int* out_w, int* out_h);

}  // namespace spk

#endif  // SPK_RUNTIME_STAGES_CROP_RESIZE_H
