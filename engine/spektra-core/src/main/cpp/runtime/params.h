/*
 * SpectraFilm for Android — native engine: minimal digested filming params.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. A tiny subset of spektrafilm/runtime/params_builder.py
 * (init_params + digest_params) sufficient for the filming stage under the
 * parity goldens' toggles (auto-exposure off, spatial+stochastic effects off).
 *
 * Only the constants the filming stage actually consumes are reproduced; the
 * exact numeric values are pulled from the Python oracle's digested params for
 * the scan_portra case.
 */
#ifndef SPK_RUNTIME_PARAMS_H
#define SPK_RUNTIME_PARAMS_H

#include "model/couplers.h"

namespace spk {

// Input color space selector for the rgb->raw chromaticity transform. Each maps
// to a baked RGB->XYZ matrix (apply_cctf_decoding=False, CAT02 to the film's
// reference illuminant) inside the filming stage.
enum class InputColorSpace {
    kProPhotoRGB,   // "ProPhoto RGB" (linear) — the spektrafilm default input.
};

// Digested parameters the filming stage needs. Mirrors the relevant fields of
// the Python digested RuntimePhotoParams for the parity toggles.
struct FilmingParams {
    InputColorSpace input_color_space = InputColorSpace::kProPhotoRGB;
    bool input_cctf_decoding = false;

    // Exposure (auto-exposure off, exposure_compensation_ev == 0 under goldens).
    double exposure_compensation_ev = 0.0;

    // Density-curve gamma (scalar broadcast to all three channels).
    float density_curve_gamma[3] = {1.0f, 1.0f, 1.0f};

    DirCouplersParams dir_couplers;  // filled by digest for the film type.
};

// Build digested filming params for a film type, mirroring
// digest_params -> _apply_film_specifics under the parity toggles
// (deactivate_spatial_effects=True => dir_couplers.diffusion_size_um=0).
// `is_negative` selects the negative/positive coupler gamma matrix.
FilmingParams digest_filming_params(bool is_negative);

}  // namespace spk

#endif  // SPK_RUNTIME_PARAMS_H
