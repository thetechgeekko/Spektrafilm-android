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

#include <cstring>
#include <string>

#include "model/color_filters.h"

namespace spk {

// standard_illuminant("TH-KG3"): blackbody 3400K through the Schott KG3 heat
// filter, mean-normalised, on the 380..780 @5nm working shape. Baked at full
// double precision from the parity oracle (illuminants.standard_illuminant).
static const double kEnlargerTHKG3[81] = {
    0.27115487737678473,0.29336302419834392,0.31540886133985474,0.33698088366111678,
    0.35867799256782462,0.38052016290102403,0.40301341542370506,0.42678707974938129,
    0.45165159211478478,0.47806469837138088,0.50667086890593793,0.5366576010691867,
    0.56807697785132394,0.59992203813426259,0.63051534785469698,0.66122173855469291,
    0.69209091153054636,0.72366475803001418,0.75760093249115634,0.79306880152308379,
    0.82908454286780298,0.8654853588024557,0.90221274920967698,0.93899005047547601,
    0.97470041559555165,1.0087417855358256,1.040916738775546,1.0715558331394295,
    1.1009111192408305,1.1319261972376911,1.1638985698017286,1.1963646065125499,
    1.2303390279092097,1.2682954247580434,1.3068524359316893,1.3428629981362672,
    1.375710370915656,1.4046281460277452,1.4308952617117419,1.4554028101360024,
    1.4771790396751103,1.4964115098687572,1.5136660039295053,1.5280688440333003,
    1.5381464692108247,1.5459434102593674,1.5515901051035574,1.5540261727761033,
    1.5535666436238242,1.5500616564793381,1.5408266875226315,1.5320737503736817,
    1.5194886088637505,1.5030832634966458,1.479985561016079,1.4599852627082766,
    1.4367535752409983,1.4032296202488896,1.3718429999284323,1.3404558220815574,
    1.3019482391882462,1.2649087261004042,1.2261391348332888,1.1802123657454517,
    1.1368677636087421,1.0926534663168934,1.0452427815027683,1.0004794004746733,
    0.94805772821339052,0.90238079207571431,0.85466519719626777,0.79222634412800819,
    0.73452122408306142,0.68922854697180047,0.6522498893006442,0.60748230199813802,
    0.5717547839358964,0.52694037497351498,0.48667619111618271,0.44953980952699202,
    0.41455292589926274,
};

FilmingParams digest_filming_params(bool is_negative, bool spatial_effects) {
    FilmingParams p;
    p.spatial_effects = spatial_effects;

    // DirCouplersParams defaults (params_schema.DirCouplersParams) + the
    // negative-film overrides applied by _apply_film_specifics. amount and the
    // two inhibition scalars are 1.0 by default. The diffusion fields are enabled
    // only when spatial effects are on; otherwise the spatial-effects debug toggle
    // forces diffusion_size_um to 0 (pointwise correction).
    DirCouplersParams& dc = p.dir_couplers;
    dc.active = true;
    dc.amount = 1.0;
    dc.inhibition_samelayer = 1.0;
    dc.inhibition_interlayer = 1.0;
    dc.high_exposure_couplers_shift = 0.0;
    if (spatial_effects) {
        // params_schema.DirCouplersParams diffusion defaults.
        dc.diffusion_size_um = 20.0;
        dc.diffusion_tail_um = 200.0;
        dc.diffusion_tail_weight = 0.06;
    } else {
        dc.diffusion_size_um = 0.0;
        dc.diffusion_tail_um = 0.0;
        dc.diffusion_tail_weight = 0.0;
    }

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

void digest_halation_params(FilmingParams& p, const char* use,
                            const char* antihalation, bool spatial_effects) {
    HalationParams& h = p.halation;

    // params_schema.HalationParams defaults (scatter unchanged by the film
    // specifics; the stock-specific scatter_core override is commented out
    // upstream for kodak_portra_400).
    h.boost_ev = 0.0;
    h.boost_range = 0.3;
    h.protect_ev = 4.0;
    h.scatter_amount = 1.0;
    h.scatter_spatial_scale = 1.0;
    h.scatter_core_um[0] = 2.2; h.scatter_core_um[1] = 2.0; h.scatter_core_um[2] = 1.6;
    h.scatter_tail_um[0] = 9.3; h.scatter_tail_um[1] = 9.7; h.scatter_tail_um[2] = 9.1;
    h.scatter_tail_weight[0] = 0.78; h.scatter_tail_weight[1] = 0.65;
    h.scatter_tail_weight[2] = 0.67;
    h.halation_amount = 1.0;
    h.halation_spatial_scale = 1.0;
    h.halation_n_bounces = 3;
    h.halation_bounce_decay = 0.5;
    h.halation_renormalize = true;

    // _apply_halation_preset: (use, antihalation) -> {sigma_h, strength}.
    // sigma_h: still -> 65µm, cine -> 50µm.
    // strength: strong -> (0.015,0.005,0.0); weak -> (0.08,0.02,0.0);
    //           no -> (0.30,0.10,0.015).
    std::string u = use ? use : "";
    std::string a = antihalation ? antihalation : "";
    bool known = true;
    double sigma_h = 65.0;
    double sr = 0.0, sg = 0.0, sb = 0.0;
    if (u == "still") sigma_h = 65.0;
    else if (u == "cine") sigma_h = 50.0;
    else known = false;
    if (a == "strong") { sr = 0.015; sg = 0.005; sb = 0.0; }
    else if (a == "weak") { sr = 0.08; sg = 0.02; sb = 0.0; }
    else if (a == "no") { sr = 0.30; sg = 0.10; sb = 0.015; }
    else known = false;

    if (known) {
        h.halation_first_sigma_um[0] = sigma_h;
        h.halation_first_sigma_um[1] = sigma_h;
        h.halation_first_sigma_um[2] = sigma_h;
        h.halation_strength[0] = sr;
        h.halation_strength[1] = sg;
        h.halation_strength[2] = sb;
    }

    // The spatial-effects debug toggle zeroes scatter_core/scatter_tail/
    // halation_first_sigma when OFF (so apply_halation_um with the cleared sigmas
    // is the identity even if it ran). We simply skip the pass entirely when
    // spatial effects are off. The preset must be known for halation to apply.
    h.active = spatial_effects && known;
    if (!spatial_effects) {
        h.scatter_core_um[0] = h.scatter_core_um[1] = h.scatter_core_um[2] = 0.0;
        h.scatter_tail_um[0] = h.scatter_tail_um[1] = h.scatter_tail_um[2] = 0.0;
        h.halation_first_sigma_um[0] = h.halation_first_sigma_um[1] =
            h.halation_first_sigma_um[2] = 0.0;
    }
}

const double* enlarger_illuminant(const char* illuminant_id) {
    if (illuminant_id && std::strcmp(illuminant_id, "TH-KG3") == 0)
        return kEnlargerTHKG3;
    return nullptr;
}

PrintingParams digest_printing_params(const double neutral_cc[3],
                                      const double* enl_illum,
                                      double exposure_factor_midgray,
                                      float gamma) {
    PrintingParams p;
    // filtered illuminant = color_enlarger(enlarger_illuminant, neutral CC).
    // m/y filter shifts are 0 under the parity defaults, so the filtered
    // illuminant uses the neutral CC values directly.
    color_enlarger(enl_illum, neutral_cc, p.filtered_illuminant);
    p.exposure_factor_midgray = exposure_factor_midgray;
    p.print_exposure = 1.0;            // enlarger.print_exposure default.
    p.bw_exposure_correction = 1.0;    // no black/white correction (defaults off).
    p.density_curve_gamma[0] = gamma;
    p.density_curve_gamma[1] = gamma;
    p.density_curve_gamma[2] = gamma;
    return p;
}

}  // namespace spk
