/*
 * SpectraFilm for Android — native engine: auto-exposure metering stage.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/utils/autoexposure.py::measure_autoexposure_ev
 * and the small_preview downscale it meters on
 * (spektrafilm/runtime/services/resize.py::ResizingService.small_preview), as
 * invoked by FilmingStage.auto_exposure inside pipeline._preprocess BEFORE
 * crop_and_rescale:
 *
 *   def auto_exposure(self, image):
 *       if self._camera.auto_exposure:
 *           small_preview = self._resize_service.small_preview(image)
 *           ev = measure_autoexposure_ev(small_preview, input_color_space,
 *                                        input_cctf_decoding, method=method)
 *           return image * 2 ** ev
 *       return image
 *
 * measure_autoexposure_ev meters the LUMINANCE Y of the preview (colour.RGB_to_XYZ
 * channel 1) under one of several metering patterns, normalises by 0.184 (the
 * 18%-ish midgray the whole engine balances to), and returns
 * exposure_compensation_ev = -log2(exposure). The full-resolution image is then
 * scaled by 2**ev (a single global gain).
 *
 * DEFAULTS / PARITY: the schema default is auto_exposure=True,
 * auto_exposure_method="center_weighted". The parity goldens, however, are
 * generated with auto_exposure=False (gen_goldens.py forces it off for
 * determinism), so this stage is a STRICT no-op when auto_exposure is off and the
 * existing goldens stay byte-identical. A non-default golden (auto_exposure on)
 * exercises the metering path.
 *
 * The metering runs entirely in float64 (numpy double), matching the rest of the
 * engine; only the final scaled buffer is consumed downstream.
 */
#ifndef SPK_RUNTIME_STAGES_AUTOEXPOSURE_H
#define SPK_RUNTIME_STAGES_AUTOEXPOSURE_H

#include <vector>

namespace spk {

// Input color spaces for the auto-exposure luminance transform. Only the values
// the engine actually feeds (io.input_color_space) are modelled; the luminance
// row of colour.RGB_to_XYZ(cs, apply_cctf_decoding=False) is baked per space.
// ProPhoto RGB is the spektrafilm default input space.
enum class AeColorSpace {
    kProPhotoRGB,
    kSRGB,
};

// Metering pattern, mirroring CameraParams.auto_exposure_method. The string ids
// are the schema values; kAverage..kHighlightWeighted map 1:1.
enum class AeMethod {
    kCenterWeighted,  // "center_weighted" (schema default)
    kAverage,         // "average"
    kMedian,          // "median"
    kPartial,         // "partial"
    kMatrix,          // "matrix"
    kMultiZone,       // "multi_zone"
    kHighlightWeighted,  // "highlight_weighted"
};

// Map a schema method string to AeMethod. Unknown strings -> kCenterWeighted's
// caller should treat an unrecognised method as "exposure = 1.0" (ev = 0); use
// ae_method_from_string's bool return to detect that. Returns true if matched.
bool ae_method_from_string(const char* s, AeMethod* out);

// Port of measure_autoexposure_ev: meter `image` (interleaved RGB float64,
// w*h*3, scene-linear in `cs`, cctf decoding off by default) under `method` and
// return the exposure compensation in EV (= -log2(exposure)). +/-inf collapses
// to 0.0 EV exactly like the Python (np.isinf guard). `apply_cctf_decoding` is
// accepted for signature parity but the engine always feeds linear input
// (input_cctf_decoding defaults False); when true the sRGB/ProPhoto inverse EOTF
// is applied before the luminance integral (matches colour's apply_cctf_decoding).
double measure_autoexposure_ev(const double* image, int w, int h,
                               AeColorSpace cs, bool apply_cctf_decoding,
                               AeMethod method, bool known_method);

// Port of ResizingService.small_preview(image, max_size=256): if the long edge
// exceeds `max_size`, skimage.transform.rescale(image, max_size/long_edge,
// channel_axis=2, order=0) (nearest-neighbour, grid_mode=True); otherwise the
// image is returned unchanged. Writes the (possibly downscaled) RGB into `out`
// and its geometry into out_w/out_h. With long_edge <= max_size this is a copy.
void small_preview(const double* in, int w, int h, int max_size,
                   std::vector<double>* out, int* out_w, int* out_h);

// Full FilmingStage.auto_exposure: meter small_preview(image) and scale the
// full-resolution `image` in place by 2**ev. `image` is interleaved RGB float64
// (w*h*3). No-op semantics are the caller's responsibility (only call when
// auto_exposure is on). Returns the EV applied.
double apply_auto_exposure(double* image, int w, int h, AeColorSpace cs,
                           bool apply_cctf_decoding, AeMethod method,
                           bool known_method, int preview_max_size = 256);

}  // namespace spk

#endif  // SPK_RUNTIME_STAGES_AUTOEXPOSURE_H
