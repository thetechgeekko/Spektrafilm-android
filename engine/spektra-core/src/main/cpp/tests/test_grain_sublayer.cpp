/*
 * Spektrafilm for Android — host STATISTICAL test for the SUBLAYER film-grain
 * model (model/grain.cpp::apply_grain_to_density_layers + add_micro_structure).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * Why statistical (not element-wise): the AgX particle grain draws Poisson +
 * Binomial variates per pixel, and the sublayer path additionally draws a
 * per-pixel lognormal clumping field. The C++ RNG (std::mt19937) and the Python
 * numpy/Numba RNG produce different streams even with a fixed seed, so the grainy
 * density cannot match element-by-element. What MUST match is the algorithm and
 * its statistics:
 *   1. mean (approximate) preservation: the sublayer path accumulates developed-
 *      particle density per sublayer, multiplies by a *unit-mean* lognormal
 *      clumping field, then subtracts the density_min offset. The clumping is
 *      unit-mean so the path is mean-preserving in expectation, but the finite
 *      multiplicative field leaves a small residual; the ORACLE itself shows
 *      |Δmean| up to ~1.1e-3. We therefore require |Δmean| < 2e-3 (absolute) for
 *      BOTH the C++ output and the oracle — tight enough to catch a missing
 *      density_min subtraction (which would shift the mean by ~0.1) yet loose
 *      enough to absorb the clumping residual.
 *   2. noise magnitude: per-channel std(grainy - smooth) must match the oracle in
 *      magnitude. Tolerance ±15% relative — same reasoning as the non-sublayer
 *      test (sampling error at this image size + different RNG micro-regimes).
 *   3. distinct noise character vs the NON-sublayer path: the sublayer particle
 *      model (per-sublayer particle accounting via density_curves_layers +
 *      agx_particle_scale_layers) injects a MEASURABLY smaller noise std than the
 *      non-sublayer path. At the 64px parity image's huge pixel size (~547µm/px)
 *      the dye-cloud/micro-structure blurs are sub-pixel and the two paths' stats
 *      coincide, so the character check is run on a uniform mid-density patch at a
 *      realistic 10µm/px, where the oracle shows a ~31% std gap. We require the
 *      C++ sublayer std to (a) match the oracle sublayer std (±15%) and (b) sit a
 *      clear margin below the non-sublayer std, consistent with the oracle gap.
 *
 * Inputs:
 *   - smooth (no-grain) density:  goldens/scan_portra/film_density_cmy.spkvec
 *   - oracle grainy realisations: tests/grain_sublayer_ref_density.spkvec (S,npix,3)
 *     (produced by tests/gen_grain_sublayer_ref.py)
 *   - film profile (density_curves + density_curves_layers): the spektrafilm
 *     bundled kodak_portra_400.json.
 *
 * Build (host):
 *   g++ -std=c++17 -O2 \
 *     -I <cpp_root> -I <tools/parity> \
 *     tests/test_grain_sublayer.cpp \
 *     model/grain.cpp model/density_curves.cpp kernels/stats.cpp \
 *     kernels/gaussian.cpp kernels/interp.cpp profiles/profile.cpp \
 *     -o /tmp/test_grain_sublayer
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "spkvec_io.h"

#include "model/density_curves.h"
#include "model/grain.h"
#include "profiles/profile.h"

namespace {

const char* kSmoothGolden =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra/"
    "film_density_cmy.spkvec";
const char* kOracleGrainy =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "grain_sublayer_ref_density.spkvec";
const char* kProfilePath =
    "/home/user/spektrafilm/src/spektrafilm/data/profiles/kodak_portra_400.json";

constexpr int kSize = 64;
constexpr double kPixelSizeUm = 35.0 * 1000.0 / kSize;  // 546.875

void channel_mean(const std::vector<float>& x, int npix, double out[3]) {
    double s[3] = {0, 0, 0};
    for (int i = 0; i < npix; ++i)
        for (int c = 0; c < 3; ++c) s[c] += x[i * 3 + c];
    for (int c = 0; c < 3; ++c) out[c] = s[c] / npix;
}

void channel_noise_std(const std::vector<float>& x,
                       const std::vector<float>& smooth, int npix, double out[3]) {
    double s[3] = {0, 0, 0}, s2[3] = {0, 0, 0};
    for (int i = 0; i < npix; ++i)
        for (int c = 0; c < 3; ++c) {
            double d = static_cast<double>(x[i * 3 + c]) - smooth[i * 3 + c];
            s[c] += d;
            s2[c] += d * d;
        }
    for (int c = 0; c < 3; ++c) {
        double m = s[c] / npix;
        out[c] = std::sqrt(std::fmax(s2[c] / npix - m * m, 0.0));
    }
}

// --- "Noise character" reference (committed in grain_sublayer_ref_stats.json,
//     section noise_character): uniform mid-density patch at a realistic pixel
//     size, sublayer vs non-sublayer noise std. ---
constexpr int kCharSize = 128;
constexpr double kCharPixelSizeUm = 10.0;
constexpr double kCharDensity = 0.8;
// Oracle per-channel noise std on the uniform patch (averaged over its seeds).
const double kOracleSubStd[3] = {0.01254699, 0.01293088, 0.02114187};
const double kOracleNonStd[3] = {0.01825277, 0.01866323, 0.03159394};

// Per-channel std of a uniform-input grain output (input is the constant patch).
void patch_noise_std(const std::vector<float>& x, double patch_val, int npix,
                     double out[3]) {
    double s[3] = {0, 0, 0}, s2[3] = {0, 0, 0};
    for (int i = 0; i < npix; ++i)
        for (int c = 0; c < 3; ++c) {
            double d = static_cast<double>(x[i * 3 + c]) - patch_val;
            s[c] += d;
            s2[c] += d * d;
        }
    for (int c = 0; c < 3; ++c) {
        double m = s[c] / npix;
        out[c] = std::sqrt(std::fmax(s2[c] / npix - m * m, 0.0));
    }
}

}  // namespace

int main(int argc, char** argv) {
    std::string smooth_path = argc > 1 ? argv[1] : kSmoothGolden;
    std::string oracle_path = argc > 2 ? argv[2] : kOracleGrainy;
    std::string profile_path = argc > 3 ? argv[3] : kProfilePath;

    spkvec::Array smooth_a = spkvec::read(smooth_path);
    spkvec::Array oracle_a = spkvec::read(oracle_path);
    spk::Profile film = spk::load_profile_file(profile_path);

    const int npix = static_cast<int>(smooth_a.count() / 3);
    std::vector<float> smooth = smooth_a.data;  // (npix,3) row-major
    const int n = film.n_density_pts;

    if (static_cast<int>(film.density_curves_layers.size()) != n * 9) {
        std::fprintf(stderr, "profile has no density_curves_layers (%zu) — cannot "
                             "run sublayer test\n",
                     film.density_curves_layers.size());
        return 2;
    }

    const int S = static_cast<int>(oracle_a.shape[0]);
    std::printf("Image: %d px, oracle realisations S=%d, pixel_size_um=%.4f\n",
                npix, S, kPixelSizeUm);

    // Oracle: (S, npix, 3). Per-channel mean + noise std averaged over S.
    double oracle_mean[3] = {0, 0, 0}, oracle_nstd[3] = {0, 0, 0};
    for (int s = 0; s < S; ++s) {
        std::vector<float> g(
            oracle_a.data.begin() + static_cast<size_t>(s) * npix * 3,
            oracle_a.data.begin() + static_cast<size_t>(s + 1) * npix * 3);
        double m[3], nstd[3];
        channel_mean(g, npix, m);
        channel_noise_std(g, smooth, npix, nstd);
        for (int c = 0; c < 3; ++c) { oracle_mean[c] += m[c]; oracle_nstd[c] += nstd[c]; }
    }
    for (int c = 0; c < 3; ++c) { oracle_mean[c] /= S; oracle_nstd[c] /= S; }

    double smooth_mean[3];
    channel_mean(smooth, npix, smooth_mean);

    // --- Build the sublayer inputs exactly as filming.cpp::develop does. ---
    // normalized_density_curves = density_curves - nanmin(density_curves, axis=0).
    std::vector<float> ndc(static_cast<size_t>(n) * 3);
    spk::normalize_density_curves(film.density_curves.data(), n, ndc.data());

    // density_max_layers[sl,c] = nanmax over le axis of the RAW density_curves_layers.
    double density_max_layers[9];
    for (int sl = 0; sl < 3; ++sl)
        for (int c = 0; c < 3; ++c) {
            double mx = -1.0; bool any = false;
            for (int k = 0; k < n; ++k) {
                float v = film.density_curves_layers[
                    static_cast<size_t>(k) * 9 + sl * 3 + c];
                if (std::isnan(v)) continue;
                if (!any || v > mx) { mx = v; any = true; }
            }
            density_max_layers[sl * 3 + c] = any ? mx : 2.2;
        }

    // interp_density_cmy_layers(smooth, ndc, raw layers, positive=false).
    std::vector<float> layers(static_cast<size_t>(npix) * 9);
    spk::interp_density_cmy_layers(smooth.data(), npix, ndc.data(),
                                   film.density_curves_layers.data(), n,
                                   film.is_positive(), layers.data());

    // Schema GrainParams (sublayer defaults) for kodak_portra_400.
    spk::GrainParams grain;
    grain.active = true;
    grain.sublayers_active = true;
    grain.agx_particle_area_um2 = 0.2;
    grain.agx_particle_scale[0] = 0.8; grain.agx_particle_scale[1] = 1.0; grain.agx_particle_scale[2] = 2.0;
    grain.agx_particle_scale_layers[0] = 2.5; grain.agx_particle_scale_layers[1] = 1.0; grain.agx_particle_scale_layers[2] = 0.5;
    grain.density_min[0] = 0.07; grain.density_min[1] = 0.08; grain.density_min[2] = 0.12;
    grain.uniformity[0] = 0.97; grain.uniformity[1] = 0.97; grain.uniformity[2] = 0.99;
    grain.blur = 0.65;
    grain.blur_dye_clouds_um = 1.0;
    grain.micro_structure[0] = 0.2; grain.micro_structure[1] = 30.0;

    // --- Run S sublayer realisations (distinct seed offsets). ---
    double cpp_mean[3] = {0, 0, 0}, cpp_nstd[3] = {0, 0, 0};
    std::vector<float> out(static_cast<size_t>(npix) * 3);
    for (int s = 0; s < S; ++s) {
        grain.seed_offset = 1000 + s * 7;
        spk::apply_grain_to_density_layers(layers.data(), npix, kSize, kSize,
                                           density_max_layers, kPixelSizeUm, grain,
                                           out.data());
        double m[3], nstd[3];
        channel_mean(out, npix, m);
        channel_noise_std(out, smooth, npix, nstd);
        for (int c = 0; c < 3; ++c) { cpp_mean[c] += m[c]; cpp_nstd[c] += nstd[c]; }
    }
    for (int c = 0; c < 3; ++c) { cpp_mean[c] /= S; cpp_nstd[c] /= S; }

    const char* chan[3] = {"C", "M", "Y"};
    const double tol_mean = 2e-3;     // mean preservation (absolute; see header)
    const double tol_std_rel = 0.15;  // noise magnitude (relative)
    bool pass = true;

    std::printf("\n=== mean preservation (|grainy_mean - smooth_mean| < %.0e) ===\n",
                tol_mean);
    for (int c = 0; c < 3; ++c) {
        double d_cpp = std::fabs(cpp_mean[c] - smooth_mean[c]);
        double d_ora = std::fabs(oracle_mean[c] - smooth_mean[c]);
        bool ok = (d_cpp < tol_mean) && (d_ora < tol_mean);
        pass = pass && ok;
        std::printf("  [%s] smooth=%.6f  cpp=%.6f (|d|=%.2e)  oracle=%.6f (|d|=%.2e)  %s\n",
                    chan[c], smooth_mean[c], cpp_mean[c], d_cpp, oracle_mean[c],
                    d_ora, ok ? "PASS" : "FAIL");
    }

    std::printf("\n=== noise magnitude (|cpp_std/oracle_std - 1| < %.0f%%) ===\n",
                tol_std_rel * 100.0);
    for (int c = 0; c < 3; ++c) {
        double rel = (oracle_nstd[c] > 0.0)
                         ? std::fabs(cpp_nstd[c] / oracle_nstd[c] - 1.0)
                         : (cpp_nstd[c] > 0 ? 1.0 : 0.0);
        bool ok = rel < tol_std_rel;
        pass = pass && ok;
        std::printf("  [%s] cpp_std=%.6f  oracle_std=%.6f  rel_err=%.1f%%  %s\n",
                    chan[c], cpp_nstd[c], oracle_nstd[c], rel * 100.0,
                    ok ? "PASS" : "FAIL");
    }

    // Distinct noise character: run BOTH paths on a uniform mid-density patch at a
    // realistic 10µm/px. The sublayer path injects a clearly smaller noise std
    // than the non-sublayer path (oracle gap ~31%). Require:
    //   (a) C++ sublayer std matches the oracle sublayer std (±15%),
    //   (b) C++ sublayer std sits >=15% below the C++ non-sublayer std (a clear
    //       margin, consistent with the committed oracle gap).
    std::printf("\n=== noise character: sublayer vs non-sublayer "
                "(uniform patch @ %.0fµm/px) ===\n", kCharPixelSizeUm);
    const int cnpix = kCharSize * kCharSize;

    // Per-sublayer densities for the uniform patch (interp the constant density).
    std::vector<float> cpatch(static_cast<size_t>(cnpix) * 3, kCharDensity);
    std::vector<float> clayers(static_cast<size_t>(cnpix) * 9);
    spk::interp_density_cmy_layers(cpatch.data(), cnpix, ndc.data(),
                                   film.density_curves_layers.data(), n,
                                   film.is_positive(), clayers.data());

    // Average over a few seed offsets for a stable empirical std (matches the
    // oracle, which averages over 5 patch seeds).
    const int CS = 5;
    double cpp_sub_std[3] = {0, 0, 0}, cpp_non_std[3] = {0, 0, 0};
    std::vector<float> cout(static_cast<size_t>(cnpix) * 3);

    spk::GrainParams cg = grain;  // sublayers_active = true
    for (int s = 0; s < CS; ++s) {
        cg.seed_offset = 2000 + s * 7;
        spk::apply_grain_to_density_layers(clayers.data(), cnpix, kCharSize,
                                           kCharSize, density_max_layers,
                                           kCharPixelSizeUm, cg, cout.data());
        double nstd[3];
        patch_noise_std(cout, kCharDensity, cnpix, nstd);
        for (int c = 0; c < 3; ++c) cpp_sub_std[c] += nstd[c];
    }

    spk::GrainParams cng = grain;
    cng.sublayers_active = false;
    for (int c = 0; c < 3; ++c) {
        double mx = -1.0; bool any = false;
        for (int k = 0; k < n; ++k) {
            float v = ndc[static_cast<size_t>(k) * 3 + c];
            if (std::isnan(v)) continue;
            if (!any || v > mx) { mx = v; any = true; }
        }
        cng.density_max_curves[c] = any ? mx : 2.2;
    }
    for (int s = 0; s < CS; ++s) {
        cng.seed_offset = 2000 + s * 7;
        spk::apply_grain_to_density(cpatch.data(), cnpix, kCharSize, kCharSize,
                                    kCharPixelSizeUm, cng, cout.data());
        double nstd[3];
        patch_noise_std(cout, kCharDensity, cnpix, nstd);
        for (int c = 0; c < 3; ++c) cpp_non_std[c] += nstd[c];
    }
    for (int c = 0; c < 3; ++c) { cpp_sub_std[c] /= CS; cpp_non_std[c] /= CS; }

    const double tol_char_match = 0.15;  // C++ sub std vs oracle sub std
    const double tol_char_gap = 0.15;    // min relative gap sub-below-non
    for (int c = 0; c < 3; ++c) {
        double match = std::fabs(cpp_sub_std[c] / kOracleSubStd[c] - 1.0);
        double gap = (cpp_non_std[c] > 0.0)
                         ? (cpp_non_std[c] - cpp_sub_std[c]) / cpp_non_std[c]
                         : 0.0;
        double ora_gap = (kOracleNonStd[c] > 0.0)
                             ? (kOracleNonStd[c] - kOracleSubStd[c]) / kOracleNonStd[c]
                             : 0.0;
        bool ok = (match < tol_char_match) && (gap > tol_char_gap);
        pass = pass && ok;
        std::printf("  [%s] cpp_sub=%.5f cpp_non=%.5f (gap=%.0f%%, oracle_gap=%.0f%%)  "
                    "oracle_sub=%.5f (match=%.0f%%)  %s\n",
                    chan[c], cpp_sub_std[c], cpp_non_std[c], gap * 100.0,
                    ora_gap * 100.0, kOracleSubStd[c], match * 100.0,
                    ok ? "PASS" : "FAIL");
    }

    std::printf("\n%s\n", pass ? "ALL PASS" : "FAILED");
    return pass ? 0 : 1;
}
