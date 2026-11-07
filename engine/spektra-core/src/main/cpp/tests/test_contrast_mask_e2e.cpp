/*
 * Spektrafilm for Android — END-TO-END host gate for the opt-in virtual
 * CONTRAST MASK at the print (spk_params.contrast_mask_*, model/contrast_mask.h).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The contrast mask is NOT an upstream spektrafilm stage (the idea is
 * SpektraLab's RFC-024), so there is no oracle golden for it. This gate proves:
 *
 *   A. INERT when off. Default params, `active=1` with both amounts 0, and
 *      `active=0` with amounts set are byte-identical on the print route; the
 *      scan_film route (no print) ignores it entirely and stays within parity
 *      of the scan_portra golden.
 *   B. The curve: exactly 0 inside the core and on a zero-amount branch, positive
 *      (more paper light) below the core and negative above it, monotone, bounded
 *      by the gain limit, and calibrated -- a base kReach stops past the core
 *      moves by exactly the amount.
 *   C. The pass: a uniform mid-grey frame is untouched byte for byte; a uniform
 *      frame outside the core gets exactly 2^delta on its image exposure, with the
 *      pre-flash re-added unscaled.
 *   D. POSITION: the film is untouched (film_density_cmy tap byte-identical on
 *      vs off); only the print changes.
 *   E. DIRECTION: the brightest print areas get darker and the darkest lighter.
 *   F. MEMO KEYS: off -> on -> off on a warm engine, and every field perturbed
 *      alone gives warm == fresh != on.
 *   G. It also engages on the enlarger-LUT route (the one previews use).
 *   H. THREAD INVARIANCE: SPK_NUM_THREADS 1 vs 8, fresh engines, byte-identical.
 *   I. NO HALO: next to a hard edge the shadow is lifted like the rest of it.
 *
 * Run: /tmp/test_contrast_mask_e2e <asset_dir> <scan_portra_golden_dir> <input.f64>
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

#include "model/contrast_mask.h"
#include "spkvec_io.h"
#include "spektra.h"

namespace {

int g_fail = 0;

void expect(bool ok, const char* what) {
    std::printf("[%s] %s\n", what, ok ? "PASS" : "FAIL");
    if (!ok) ++g_fail;
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
    p->print_glare_active = 0;
    p->scan_film = 0;  // the print route: the mask is a printing stage
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    p->rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p->preview_max_size = 640;
}

// Strong enough to move a 64 px frame visibly.
void set_mask_on(spk_params* p) {
    p->contrast_mask_active = 1;
    p->contrast_mask_highlights = 2.0f;
    p->contrast_mask_shadows = 2.0f;
    p->contrast_mask_core = 0.25f;
    p->contrast_mask_scale = 0.05f;
}

std::vector<float> render(spk_engine* eng, const spk_image& in, const spk_params& p,
                          const char* tap = nullptr) {
    spk_image out{};
    const spk_status st = tap ? spk_simulate_tap(eng, &in, &p, tap, &out)
                              : spk_simulate(eng, &in, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "render failed (%d)\n", int(st));
        std::exit(2);
    }
    std::vector<float> v(out.data, out.data + size_t(out.width) * out.height * 3);
    spk_image_free(&out);
    return v;
}

bool same_bytes(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() &&
           std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}

void unit_curve() {
    spk::ContrastMaskParams m;
    m.active = true;
    m.highlights = 1.5;
    m.shadows = 0.75;
    m.core = 1.0;
    bool core_zero = true;
    for (double b = -1.0; b <= 1.0; b += 0.125)
        if (spk::contrast_mask_delta(b, m) != 0.0) core_zero = false;
    expect(core_zero, "B exactly 0 inside the core");
    expect(spk::contrast_mask_delta(-2.0, m) > 0.0 && spk::contrast_mask_delta(2.0, m) < 0.0,
           "B more light below the core, less above it");
    bool mono = true, bounded = true;
    double prev = spk::contrast_mask_delta(-30.0, m);
    for (double b = -30.0; b <= 30.0; b += 0.01) {
        const double d = spk::contrast_mask_delta(b, m);
        // Below the core delta falls toward 0 as b rises; above it it falls further.
        if (d > prev + 1e-12) mono = false;
        if (std::fabs(d) >= spk::kContrastMaskGainLimit) bounded = false;
        prev = d;
    }
    expect(mono, "B monotone non-increasing in the base");
    expect(bounded, "B bounded by the gain limit");
    const double hi = spk::contrast_mask_delta(-m.core - spk::kContrastMaskReach, m);
    const double lo = spk::contrast_mask_delta(m.core + spk::kContrastMaskReach, m);
    std::printf("B at the reach: highlights %.9f (want %.2f), shadows %.9f (want -%.2f)\n",
                hi, m.highlights, lo, m.shadows);
    expect(std::fabs(hi - m.highlights) < 1e-9 && std::fabs(lo + m.shadows) < 1e-9,
           "B amount is the shift at the reach");
    // The CORRECTED exposure must keep tonal order at every setting: a monotone
    // delta is not enough, a slope below -1 makes darker regions print lighter.
    bool order = true;
    double worst = 1e9;
    for (double amount : {0.25, 1.0, 2.0, 2.9, 3.0}) {
        for (double core : {0.0, 0.25, 1.0}) {
            spk::ContrastMaskParams q;
            q.active = true;
            q.highlights = amount;
            q.shadows = amount;
            q.core = core;
            double prev_c = -30.0 + spk::contrast_mask_delta(-30.0, q);
            for (double b = -30.0 + 0.005; b <= 30.0; b += 0.005) {
                const double c = b + spk::contrast_mask_delta(b, q);
                worst = std::min(worst, (c - prev_c) / 0.005);
                if (c < prev_c - 1e-12) order = false;
                prev_c = c;
            }
        }
    }
    std::printf("B corrected exposure: minimum slope %.6f (want >= 0)\n", worst);
    expect(order, "B corrected exposure keeps tonal order at every amount and core");
    spk::ContrastMaskParams only_hi = m;
    only_hi.shadows = 0.0;
    expect(spk::contrast_mask_delta(5.0, only_hi) == 0.0, "B a zero-amount branch is inert");
}

void unit_pass() {
    spk::ContrastMaskParams m;
    m.active = true;
    m.highlights = 2.0;
    m.shadows = 1.0;
    m.core = 1.0;
    m.scale = 0.05;
    const int W = 40, H = 30;
    const double pf[3] = {0.01, 0.02, 0.03};
    const double mult = 1.0;
    auto plane = [&](double a) {
        std::vector<double> y(size_t(W) * H * 3);
        for (size_t i = 0; i < y.size(); ++i) y[i] = std::max(a + pf[i % 3], 0.0) + 1e-10;
        return y;
    };
    std::vector<double> grey = plane(1.0), grey0 = grey;  // x = log2(1) = 0
    spk::contrast_mask_apply(grey.data(), W, H, pf, mult, m);
    expect(std::memcmp(grey.data(), grey0.data(), grey.size() * sizeof(double)) == 0,
           "C a mid-grey frame is untouched byte for byte");

    const double a = std::exp2(-3.0);  // x = -3, outside the core: a print highlight
    std::vector<double> bright = plane(a);
    spk::contrast_mask_apply(bright.data(), W, H, pf, mult, m);
    const double k = std::exp2(spk::contrast_mask_delta(-3.0, m));
    double worst = 0.0;
    for (size_t i = 0; i < bright.size(); ++i) {
        const double want = std::max(k * a + pf[i % 3], 0.0) + 1e-10;
        worst = std::max(worst, std::fabs(bright[i] - want) / want);
    }
    std::printf("C uniform x=-3: gain %.6f, worst relative error %.3e\n", k, worst);
    expect(k > 1.0 && worst < 1e-9, "C uniform frame gets 2^delta on A, pre-flash unscaled");
}

// I. NO HALO at a hard edge. A frame half print-highlight (x = -1.5) and half
//    print-shadow (x = +2): with the shadows branch on, the shadow pixel right
//    at the edge is lifted nearly as much as one far from it. A plain Gaussian
//    base mixes the bright half in near the edge and leaves a dark band there
//    one blur radius wide (found on the phone, 2026-11-07); the guided base
//    follows the edge.
void unit_edge() {
    spk::ContrastMaskParams m;
    m.active = true;
    m.highlights = 0.0;
    m.shadows = 2.0;
    m.core = 0.25;
    m.scale = 0.05;
    const int W = 256, H = 64;
    const double pf[3] = {0.0, 0.0, 0.0};
    std::vector<double> y(size_t(W) * H * 3), y0;
    for (int py = 0; py < H; ++py)
        for (int px = 0; px < W; ++px)
            for (int c = 0; c < 3; ++c)
                y[(size_t(py) * W + px) * 3 + c] = std::exp2(px < W / 2 ? -1.5 : 2.0) + 1e-10;
    y0 = y;
    spk::contrast_mask_apply(y.data(), W, H, pf, 1.0, m);
    auto lift = [&](int px) {  // stops moved, mid row, green
        const size_t i = (size_t(H / 2) * W + px) * 3 + 1;
        return std::log2(y0[i] / y[i]);
    };
    double at_edge = lift(W / 2);  // the least-lifted shadow pixel, wherever the band is
    for (int px = W / 2; px < W; ++px) at_edge = std::min(at_edge, lift(px));
    const double far = lift(W - 4), bright = lift(W / 2 - 1);
    std::printf("I shadow lift min %.3f, far %.3f; bright side %.3f\n", at_edge, far, bright);
    expect(far > 0.2 && at_edge > 0.8 * far, "I the shadow next to a hard edge is lifted like the rest");
    expect(std::fabs(bright) < 0.05, "I the highlight side is not darkened by the shadow branch");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <asset_dir> <scan_portra_golden_dir> <input.f64>\n",
                     argv[0]);
        return 2;
    }
    const std::string asset_dir = argv[1], golden_dir = argv[2], input_path = argv[3];

    unit_curve();
    unit_pass();
    unit_edge();

    spkvec::Array gold_rgb = spkvec::read(golden_dir + "/final_rgb.spkvec");
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
    spk_params on = base;
    set_mask_on(&on);
    spk_params zero_amounts = on;
    zero_amounts.contrast_mask_highlights = 0.0f;
    zero_amounts.contrast_mask_shadows = 0.0f;
    spk_params inactive = on;
    inactive.contrast_mask_active = 0;
    const std::vector<float> r_def = render(eng, in, base);
    expect(same_bytes(r_def, render(eng, in, zero_amounts)),
           "A active with both amounts 0 is byte-identical to default");
    expect(same_bytes(r_def, render(eng, in, inactive)),
           "A inactive with amounts set is byte-identical to default");
    {
        spk_params scan_on = on;
        scan_on.scan_film = 1;
        within_parity("A scan_film route with the mask on vs golden",
                      render(eng, in, scan_on).data(), gold_rgb.data);
    }

    // ---- D: the film is untouched ----
    const std::vector<float> r_on = render(eng, in, on);
    expect(!same_bytes(r_def, r_on), "D the mask changes the print");
    expect(same_bytes(render(eng, in, base, "film_density_cmy"),
                      render(eng, in, on, "film_density_cmy")),
           "D film density byte-identical on vs off (the mask is after the film)");

    // ---- E: direction ----
    {
        std::vector<std::pair<double, size_t>> lum(n / 3);
        for (size_t i = 0; i < n / 3; ++i)
            lum[i] = {0.2126 * r_def[3 * i] + 0.7152 * r_def[3 * i + 1] +
                          0.0722 * r_def[3 * i + 2],
                      i};
        std::sort(lum.begin(), lum.end());
        const size_t dec = std::max<size_t>(1, lum.size() / 10);
        auto mean_change = [&](size_t from, size_t to) {
            double s = 0.0;
            for (size_t j = from; j < to; ++j) {
                const size_t i = lum[j].second;
                for (int c = 0; c < 3; ++c) s += double(r_on[3 * i + c]) - double(r_def[3 * i + c]);
            }
            return s / double(3 * (to - from));
        };
        const double dark = mean_change(0, dec), bright = mean_change(lum.size() - dec, lum.size());
        std::printf("E darkest decile %+.4f, brightest decile %+.4f\n", dark, bright);
        expect(dark > 0.0 && bright < 0.0, "E shadows lift and highlights darken");
    }

    // ---- F: memo keys ----
    {
        expect(same_bytes(r_def, render(eng, in, base)), "F off->on->off returns to off bytes");
        struct Edit { const char* name; void (*apply)(spk_params*); };
        const Edit edits[] = {
            {"highlights", [](spk_params* q) { q->contrast_mask_highlights = 1.0f; }},
            {"shadows", [](spk_params* q) { q->contrast_mask_shadows = 1.0f; }},
            {"core", [](spk_params* q) { q->contrast_mask_core = 0.6f; }},
            {"scale", [](spk_params* q) { q->contrast_mask_scale = 0.1f; }},
        };
        for (const Edit& e : edits) {
            spk_params q = on;
            e.apply(&q);
            (void)render(eng, in, on);
            const std::vector<float> warm = render(eng, in, q);
            spk_engine* e2 = nullptr;
            if (spk_engine_create(asset_dir.c_str(), &e2) != SPK_OK) return 2;
            const std::vector<float> fresh = render(e2, in, q);
            spk_engine_destroy(e2);
            char label[128];
            std::snprintf(label, sizeof label, "F %s: warm == fresh, != on", e.name);
            expect(same_bytes(warm, fresh) && !same_bytes(warm, r_on), label);
        }
    }

    // ---- G: the enlarger-LUT route ----
    {
        spk_params lut_off = base, lut_on = on;
        lut_off.use_enlarger_lut = 1;
        lut_on.use_enlarger_lut = 1;
        const std::vector<float> a = render(eng, in, lut_off), b = render(eng, in, lut_on);
        double worst = 0.0;
        for (size_t i = 0; i < n; ++i)
            worst = std::max(worst, std::fabs(double(b[i]) - double(r_on[i])));
        std::printf("G LUT route on vs direct route on: max_abs %.3e\n", worst);
        expect(!same_bytes(a, b) && worst < 2e-3, "G engages on the LUT route, close to direct");
    }
    spk_engine_destroy(eng);

    // ---- H: thread invariance ----
    std::vector<float> t1, t8;
    for (int threads : {1, 8}) {
        setenv("SPK_NUM_THREADS", threads == 1 ? "1" : "8", 1);
        spk_engine* e = nullptr;
        if (spk_engine_create(asset_dir.c_str(), &e) != SPK_OK) return 2;
        (threads == 1 ? t1 : t8) = render(e, in, on);
        spk_engine_destroy(e);
    }
    unsetenv("SPK_NUM_THREADS");
    expect(same_bytes(t1, t8), "H SPK_NUM_THREADS 1 vs 8 byte-identical");

    // NB: the runner greps case-insensitively for "FAIL", so a green summary
    // must not contain that word.
    std::printf("contrast_mask_e2e: %s (%d bad)\n", g_fail ? "FAIL" : "ALL PASS", g_fail);
    return g_fail ? 1 : 0;
}
