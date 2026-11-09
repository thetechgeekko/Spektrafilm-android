/*
 * Spektrafilm for Android — host gate for BAND RENDERING (bounded-memory
 * full-resolution export). GPLv3. Film modeling powered by spektrafilm.
 *
 * spk_simulate renders a one-shot frame above a pixel threshold as horizontal
 * bands with a margin that covers every spatial effect's reach (see
 * plan_band_render in spektra.cpp). This test forces banding on a small
 * synthetic frame through spk_set_band_render_limits and checks:
 *
 *   A. Deterministic effects (grain/glare off), every spatial effect ON and
 *      wide (halation + scatter, DIR diffusion, camera lens blur, scanner
 *      blur, scanner unsharp), then each wide blur alone, auto exposure ON and a date-back imprint
 *      straddling a band edge: banded output is within the parity tolerance
 *      (max_abs <= 1e-4, rms <= 1e-5) of the whole-frame render, on BOTH the
 *      print and the scan_film routes, for two band heights.
 *   B. Banding is thread-invariant: SPK_NUM_THREADS=1 and =8 give identical bytes.
 *   C. Stochastic effects ON (grain + glare): a repeat is byte-identical, and
 *      the banded frame keeps the whole frame's mean per channel (grain is
 *      re-seeded per band, so bytes differ, statistics must not).
 *   D. Highlight boost, engine crop and the contrast mask band too: the boost
 *      takes the frame maximum from a pre-pass over the band cores, a crop
 *      bands its pixel box with pixel size and auto exposure pinned to the
 *      uncropped input, and the mask builds its frame grid in a pass over the
 *      bands before rendering them. Banded is within the parity tolerance of
 *      whole, both routes, for a narrow crop (rows copied per band), a
 *      full-width crop (rows used in place), crop + boost and mask + crop +
 *      boost; one pre-pass of each kind exactly when it applies; 1-vs-8
 *      threads byte-identical.
 *   D2. Frame-wide effects that do not band fall back: the "banded" call is
 *      byte-identical to the whole-frame render, for the enlarger diffusion
 *      filter always and for the camera diffusion filter at or below the
 *      whole-frame limit (and with no limit).
 *   E. The whole-frame limit: above spk_set_whole_frame_pixel_limit a frame
 *      that cannot band (engine rescale) fails with SPK_ERR_OOM and an
 *      empty output, a frame that bands still renders, and 0 lifts the limit.
 *   F. Above the whole-frame limit the camera diffusion filter bands with the
 *      near/far split of model/diffusion.h, which is NOT bit-exact: on the
 *      irradiance it is held to |log10(banded/whole)| <= 1e-3 worst and 1e-4
 *      RMS against apply_diffusion_filter_um (four families, f = 4 and 8, a
 *      scene of small highlights up to 8 stops over a dark floor; 3e-3 / 2e-4
 *      where a thin frame caps the kernel at a twelfth of its reach), and end to end to a
 *      measured output bound, both routes, with and without highlight boost;
 *      one coarse-frame pre-pass per render; 1-vs-8 threads byte-identical.
 *   F3. The enlarger diffusion filter bands the same way on the print route,
 *      alone and stacked with the camera filter, the contrast mask and the
 *      boost (two diffusion pre-passes after the mask grid); output bound and
 *      1-vs-8 thread identity as F2.
 *
 * Build from the cpp root like the other e2e gates:
 *   g++ -std=c++17 -O2 -pthread -I. tests/test_band_render.cpp spektra.cpp \
 *     gpu/*.cpp kernels/*.cpp io/*.cpp model/*.cpp profiles/*.cpp \
 *     runtime/*.cpp runtime/stages/*.cpp -o /tmp/test_band_render
 * Run: /tmp/test_band_render <asset_dir>
 */
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "spektra.h"
#include "model/diffusion.h"

namespace {

constexpr int kW = 960;
constexpr int kH = 1440;
// Large enough that a whole-frame call never bands.
constexpr int64_t kNeverBand = static_cast<int64_t>(1) << 40;

int g_failures = 0;
// End-to-end bound for the banded camera diffusion filter (F2), set from the
// measured error with headroom; the irradiance-level bound is F1's.
constexpr double kDiffusionOutMaxAbs = 2e-3;
constexpr double kDiffusionOutRms = 5e-5;

void expect(bool ok, const char* what) {
    std::printf("[%s] %s\n", what, ok ? "PASS" : "FAIL");
    if (!ok) ++g_failures;
}

// A frame with what the spatial effects react to: smooth gradients, hard
// edges and small, very bright highlights (halation), plus fine texture.
std::vector<float> make_frame() {
    std::vector<float> f(static_cast<size_t>(kW) * kH * 3);
    for (int y = 0; y < kH; ++y) {
        for (int x = 0; x < kW; ++x) {
            const double u = static_cast<double>(x) / kW;
            const double v = static_cast<double>(y) / kH;
            double r = 0.05 + 0.25 * u + 0.05 * std::sin(0.11 * x) * std::cos(0.07 * y);
            double g = 0.05 + 0.25 * v + 0.04 * std::sin(0.05 * (x + y));
            double b = 0.08 + 0.15 * (1.0 - u) * v;
            // Hard-edged blocks across band boundaries.
            if (((x / 120) + (y / 150)) % 2 == 0) { r *= 0.4; g *= 0.5; b *= 0.6; }
            // Bright lamps: sources several stops over mid grey.
            const int lamps[][2] = {{200, 300}, {700, 520}, {480, 760}, {120, 1100}, {820, 1300}};
            for (const auto& l : lamps) {
                const double dx = x - l[0], dy = y - l[1];
                const double d2 = dx * dx + dy * dy;
                if (d2 < 36.0 * 36.0) { r += 12.0; g += 10.0; b += 7.0; }
            }
            float* p = &f[(static_cast<size_t>(y) * kW + x) * 3];
            p[0] = static_cast<float>(r);
            p[1] = static_cast<float>(g);
            p[2] = static_cast<float>(b);
        }
    }
    return f;
}

void base_params(spk_params* p, bool scan_film, bool stochastic) {
    spk_default_params(p);
    p->film_profile = "kodak_portra_400";
    p->print_profile = "kodak_portra_endura";
    p->scan_film = scan_film ? 1 : 0;
    p->disable_buffer_memos = 1;  // banding is for one-shot (export) renders
    p->grain_active = stochastic ? 1 : 0;
    p->glare_active = stochastic ? 1 : 0;
    p->print_glare_active = stochastic ? 1 : 0;
    p->halation_active = 1;
    p->dir_couplers_active = 1;
    p->lens_blur_um = 40.0f;
    p->scanner_lens_blur = 1.5f;
    p->auto_exposure = 1;
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    // ~21 um per pixel: the frame is small, so a 30 mm format keeps the margins
    // (sized in micrometres) to a fraction of its height and leaves several bands.
    p->film_format_mm = 30.0f;
    // Imprint in the bottom-left corner; its box straddles band edges below.
    p->date_stamp_active = 1;
    p->date_stamp_text = "'26 11 09";
    p->date_stamp_corner = 1;
    p->date_stamp_height = 0.12f;
}

// Renders `frame`; the output must be `ew` x `eh` (the input size by default,
// the crop box when the engine crop is on).
bool render(spk_engine* eng, const std::vector<float>& frame, const spk_params& p,
            int64_t min_pixels, int64_t band_pixels, std::vector<float>* out,
            int ew = kW, int eh = kH) {
    spk_set_band_render_limits(min_pixels, band_pixels);
    spk_image in{const_cast<float*>(frame.data()), kW, kH, SPK_CS_PROPHOTO};
    spk_image o{};
    const spk_status st = spk_simulate(eng, &in, &p, &o);
    spk_set_band_render_limits(0, 0);
    if (st != SPK_OK || o.width != ew || o.height != eh) {
        std::printf("render failed: status %d (%dx%d, wanted %dx%d)\n", st, o.width,
                    o.height, ew, eh);
        spk_image_free(&o);
        return false;
    }
    out->assign(o.data, o.data + static_cast<size_t>(ew) * eh * 3);
    spk_image_free(&o);
    return true;
}

void compare(const char* label, const std::vector<float>& got,
             const std::vector<float>& ref, int width = kW) {
    double max_abs = 0.0, sse = 0.0;
    size_t worst = 0;
    for (size_t i = 0; i < ref.size(); ++i) {
        const double d = std::fabs(static_cast<double>(got[i]) - ref[i]);
        if (d > max_abs) { max_abs = d; worst = i; }
        sse += d * d;
    }
    const double rms = std::sqrt(sse / static_cast<double>(ref.size()));
    const bool ok = max_abs <= 1e-4 && rms <= 1e-5;
    std::printf("[%s] max_abs=%.3e rms=%.3e worst row=%zu -> %s\n", label, max_abs, rms,
                worst / 3 / static_cast<size_t>(width), ok ? "PASS" : "FAIL");
    if (!ok) ++g_failures;
}

bool differs(const std::vector<float>& a, const std::vector<float>& b) {
    return std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) != 0;
}

}  // namespace

int main(int argc, char** argv) {
    const std::string assets = argc > 1 ? argv[1] : "../assets/spektra";
    spk_engine* eng = nullptr;
    if (spk_engine_create(assets.c_str(), &eng) != SPK_OK) {
        std::printf("engine create failed for %s\nFAIL\n", assets.c_str());
        return 1;
    }
    const std::vector<float> frame = make_frame();

    // A: banded vs whole, deterministic effects, both routes, two band sizes.
    // 960 x 400 rows leaves a small core per band after the margins, so the
    // frame splits into several bands; 960 x 700 gives fewer, taller ones.
    const int64_t band_sizes[] = {static_cast<int64_t>(kW) * 400,
                                  static_cast<int64_t>(kW) * 700};
    for (int route = 0; route < 2; ++route) {
        spk_params p;
        base_params(&p, route == 1, /*stochastic=*/false);
        std::vector<float> whole;
        if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
        for (int64_t band : band_sizes) {
            std::vector<float> banded;
            const uint64_t before = spk_band_render_bands();
            if (!render(eng, frame, p, 1, band, &banded)) return 1;
            const uint64_t bands = spk_band_render_bands() - before;
            char label[96];
            std::snprintf(label, sizeof label, "%s band=%lld rows",
                          route ? "scan_film" : "print",
                          static_cast<long long>(band / kW));
            std::printf("  %s: %llu bands\n", label, static_cast<unsigned long long>(bands));
            expect(bands >= 3, "frame rendered as 3+ bands");
            compare(label, banded, whole);
        }
    }

    // A2: one wide spatial effect at a time, so a generous margin needed by
    // another effect (the DIR tail) cannot hide a short one. Each blur here is
    // wide enough to run as an IIR, whose band-edge error decays exponentially.
    struct Isolated {
        const char* name;
        void (*set)(spk_params*);
    };
    const Isolated isolated[] = {
        {"scanner lens blur 20 px",
         [](spk_params* q) { q->scanner_lens_blur = 20.0f; }},
        {"scanner unsharp sigma 12 px",
         [](spk_params* q) { q->scanner_unsharp[0] = 12.0f; q->scanner_unsharp[1] = 1.0f; }},
        {"camera lens blur 400 um",
         [](spk_params* q) { q->lens_blur_um = 400.0f; }},
        {"DIR couplers only", [](spk_params* q) { q->dir_couplers_active = 1; }},
        {"halation only", [](spk_params* q) { q->halation_active = 1; }},
    };
    for (const Isolated& c : isolated) {
        for (int route = 0; route < 2; ++route) {
            spk_params p;
            base_params(&p, route == 1, /*stochastic=*/false);
            p.halation_active = 0;
            p.dir_couplers_active = 0;
            p.lens_blur_um = 0.0f;
            p.scanner_lens_blur = 0.0f;
            p.scanner_unsharp[0] = 0.0f;
            p.date_stamp_active = 0;
            c.set(&p);
            std::vector<float> whole, banded;
            if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
            const uint64_t before = spk_band_render_bands();
            if (!render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &banded)) return 1;
            const uint64_t bands = spk_band_render_bands() - before;
            char label[128];
            std::snprintf(label, sizeof label, "%s, %s (%llu bands)", c.name,
                          route ? "scan_film" : "print",
                          static_cast<unsigned long long>(bands));
            expect(bands >= 2, "isolated effect rendered as 2+ bands");
            compare(label, banded, whole);
        }
    }

    // B: thread invariance of the banded render.
    {
        spk_params p;
        base_params(&p, false, false);
        std::vector<float> t1, t8;
        setenv("SPK_NUM_THREADS", "1", 1);
        if (!render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &t1)) return 1;
        setenv("SPK_NUM_THREADS", "8", 1);
        if (!render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &t8)) return 1;
        unsetenv("SPK_NUM_THREADS");
        expect(!differs(t1, t8), "banded 1-vs-8 threads byte-identical");
    }

    // C: stochastic effects on: reproducible, and the same statistics.
    {
        spk_params p;
        base_params(&p, false, true);
        std::vector<float> whole, b1, b2;
        if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
        if (!render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &b1)) return 1;
        if (!render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &b2)) return 1;
        expect(!differs(b1, b2), "grain+glare banded repeat byte-identical");
        expect(differs(b1, whole), "grain+glare banded is re-seeded (bytes differ)");
        bool means_ok = true;
        for (int c = 0; c < 3; ++c) {
            double mw = 0.0, mb = 0.0;
            for (size_t i = c; i < whole.size(); i += 3) { mw += whole[i]; mb += b1[i]; }
            mw /= whole.size() / 3;
            mb /= whole.size() / 3;
            std::printf("  channel %d mean whole=%.6f banded=%.6f\n", c, mw, mb);
            if (std::fabs(mw - mb) > 2e-3) means_ok = false;
        }
        expect(means_ok, "grain+glare banded keeps per-channel means (2e-3)");
    }

    // D: highlight boost and engine crop band, and match the whole frame.
    struct Banded {
        const char* name;
        bool boost;
        bool crop;
        float crop_w;  // crop_size x, a fraction of the long side (kH)
        int ew, eh;    // the expected output (crop box) size
        bool mask = false;
    };
    // Long side 1440: size 0.4 x 0.6 -> 576 x 864 px; 0.7 x 0.75 asks for 1008
    // columns, which the crop clips to the full 960 width (rows used in place).
    const Banded banded_cases[] = {
        {"highlight boost", true, false, 0.0f, kW, kH},
        {"crop 576x864", false, true, 0.4f, 576, 864},
        {"crop full width 960x1080", false, true, 0.7f, kW, 1080},
        {"crop 576x864 + highlight boost", true, true, 0.4f, 576, 864},
        {"contrast mask", false, false, 0.0f, kW, kH, true},
        {"contrast mask + crop + boost", true, true, 0.4f, 576, 864, true},
    };
    auto set_mask = [](spk_params* q) {
        q->contrast_mask_active = 1;
        q->contrast_mask_highlights = 1.5f;
        q->contrast_mask_shadows = 1.5f;
        q->contrast_mask_scale = 0.08f;  // a blur wider than a band
    };
    for (const Banded& c : banded_cases) {
        for (int route = 0; route < 2; ++route) {
            spk_params p;
            base_params(&p, route == 1, /*stochastic=*/false);
            if (c.boost) {
                p.halation_boost_ev = 1.5f;
                p.halation_protect_ev = 0.5f;  // lets the boost reach the mid tones too
            }
            if (c.mask) set_mask(&p);
            if (c.crop) {
                p.crop = 1;
                p.crop_center[0] = 0.45f;
                p.crop_center[1] = 0.55f;
                p.crop_size[0] = c.crop_w;
                p.crop_size[1] = c.crop_w == 0.4f ? 0.6f : 0.75f;
            }
            std::vector<float> whole, banded;
            if (!render(eng, frame, p, kNeverBand, 0, &whole, c.ew, c.eh)) return 1;
            const uint64_t before = spk_band_render_bands();
            const uint64_t pre_before = spk_band_boost_prepasses();
            const uint64_t mask_before = spk_band_mask_prepasses();
            const int64_t band = static_cast<int64_t>(c.ew) * 300;
            if (!render(eng, frame, p, 1, band, &banded, c.ew, c.eh)) return 1;
            const uint64_t bands = spk_band_render_bands() - before;
            const uint64_t pre = spk_band_boost_prepasses() - pre_before;
            char label[160];
            std::snprintf(label, sizeof label, "%s, %s (%llu bands)", c.name,
                          route ? "scan_film" : "print",
                          static_cast<unsigned long long>(bands));
            expect(bands >= 2, "rendered as 2+ bands");
            expect(pre == (c.boost ? 1u : 0u), "one boost pre-pass exactly when boosted");
            // The mask only exists at the print exposure: no pass on scan_film.
            const uint64_t mpre = spk_band_mask_prepasses() - mask_before;
            expect(mpre == ((c.mask && route == 0) ? 1u : 0u),
                   "one mask pre-pass exactly when the print is masked");
            expect(differs(banded, std::vector<float>(banded.size(), 0.0f)),
                   "banded output is not blank");
            compare(label, banded, whole, c.ew);
            if ((c.boost || (c.mask && route == 0)) && !c.crop) {
                // The effect must actually change the picture, or the case above
                // proves nothing about its pre-pass.
                spk_params q = p;
                q.halation_boost_ev = 0.0f;
                q.contrast_mask_active = 0;
                std::vector<float> plain;
                if (!render(eng, frame, q, kNeverBand, 0, &plain)) return 1;
                expect(differs(plain, whole), "the effect changes the render");
            }
        }
    }
    {
        spk_params p;
        base_params(&p, false, false);
        p.halation_boost_ev = 1.5f;
        set_mask(&p);
        p.crop = 1;
        p.crop_center[0] = 0.45f;
        p.crop_center[1] = 0.55f;
        p.crop_size[0] = 0.4f;
        p.crop_size[1] = 0.6f;
        std::vector<float> t1, t8;
        setenv("SPK_NUM_THREADS", "1", 1);
        if (!render(eng, frame, p, 1, 576 * 300, &t1, 576, 864)) return 1;
        setenv("SPK_NUM_THREADS", "8", 1);
        if (!render(eng, frame, p, 1, 576 * 300, &t8, 576, 864)) return 1;
        unsetenv("SPK_NUM_THREADS");
        expect(!differs(t1, t8), "mask + crop + boost banded 1-vs-8 threads byte-identical");
    }

    // D1: an invalid crop never reaches the band planner; it is rejected as before.
    {
        spk_params p;
        base_params(&p, false, false);
        p.crop = 1;
        p.crop_center[0] = 0.5f;
        p.crop_center[1] = 0.5f;
        p.crop_size[0] = std::nanf("");
        p.crop_size[1] = 0.5f;
        spk_set_band_render_limits(1, static_cast<int64_t>(kW) * 300);
        spk_image in{const_cast<float*>(frame.data()), kW, kH, SPK_CS_PROPHOTO};
        spk_image o{};
        const uint64_t before = spk_band_render_bands();
        const spk_status st = spk_simulate(eng, &in, &p, &o);
        spk_set_band_render_limits(0, 0);
        expect(st == SPK_ERR_BAD_ARGS, "NaN crop size -> SPK_ERR_BAD_ARGS");
        expect(spk_band_render_bands() == before, "NaN crop renders no bands");
        spk_image_free(&o);
    }

    // D2: a frame-wide effect that cannot band falls back to the whole frame.
    auto set_diffusion = [](spk_params* q) {
        q->camera_diffusion_active = 1;
        q->camera_diffusion_strength = 0.5f;
    };
    {
        spk_params p;
        base_params(&p, false, false);
        set_diffusion(&p);
        std::vector<float> whole, banded;
        if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
        const uint64_t before = spk_band_render_bands();
        if (!render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &banded)) return 1;
        expect(spk_band_render_bands() == before, "camera diffusion renders no bands");
        expect(!differs(banded, whole), "camera diffusion falls back (byte-identical)");
    }

    {
        spk_params p;
        base_params(&p, false, false);
        p.enlarger_diffusion_active = 1;
        p.enlarger_diffusion_strength = 0.5f;
        std::vector<float> whole, banded;
        if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
        const uint64_t before = spk_band_render_bands();
        if (!render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &banded)) return 1;
        expect(spk_band_render_bands() == before, "enlarger diffusion renders no bands");
        expect(!differs(banded, whole), "enlarger diffusion falls back (byte-identical)");
    }
    {
        // At the whole-frame limit the camera diffusion filter still renders whole.
        spk_params p;
        base_params(&p, false, false);
        set_diffusion(&p);
        std::vector<float> whole, banded;
        if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
        spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH);
        const uint64_t before = spk_band_render_bands();
        const bool ok = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &banded);
        spk_set_whole_frame_pixel_limit(0);
        if (!ok) return 1;
        expect(spk_band_render_bands() == before,
               "camera diffusion at the whole-frame limit renders no bands");
        expect(!differs(banded, whole), "camera diffusion at the limit is exact");
    }

    // E: a frame that must render whole is refused above the whole-frame limit.
    {
        spk_params boost;
        base_params(&boost, false, false);
        boost.upscale_factor = 0.5f;  // engine rescale needs the whole frame
        spk_params plain;
        base_params(&plain, false, false);
        spk_image in{const_cast<float*>(frame.data()), kW, kH, SPK_CS_PROPHOTO};
        const int64_t limit = static_cast<int64_t>(kW) * kH - 1;

        spk_set_whole_frame_pixel_limit(limit);
        spk_set_band_render_limits(1, static_cast<int64_t>(kW) * 400);
        spk_image o{};
        const spk_status refused = spk_simulate(eng, &in, &boost, &o);
        expect(refused == SPK_ERR_OOM, "unbandable frame above the limit -> SPK_ERR_OOM");
        expect(o.data == nullptr && o.width == 0 && o.height == 0,
               "refused render leaves the output empty");
        const spk_status banded = spk_simulate(eng, &in, &plain, &o);
        expect(banded == SPK_OK && o.data != nullptr,
               "bandable frame above the limit still renders");
        spk_image_free(&o);
        spk_set_band_render_limits(0, 0);

        spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH);
        const spk_status at_limit = spk_simulate(eng, &in, &boost, &o);
        expect(at_limit == SPK_OK, "frame AT the limit renders whole");
        spk_image_free(&o);

        spk_set_whole_frame_pixel_limit(0);
        const spk_status lifted = spk_simulate(eng, &in, &boost, &o);
        expect(lifted == SPK_OK, "limit 0 lifts the refusal");
        spk_image_free(&o);
    }

    // F1: the banded diffusion filter against the whole-frame one, on the
    // irradiance. Small highlights up to 8 stops over a dark floor are where a
    // coarse far term errs most: their light lands on pixels hundreds of times
    // darker.
    {
        const int W = 2000, H = 1500;
        std::vector<double> img(static_cast<size_t>(W) * H * 3);
        uint64_t rng = 0x9e3779b97f4a7c15ull;
        auto uni = [&]() {
            rng = rng * 6364136223846793005ull + 1442695040888963407ull;
            return static_cast<double>(rng >> 11) / 9007199254740992.0;
        };
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x)
                for (int c = 0; c < 3; ++c) {
                    const double v = 0.18 * (0.5 + 0.5 * std::sin(x * 0.01 + c) *
                                                      std::cos(y * 0.013)) +
                                     0.05 * uni();
                    img[(static_cast<size_t>(y) * W + x) * 3 + c] = v > 0.002 ? v : 0.002;
                }
        for (int k = 0; k < 60; ++k) {
            const int cx = static_cast<int>(uni() * W), cy = static_cast<int>(uni() * H);
            const double a = std::pow(2.0, uni() * 8.0);
            const int rr = 1 + static_cast<int>(uni() * 10.0);
            for (int y = std::max(0, cy - rr); y < std::min(H, cy + rr); ++y)
                for (int x = std::max(0, cx - rr); x < std::min(W, cx + rr); ++x)
                    for (int c = 0; c < 3; ++c)
                        img[(static_cast<size_t>(y) * W + x) * 3 + c] += a;
        }
        // Pixel sizes put the whole-frame radius cap (min(w,h)/2-1 = 749) at about
        // half the kernel's natural reach, as at 50-200 MP; the last case caps it
        // at a twelfth (a thin panorama crop), where the cut is still bright and
        // the coarse far term blurs it, so it gets its own, looser bound.
        struct Case { spk::DiffusionFamily fam; double px; int f; double worst, rms;
                      double scale = 1.0, core_size = 1.0; };
        const Case cases[] = {
            {spk::DiffusionFamily::kBlackProMist, 5.1, 4, 1e-3, 1e-4},
            {spk::DiffusionFamily::kProMist, 8.7, 4, 1e-3, 1e-4},
            {spk::DiffusionFamily::kGlimmerglass, 3.5, 4, 1e-3, 1e-4},
            {spk::DiffusionFamily::kCinebloom, 13.4, 4, 1e-3, 1e-4},
            {spk::DiffusionFamily::kBlackProMist, 5.1, 8, 1e-3, 1e-4},
            {spk::DiffusionFamily::kCinebloom, 13.4, 8, 1e-3, 1e-4},
            {spk::DiffusionFamily::kCinebloom, 2.2, 4, 3e-3, 2e-4},
            // Widened core (the editor allows spatial scale and core size up to
            // 4): its tail must reach the far part, or it loses mass.
            {spk::DiffusionFamily::kBlackProMist, 20.4, 4, 1e-3, 1e-4, 4.0, 4.0},
        };
        for (const Case& c : cases) {
            spk::DiffusionFilterParams d;
            d.active = true;
            d.family = c.fam;
            d.strength = 0.5;
            d.spatial_scale = c.scale;
            d.core_size = c.core_size;
            std::vector<double> whole = img;
            spk::apply_diffusion_filter_um(whole.data(), W, H, d, c.px, false);
            spk::DiffusionFarField far;
            if (!spk::diffusion_far_init(d, c.px, W, H, &far, c.f)) {
                expect(false, "diffusion_far_init");
                continue;
            }
            const int core = 400, m = far.near_radius;
            for (int c0 = 0; c0 < H; c0 += core) {
                const int c1 = std::min(H, c0 + core);
                spk::diffusion_far_accumulate(img.data() + static_cast<size_t>(c0) * W * 3,
                                              W, c1 - c0, c0, &far);
            }
            spk::diffusion_far_finish(&far);
            std::vector<double> out(img.size());
            for (int c0 = 0; c0 < H; c0 += core) {
                const int c1 = std::min(H, c0 + core);
                const int y0 = std::max(0, c0 - m), y1 = std::min(H, c1 + m);
                std::vector<double> b(img.begin() + static_cast<ptrdiff_t>(y0) * W * 3,
                                      img.begin() + static_cast<ptrdiff_t>(y1) * W * 3);
                spk::apply_diffusion_filter_banded(b.data(), W, y1 - y0, y0, far);
                std::copy(b.begin() + static_cast<ptrdiff_t>(c0 - y0) * W * 3,
                          b.begin() + static_cast<ptrdiff_t>(c1 - y0) * W * 3,
                          out.begin() + static_cast<ptrdiff_t>(c0) * W * 3);
            }
            double worst = 0.0, sse = 0.0;
            for (size_t i = 0; i < out.size(); ++i) {
                const double l = std::fabs(std::log10(out[i] / whole[i]));
                if (!(l <= worst)) worst = l;  // NaN counts as worst
                sse += l * l;
            }
            const double rms = std::sqrt(sse / static_cast<double>(out.size()));
            const bool ok = worst <= c.worst && rms <= c.rms;
            std::printf("[F1 family %d px %.2f f=%d R=%d near=%d] log10 worst=%.2e rms=%.2e -> %s\n",
                        static_cast<int>(c.fam), c.px, c.f, far.radius, far.near_radius,
                        worst, rms, ok ? "PASS" : "FAIL");
            if (!ok) ++g_failures;
        }
    }

    // F1b: the split keeps the kernel's mass, so a flat field stays flat (a
    // widened core included).
    {
        const int W = 1200, H = 900;
        spk::DiffusionFilterParams d;
        d.active = true;
        d.strength = 0.5;
        d.spatial_scale = 4.0;
        d.core_size = 4.0;
        spk::DiffusionFarField far;
        if (spk::diffusion_far_init(d, 4.16, W, H, &far, 4)) {
            std::vector<double> flat(static_cast<size_t>(W) * H * 3, 1.0);
            spk::diffusion_far_accumulate(flat.data(), W, H, 0, &far);
            spk::diffusion_far_finish(&far);
            spk::apply_diffusion_filter_banded(flat.data(), W, H, 0, far);
            double worst = 0.0;
            for (double v : flat) worst = std::max(worst, std::fabs(v - 1.0));
            std::printf("[F1b flat field, widened core] worst |out-1|=%.2e -> %s\n", worst,
                        worst <= 1e-6 ? "PASS" : "FAIL");
            if (!(worst <= 1e-6)) ++g_failures;
        } else {
            expect(false, "diffusion_far_init (flat)");
        }
    }

    // F2: end to end. Above the whole-frame limit the camera diffusion filter
    // bands (one coarse-frame pre-pass), close to the whole render.
    for (int scan_film = 0; scan_film <= 1; ++scan_film) {
        for (int boost = 0; boost <= 1; ++boost) {
            spk_params p;
            base_params(&p, scan_film != 0, false);
            set_diffusion(&p);
            if (boost) p.halation_boost_ev = 2.0f;
            std::vector<float> whole, banded;
            if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
            spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH - 1);
            const uint64_t bands0 = spk_band_render_bands();
            const uint64_t pre0 = spk_band_diffusion_prepasses();
            const bool ok = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 700, &banded);
            spk_set_whole_frame_pixel_limit(0);
            if (!ok) return 1;
            char label[96];
            std::snprintf(label, sizeof(label), "F2 camera diffusion %s%s",
                          scan_film ? "scan_film" : "print", boost ? " + boost" : "");
            expect(spk_band_render_bands() > bands0 + 1, label);
            expect(spk_band_diffusion_prepasses() == pre0 + 1, "one diffusion pre-pass");
            double max_abs = 0.0, sse = 0.0;
            for (size_t i = 0; i < whole.size(); ++i) {
                const double dd = std::fabs(static_cast<double>(banded[i]) - whole[i]);
                if (!(dd <= max_abs)) max_abs = dd;
                sse += dd * dd;
            }
            const double rms = std::sqrt(sse / static_cast<double>(whole.size()));
            const bool close = max_abs <= kDiffusionOutMaxAbs && rms <= kDiffusionOutRms;
            std::printf("[%s] max_abs=%.3e rms=%.3e -> %s\n", label, max_abs, rms,
                        close ? "PASS" : "FAIL");
            if (!close) ++g_failures;
        }
    }
    // F3: the enlarger diffusion filter, alone and stacked with everything that
    // has a frame-wide pre-pass.
    for (int stack = 0; stack <= 1; ++stack) {
        spk_params p;
        base_params(&p, false, false);
        p.enlarger_diffusion_active = 1;
        p.enlarger_diffusion_strength = 0.5f;
        if (stack) {
            set_diffusion(&p);
            p.halation_boost_ev = 2.0f;
            p.contrast_mask_active = 1;
            p.contrast_mask_highlights = 0.5f;
            p.contrast_mask_shadows = 0.3f;
        }
        std::vector<float> whole, banded;
        if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
        spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH - 1);
        const uint64_t bands0 = spk_band_render_bands();
        const uint64_t pre0 = spk_band_diffusion_prepasses();
        const uint64_t mask0 = spk_band_mask_prepasses();
        const bool ok = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 900, &banded);
        spk_set_whole_frame_pixel_limit(0);
        if (!ok) return 1;
        const char* label = stack ? "F3 enlarger + camera diffusion + mask + boost"
                                  : "F3 enlarger diffusion";
        expect(spk_band_render_bands() > bands0 + 1, label);
        expect(spk_band_diffusion_prepasses() == pre0 + (stack ? 2u : 1u),
               "diffusion pre-pass count");
        expect(spk_band_mask_prepasses() == mask0 + (stack ? 1u : 0u), "mask pre-pass count");
        double max_abs = 0.0, sse = 0.0;
        for (size_t i = 0; i < whole.size(); ++i) {
            const double dd = std::fabs(static_cast<double>(banded[i]) - whole[i]);
            if (!(dd <= max_abs)) max_abs = dd;
            sse += dd * dd;
        }
        const double rms = std::sqrt(sse / static_cast<double>(whole.size()));
        const bool close = max_abs <= kDiffusionOutMaxAbs && rms <= kDiffusionOutRms;
        std::printf("[%s] max_abs=%.3e rms=%.3e -> %s\n", label, max_abs, rms,
                    close ? "PASS" : "FAIL");
        if (!close) ++g_failures;
        if (stack) {
            std::vector<float> t1, t8;
            spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH - 1);
            setenv("SPK_NUM_THREADS", "1", 1);
            const bool ok1 = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 900, &t1);
            setenv("SPK_NUM_THREADS", "8", 1);
            const bool ok8 = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 900, &t8);
            unsetenv("SPK_NUM_THREADS");
            spk_set_whole_frame_pixel_limit(0);
            if (!ok1 || !ok8) return 1;
            expect(!differs(t1, t8), "banded stacked diffusion: 1 vs 8 threads byte-identical");
        }
    }
    {
        spk_params p;
        base_params(&p, false, false);
        set_diffusion(&p);
        p.halation_boost_ev = 2.0f;
        std::vector<float> t1, t8;
        spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH - 1);
        setenv("SPK_NUM_THREADS", "1", 1);
        const bool ok1 = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 700, &t1);
        setenv("SPK_NUM_THREADS", "8", 1);
        const bool ok8 = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 700, &t8);
        unsetenv("SPK_NUM_THREADS");
        spk_set_whole_frame_pixel_limit(0);
        if (!ok1 || !ok8) return 1;
        expect(!differs(t1, t8), "banded camera diffusion: 1 vs 8 threads byte-identical");
    }
    // F4: film-density reuse. With a mask or enlarger pre-pass the film side runs
    // once and later passes read it back; deterministic stacks must match the
    // recomputing render at the parity bar, and stay thread-identical.
    for (int mode = 0; mode < 3; ++mode) {
        spk_params p;
        base_params(&p, false, false);
        set_diffusion(&p);
        p.halation_boost_ev = 2.0f;
        if (mode != 1) {
            p.contrast_mask_active = 1;
            p.contrast_mask_highlights = 0.5f;
            p.contrast_mask_shadows = 0.3f;
        }
        if (mode != 0) {
            p.enlarger_diffusion_active = 1;
            p.enlarger_diffusion_strength = 0.5f;
        }
        const char* label = mode == 0   ? "F4 reuse: mask"
                            : mode == 1 ? "F4 reuse: enlarger diffusion"
                                        : "F4 reuse: mask + enlarger diffusion";
        std::vector<float> recomputed, reused, t1, t8;
        spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH - 1);
        spk_set_band_film_reuse(0);
        const uint64_t reuse0 = spk_band_film_reuse_passes();
        const uint64_t bands_a = spk_band_render_bands();
        const bool ok0 = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 900, &recomputed);
        const uint64_t bands_b = spk_band_render_bands();
        expect(spk_band_film_reuse_passes() == reuse0, "reuse off: no reuse pass");
        spk_set_band_film_reuse(1);
        const bool ok1 = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 900, &reused);
        const uint64_t bands_c = spk_band_render_bands();
        expect(spk_band_film_reuse_passes() == reuse0 + 1, "reuse on: one reuse pass");
        // The print passes need only the print-side margin, so the same band
        // size covers the frame in fewer final bands.
        std::printf("  final bands: recompute %llu, reuse %llu\n",
                    static_cast<unsigned long long>(bands_b - bands_a),
                    static_cast<unsigned long long>(bands_c - bands_b));
        expect(bands_c - bands_b < bands_b - bands_a, "reuse: fewer final bands");
        setenv("SPK_NUM_THREADS", "1", 1);
        const bool okt1 = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 900, &t1);
        setenv("SPK_NUM_THREADS", "8", 1);
        const bool okt8 = render(eng, frame, p, 1, static_cast<int64_t>(kW) * 900, &t8);
        unsetenv("SPK_NUM_THREADS");
        spk_set_whole_frame_pixel_limit(0);
        if (!ok0 || !ok1 || !okt1 || !okt8) return 1;
        compare(label, reused, recomputed);
        expect(!differs(t1, t8), "film reuse: 1 vs 8 threads byte-identical");
    }

    spk_engine_destroy(eng);
    if (g_failures) {
        std::printf("FAIL: %d check(s)\n", g_failures);
        return 1;
    }
    std::printf("ALL PASS\n");
    return 0;
}
