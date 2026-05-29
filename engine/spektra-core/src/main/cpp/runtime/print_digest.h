/*
 * SpectraFilm for Android — native engine: print-route digest (neutral CC +
 * midgray exposure factor) computed natively for ANY (film, paper) pair.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Replaces the baked per-pair PrintDigest table with a native
 * reproduction of the two inputs the printing stage needs:
 *
 *   1) The neutral dichroic CC values — resolved from
 *      data/filters/neutral_print_filters.json exactly like params_builder.py's
 *      apply_database_neutral_print_filters: the lookup key is
 *      [print_stock][enlarger_illuminant][film_stock] -> [C, M, Y] (Kodak CC).
 *      Falls back to the schema defaults (0,0,0) when the triple is absent,
 *      mirroring the Python "use defaults" warning branch.
 *
 *   2) The midgray exposure normalisation factor — reproduced from the filming
 *      midgray balance (FilmingStage._compute_density_spectral_midgray_to_balance_print
 *      with rgb=0.184 gray, color_space='sRGB' as hardcoded in
 *      _simple_rgb_to_density_spectral) fed through
 *      PrintingStage._exposure_factor against the print sensitivity and the
 *      filtered enlarger illuminant. Under the parity toggles
 *      (normalize_print_exposure & print_exposure_compensation both on,
 *      exposure_compensation_ev == 0) this equals factor_midgray_comp.
 */
#ifndef SPK_RUNTIME_PRINT_DIGEST_H
#define SPK_RUNTIME_PRINT_DIGEST_H

#include <string>

#include "io/npy_lut.h"
#include "profiles/profile.h"

namespace spk {

// Resolve the neutral dichroic CC values for a (print_stock, illuminant,
// film_stock) triple from a neutral_print_filters.json file. `json_path` is the
// full path to the database (asset_dir/filters/neutral_print_filters.json).
//
// Writes [C, M, Y] (Kodak CC units) to `cc_out` and returns true when the triple
// is found. When the file is missing/unparseable or the triple is absent, fills
// `cc_out` with the schema defaults {0,0,0} and returns false (mirroring the
// Python "Using defaults." branch).
bool resolve_neutral_cc(const std::string& json_path,
                        const std::string& print_stock,
                        const std::string& illuminant,
                        const std::string& film_stock,
                        double cc_out[3]);

// Compute the midgray exposure normalisation factor natively.
//
//   density_spectral_midgray = compute_density_spectral(
//       film.channel_density,
//       develop_simple(log10(rgb_to_raw_hanatos2025(0.184 gray, sRGB)),
//                      film.log_exposure, film.density_curves /*raw*/, gamma),
//       film.base_density)
//   factor = _exposure_factor(print_sensitivity, filtered_illuminant,
//                             density_spectral_midgray)
//          = 1 / geomean_k( sum_l 10^-density_spectral[l] * filtered_illum[l]
//                                 * print_sens[l,k] )
//
// `filming_tc_lut` is build_filming_tc_lut(film, ...) (the same LUT the filming
// expose uses). `filtered_illuminant` is the 81-band enlarger illuminant after
// the dichroic neutral CC filters (PrintingParams::filtered_illuminant).
// `gamma` is the film density-curve gamma (scalar). NaN dye/illuminant entries
// are zeroed exactly as the Python pipeline does.
double compute_midgray_exposure_factor(const Profile& film,
                                       const Profile& print_profile,
                                       const NdArray& filming_tc_lut,
                                       const double* filtered_illuminant,
                                       float gamma);

}  // namespace spk

#endif  // SPK_RUNTIME_PRINT_DIGEST_H
