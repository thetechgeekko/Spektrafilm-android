/*
 * Spektrafilm for Android — END-TO-END host gate for the opt-in DATE-BACK
 * imprint (spk_params.date_stamp_*, model/date_stamp.h).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The date back is NOT an upstream spektrafilm stage (the idea is SpektraLab's
 * RFC-031), so there is no oracle golden for it. This gate proves:
 *
 *   A. INERT when off. Default params, `active=1` with no text, `active=1` with
 *      text that has no drawable glyph, and `active=0` with text are all
 *      byte-identical to each other and within parity of the scan_portra golden.
 *   B. The mask: bounding box inside the frame and in the requested corner,
 *      coverage in [0,1] and reaching 1 on a stroke, "8" covers more than "1",
 *      unknown characters are skipped, and the light is colour/Y * 0.184 * 2^ev
 *      with the exposure compensation divided back out.
 *   C. WIRING POSITION. With the stamp on, the film_log_raw tap equals the tap of
 *      the stamp OFF on an input with coverage*light ADDED -- i.e. the LED is
 *      extra light on the scene-linear RGB right before the film -- and every
 *      pixel outside the bounding box is byte-identical to the default render.
 *   D. It looks like an LED date: under the strokes red rises more than blue.
 *   E. MEMO KEYS: off -> on -> off on a warm engine, on BOTH routes, and every
 *      field (text included) perturbed alone gives warm == fresh != on.
 *   F. THREAD INVARIANCE: SPK_NUM_THREADS 1 vs 8, fresh engines, byte-identical.
 *   G. It halates: with halation on, pixels OUTSIDE the box change too.
 *
 * Run: /tmp/test_date_stamp_e2e <asset_dir> <scan_portra_golden_dir> <input.f64>
 */
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "model/date_stamp.h"
#include "spkvec_io.h"
#include "spektra.h"

namespace {

int g_fail = 0;

void expect(bool ok, const char* what) {
    std::printf("[%s] %s\n", what, ok ? "PASS" : "FAIL");
    if (!ok) ++g_fail;
}

double max_abs_diff(const float* a, const float* b, size_t n) {
    double m = 0.0;
    for (size_t i = 0; i < n; ++i) {
        const double d = std::fabs(double(a[i]) - double(b[i]));
        if (d > m || std::isnan(d)) m = std::isnan(d) ? INFINITY : d;
    }
    return m;
}

bool within_parity(const char* label, const float* got, const std::vector<float>& gold) {
    double max_abs = 0.0, sse = 0.0;
    for (size_t i = 0; i < gold.size(); ++i) {
        const double d = std::fabs(double(got[i]) - double(gold[i]));
        max_abs = std::max(max_abs, d);
        sse += d * d;
    }
    const double rms = std::sqrt(sse / double(gold.size()));
    const bool ok = max_abs <= 1e-4 && rms <= 1e-5;
    std::printf("[%s] max_abs=%.3e rms=%.3e -> %s\n", label, max_abs, rms,
                ok ? "PASS" : "FAIL");
    if (!ok) ++g_fail;
    return ok;
}

void set_base_params(spk_params* p) {
    p->film_profile = "kodak_portra_400";
    p->print_profile = "kodak_portra_endura";
    spk_default_params(p);
    p->exposure_compensation_ev = 0.0f;
    p->auto_exposure = 0;
    p->density_curve_gamma = 1.0f;
    p->grain_active = 0;
    p->halation_active = 0;
    p->dir_diffusion_size_um = 0.0f;
    p->scanner_unsharp[0] = 0.0f;
    p->scanner_unsharp[1] = 0.0f;
    p->dir_couplers_active = 1;
    p->glare_active = 0;
    p->scan_film = 1;
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    p->rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p->preview_max_size = 640;
}

const char* const kText = "'26 11 07";

// As big as fits across the 64 px golden frame (strokes ~1 px wide).
void set_stamp_on(spk_params* p) {
    p->date_stamp_active = 1;
    p->date_stamp_text = kText;
    p->date_stamp_corner = 0;
    p->date_stamp_height = 0.15f;
    p->date_stamp_margin = 0.05f;
    p->date_stamp_exposure_ev = 2.5f;
    p->date_stamp_color[0] = 1.0f;
    p->date_stamp_color[1] = 0.18f;
    p->date_stamp_color[2] = 0.0f;
}

spk::DateStampParams to_model(const spk_params& p) {
    spk::DateStampParams d;
    d.active = p.date_stamp_active != 0;
    d.text = p.date_stamp_text ? p.date_stamp_text : "";
    d.corner = p.date_stamp_corner;
    d.height = p.date_stamp_height;
    d.margin = p.date_stamp_margin;
    d.exposure_ev = p.date_stamp_exposure_ev;
    for (int c = 0; c < 3; ++c) d.color[c] = p.date_stamp_color[c];
    return d;
}

struct Run {
    std::vector<float> rgb, logr;
};

bool render(spk_engine* eng, const spk_image& in, const spk_params& p, Run* r,
            bool want_tap = true) {
    spk_image out{};
    if (spk_simulate(eng, &in, &p, &out) != SPK_OK) return false;
    r->rgb.assign(out.data, out.data + size_t(out.width) * out.height * 3);
    spk_image_free(&out);
    if (want_tap) {
        spk_image tap{};
        if (spk_simulate_tap(eng, &in, &p, "film_log_raw", &tap) != SPK_OK) return false;
        r->logr.assign(tap.data, tap.data + size_t(tap.width) * tap.height * 3);
        spk_image_free(&tap);
    }
    return true;
}

bool same_bytes(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() &&
           std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}

double coverage_sum(const spk::DateStampMask& m) {
    double s = 0.0;
    for (float c : m.coverage) s += c;
    return s;
}

void unit_mask() {
    spk::DateStampParams d;
    d.active = true;
    d.text = kText;
    d.height = 0.1;
    const int W = 400, H = 300;
    bool corners_ok = true;
    for (int corner = 0; corner < 4; ++corner) {
        d.corner = corner;
        const spk::DateStampMask m = spk::build_date_stamp_mask(d, W, H, 0.0);
        const bool inside = !m.empty() && m.x0 >= 0 && m.y0 >= 0 && m.x0 + m.w <= W &&
                            m.y0 + m.h <= H;
        const bool right = m.x0 + m.w / 2 > W / 2, bottom = m.y0 + m.h / 2 > H / 2;
        const bool want_right = corner == 0 || corner == 2;
        const bool want_bottom = corner == 0 || corner == 1;
        if (!inside || right != want_right || bottom != want_bottom) corners_ok = false;
    }
    expect(corners_ok, "B box inside the frame, in the requested corner");

    d.corner = 0;
    const spk::DateStampMask m = spk::build_date_stamp_mask(d, W, H, 0.0);
    float lo = 1.0f, hi = 0.0f;
    for (float c : m.coverage) {
        lo = std::min(lo, c);
        hi = std::max(hi, c);
    }
    expect(lo >= 0.0f && hi <= 1.0f && hi > 0.99f, "B coverage in [0,1], strokes reach 1");
    expect(m.at(-1, -1) == 0.0f && m.at(W - 1, 0) == 0.0f, "B coverage 0 outside the box");

    spk::DateStampParams d8 = d, d1 = d;
    d8.text = "8";
    d1.text = "1";
    expect(coverage_sum(spk::build_date_stamp_mask(d8, W, H, 0.0)) >
               2.0 * coverage_sum(spk::build_date_stamp_mask(d1, W, H, 0.0)),
           "B '8' lights more segments than '1'");

    spk::DateStampParams dx = d;
    dx.text = std::string("X") + kText + "?";
    const spk::DateStampMask mx = spk::build_date_stamp_mask(dx, W, H, 0.0);
    expect(mx.w == m.w && mx.h == m.h && mx.coverage == m.coverage,
           "B unknown characters are skipped");
    spk::DateStampParams dn = d;
    dn.text = "XYZ";
    expect(!dn.engaged() && spk::build_date_stamp_mask(dn, W, H, 0.0).empty(),
           "B no drawable glyph is not engaged");

    const double y0 = 0.2880402 * m.light[0] + 0.7118741 * m.light[1] +
                      0.0000857 * m.light[2];
    expect(std::fabs(y0 - 0.184 * std::exp2(d.exposure_ev)) < 1e-9,
           "B light luminance is 0.184 * 2^ev");
    expect(std::fabs(m.light[1] / m.light[0] - d.color[1] / d.color[0]) < 1e-12,
           "B light keeps the LED colour ratio");
    const spk::DateStampMask mc = spk::build_date_stamp_mask(d, W, H, 1.0);
    expect(std::fabs(mc.light[0] * 2.0 - m.light[0]) < 1e-12,
           "B exposure compensation divided out of the light");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <asset_dir> <scan_portra_golden_dir> <input.f64>\n",
                     argv[0]);
        return 2;
    }
    const std::string asset_dir = argv[1], golden_dir = argv[2], input_path = argv[3];

    unit_mask();

    spkvec::Array gold_rgb = spkvec::read(golden_dir + "/final_rgb.spkvec");
    spkvec::Array gold_logr = spkvec::read(golden_dir + "/film_log_raw.spkvec");
    const int height = int(gold_rgb.shape[0]), width = int(gold_rgb.shape[1]);
    const size_t n = size_t(width) * height * 3;
    std::vector<double> rgb64(n);
    {
        std::ifstream f(input_path, std::ios::binary);
        f.read(reinterpret_cast<char*>(rgb64.data()), std::streamsize(n * sizeof(double)));
        if (f.gcount() != std::streamsize(n * sizeof(double))) {
            std::fprintf(stderr, "input size mismatch\n");
            return 2;
        }
    }
    std::vector<float> rgb32(rgb64.begin(), rgb64.end());
    const spk_image in{rgb32.data(), width, height, SPK_CS_PROPHOTO};
    std::printf("frame %dx%d\n", width, height);

    spk_engine* eng = nullptr;
    if (spk_engine_create(asset_dir.c_str(), &eng) != SPK_OK) return 2;

    // ---- A: inert when off ----
    spk_params base{};
    set_base_params(&base);
    spk_params p_notext = base;
    p_notext.date_stamp_active = 1;
    spk_params p_noglyph = base;
    p_noglyph.date_stamp_active = 1;
    p_noglyph.date_stamp_text = "XYZ";
    spk_params p_inactive = base;
    set_stamp_on(&p_inactive);
    p_inactive.date_stamp_active = 0;
    Run r_def, r_notext, r_noglyph, r_inactive;
    if (!render(eng, in, base, &r_def) || !render(eng, in, p_notext, &r_notext) ||
        !render(eng, in, p_noglyph, &r_noglyph) || !render(eng, in, p_inactive, &r_inactive)) {
        std::fprintf(stderr, "render failed\n");
        return 2;
    }
    within_parity("A default final_rgb vs golden", r_def.rgb.data(), gold_rgb.data);
    within_parity("A default film_log_raw vs golden", r_def.logr.data(), gold_logr.data);
    expect(same_bytes(r_def.rgb, r_notext.rgb) && same_bytes(r_def.logr, r_notext.logr),
           "A active with no text is byte-identical to default");
    expect(same_bytes(r_def.rgb, r_noglyph.rgb) && same_bytes(r_def.logr, r_noglyph.logr),
           "A active with no drawable glyph is byte-identical to default");
    expect(same_bytes(r_def.rgb, r_inactive.rgb) &&
               same_bytes(r_def.logr, r_inactive.logr),
           "A inactive with text set is byte-identical to default");

    // ---- C: wiring position ----
    spk_params p_on = base;
    set_stamp_on(&p_on);
    Run r_on;
    if (!render(eng, in, p_on, &r_on)) return 2;
    const spk::DateStampMask mask = spk::build_date_stamp_mask(to_model(p_on), width, height,
                                                               p_on.exposure_compensation_ev);
    std::printf("C box x0=%d y0=%d w=%d h=%d\n", mask.x0, mask.y0, mask.w, mask.h);
    expect(!mask.empty(), "C stamp has a box on the golden frame");
    std::vector<float> lit(rgb32);
    size_t n_lit = 0;
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            const float c = mask.at(x, y);
            if (c <= 0.0f) continue;
            ++n_lit;
            const size_t i = (size_t(y) * width + x) * 3;
            for (int k = 0; k < 3; ++k)
                lit[i + k] = float(double(rgb32[i + k]) + double(c) * mask.light[k]);
        }
    std::printf("C lit pixels: %zu of %zu\n", n_lit, n / 3);
    const spk_image in_lit{lit.data(), width, height, SPK_CS_PROPHOTO};
    Run r_ref;
    if (!render(eng, in_lit, base, &r_ref)) return 2;
    const double dl = max_abs_diff(r_on.logr.data(), r_ref.logr.data(), n);
    const double dr = max_abs_diff(r_on.rgb.data(), r_ref.rgb.data(), n);
    std::printf("C on vs off(lit input): film_log_raw max_abs=%.3e final_rgb max_abs=%.3e\n",
                dl, dr);
    expect(n_lit > 0 && dl <= 1e-5 && dr <= 1e-4,
           "C stage == coverage*light added before the film");
    bool outside_same = true;
    for (int y = 0; y < height && outside_same; ++y)
        for (int x = 0; x < width; ++x) {
            if (x >= mask.x0 && x < mask.x0 + mask.w && y >= mask.y0 && y < mask.y0 + mask.h)
                continue;
            const size_t i = (size_t(y) * width + x) * 3;
            if (std::memcmp(&r_on.rgb[i], &r_def.rgb[i], 3 * sizeof(float)) != 0 ||
                std::memcmp(&r_on.logr[i], &r_def.logr[i], 3 * sizeof(float)) != 0) {
                outside_same = false;
                break;
            }
        }
    expect(outside_same, "C pixels outside the box byte-identical (halation off)");

    // ---- D: it reads as an orange/red LED ----
    // On the print route: scan_film shows the NEGATIVE, where extra red light
    // becomes cyan density, i.e. LESS red.
    spk_params pr_off = base, pr_on = p_on;
    pr_off.scan_film = 0;
    pr_on.scan_film = 0;
    Run rp_off, rp_on;
    if (!render(eng, in, pr_off, &rp_off, false) || !render(eng, in, pr_on, &rp_on, false))
        return 2;
    double dR = 0.0, dB = 0.0;
    size_t n_core = 0;
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            if (mask.at(x, y) < 0.9f) continue;
            const size_t i = (size_t(y) * width + x) * 3;
            dR += double(rp_on.rgb[i + 0]) - double(rp_off.rgb[i + 0]);
            dB += double(rp_on.rgb[i + 2]) - double(rp_off.rgb[i + 2]);
            ++n_core;
        }
    std::printf("D stroke cores=%zu mean dR=%.4f dB=%.4f\n", n_core,
                n_core ? dR / n_core : 0.0, n_core ? dB / n_core : 0.0);
    expect(n_core > 0 && dR > 0.0 && dR > dB, "D strokes push red up more than blue");

    // ---- E: memo keys, both routes, every field ----
    for (int route = 0; route < 2; ++route) {
        spk_params off = base;
        off.scan_film = route == 0 ? 1 : 0;
        spk_params on = off;
        set_stamp_on(&on);
        Run a, b, c;
        if (!render(eng, in, off, &a, false) || !render(eng, in, on, &b, false) ||
            !render(eng, in, off, &c, false))
            return 2;
        const char* rn = route == 0 ? "scan_film" : "print";
        char label[128];
        std::snprintf(label, sizeof label, "E %s: on differs from off", rn);
        expect(!same_bytes(a.rgb, b.rgb), label);
        std::snprintf(label, sizeof label, "E %s: off->on->off returns to off bytes", rn);
        expect(same_bytes(a.rgb, c.rgb), label);

        // Text from a DIFFERENT buffer with different content: the key must
        // fold the bytes, not the pointer.
        static char other_text[] = "'26 11 08";
        struct Edit { const char* name; void (*apply)(spk_params*); };
        const Edit edits[] = {
            {"text", [](spk_params* q) { q->date_stamp_text = other_text; }},
            {"corner", [](spk_params* q) { q->date_stamp_corner = 3; }},
            {"height", [](spk_params* q) { q->date_stamp_height = 0.12f; }},
            {"margin", [](spk_params* q) { q->date_stamp_margin = 0.1f; }},
            {"exposure_ev", [](spk_params* q) { q->date_stamp_exposure_ev = 1.0f; }},
            {"color", [](spk_params* q) { q->date_stamp_color[2] = 0.5f; }},
        };
        for (const Edit& e : edits) {
            spk_params q = on;
            e.apply(&q);
            Run warm, fresh;
            if (!render(eng, in, on, &warm, false) || !render(eng, in, q, &warm, false))
                return 2;
            spk_engine* e2 = nullptr;
            if (spk_engine_create(asset_dir.c_str(), &e2) != SPK_OK) return 2;
            const bool ok = render(e2, in, q, &fresh, false);
            spk_engine_destroy(e2);
            if (!ok) return 2;
            std::snprintf(label, sizeof label, "E %s: %s warm == fresh, != on", rn, e.name);
            expect(same_bytes(warm.rgb, fresh.rgb) && !same_bytes(warm.rgb, b.rgb), label);
        }
    }

    // ---- G: the imprint halates ----
    {
        spk_params h_off = base;
        h_off.halation_active = 1;
        // 64 px across 35 mm is ~550 um per pixel, far wider than the halation
        // kernel; a 2 mm frame (~31 um/px) lets the glow cross pixels.
        h_off.film_format_mm = 2.0f;
        spk_params h_on = h_off;
        set_stamp_on(&h_on);
        Run a, b;
        if (!render(eng, in, h_off, &a, false) || !render(eng, in, h_on, &b, false)) return 2;
        size_t changed_outside = 0;
        for (int y = 0; y < height; ++y)
            for (int x = 0; x < width; ++x) {
                if (x >= mask.x0 && x < mask.x0 + mask.w && y >= mask.y0 &&
                    y < mask.y0 + mask.h)
                    continue;
                const size_t i = (size_t(y) * width + x) * 3;
                if (std::memcmp(&a.rgb[i], &b.rgb[i], 3 * sizeof(float)) != 0)
                    ++changed_outside;
            }
        std::printf("G pixels outside the box changed by the glow: %zu\n", changed_outside);
        expect(changed_outside > 0, "G halation spreads the imprint past its box");
    }
    spk_engine_destroy(eng);

    // ---- F: thread invariance ----
    Run t1, t8;
    for (int threads : {1, 8}) {
        setenv("SPK_NUM_THREADS", threads == 1 ? "1" : "8", 1);
        spk_engine* e = nullptr;
        if (spk_engine_create(asset_dir.c_str(), &e) != SPK_OK) return 2;
        const bool ok = render(e, in, p_on, threads == 1 ? &t1 : &t8);
        spk_engine_destroy(e);
        if (!ok) return 2;
    }
    unsetenv("SPK_NUM_THREADS");
    expect(same_bytes(t1.rgb, t8.rgb) && same_bytes(t1.logr, t8.logr),
           "F SPK_NUM_THREADS 1 vs 8 byte-identical");

    // NB: the runner greps case-insensitively for "FAIL", so a green summary
    // must not contain that word.
    std::printf("date_stamp_e2e: %s (%d bad)\n", g_fail ? "FAIL" : "ALL PASS", g_fail);
    return g_fail ? 1 : 0;
}
