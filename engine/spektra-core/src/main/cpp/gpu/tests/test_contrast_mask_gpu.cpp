/*
 * Spektrafilm for Android -- GPU contrast-mask gain gate (gpu/contrast_mask.comp).
 * GPLv3. Film modeling powered by spektrafilm.
 *
 * On the GPU print route the contrast mask's per-pixel gain comes from the GPU
 * and the CPU applies it to the float64 exposure. This gates, on a whole frame
 * and on a banded frame (grid route), that:
 *   - the GPU mask actually engaged (its pass counter rose),
 *   - the GPU export is within the oracle band (1e-4) of the CPU export with
 *     the same mask, and the mask itself moved the image far more than that,
 *   - repeated GPU renders are byte-identical.
 * Without a Vulkan device nothing engages and the gate fails loudly rather
 * than passing vacuously.
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "runtime/stages/printing.h"
#include "spektra.h"

namespace {

int g_fail = 0;

void check(bool ok, const std::string& what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what.c_str());
    if (!ok) g_fail = 1;
}

double max_abs(const std::vector<float>& a, const std::vector<float>& b) {
    if (a.size() != b.size()) return 1e30;
    double m = 0.0;
    for (size_t i = 0; i < a.size(); ++i) {
        const double d = std::fabs(static_cast<double>(a[i]) - static_cast<double>(b[i]));
        if (d > m) m = d;
    }
    return m;
}

// Highlights, shadows and mid-tones in large areas, so the mask has both
// directions to pull and a core to leave alone.
std::vector<float> make_input(int w, int h) {
    std::vector<float> v(static_cast<size_t>(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const size_t i = (static_cast<size_t>(y) * w + x) * 3;
            const float fx = static_cast<float>(x) / static_cast<float>(w - 1);
            const float fy = static_cast<float>(y) / static_cast<float>(h - 1);
            float base = fx < 0.33f ? 0.004f : (fx < 0.66f ? 0.18f : 0.95f);
            base *= 0.7f + 0.6f * fy;
            v[i + 0] = base * (1.0f + 0.1f * ((x + y) % 7) / 6.0f);
            v[i + 1] = base;
            v[i + 2] = base * (0.9f + 0.2f * ((x * 3 + y) % 5) / 4.0f);
        }
    return v;
}

const int W = 240, H = 180;
std::vector<float> g_input;

bool render(spk_engine* eng, const spk_params& q, std::vector<float>* out) {
    spk_image in{g_input.data(), W, H, 0};
    spk_image o{};
    const spk_status st = spk_simulate(eng, &in, &q, &o);
    if (st != SPK_OK) {
        std::printf("FAIL: render st=%d detail=%s\n", st, spk_last_error_message());
        g_fail = 1;
        return false;
    }
    out->assign(o.data, o.data + static_cast<size_t>(o.width) * o.height * 3);
    spk_image_free(&o);
    return true;
}

spk_params base_params() {
    spk_params p{};
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    spk_default_params(&p);
    p.auto_exposure = 0;
    p.grain_active = 0;
    p.halation_active = 0;
    p.glare_active = 0;
    p.disable_buffer_memos = 1;
    p.contrast_mask_active = 1;
    p.contrast_mask_highlights = 1.5f;
    p.contrast_mask_shadows = 1.0f;
    p.contrast_mask_core = 0.25f;
    p.contrast_mask_scale = 0.05f;
    return p;
}

void run_route(spk_engine* eng, const char* label) {
    spk_params cpu = base_params();
    cpu.gpu_export = 0;
    cpu.allow_gpu_scan = 0;
    spk_params gpu = base_params();
    gpu.gpu_export = 1;
    spk_params off = cpu;
    off.contrast_mask_active = 0;

    std::vector<float> c, g1, g2, o;
    if (!render(eng, cpu, &c) || !render(eng, off, &o)) return;
    const unsigned long long before = spk::gpu_contrast_mask_frames_rendered();
    if (!render(eng, gpu, &g1) || !render(eng, gpu, &g2)) return;
    const unsigned long long engaged = spk::gpu_contrast_mask_frames_rendered() - before;
    std::printf("%s: gpu mask passes=%llu engaged=%d\n", label, engaged, engaged > 0 ? 1 : 0);
    check(engaged > 0, std::string(label) + ": GPU contrast-mask gain engaged");
    const double band = max_abs(g1, c);
    const double moved = max_abs(c, o);
    std::printf("%s: GPU-vs-CPU max_abs=%.3e, mask effect max_abs=%.3e\n", label, band, moved);
    check(band <= 1e-4, std::string(label) + ": GPU mask export within 1e-4 of CPU mask export");
    check(moved > 1e-2, std::string(label) + ": the mask moved the image (not a vacuous pass)");
    check(g1.size() == g2.size() &&
              std::memcmp(g1.data(), g2.data(), g1.size() * sizeof(float)) == 0,
          std::string(label) + ": repeated GPU renders byte-identical");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <asset_dir>\n", argv[0]);
        return 2;
    }
    spk_engine* eng = nullptr;
    if (spk_engine_create(argv[1], &eng) != SPK_OK) {
        std::fprintf(stderr, "engine create failed from %s\n", argv[1]);
        return 2;
    }
    g_input = make_input(W, H);
    check(spk::gpu::available(), "Vulkan device available");

    run_route(eng, "whole");
    // Force the banded renderer on this small frame: bands of ~1/3 of it, so
    // the mask runs as accumulate -> frame grid -> per-band apply.
    spk_set_band_render_limits(static_cast<int64_t>(W) * H / 2, static_cast<int64_t>(W) * 60);
    const uint64_t prepasses_before = spk_band_mask_prepasses();
    run_route(eng, "banded");
    check(spk_band_mask_prepasses() > prepasses_before,
          "banded: the mask ran through the frame-grid pre-pass");
    spk_set_band_render_limits(0, 0);

    spk_engine_destroy(eng);
    std::printf("test_contrast_mask_gpu: %s\n", g_fail ? "FAIL" : "ALL OK");
    return g_fail;
}
