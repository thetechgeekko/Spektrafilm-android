// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// A WHOLE FRAME through the CPU route and a WHOLE FRAME through the Fast GPU
// route, compared as pixels.
//
// WHY THIS WAS MISSING, and why that is the wrong kind of gap. Every GPU pass in
// this engine has a gate: test_halation_gpu, test_grain_gpu, test_fft_convolve_gpu,
// test_filming_stage_gpu, test_gpu_host. Each one compares ONE pass against its
// CPU counterpart and holds it to a tolerance. None of them compares a finished
// render against a finished render.
//
// That is not the same check, and the difference is the whole point of a
// tolerance-bounded route. Eight passes each within 1e-6 of the CPU can still
// drift, because they are not independent: the grain sampler draws a DIFFERENT
// REALISATION by construction, the diffusion transform is f32 against f64, and
// the density curves that follow them are steep. A per-pass gate cannot see the
// composition, and the composition is what the user looks at.
//
// The unit is 8-BIT DISPLAY CODES, not relative error, because the output is
// already sRGB-encoded and clipped to [0,1] and a code is what a difference
// means to a viewer. `max_abs` on a [0,1] encoded buffer is quoted too, but the
// code histogram is the number to read: a route that moves 0.4% of pixels by one
// code is invisible, and one that moves any pixel by ten is not.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "spektra.h"

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// The same synthetic scene stage_split renders: a gradient with hard edges and
// speculars, so the spatial stages have something to do and the density curves
// see the full exposure range.
std::vector<float> make_scene(int w, int h) {
    std::vector<float> img(static_cast<size_t>(w) * h * 3);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const double u = static_cast<double>(x) / (w > 1 ? w - 1 : 1);
            const double v = static_cast<double>(y) / (h > 1 ? h - 1 : 1);
            // ~8 stops of range.
            double lum = 0.02 * std::pow(2.0, 8.0 * u);
            // Hard edges: the spatial stages must have gradients to smear.
            if (((x / 64) + (y / 64)) % 2 == 0) lum *= 1.35;
            // Speculars, which is where halation and diffusion actually show.
            const int sx = w / 8, sy = h / 8;
            if ((x % sx) < 6 && (y % sy) < 6) lum = 8.0;
            const size_t o = (static_cast<size_t>(y) * w + x) * 3;
            img[o + 0] = static_cast<float>(lum * (0.55 + 0.45 * v));
            img[o + 1] = static_cast<float>(lum * (0.85 - 0.25 * u));
            img[o + 2] = static_cast<float>(lum * (0.40 + 0.55 * (1.0 - v)));
        }
    }
    return img;
}

void fill_params(spk_params& p, bool all_on, int gpu_export, bool grain) {
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    spk_default_params(&p);
    p.auto_exposure = 0;
    p.exposure_compensation_ev = 0.0f;
    p.density_curve_gamma = 1.0f;
    p.scan_film = 0;
    p.grain_active = grain ? 1 : 0;
    p.halation_active = 1;
    p.dir_couplers_active = 1;
    if (all_on) {
        p.glare_active = 1;
        p.camera_diffusion_active = 1;
        p.camera_diffusion_strength = 0.5f;
        p.lens_blur_um = 30.0f;
        p.halation_boost_ev = 1.0f;
    }
    p.output_color_space = SPK_CS_SRGB;
    p.output_cctf_encoding = 1;
    p.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p.preview_max_size = 0;   // full resolution: the export case
    p.gpu_export = gpu_export;
}

struct Result {
    std::vector<float> px;
    double ms = 0.0;
    char timings[1024] = {0};
};

bool render(const char* asset_dir, const std::vector<float>& scene, int w, int h,
            bool all_on, int gpu_export, bool grain, Result* out) {
    spk_engine* eng = nullptr;
    if (spk_engine_create(asset_dir, &eng) != SPK_OK) {
        std::fprintf(stderr, "engine create failed\n");
        return false;
    }
    spk_params p{};
    fill_params(p, all_on, gpu_export, grain);
    spk_image in{const_cast<float*>(scene.data()), w, h, SPK_CS_PROPHOTO};
    spk_image img{};
    const auto t0 = Clock::now();
    const spk_status st = spk_simulate(eng, &in, &p, &img);
    out->ms = ms_since(t0);
    if (st != SPK_OK) {
        std::fprintf(stderr, "simulate failed (gpu_export=%d): %s\n", gpu_export,
                     spk_status_str(st));
        spk_engine_destroy(eng);
        return false;
    }
    spk_stage_timings(out->timings, sizeof(out->timings));
    out->px.assign(img.data, img.data + static_cast<size_t>(img.width) * img.height * 3);
    spk_image_free(&img);
    spk_engine_destroy(eng);
    return true;
}

int g_failures = 0;

void compare(const char* label, const Result& cpu, const Result& gpu) {
    if (cpu.px.size() != gpu.px.size()) {
        std::printf("[FAIL] %s: size mismatch\n", label);
        ++g_failures;
        return;
    }
    double max_abs = 0.0, sq = 0.0;
    int max_code = 0;
    // A histogram, not just a maximum: one stray pixel and a whole frame shifted
    // read identically through max_code alone, and they are not the same defect.
    long long hist[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    size_t worst_i = 0;
    for (size_t i = 0; i < cpu.px.size(); ++i) {
        const double a = cpu.px[i], b = gpu.px[i];
        const double diff = std::fabs(a - b);
        if (diff > max_abs) { max_abs = diff; worst_i = i; }
        sq += diff * diff;
        const int ca = static_cast<int>(std::lround(std::min(std::max(a, 0.0), 1.0) * 255.0));
        const int cb = static_cast<int>(std::lround(std::min(std::max(b, 0.0), 1.0) * 255.0));
        const int cd = std::abs(ca - cb);
        if (cd > max_code) max_code = cd;
        hist[cd < 7 ? cd : 7] += 1;
    }
    const double n = static_cast<double>(cpu.px.size());
    std::printf("  %s\n", label);
    std::printf("    cpu %.1f ms   gpu %.1f ms   %.2fx\n", cpu.ms, gpu.ms,
                gpu.ms > 0 ? cpu.ms / gpu.ms : 0.0);
    std::printf("    max_abs %.4e (encoded 0-1)  rms %.4e  worst component %zu\n",
                max_abs, std::sqrt(sq / n), worst_i);
    std::printf("    display codes: max %d   equal %.4f%%  <=1 %.4f%%  <=2 %.4f%%\n",
                max_code, 100.0 * hist[0] / n,
                100.0 * (hist[0] + hist[1]) / n,
                100.0 * (hist[0] + hist[1] + hist[2]) / n);
    std::printf("    histogram by code delta 0..6+, counts: ");
    for (int k = 0; k < 8; ++k) std::printf("%lld ", hist[k]);
    std::printf("\n");

    // NO PASS/FAIL BAR IS ASSERTED HERE ON THE COMPOSITION, deliberately. The
    // grain sampler on the Fast GPU route draws a different realisation by
    // design (owner decision #180), so the two frames CANNOT match pixel-wise
    // and any tolerance would be an invented one. What this reports is the
    // distribution, so the number can be judged rather than hidden. The one
    // thing it does assert is that neither route produced garbage.
    long long nonfinite = 0;
    for (float v : gpu.px) if (!std::isfinite(v)) ++nonfinite;
    if (nonfinite) {
        std::printf("[FAIL] %s: %lld non-finite components in the GPU render\n",
                    label, nonfinite);
        ++g_failures;
    }
    if (max_code > 64) {
        std::printf("[FAIL] %s: %d display codes is not a tolerance, it is a bug\n",
                    label, max_code);
        ++g_failures;
    }
}

}  // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    const char* asset_dir = argc > 1 ? argv[1] : "../assets/spektra";
    const int side = argc > 2 ? std::atoi(argv[2]) : 1024;

    std::printf("== whole-frame CPU route vs Fast GPU route ==\n");
    std::printf("side=%d  assets=%s\n\n", side, asset_dir);
    const std::vector<float> scene = make_scene(side, side);

    // GRAIN OFF FIRST, and it is the case that actually answers the question.
    // With grain on, the Fast GPU route draws a DIFFERENT REALISATION by design
    // (owner decision #180), so the two frames cannot match and a code histogram
    // measures the noise, not the pipeline. Switch grain off and every remaining
    // stage is supposed to compute the SAME numbers to within f32 -- so this row
    // is the one that says whether eight tolerance-bounded passes in series have
    // drifted.
    struct Case { const char* name; bool all_on; bool grain; };
    const Case cases[] = {
        {"GRAIN OFF, ALL ON otherwise -- the deterministic comparison", true, false},
        {"defaults (grain + halation + DIR)", false, true},
        {"ALL ON (+ glare, Pro-Mist, lens blur, highlight boost)", true, true},
    };

    for (const Case& cs : cases) {
        Result cpu, gpu;
        if (!render(asset_dir, scene, side, side, cs.all_on, 0, cs.grain, &cpu)) return 2;
        if (!render(asset_dir, scene, side, side, cs.all_on, 1, cs.grain, &gpu)) return 2;
        compare(cs.name, cpu, gpu);
        std::printf("    cpu stages: %s\n", cpu.timings);
        std::printf("    gpu stages: %s\n\n", gpu.timings);
    }

    if (g_failures == 0) std::printf("probe_route_compare: no failures\n");
    else std::printf("probe_route_compare: %d FAIL\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
