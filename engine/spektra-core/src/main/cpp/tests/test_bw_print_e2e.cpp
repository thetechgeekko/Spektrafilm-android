/*
 * Spektrafilm for Android — black-and-white print gate. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The black-and-white stocks (channel_model "bw": Tri-X 400, T-Max 100, HP5 Plus 400,
 * Neopan Acros 100 II, and the Multigrade IV RC paper) have no oracle golden: spektrafilm's
 * c1d0e44 tree has no such profiles. So this gates their properties instead, through the
 * public API on the bundled assets:
 *
 *   A. LOAD + RENDER: every silver film prints on Multigrade (these profiles carry no
 *      Hanatos2025 adaptation window; the filming tc-LUT must take the bare-sensitivity
 *      branch instead of refusing the profile).
 *   B. NEUTRAL: neutral silver in every channel renders R == G == B, for a grey ramp AND for
 *      a saturated colour patch, on every film, and for a colour negative on Multigrade.
 *   C. MONOTONE: a grey ramp prints as a strictly rising ramp (no tone reversal).
 *   D. GRADES: the app's Multigrade table (SilverPrint.GRADES, c/m/y_filter_neutral +
 *      print_exposure with the database lookup off) holds mid-grey within 2 % at every
 *      grade 00..5 while the contrast across mid-grey rises strictly from 00 to 5.
 *   E. DATABASE: grade 2 sent by hand equals the neutral_print_filters.json entry for the
 *      pair (database lookup on), so the default render IS grade 2.
 *   F. THREAD INVARIANCE: Tri-X with grain ON, SPK_NUM_THREADS 1 vs 8, fresh engines,
 *      byte-identical.
 *
 * Run: /tmp/test_bw_print_e2e <asset_dir>
 */
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "spektra.h"

namespace {

int g_fail = 0;

void expect(bool ok, const std::string& what) {
    if (!ok) ++g_fail;
    std::printf("[%s] %s\n", what.c_str(), ok ? "PASS" : "FAIL");
}

constexpr int kRamp = 9;            // 0.184 * 2^(i-4), i = 0..8; index 4 is mid-grey
constexpr int kW = kRamp + 1;       // + one saturated colour patch
const char* const kPaper = "ilford_multigrade_iv_rc";
const char* const kFilms[] = {"kodak_tri_x_400", "kodak_tmax_100",
                              "fujifilm_neopan_acros_100_ii", "ilford_hp5_plus_400"};

struct Grade { const char* label; float m, y, pe; };
// Mirrors app SilverPrint.GRADES (from the Multigrade paper_grades table).
const Grade kGrades[] = {
    {"00", 0.0f, 199.0f, 0.5323f}, {"0", 0.0f, 137.9f, 0.6642f},
    {"1", 0.0f, 99.8f, 0.8257f},   {"2", 0.0f, 68.0f, 1.0f},
    {"3", 0.0f, 34.1f, 1.159f},    {"4", 39.1f, 0.0f, 1.334f},
    {"5", 151.7f, 0.0f, 1.3507f},
};

std::vector<float> input() {
    std::vector<float> px(static_cast<size_t>(kW) * 3);
    for (int i = 0; i < kRamp; ++i) {
        const float v = 0.184f * std::pow(2.0f, static_cast<float>(i - 4));
        px[3 * i] = px[3 * i + 1] = px[3 * i + 2] = v;
    }
    px[3 * kRamp + 0] = 0.40f;  // saturated orange-red
    px[3 * kRamp + 1] = 0.08f;
    px[3 * kRamp + 2] = 0.02f;
    return px;
}

spk_params base(const char* film, bool grain) {
    spk_params p;
    spk_default_params(&p);
    p.film_profile = film;
    p.print_profile = kPaper;
    p.auto_exposure = 0;
    p.grain_active = grain ? 1 : 0;
    p.halation_active = 0;
    p.glare_active = 0;
    p.print_glare_active = 0;
    // Sharpening on a 10-pixel ramp of whole-stop steps overshoots into the next step,
    // which would read as a tone reversal that the print does not have.
    p.scanner_unsharp[1] = 0.0f;
    p.scanner_lens_blur = 0.0f;
    p.output_color_space = SPK_CS_LINEAR_SRGB;
    return p;
}

void with_grade(spk_params* p, const Grade& g) {
    p->neutral_print_filters_from_database = 0;
    p->c_filter_neutral = 0.0f;
    p->m_filter_neutral = g.m;
    p->y_filter_neutral = g.y;
    p->print_exposure = g.pe;
}

bool render(spk_engine* e, const spk_params& p, std::vector<float>* out) {
    std::vector<float> px = input();
    spk_image in{px.data(), kW, 1, SPK_CS_LINEAR_SRGB};
    spk_image o{};
    const spk_status s = spk_simulate(e, &in, &p, &o);
    if (s != SPK_OK) {
        std::printf("  simulate(%s on %s): %s %s\n", p.film_profile, p.print_profile,
                    spk_status_str(s), spk_last_error_message());
        return false;
    }
    out->assign(o.data, o.data + static_cast<size_t>(o.width) * o.height * 3);
    spk_image_free(&o);
    return out->size() == static_cast<size_t>(kW) * 3;
}

float lum(const std::vector<float>& v, int i) {
    return 0.2126f * v[3 * i] + 0.7152f * v[3 * i + 1] + 0.0722f * v[3 * i + 2];
}

bool neutral(const std::vector<float>& v) {
    for (int i = 0; i < kW; ++i) {
        const float r = v[3 * i], g = v[3 * i + 1], b = v[3 * i + 2];
        const float tol = 1e-3f * std::max(1e-2f, g);
        if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b)) return false;
        if (std::fabs(r - g) > tol || std::fabs(b - g) > tol) return false;
    }
    return true;
}

bool rising(const std::vector<float>& v) {
    for (int i = 1; i < kRamp; ++i)
        if (!(lum(v, i) > lum(v, i - 1))) return false;
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <asset_dir>\n", argv[0]);
        return 2;
    }
    const std::string asset_dir = argv[1];
    spk_engine* e = nullptr;
    if (spk_engine_create(asset_dir.c_str(), &e) != SPK_OK) return 2;

    for (const char* film : kFilms) {
        const std::string f = film;
        std::vector<float> db;
        const bool ok = render(e, base(film, false), &db);
        expect(ok, "A " + f + " renders on Multigrade");
        if (!ok) continue;
        expect(neutral(db), "B " + f + " neutral (ramp + colour patch)");
        expect(rising(db), "C " + f + " grey ramp prints rising");

        float mid2 = 0.0f, prev_contrast = -1.0f;
        bool held = true, harder = true, all_neutral = true, all_rising = true;
        std::vector<float> g2;
        for (const Grade& g : kGrades) {
            spk_params p = base(film, false);
            with_grade(&p, g);
            std::vector<float> v;
            if (!render(e, p, &v)) { held = false; continue; }
            all_neutral = all_neutral && neutral(v);
            all_rising = all_rising && rising(v);
            if (std::strcmp(g.label, "2") == 0) { mid2 = lum(v, 4); g2 = v; }
            // Contrast across mid-grey: print log-luminance over the two stops around it.
            const float c = std::log10(lum(v, 5) / lum(v, 3));
            if (!(c > prev_contrast)) harder = false;
            prev_contrast = c;
        }
        for (const Grade& g : kGrades) {
            spk_params p = base(film, false);
            with_grade(&p, g);
            std::vector<float> v;
            if (render(e, p, &v) && mid2 > 0.0f)
                held = held && std::fabs(lum(v, 4) / mid2 - 1.0f) < 0.02f;
        }
        expect(all_neutral && all_rising, "D " + f + " every grade neutral and rising");
        expect(held, "D " + f + " mid-grey held within 2% at grades 00..5");
        expect(harder, "D " + f + " contrast rises strictly from 00 to 5");

        float worst = 0.0f;
        for (size_t i = 0; i < g2.size() && g2.size() == db.size(); ++i)
            worst = std::max(worst, std::fabs(g2[i] - db[i]));
        expect(g2.size() == db.size() && worst <= 1e-6f,
               "E " + f + " grade 2 by hand == database pack");
    }

    {
        std::vector<float> v;
        const bool ok = render(e, base("kodak_portra_400", false), &v);
        expect(ok && neutral(v) && rising(v), "B colour negative on Multigrade prints neutral");
    }
    spk_engine_destroy(e);

    std::vector<float> t1, t8;
    for (int threads : {1, 8}) {
        setenv("SPK_NUM_THREADS", threads == 1 ? "1" : "8", 1);
        spk_engine* te = nullptr;
        if (spk_engine_create(asset_dir.c_str(), &te) != SPK_OK) return 2;
        render(te, base("kodak_tri_x_400", true), threads == 1 ? &t1 : &t8);
        spk_engine_destroy(te);
    }
    unsetenv("SPK_NUM_THREADS");
    expect(!t1.empty() && t1.size() == t8.size() &&
               std::memcmp(t1.data(), t8.data(), t1.size() * sizeof(float)) == 0,
           "F grain on, SPK_NUM_THREADS 1 vs 8 byte-identical");

    // NB: the runner greps case-insensitively for "FAIL", so a green summary
    // must not contain that word.
    std::printf("bw_print_e2e: %s (%d bad)\n", g_fail ? "FAIL" : "ALL PASS", g_fail);
    return g_fail ? 1 : 0;
}
