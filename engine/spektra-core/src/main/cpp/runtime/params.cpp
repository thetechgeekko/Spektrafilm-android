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
 * spektrafilm. Implements runtime/params.h.
 */
#include "runtime/params.h"

namespace spk {

FilmingParams digest_filming_params(bool is_negative) {
    FilmingParams p;

    // DirCouplersParams defaults (params_schema.DirCouplersParams) + the
    // negative-film overrides applied by _apply_film_specifics. amount and the
    // two inhibition scalars are 1.0 by default. diffusion is forced off by the
    // spatial-effects toggle.
    DirCouplersParams& dc = p.dir_couplers;
    dc.active = true;
    dc.amount = 1.0;
    dc.inhibition_samelayer = 1.0;
    dc.inhibition_interlayer = 1.0;
    dc.high_exposure_couplers_shift = 0.0;

    if (is_negative) {
        dc.gamma_samelayer_rgb[0] = 0.336;
        dc.gamma_samelayer_rgb[1] = 0.319;
        dc.gamma_samelayer_rgb[2] = 0.273;
        dc.gamma_interlayer_r_to_gb[0] = 0.353;
        dc.gamma_interlayer_r_to_gb[1] = 0.302;
        dc.gamma_interlayer_g_to_rb[0] = 0.154;
        dc.gamma_interlayer_g_to_rb[1] = 0.353;
        dc.gamma_interlayer_b_to_rg[0] = 0.168;
        dc.gamma_interlayer_b_to_rg[1] = 0.226;
    } else {
        dc.gamma_samelayer_rgb[0] = 0.12;
        dc.gamma_samelayer_rgb[1] = 0.08;
        dc.gamma_samelayer_rgb[2] = 0.06;
        dc.gamma_interlayer_r_to_gb[0] = 0.12;
        dc.gamma_interlayer_r_to_gb[1] = 0.06;
        dc.gamma_interlayer_g_to_rb[0] = 0.08;
        dc.gamma_interlayer_g_to_rb[1] = 0.06;
        dc.gamma_interlayer_b_to_rg[0] = 0.06;
        dc.gamma_interlayer_b_to_rg[1] = 0.06;
    }

    return p;
}

}  // namespace spk
