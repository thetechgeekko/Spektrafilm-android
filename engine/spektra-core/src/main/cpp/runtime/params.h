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
#include "model/diffusion.h"

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

    // Spatial-effects toggle. When false (the spatial-OFF parity goldens) the
    // halation pass is skipped in expose() and the DIR-coupler correction is
    // applied pointwise (diffusion_size_um forced to 0). When true the halation
    // pass runs and the coupler correction is spatially diffused.
    bool spatial_effects = false;

    // µm -> pixel conversion driving every spatial sigma. Equals the resize
    // service's pixel_size_um = film_format_mm * 1000 / max(width, height) for the
    // parity image (no crop). Only consumed when spatial_effects is true.
    double pixel_size_um = 0.0;

    DirCouplersParams dir_couplers;  // filled by digest for the film type.
    HalationParams halation;         // filled by digest; active only if spatial.
};

// Build digested filming params for a film type, mirroring
// digest_params -> _apply_film_specifics under the parity toggles.
// `is_negative` selects the negative/positive coupler gamma matrix.
//
// When `spatial_effects` is false (the default, spatial-OFF goldens):
//   dir_couplers.diffusion_size_um == 0 (pointwise), halation.active == false.
// When `spatial_effects` is true (scan_portra_spatial):
//   dir_couplers diffusion (size 20µm, tail 200µm, weight 0.06) is enabled and
//   the digested HalationParams (scatter + back-reflection) are filled.
FilmingParams digest_filming_params(bool is_negative, bool spatial_effects = false);

// Fill p.halation from the film's halation preset tags (info.use / info.antihalation
// in the profile), mirroring params_builder._apply_halation_preset +
// _HALATION_PRESETS. Sets sigma_h / strength from the (use, antihalation) preset;
// scatter parameters stay at the params_schema HalationParams defaults. Marks
// halation active only when `spatial_effects` is true (otherwise apply_halation_um
// is skipped). Unknown tag pairs leave halation inactive.
void digest_halation_params(FilmingParams& p, const char* use,
                            const char* antihalation, bool spatial_effects);

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
//     when both normalize + compensation are on). Computed natively by
//     runtime/print_digest.h::compute_midgray_exposure_factor from the full
//     filming midgray chain (rgb_to_raw of 0.184 gray) — no longer oracle-baked.
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

// Build digested printing params for any (film, paper) pair. `neutral_cc` are the
// neutral C/M/Y filter values (Kodak CC units) resolved natively from
// neutral_print_filters.json by runtime/print_digest.h::resolve_neutral_cc for
// the film/print/illuminant triple; `enl_illum` is enlarger_illuminant(id). The
// filtered illuminant is computed exactly via color_enlarger (shifts==0).
// `exposure_factor_midgray` is the native midgray factor from
// runtime/print_digest.h::compute_midgray_exposure_factor; `gamma` is the print
// density-curve gamma. (Pass exposure_factor_midgray=1.0 to build the filtered
// illuminant first, then set it from the native computation.)
PrintingParams digest_printing_params(const double neutral_cc[3],
                                      const double* enl_illum,
                                      double exposure_factor_midgray,
                                      float gamma);

}  // namespace spk

#endif  // SPK_RUNTIME_PARAMS_H
