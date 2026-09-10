// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// The two spatial stages moved in #215, CPU vs GPU, on device.
//
//   gpu::gaussian_blur_rgb  -- the camera lens blur, the scanner lens blur and
//                              the unsharp mask's blur term, all of which are
//                              gaussian_blur_per_channel_d(rgb, w, h, 3, sigma)
//   gpu::glare_field        -- the lognormal viewing-glare field and its blur
//
// The lavapipe gates already prove both agree with the CPU (the blur to
// max_abs ~1e-6, the glare field statistically). lavapipe is a CPU rasterizer,
// so it says NOTHING about speed; that is what this is for.
//
// Both are reported at 1080p and at 12.5 MP because the two answer different
// questions. The export verdict for these stages was "below the +/-250 ms noise
// floor, not worth porting"; the video verdict is the opposite, because 33 ms is
// the whole frame budget. Printing both keeps that honest.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "kernels/exponential_filter.h"
#include "model/glare.h"

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

void fill(std::vector<double>& img, int w, int h) {
    img.resize(static_cast<size_t>(w) * h * 3u);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const size_t i = (static_cast<size_t>(y) * w + x) * 3u;
            const double t = static_cast<double>(x) / (w > 1 ? w - 1 : 1);
            const double u = static_cast<double>(y) / (h > 1 ? h - 1 : 1);
            img[i] = 0.05 + 0.9 * t;
            img[i + 1] = 0.05 + 0.9 * u;
            img[i + 2] = 0.05 + 0.45 * (t + u);
        }
}

void blur_case(int w, int h, double sigma) {
    std::vector<double> base;
    fill(base, w, h);
    const double sg[3] = {sigma, sigma, sigma};

    std::vector<double> cpu = base;
    const auto t0 = Clock::now();
    spk::gaussian_blur_per_channel_d(cpu.data(), w, h, 3, sg);
    const double cpu_ms = ms_since(t0);

    // Warm first: the first call builds the pipeline and the whole-frame work
    // buffers, and reporting that as the per-call cost is the #208 mistake.
    std::vector<double> warm = base;
    const auto tw = Clock::now();
    const bool ok_warm = spk::gpu::gaussian_blur_rgb(warm.data(), w, h, sg);
    const double cold_ms = ms_since(tw);

    std::vector<double> gpu = base;
    const auto t1 = Clock::now();
    const bool ok = spk::gpu::gaussian_blur_rgb(gpu.data(), w, h, sg);
    const double gpu_ms = ms_since(t1);

    if (!ok || !ok_warm) {
        std::printf("  blur sigma %-5.2f  %dx%d   CPU %8.1f ms   GPU REFUSED\n",
                    sigma, w, h, cpu_ms);
        return;
    }
    double worst = 0.0, sq = 0.0;
    for (size_t i = 0; i < cpu.size(); ++i) {
        const double d = std::fabs(cpu[i] - gpu[i]);
        if (d > worst) worst = d;
        sq += d * d;
    }
    std::printf("  blur sigma %-5.2f  %dx%d   CPU %8.1f ms   GPU %7.1f ms (cold %7.1f)  "
                "%6.2fx   max_abs %.2e  rms %.2e\n",
                sigma, w, h, cpu_ms, gpu_ms, cold_ms,
                gpu_ms > 0 ? cpu_ms / gpu_ms : 0.0, worst,
                std::sqrt(sq / static_cast<double>(cpu.size())));
}

void glare_case(int w, int h) {
    const size_t n = static_cast<size_t>(w) * h;
    const float amount = 0.03f, roughness = 0.7f, blur = 0.5f;

    std::vector<float> cpu(n, 0.0f);
    const auto t0 = Clock::now();
    spk::compute_random_glare_amount(amount, roughness, blur, w, h, 12345u,
                                     cpu.data(), spk::StatsRng::Generator::Exact);
    const double cpu_ms = ms_since(t0);

    spk::gpu::GlareRequest req;
    req.amount = amount;
    req.roughness = roughness;
    req.blur_px = blur;
    req.seed = 12345u;

    std::vector<float> warm(n, 0.0f);
    spk::gpu::GlareDiagnostics dw{};
    const auto tw = Clock::now();
    const bool ok_warm = spk::gpu::glare_field(warm.data(), w, h, req, &dw);
    const double cold_ms = ms_since(tw);

    std::vector<float> gpu(n, 0.0f);
    spk::gpu::GlareDiagnostics d{};
    const auto t1 = Clock::now();
    const bool ok = spk::gpu::glare_field(gpu.data(), w, h, req, &d);
    const double gpu_ms = ms_since(t1);

    if (!ok || !ok_warm) {
        std::printf("  glare          %dx%d   CPU %8.1f ms   GPU REFUSED (%s)\n",
                    w, h, cpu_ms, d.reason && *d.reason ? d.reason : "?");
        return;
    }
    double sc = 0.0, sg = 0.0, qc = 0.0, qg = 0.0;
    for (size_t i = 0; i < n; ++i) {
        sc += cpu[i]; qc += static_cast<double>(cpu[i]) * cpu[i];
        sg += gpu[i]; qg += static_cast<double>(gpu[i]) * gpu[i];
    }
    const double nn = static_cast<double>(n);
    const double mc = sc / nn, mg = sg / nn;
    const double sdc = std::sqrt(std::max(0.0, qc / nn - mc * mc));
    const double sdg = std::sqrt(std::max(0.0, qg / nn - mg * mg));
    std::printf("  glare          %dx%d   CPU %8.1f ms   GPU %7.1f ms (cold %7.1f)  "
                "%6.2fx   mean %.4e/%.4e  sd ratio %.4f\n",
                w, h, cpu_ms, gpu_ms, cold_ms, gpu_ms > 0 ? cpu_ms / gpu_ms : 0.0,
                mc, mg, sdc > 0 ? sdg / sdc : 0.0);
}

}  // namespace

int main(int argc, char** argv) {
    std::printf("== spatial stages moved in #215: CPU vs GPU ==\n");
    std::printf("gpu available: %s\n\n", spk::gpu::available() ? "yes" : "NO");

    struct Frame { int w, h; const char* what; };
    const Frame frames[] = {
        {1920, 1080, "1080p video"},
        {4080, 3060, "12.5 MP export"},
    };
    for (const Frame& f : frames) {
        std::printf("%s (%dx%d):\n", f.what, f.w, f.h);
        // 0.7 is the production unsharp default; 3.5 is an IIR-class scanner blur.
        blur_case(f.w, f.h, 0.7);
        blur_case(f.w, f.h, 3.5);
        glare_case(f.w, f.h);
        std::printf("\n");
    }
    (void)argc; (void)argv;
    return 0;
}
