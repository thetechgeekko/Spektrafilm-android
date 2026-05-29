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

// Digested parameters the printing (enlarger) stage needs, under the print_portra
// parity toggles (auto-exposure off, spatial + stochastic effects off, preflash
// off, print_exposure==1, normalize_print_exposure & print_exposure_compensation
// both on so the midgray normalisation reduces to the single midgray factor).
//
// Mirrors the relevant digested RuntimePhotoParams fields:
//   - filtered_illuminant: the enlarger light source after the dichroic Y/M/C
//     filters (color_enlarger with the digested neutral CC values + shifts==0),
//     i.e. enlarger_service.enlarger_filtered_illuminant(standard_illuminant(
//     enlarger.illuminant)). 81 samples on the working shape.
//   - exposure_factor_midgray: the scalar midgray normalisation factor returned
//     by PrintingStage._compute_exposure_factor_midgray (= factor_midgray_comp
//     when both normalize + compensation are on). Pulled from the oracle because
//     it derives from the full filming midgray chain (rgb_to_raw of 0.184 gray).
//   - print_exposure / bw_exposure_correction: scalar multipliers in expose()
//     (1.0 each under the parity defaults: no black/white correction).
//   - density_curve_gamma: print_render.density_curve_gamma (scalar broadcast).
struct PrintingParams {
    double filtered_illuminant[81] = {0.0};
    double exposure_factor_midgray = 1.0;
    double print_exposure = 1.0;
    double bw_exposure_correction = 1.0;
    float density_curve_gamma[3] = {1.0f, 1.0f, 1.0f};
};

// Enlarger illuminant on the 81-band working shape for a given illuminant id.
// Currently only the default "TH-KG3" (tungsten-halogen blackbody 3400K through
// the Schott KG3 heat filter, mean-normalised) is baked; returns nullptr for
// unknown ids. Equal to illuminants.standard_illuminant(id).
const double* enlarger_illuminant(const char* illuminant_id);

// Build digested printing params for the print_portra parity case. `neutral_cc`
// are the digested neutral C/M/Y filter values (Kodak CC units, from
// neutral_print_filters.json for the film/print/illuminant triple); `enl_illum`
// is enlarger_illuminant(id). The filtered illuminant is computed exactly via
// color_enlarger (shifts==0). `exposure_factor_midgray` and `gamma` are passed in
// from the oracle-derived digested values.
PrintingParams digest_printing_params(const double neutral_cc[3],
                                      const double* enl_illum,
                                      double exposure_factor_midgray,
                                      float gamma);

}  // namespace spk

#endif  // SPK_RUNTIME_PARAMS_H
