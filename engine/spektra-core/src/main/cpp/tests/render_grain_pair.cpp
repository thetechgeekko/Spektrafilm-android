// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// TOOL, NOT A GATE. Absent from the engine-parity table and from CI on purpose:
// it renders images for a human to look at, and "does this still look like the
// film stock" is a judgement, not a threshold.
//
// #180's last acceptance item is a VISUAL gate on the cheaper sampler. This
// renders the pair to judge.
//
// Run it with NO Vulkan ICD visible. Then gpu_export=1 engages no GPU at all,
// and the ONLY difference between the two renders is the #180 sampler swap --
// which is precisely what is being judged. With an ICD present the shader
// differences (~2e-6) would ride along and muddy the comparison.
//
// Writes two binary PPMs, plus a difference map scaled so the noise is visible.
//
// Usage:
//   g++ -std=c++17 -O3 -ffast-math -fno-finite-math-only -pthread -I. //     tests/render_grain_pair.cpp spektra.cpp gpu/*.cpp kernels/*.cpp io/*.cpp //     model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp //     -o /tmp/render_grain_pair
//   VK_ICD_FILENAMES=/nonexistent.json /tmp/render_grain_pair <assets> <prefix> [particle_area_um2]
//
// The third argument stresses the grain (default 0.2). At the default the two
// realisations differ by 0.0072 rms -- about 1.8 levels out of 255 -- which is
// too subtle to judge; 4.0 makes the grain plainly visible and gives 0.0324
// rms. Judge at the stressed setting, then confirm at the default.
//
// Output is binary PPM so this needs no image library. Convert with any tool.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "spektra.h"

namespace {

const int W = 900, H = 600;

// A smooth mid-tone ramp with flat patches. Grain is most visible in smooth
// mid-tones, so this is a harsher test of the realisation than a busy photo.
std::vector<float> make_input() {
    std::vector<float> v(static_cast<size_t>(W) * H * 3);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const size_t i = (static_cast<size_t>(y) * W + x) * 3;
            // Horizontal exposure ramp over ~4 stops.
            double l = 0.02 + 0.55 * (static_cast<double>(x) / (W - 1));
            // Six flat patches across the lower third: no gradient to hide in.
            if (y > H * 2 / 3) {
                const int patch = x * 6 / W;
                l = 0.04 + 0.10 * patch;
            }
            // Gentle vertical warm/cool tilt so all three channels are exercised.
            const double t = static_cast<double>(y) / (H - 1);
            v[i + 0] = static_cast<float>(l * (0.92 + 0.16 * t));
            v[i + 1] = static_cast<float>(l);
            v[i + 2] = static_cast<float>(l * (1.08 - 0.16 * t));
        }
    }
    return v;
}

bool write_ppm(const std::string& path, const std::vector<float>& rgb) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    std::fprintf(f, "P6\n%d %d\n255\n", W, H);
    std::vector<unsigned char> row(static_cast<size_t>(W) * 3);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W * 3; ++x) {
            double v = rgb[(static_cast<size_t>(y) * W * 3) + x];
            if (!(v > 0.0)) v = 0.0;
            if (v > 1.0) v = 1.0;
            row[x] = static_cast<unsigned char>(v * 255.0 + 0.5);
        }
        std::fwrite(row.data(), 1, row.size(), f);
    }
    std::fclose(f);
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s <asset_dir> <out_prefix>\n", argv[0]);
        return 2;
    }
    spk_engine* eng = nullptr;
    if (spk_engine_create(argv[1], &eng) != SPK_OK) {
        std::fprintf(stderr, "engine create failed\n");
        return 2;
    }
    const std::vector<float> input = make_input();

    std::vector<float> out[2];
    for (int fast = 0; fast < 2; ++fast) {
        spk_params p;
        p.film_profile = "kodak_portra_400";
        p.print_profile = "kodak_portra_endura";
        spk_default_params(&p);
        p.gpu_export = fast;  // with no ICD this ONLY swaps the sampler
        // Optional grain stress. Larger particles mean fewer of them per pixel,
        // so the particle model's variance rises and the grain becomes plainly
        // visible -- the regime a visual gate should be judged in, rather than
        // the default where the realisations differ by ~1.8/255 rms.
        if (argc > 3) p.grain_particle_area_um2 = std::atof(argv[3]);

        spk_image in{const_cast<float*>(input.data()), W, H, 0};
        spk_image o{};
        if (spk_simulate(eng, &in, &p, &o) != SPK_OK) {
            std::fprintf(stderr, "render failed: %s\n", spk_last_error_message());
            return 2;
        }
        out[fast].assign(o.data,
                         o.data + static_cast<size_t>(o.width) * o.height * 3);
        spk_image_free(&o);
    }

    const std::string prefix = argv[2];
    write_ppm(prefix + "-exact.ppm", out[0]);
    write_ppm(prefix + "-fast.ppm", out[1]);

    // Difference, centred on mid grey and amplified, so "different realisation"
    // is visible as noise rather than as a black frame.
    std::vector<float> diff(out[0].size());
    double max_abs = 0.0, sum_sq = 0.0;
    for (size_t i = 0; i < diff.size(); ++i) {
        const double d = static_cast<double>(out[1][i]) - static_cast<double>(out[0][i]);
        max_abs = std::fmax(max_abs, std::fabs(d));
        sum_sq += d * d;
        diff[i] = static_cast<float>(0.5 + d * 8.0);
    }
    write_ppm(prefix + "-diff8x.ppm", diff);

    std::printf("wrote %s-{exact,fast,diff8x}.ppm  %dx%d\n", prefix.c_str(), W, H);
    std::printf("difference: max_abs=%.5f  rms=%.5f  (display range 0..1)\n",
                max_abs, std::sqrt(sum_sq / static_cast<double>(diff.size())));
    spk_engine_destroy(eng);
    return 0;
}
