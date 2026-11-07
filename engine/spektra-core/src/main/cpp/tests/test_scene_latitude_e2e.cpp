/*
 * Spektrafilm for Android — END-TO-END host gate for the opt-in SCENE LATITUDE
 * mapping (spk_params.scene_latitude_*, model/scene_latitude.h).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Scene latitude is NOT an upstream spektrafilm stage (the idea is SpektraLab's
 * RFC-023), so there is no oracle golden for it. This gate therefore proves:
 *
 *   A. INERT when off. Default params, `active=1` with both rooms 0, and
 *      `active=0` with non-zero rooms are all byte-identical to each other and
 *      within parity tolerance of the scan_portra golden.
 *   B. The stop mapping itself: identity in the core, slope 1 at the knee,
 *      strictly monotone, bounded by knee +/- room, shadow lift bounded.
 *   C. WIRING POSITION. With latitude on, the film_log_raw tap equals the tap of
 *      latitude OFF on an input pre-mapped by an INDEPENDENT in-test
 *      reimplementation (gain, plus the highlight desaturation) -- i.e. the stage
 *      is a per-pixel map of the scene-linear RGB right before the film is
 *      exposed, and nothing else.
 *   D. It does something: highlights above the knee move down, shadows below
 *      the shadow knee move up, ordering is preserved.
 *   E. MEMO KEYS: on a warm engine off -> on -> off, `on` differs and the second
 *      `off` is byte-identical to the first (the film-density memo key folds
 *      every scene_latitude field), on BOTH the scan_film and the print route,
 *      and each field perturbed alone changes the output.
 *   F. THREAD INVARIANCE: SPK_NUM_THREADS 1 vs 8, fresh engines, byte-identical.
 *
 * Run: /tmp/test_scene_latitude_e2e <asset_dir> <scan_portra_golden_dir> <input.f64>
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

#include "model/scene_latitude.h"
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

// The "on" setting used by C-F: knees close to mid-grey so the portra frame has
// pixels on both sides of the core.
void set_latitude_on(spk_params* p) {
    p->scene_latitude_active = 1;
    p->scene_latitude_highlight_knee = 0.5f;
    p->scene_latitude_highlight_room = 1.5f;
    p->scene_latitude_shadow_knee = -1.0f;
    p->scene_latitude_shadow_room = 1.0f;
    p->scene_latitude_rolloff = 2.0f;
    p->scene_latitude_max_lift = 4.0f;
}

// INDEPENDENT reference of the documented formula (model/scene_latitude.h), written
// from the spec, not by calling the engine: used to pre-scale the input in C.
double ref_gain(const spk_params& p, const float* rgb) {
    const double y = 0.2880402 * std::max(double(rgb[0]), 0.0) +
                     0.7118741 * std::max(double(rgb[1]), 0.0) +
                     0.0000857 * std::max(double(rgb[2]), 0.0);
    if (!(y > 0.0) || !std::isfinite(y)) return 1.0;
    const double E = std::min(24.0, std::max(-24.0, std::log2(y / 0.184)));
    const double m = p.scene_latitude_rolloff;
    auto comp = [m](double D, double H) {
        return D * H / std::pow(std::pow(H, m) + std::pow(D, m), 1.0 / m);
    };
    double Ep = E;
    const double kh = p.scene_latitude_highlight_knee, hh = p.scene_latitude_highlight_room;
    const double ks = p.scene_latitude_shadow_knee, hs = p.scene_latitude_shadow_room;
    if (hh > 0.0 && E > kh) Ep = kh + comp(E - kh, hh);
    else if (hs > 0.0 && E < ks) Ep = ks - comp(ks - E, hs);
    double d = Ep - E;
    if (d > 0.0) {
        const double L = p.scene_latitude_max_lift;
        d = d * L / std::sqrt(L * L + d * d);
    }
    return std::exp2(d);
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

void unit_mapping() {
    spk::SceneLatitudeParams s;
    s.active = true;
    s.highlight_knee = 2.0; s.highlight_room = 3.0;
    s.shadow_knee = -2.0; s.shadow_room = 2.0;
    bool core = true, mono = true, bound = true;
    double prev = -1e300;
    for (double E = -20.0; E <= 20.0; E += 0.01) {
        const double Ep = spk::scene_latitude_map_stops(s, E);
        if (E >= -2.0 && E <= 2.0 && Ep != E) core = false;
        if (!(Ep > prev)) mono = false;
        if (!(Ep < 5.0 && Ep > -4.0)) bound = false;
        prev = Ep;
    }
    expect(core, "B core is exact identity");
    expect(mono, "B mapping strictly monotone");
    expect(bound, "B mapping bounded by knee +/- room");
    const double h = 1e-6;
    const double slope = (spk::scene_latitude_map_stops(s, 2.0 + h) - 2.0) / h;
    expect(std::fabs(slope - 1.0) < 1e-3, "B slope 1 at the knee (C1 join)");

    spk::SceneLatitudeParams off = s;
    off.active = false;
    const double px[3] = {50.0, 40.0, 30.0};
    expect(spk::scene_latitude_gain(off, px) == 1.0, "B inactive gain is 1");
    off = s; off.highlight_room = 0.0; off.shadow_room = 0.0;
    expect(!off.engaged(), "B both rooms 0 is not engaged");
    const double nanpx[3] = {NAN, 1.0, 1.0};
    const double zero[3] = {0.0, 0.0, 0.0};
    expect(spk::scene_latitude_gain(s, nanpx) == 1.0 &&
               spk::scene_latitude_gain(s, zero) == 1.0,
           "B degenerate pixels pass through");
    // Highlight desaturation: luminance is exactly k*Y, chroma shrinks by k
    // relative to it, a neutral stays neutral, and shadows are a pure gain.
    {
        double px[3] = {40.0, 20.0, 60.0};
        const double y0 = 0.2880402 * px[0] + 0.7118741 * px[1] + 0.0000857 * px[2];
        const double k = spk::scene_latitude_gain(s, px);
        spk::scene_latitude_apply(s, px);
        const double y1 = 0.2880402 * px[0] + 0.7118741 * px[1] + 0.0000857 * px[2];
        const double sat0 = (60.0 - 20.0) / y0, sat1 = (px[2] - px[1]) / y1;
        expect(k < 1.0 && std::fabs(y1 - k * y0) < 1e-9 * y0 &&
                   std::fabs(sat1 - k * sat0) < 1e-9,
               "B highlight: luminance k*Y, saturation scaled by k");
        double grey[3] = {30.0, 30.0, 30.0};
        spk::scene_latitude_apply(s, grey);
        expect(grey[0] == grey[1] && grey[1] == grey[2] && grey[0] < 30.0,
               "B highlight: neutral stays neutral");
        double dark[3] = {0.002, 0.001, 0.003};
        const double kd = spk::scene_latitude_gain(s, dark);
        const double d0 = dark[0], d2 = dark[2];
        spk::scene_latitude_apply(s, dark);
        expect(kd > 1.0 && dark[0] == d0 * kd && dark[2] == d2 * kd,
               "B shadows: pure gain, chromaticity kept");
    }
    spk::SceneLatitudeParams lift = s;
    lift.shadow_room = 20.0; lift.max_lift = 2.0;
    const double dark[3] = {1e-9, 1e-9, 1e-9};
    expect(std::log2(spk::scene_latitude_gain(lift, dark)) < 2.0,
           "B shadow lift bounded by max_lift");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <asset_dir> <scan_portra_golden_dir> <input.f64>\n",
                     argv[0]);
        return 2;
    }
    const std::string asset_dir = argv[1], golden_dir = argv[2], input_path = argv[3];

    unit_mapping();

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

    spk_engine* eng = nullptr;
    if (spk_engine_create(asset_dir.c_str(), &eng) != SPK_OK) return 2;

    // ---- A: inert when off ----
    spk_params base{};
    set_base_params(&base);
    Run r_def, r_zero, r_inactive;
    spk_params p_zero = base;
    p_zero.scene_latitude_active = 1;
    p_zero.scene_latitude_highlight_room = 0.0f;
    p_zero.scene_latitude_shadow_room = 0.0f;
    spk_params p_inactive = base;
    set_latitude_on(&p_inactive);
    p_inactive.scene_latitude_active = 0;
    if (!render(eng, in, base, &r_def) || !render(eng, in, p_zero, &r_zero) ||
        !render(eng, in, p_inactive, &r_inactive)) {
        std::fprintf(stderr, "render failed\n");
        return 2;
    }
    within_parity("A default final_rgb vs golden", r_def.rgb.data(), gold_rgb.data);
    within_parity("A default film_log_raw vs golden", r_def.logr.data(), gold_logr.data);
    expect(same_bytes(r_def.rgb, r_zero.rgb) && same_bytes(r_def.logr, r_zero.logr),
           "A active with both rooms 0 is byte-identical to default");
    expect(same_bytes(r_def.rgb, r_inactive.rgb) &&
               same_bytes(r_def.logr, r_inactive.logr),
           "A inactive with rooms set is byte-identical to default");

    // ---- C: wiring position (on == off over a pre-scaled input) ----
    spk_params p_on = base;
    set_latitude_on(&p_on);
    Run r_on;
    if (!render(eng, in, p_on, &r_on)) return 2;
    std::vector<float> scaled(rgb32);
    size_t n_hi = 0, n_lo = 0;
    for (size_t i = 0; i < n; i += 3) {
        const double k = ref_gain(p_on, &rgb32[i]);
        if (k < 1.0) ++n_hi;
        if (k > 1.0) ++n_lo;
        // Highlights (k < 1) also lose chroma: RGB' = k * (Y + k * (RGB - Y)).
        const double y = 0.2880402 * std::max(double(rgb32[i]), 0.0) +
                         0.7118741 * std::max(double(rgb32[i + 1]), 0.0) +
                         0.0000857 * std::max(double(rgb32[i + 2]), 0.0);
        for (int c = 0; c < 3; ++c) {
            const double v = double(rgb32[i + c]);
            scaled[i + c] = float(k < 1.0 ? k * (y + k * (v - y)) : v * k);
        }
    }
    std::printf("C pixels compressed: %zu highlights, %zu shadows of %zu\n", n_hi, n_lo,
                n / 3);
    expect(n_hi > 0 && n_lo > 0, "C fixture exercises both sides");
    const spk_image in_scaled{scaled.data(), width, height, SPK_CS_PROPHOTO};
    Run r_ref;
    if (!render(eng, in_scaled, base, &r_ref)) return 2;
    const double dl = max_abs_diff(r_on.logr.data(), r_ref.logr.data(), n);
    const double dr = max_abs_diff(r_on.rgb.data(), r_ref.rgb.data(), n);
    std::printf("C on vs off(pre-scaled): film_log_raw max_abs=%.3e final_rgb max_abs=%.3e\n",
                dl, dr);
    expect(dl <= 1e-5 && dr <= 1e-4,
           "C stage == per-pixel gain (+ highlight desaturation) before the film");

    // ---- D: direction ----
    // Total film exposure (sum of the three layers' linear raw). Not one layer:
    // the highlight desaturation can raise a single layer while the total falls.
    auto exposure = [](const std::vector<float>& lr, size_t i) {
        return std::pow(10.0, double(lr[i])) + std::pow(10.0, double(lr[i + 1])) +
               std::pow(10.0, double(lr[i + 2]));
    };
    size_t down = 0, up = 0, wrong = 0;
    for (size_t i = 0; i < n; i += 3) {
        const double k = ref_gain(p_on, &rgb32[i]);
        const double d = exposure(r_on.logr, i) - exposure(r_def.logr, i);
        if (k < 1.0) { if (d < 0.0) ++down; else if (d > 0.0) ++wrong; }
        if (k > 1.0) { if (d > 0.0) ++up; else if (d < 0.0) ++wrong; }
    }
    std::printf("D highlights down=%zu shadows up=%zu wrong-way=%zu\n", down, up, wrong);
    expect(down > 0 && up > 0 && wrong == 0, "D highlights pulled down, shadows lifted");

    // ---- E: memo keys, both routes, every field ----
    for (int route = 0; route < 2; ++route) {
        spk_params off = base;
        off.scan_film = route == 0 ? 1 : 0;
        spk_params on = off;
        set_latitude_on(&on);
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
        // Each field alone must bust the memo (render on, then perturb one field).
        struct Field { const char* name; float spk_params::*m; float v; };
        const Field fields[] = {
            {"highlight_knee", &spk_params::scene_latitude_highlight_knee, 0.25f},
            {"highlight_room", &spk_params::scene_latitude_highlight_room, 0.75f},
            {"shadow_knee", &spk_params::scene_latitude_shadow_knee, -0.5f},
            {"shadow_room", &spk_params::scene_latitude_shadow_room, 2.0f},
            {"rolloff", &spk_params::scene_latitude_rolloff, 1.0f},
            {"max_lift", &spk_params::scene_latitude_max_lift, 0.25f},
        };
        for (const Field& f : fields) {
            spk_params q = on;
            q.*(f.m) = f.v;
            Run warm, fresh;
            if (!render(eng, in, on, &warm, false) || !render(eng, in, q, &warm, false))
                return 2;
            spk_engine* e2 = nullptr;
            if (spk_engine_create(asset_dir.c_str(), &e2) != SPK_OK) return 2;
            const bool ok = render(e2, in, q, &fresh, false);
            spk_engine_destroy(e2);
            if (!ok) return 2;
            std::snprintf(label, sizeof label, "E %s: %s warm == fresh, != on", rn, f.name);
            expect(same_bytes(warm.rgb, fresh.rgb) && !same_bytes(warm.rgb, b.rgb), label);
        }
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
    std::printf("scene_latitude_e2e: %s (%d bad)\n", g_fail ? "FAIL" : "ALL PASS", g_fail);
    return g_fail ? 1 : 0;
}
