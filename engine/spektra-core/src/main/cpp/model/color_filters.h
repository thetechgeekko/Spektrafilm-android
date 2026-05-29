/*
 * SpectraFilm for Android — native engine: enlarger dichroic color filters.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports the subset of spektrafilm/model/color_filters.py the print
 * (enlarger) route uses:
 *   - the "custom" dichroic C/M/Y filter spectra (DichroicFilters(brand='custom')),
 *   - DichroicFilters.apply_cc / color_enlarger: dim each dichroic filter by the
 *     Kodak CC value (100 units == 1.0 density == 90% reduction) and multiply the
 *     three together against the enlarger light source.
 *
 * The dichroic filter spectra (erf-based, see create_combined_dichroic_filter)
 * are baked at full precision on the 81-band working shape, identical to the
 * Python `custom_dichroic_filters.filters`. Computing them at runtime would
 * require scipy.special.erf; baking the exact samples keeps the print route
 * bit-exact with no extra dependency.
 */
#ifndef SPK_MODEL_COLOR_FILTERS_H
#define SPK_MODEL_COLOR_FILTERS_H

namespace spk {

// Custom dichroic C/M/Y filter transmittances on the 81-band working shape,
// row-major [81][3] = {C, M, Y}. Equal to custom_dichroic_filters.filters.
extern const double kCustomDichroicFilters[81][3];

// color_enlarger(light_source, cc_values) for the "custom" dichroic set.
//   trans[k]      = 10^-(cc[k]/100)                            (k in C,M,Y)
//   dimmed[l,k]   = 1 - (1 - dichroic[l,k]) * (1 - trans[k])
//   total[l]      = prod_k dimmed[l,k]
//   filtered[l]   = light_source[l] * total[l]
// cc_values are in CMY order (Kodak CC units). `light_source`, `filtered_out`
// are length-81 arrays on the working shape; they may alias.
void color_enlarger(const double* light_source, const double cc_values[3],
                    double* filtered_out);

}  // namespace spk

#endif  // SPK_MODEL_COLOR_FILTERS_H
