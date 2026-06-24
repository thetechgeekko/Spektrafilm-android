/*
 * Spektrafilm for Android — host parity test for the s023 print density-curve
 * morph (model/morph_curves.cpp), the opt-in print-develop alternative.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * apply_print_curves_morph rebuilds a print paper's density table from its
 * parametric density_curves_model (sum of amplitude-weighted Gaussian CDFs per
 * channel/sub-layer) under a coupled-gamma + developer-exhaustion morph. It is
 * OPT-IN / default-OFF (the engine default interpolates the stored density_curves
 * table, byte-identical to the c1d0e44 print goldens); this test gates the active
 * path.
 *
 * It:
 *   1) loads the bundled kodak_portra_endura paper profile (its density_curves_model
 *      + log_exposure axis + info.type are parsed by profiles/profile.cpp),
 *   2) applies a NON-identity morph (coupled gamma across band/channel +
 *      developer_exhaustion=0.35, exercising the Gumbel blend and the brentq
 *      D(0)-preserving center offset) — the same params the golden was cut with,
 *   3) asserts the (256,3) morphed density table matches
 *      goldens/print_curves_morph/print_density.spkvec, generated directly from the
 *      upstream utils/morph_curves.py oracle, within parity tolerance
 *      (max_abs <= 1e-4, rms <= 1e-5).
 *
 * Stage-local golden (morph_curves.py + scipy only), so it is independent of the
 * full-pipeline oracle SHA. Regenerate: tools/parity/gen_print_curves_morph_golden.py.
 *
 * Build (host): full source set per tests/README, e.g.
 *   g++ -std=c++17 -O2 -pthread -I <cpp_root> -I <tools/parity> \
 *     tests/test_print_curves_morph.cpp spektra.cpp kernels/*.cpp io/*.cpp \
 *     model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_print_curves_morph
 */
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "spkvec_io.h"
#include "profiles/profile.h"
#include "model/morph_curves.h"

namespace {

const char* kAssetDir = "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/assets/spektra";
const char* kGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_curves_morph";

bool check(const char* label, const std::vector<float>& got,
           const std::vector<float>& gold) {
    if (got.size() != gold.size()) {
        std::printf("[%s] size mismatch got=%zu gold=%zu -> FAIL\n",
                    label, got.size(), gold.size());
        return false;
    }
    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < gold.size(); ++i) {
        double d = std::fabs(static_cast<double>(got[i]) - static_cast<double>(gold[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    double rms = std::sqrt(sse / static_cast<double>(gold.size()));
    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass = (max_abs <= tol_max_abs) && (rms <= tol_rms);
    std::printf("[%s] max_abs=%.6e (tol %.0e) rms=%.6e (tol %.0e) "
                "worst idx=%zu got=%.8f gold=%.8f -> %s\n",
                label, max_abs, tol_max_abs, rms, tol_rms, argmax,
                got[argmax], gold[argmax], pass ? "PASS" : "FAIL");
    return pass;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;
    if (argc > 3) golden_dir = std::string(argv[3]) + "/print_curves_morph";

    const std::string profile_path = asset_dir + "/profiles/kodak_portra_endura.json";
    spk::Profile prof;
    try {
        prof = spk::load_profile_file(profile_path);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "cannot load profile %s: %s\n", profile_path.c_str(), e.what());
        return 2;
    }
    if (prof.dc_model_n_layers <= 0) {
        std::fprintf(stderr, "profile has no density_curves_model\n");
        return 2;
    }

    // Same non-identity morph the golden was generated with
    // (tools/parity/gen_print_curves_morph_golden.py).
    spk::PrintCurvesMorphParams morph;
    morph.active = true;
    morph.gamma_factor = 1.15;
    morph.gamma_factor_fast = 0.90;
    morph.gamma_factor_slow = 1.05;
    morph.gamma_factor_red = 1.08;
    morph.gamma_factor_green = 0.97;
    morph.gamma_factor_blue = 1.03;
    morph.developer_exhaustion = 0.35;

    const int n = prof.n_density_pts;
    std::vector<float> morphed(static_cast<size_t>(n) * 3);
    spk::apply_print_curves_morph(prof.dc_model_centers.data(),
                                  prof.dc_model_amplitudes.data(),
                                  prof.dc_model_sigmas.data(),
                                  prof.dc_model_n_layers, morph,
                                  prof.is_positive(),
                                  prof.log_exposure.data(), n, morphed.data());

    spkvec::Array gold;
    try {
        gold = spkvec::read(golden_dir + "/print_density.spkvec");
    } catch (const std::exception& e) {
        std::fprintf(stderr, "cannot read golden: %s\n", e.what());
        return 2;
    }
    std::printf("Profile kodak_portra_endura type=%s n_layers=%d -> morphed (%d,3), golden %u elems\n",
                prof.type.c_str(), prof.dc_model_n_layers, n,
                static_cast<unsigned>(gold.data.size()));

    bool pass = check("print_curves_morph print_density", morphed, gold.data);
    std::printf("%s\n", pass ? "ALL PASS" : "FAIL");
    return pass ? 0 : 1;
}
