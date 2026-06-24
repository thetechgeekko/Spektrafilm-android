/*
 * Spektrafilm for Android — native engine: print density-curve morph (s023).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports utils/morph_curves.py::apply_print_curves_morph (the s023
 * coupled-gamma print-curve morph) for the 'cdfs' density-curves model.
 *
 * OPT-IN / DEFAULT-OFF. The morph is an alternative way to *develop* the print:
 * instead of interpolating the stored print density_curves table (the c1d0e44
 * default path, left byte-identical), when active it rebuilds the print density
 * table from the profile's parametric density_curves_model (a sum of amplitude-
 * weighted Gaussian CDFs per channel/sub-layer) with coupled-gamma scaling, then
 * interpolates that. params.active defaults false, so every existing golden is
 * untouched; the active path is gated by its own oracle golden
 * (tests/test_print_curves_morph_e2e.cpp).
 *
 * The morph composes a per-channel effective gamma from a global, a per-band
 * (fast/slow by grain SPEED = ascending center) and a per-RGB-channel factor,
 * applies it as sigma' = sigma/gamma, mu' = mu/gamma (amplitude fixed), then
 * optionally blends each sub-layer toward a Gumbel-max-matched CDF (developer
 * exhaustion) with a common per-channel center offset that preserves D(0).
 * Identity at all defaults; A_i, D(0) and D_max preserved by construction.
 */
#ifndef SPK_MODEL_MORPH_CURVES_H
#define SPK_MODEL_MORPH_CURVES_H

namespace spk {

// User-facing controls for the s023 print density-curve morph. Defaults are the
// identity morph; `active` defaults false so the engine default path is the
// stored density-curve table (no morph).
struct PrintCurvesMorphParams {
    bool   active = false;
    double gamma_factor = 1.0;
    double gamma_factor_fast = 1.0;
    double gamma_factor_slow = 1.0;
    double gamma_factor_red = 1.0;
    double gamma_factor_green = 1.0;
    double gamma_factor_blue = 1.0;
    double developer_exhaustion = 0.0;  // [0, 1]
};

// Port of apply_print_curves_morph for the active path. Evaluates the morphed
// print density table over the profile log-exposure axis `log_exposure` (n
// points) and writes (n, 3) row-major [k*3 + c] into `out`.
//
//   centers / amplitudes / sigmas : (3 * n_layers) row-major [c*n_layers + i]
//                                   (3 channels, n_layers >= 1 sub-layers each)
//   profile_positive              : true for positive (reversal) film/paper,
//                                   which flips the CDF orientation (signed_z)
//
// The caller must guarantee params.active and n_layers >= 1. With identity gamma
// factors and developer_exhaustion == 0 this reproduces the model's fitted
// density curves exactly (bit-exact identity), matching the oracle.
void apply_print_curves_morph(const double* centers, const double* amplitudes,
                              const double* sigmas, int n_layers,
                              const PrintCurvesMorphParams& params,
                              bool profile_positive,
                              const float* log_exposure, int n,
                              float* out);

}  // namespace spk

#endif  // SPK_MODEL_MORPH_CURVES_H
