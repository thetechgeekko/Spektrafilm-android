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
 *   D. Highlight boost and engine crop band too: the boost takes the frame
 *      maximum from a pre-pass over the band cores, and a crop bands its pixel
 *      box with pixel size and auto exposure pinned to the uncropped input.
 *      Banded is within the parity tolerance of whole, both routes, for a
 *      narrow crop (rows copied per band), a full-width crop (rows used in
 *      place) and crop + boost together; 1-vs-8 threads byte-identical.
 *   D2. A frame-wide effect that still cannot band (the contrast mask) falls
 *      back: the "banded" call is byte-identical to the whole-frame render.
 *   E. The whole-frame limit: above spk_set_whole_frame_pixel_limit a frame
 *      that cannot band (contrast mask) fails with SPK_ERR_OOM and an empty
 *      output, a frame that bands still renders, and 0 lifts the limit.
 *
 * Build from the cpp root like the other e2e gates:
 *   g++ -std=c++17 -O2 -pthread -I. tests/test_band_render.cpp spektra.cpp \
 *     gpu/*.cpp kernels/*.cpp io/*.cpp model/*.cpp profiles/*.cpp \
 *     runtime/*.cpp runtime/stages/*.cpp -o /tmp/test_band_render
 * Run: /tmp/test_band_render <asset_dir>
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "spektra.h"

namespace {

constexpr int kW = 960;
constexpr int kH = 1440;
// Large enough that a whole-frame call never bands.
constexpr int64_t kNeverBand = static_cast<int64_t>(1) << 40;

int g_failures = 0;

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
    };
    // Long side 1440: size 0.4 x 0.6 -> 576 x 864 px; 0.7 x 0.75 asks for 1008
    // columns, which the crop clips to the full 960 width (rows used in place).
    const Banded banded_cases[] = {
        {"highlight boost", true, false, 0.0f, kW, kH},
        {"crop 576x864", false, true, 0.4f, 576, 864},
        {"crop full width 960x1080", false, true, 0.7f, kW, 1080},
        {"crop 576x864 + highlight boost", true, true, 0.4f, 576, 864},
    };
    for (const Banded& c : banded_cases) {
        for (int route = 0; route < 2; ++route) {
            spk_params p;
            base_params(&p, route == 1, /*stochastic=*/false);
            if (c.boost) {
                p.halation_boost_ev = 1.5f;
                p.halation_protect_ev = 0.5f;  // lets the boost reach the mid tones too
            }
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
            expect(differs(banded, std::vector<float>(banded.size(), 0.0f)),
                   "banded output is not blank");
            compare(label, banded, whole, c.ew);
            if (c.boost && !c.crop) {
                // The boost must actually change the picture, or the case above
                // proves nothing about the pre-pass.
                spk_params q = p;
                q.halation_boost_ev = 0.0f;
                std::vector<float> plain;
                if (!render(eng, frame, q, kNeverBand, 0, &plain)) return 1;
                expect(differs(plain, whole), "highlight boost changes the render");
            }
        }
    }
    {
        spk_params p;
        base_params(&p, false, false);
        p.halation_boost_ev = 1.5f;
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
        expect(!differs(t1, t8), "crop + boost banded 1-vs-8 threads byte-identical");
    }

    // D2: a frame-wide effect that cannot band falls back to the whole frame.
    auto set_mask = [](spk_params* q) {
        q->contrast_mask_active = 1;
        q->contrast_mask_highlights = 1.0f;
        q->contrast_mask_shadows = 1.0f;
    };
    {
        spk_params p;
        base_params(&p, false, false);
        set_mask(&p);
        std::vector<float> whole, banded;
        if (!render(eng, frame, p, kNeverBand, 0, &whole)) return 1;
        const uint64_t before = spk_band_render_bands();
        if (!render(eng, frame, p, 1, static_cast<int64_t>(kW) * 400, &banded)) return 1;
        expect(spk_band_render_bands() == before, "contrast mask renders no bands");
        expect(!differs(banded, whole), "contrast mask falls back (byte-identical)");
    }

    // E: a frame that must render whole is refused above the whole-frame limit.
    {
        spk_params boost;
        base_params(&boost, false, false);
        set_mask(&boost);
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

    spk_engine_destroy(eng);
    if (g_failures) {
        std::printf("FAIL: %d check(s)\n", g_failures);
        return 1;
    }
    std::printf("ALL PASS\n");
    return 0;
}
