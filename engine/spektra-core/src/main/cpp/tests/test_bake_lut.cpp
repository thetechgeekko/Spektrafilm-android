/*
 * SpectraFilm for Android — host test for spk_bake_cube_lut.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Bakes a 17^3 3D LUT for the default scan_film look and validates the emitted
 * .cube text:
 *   - header: LUT_3D_SIZE 17, TITLE, DOMAIN_MIN/MAX present
 *   - exactly N^3 RGB data lines
 *   - values sane (finite, within a generous [-0.01, 1.5] band)
 *   - non-degenerate: the LUT is neither the identity map nor a constant.
 *
 * Build (host):
 *   g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
 *     tests/test_bake_lut.cpp spektra.cpp \
 *     runtime/stages/filming.cpp runtime/stages/scanning.cpp \
 *     runtime/stages/printing.cpp runtime/params.cpp runtime/print_digest.cpp \
 *     model/couplers.cpp model/density_curves.cpp model/color_output.cpp \
 *     model/emulsion.cpp model/conversions.cpp model/spectral.cpp \
 *     model/color_filters.cpp model/grain.cpp model/diffusion.cpp model/glare.cpp \
 *     kernels/spectral_upsampling.cpp kernels/interp.cpp kernels/gaussian.cpp \
 *     kernels/exponential_filter.cpp kernels/stats.cpp \
 *     io/npy_lut.cpp profiles/profile.cpp \
 *     -o /tmp/test_bake_lut
 */
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#include "spektra.h"

namespace {

const char* kAssetDir = "/home/user/spektrafilm/src/spektrafilm/data";

// Extract the integer value following a header token, or -1 if absent.
int header_int(const std::string& text, const std::string& token) {
    size_t pos = text.find(token);
    if (pos == std::string::npos) return -1;
    return std::atoi(text.c_str() + pos + token.size());
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    const int N = 17;

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        std::printf("FAIL\n");
        return 2;
    }

    spk_params p{};
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    spk_default_params(&p);
    p.scan_film = 1;                       // negative scan route
    p.auto_exposure = 0;
    p.output_color_space = SPK_CS_SRGB;
    p.output_cctf_encoding = 1;
    // Deliberately leave the stochastic/spatial toggles ON in params to prove the
    // bake forces them off internally (must still succeed & be deterministic).
    p.grain_active = 1;
    p.halation_active = 1;
    p.glare_active = 1;

    bool ok = true;

    // --- Pass 1: size the buffer (null out_text). ---------------------------
    size_t needed = 0;
    st = spk_bake_cube_lut(eng, &p, N, nullptr, 0, &needed);
    if (needed == 0) {
        std::fprintf(stderr, "sizing pass set needed=0 (st=%s)\n", spk_status_str(st));
        ok = false;
    }

    // --- Pass 2: actually bake. ---------------------------------------------
    std::vector<char> buf(needed > 0 ? needed : 1);
    st = spk_bake_cube_lut(eng, &p, N, buf.data(), buf.size(), &needed);
    if (st != SPK_OK) {
        std::fprintf(stderr, "bake failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        std::printf("FAIL\n");
        return 1;
    }

    std::string text(buf.data());

    // --- Determinism: a second bake must be byte-identical (no stochastics). -
    {
        size_t need2 = 0;
        spk_bake_cube_lut(eng, &p, N, nullptr, 0, &need2);
        std::vector<char> buf2(need2);
        spk_bake_cube_lut(eng, &p, N, buf2.data(), buf2.size(), &need2);
        if (std::string(buf2.data()) != text) {
            std::fprintf(stderr, "bake is non-deterministic across runs\n");
            ok = false;
        } else {
            std::printf("[determinism] two bakes byte-identical -> PASS\n");
        }
    }

    spk_engine_destroy(eng);

    // --- Header checks. ------------------------------------------------------
    int lut_size = header_int(text, "LUT_3D_SIZE ");
    bool has_title = text.find("TITLE") != std::string::npos;
    bool has_dmin = text.find("DOMAIN_MIN") != std::string::npos;
    bool has_dmax = text.find("DOMAIN_MAX") != std::string::npos;
    bool has_input_doc = text.find("linear ProPhoto") != std::string::npos;
    bool has_excluded_doc = text.find("EXCLUDED") != std::string::npos;
    std::printf("[header] LUT_3D_SIZE=%d TITLE=%d DOMAIN_MIN=%d DOMAIN_MAX=%d "
                "input_doc=%d excluded_doc=%d\n",
                lut_size, has_title, has_dmin, has_dmax, has_input_doc,
                has_excluded_doc);
    if (lut_size != N || !has_title || !has_dmin || !has_dmax ||
        !has_input_doc || !has_excluded_doc) {
        std::fprintf(stderr, "header check failed\n");
        ok = false;
    }

    // --- Parse the N^3 data lines (skip '#' comments and header keywords). ---
    const size_t expected = static_cast<size_t>(N) * N * N;
    std::vector<float> data;  // flat RGB
    data.reserve(expected * 3);
    {
        std::istringstream iss(text);
        std::string ln;
        while (std::getline(iss, ln)) {
            if (ln.empty() || ln[0] == '#') continue;
            // header keyword lines begin with a letter (TITLE/LUT_3D_SIZE/DOMAIN_*)
            char c = ln[0];
            if (!(c == '-' || c == '.' || (c >= '0' && c <= '9'))) continue;
            float r, g, b;
            if (std::sscanf(ln.c_str(), "%f %f %f", &r, &g, &b) == 3) {
                data.push_back(r);
                data.push_back(g);
                data.push_back(b);
            }
        }
    }
    std::printf("[data] parsed %zu triples (expected %zu)\n",
                data.size() / 3, expected);
    if (data.size() != expected * 3) {
        std::fprintf(stderr, "data-line count mismatch\n");
        ok = false;
    }

    // --- Sanity + non-degeneracy. -------------------------------------------
    if (data.size() == expected * 3) {
        // Reconstruct the identity lattice in the SAME blue-fastest order the
        // baker uses, so we can compare and prove the look is not the identity.
        double max_dev_from_identity = 0.0;
        double min_v = 1e9, max_v = -1e9;
        bool finite = true;
        size_t idx = 0;
        const float denom = static_cast<float>(N - 1);
        for (int ri = 0; ri < N; ++ri) {
            float rv = ri / denom;
            for (int gi = 0; gi < N; ++gi) {
                float gv = gi / denom;
                for (int bi = 0; bi < N; ++bi) {
                    float bv = bi / denom;
                    float or_ = data[idx * 3 + 0];
                    float og = data[idx * 3 + 1];
                    float ob = data[idx * 3 + 2];
                    if (!std::isfinite(or_) || !std::isfinite(og) || !std::isfinite(ob))
                        finite = false;
                    for (float v : {or_, og, ob}) {
                        if (v < min_v) min_v = v;
                        if (v > max_v) max_v = v;
                    }
                    max_dev_from_identity = std::fmax(max_dev_from_identity,
                        std::fmax(std::fabs(or_ - rv),
                                  std::fmax(std::fabs(og - gv), std::fabs(ob - bv))));
                    ++idx;
                }
            }
        }
        // Variance across the table (per channel) proves it is not constant.
        double mean[3] = {0, 0, 0};
        for (size_t i = 0; i < expected; ++i)
            for (int c = 0; c < 3; ++c) mean[c] += data[i * 3 + c];
        for (int c = 0; c < 3; ++c) mean[c] /= expected;
        double var = 0.0;
        for (size_t i = 0; i < expected; ++i)
            for (int c = 0; c < 3; ++c) {
                double d = data[i * 3 + c] - mean[c];
                var += d * d;
            }
        var /= (expected * 3);

        std::printf("[values] finite=%d range=[%.4f,%.4f] "
                    "max_dev_from_identity=%.4f variance=%.6f\n",
                    finite, min_v, max_v, max_dev_from_identity, var);

        if (!finite) { std::fprintf(stderr, "non-finite values\n"); ok = false; }
        if (min_v < -0.01 || max_v > 1.5) {
            std::fprintf(stderr, "values out of sane band\n"); ok = false;
        }
        if (max_dev_from_identity < 1e-3) {
            std::fprintf(stderr, "LUT is (near) identity — film look not applied\n");
            ok = false;
        }
        if (var < 1e-5) {
            std::fprintf(stderr, "LUT is (near) constant\n"); ok = false;
        }
    }

    // Print the header snippet for the record.
    {
        size_t cut = 0;
        for (int i = 0; i < 18 && cut != std::string::npos; ++i)
            cut = text.find('\n', cut + 1);
        std::printf("---- .cube header ----\n%s----\n",
                    text.substr(0, cut == std::string::npos ? text.size() : cut + 1).c_str());
    }

    std::printf("%s\n", ok ? "PASS" : "FAIL");
    return ok ? 0 : 1;
}
