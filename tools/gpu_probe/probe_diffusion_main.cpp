// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// The camera diffusion filter, CPU (exact FFT) vs GPU (Gaussian mixture), on device (#213).
//
// This stage was 13559 ms of a 25319 ms real export -- the largest single stage, and
// until now the only one with no GPU path. Two questions have to be answered on real
// hardware and neither can be answered on the host:
//
//   1. Does the GPU path ENGAGE, or does it quietly refuse and fall back? The pass is
//      fail-closed by design, so a refusal looks exactly like success from the outside
//      unless the diagnostics are read. They are printed here.
//   2. What does it actually cost, and how far from the exact result is it? The
//      Gaussian mixture is an approximation whose error concentrates on the r = 0 cusp
//      -- the bright core of the bloom -- so a single "max_abs" is worth more than a
//      speedup number on its own.
//
// The image is synthetic and deliberately hostile to a blur comparison: a few very
// bright small highlights on a dim field, which is precisely the content a diffusion
// filter is bought for and precisely where a cusp error shows.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "model/diffusion.h"

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// Bright specular points on a dim gradient: the diffusion filter's actual job.
void fill_scene(std::vector<double>& img, int w, int h) {
    img.assign(static_cast<size_t>(w) * h * 3u, 0.0);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const size_t i = (static_cast<size_t>(y) * w + x) * 3u;
            const double base = 0.02 + 0.08 * (static_cast<double>(x) / w);
            img[i] = base;
            img[i + 1] = base * 0.9;
            img[i + 2] = base * 0.8;
        }
    }
    // Highlights at 1e3 -- a real specular is orders above midgray, and the wide
    // dynamic range is what makes the tail of the PSF visible at all.
    const int pts[][2] = {{1, 3}, {2, 2}, {3, 4}, {5, 5}, {7, 2}};
    for (const auto& p : pts) {
        const int cx = w * p[0] / 8, cy = h * p[1] / 8;
        for (int dy = -2; dy <= 2; ++dy)
            for (int dx = -2; dx <= 2; ++dx) {
                const int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                const size_t i = (static_cast<size_t>(y) * w + x) * 3u;
                img[i] = 1.0e3;
                img[i + 1] = 9.0e2;
                img[i + 2] = 7.5e2;
            }
    }
}

// The same request filming.cpp's gpu_diffusion_pass builds.
bool gpu_diffusion(std::vector<double>& img, int w, int h,
                   const spk::DiffusionFilterParams& p, double pixel_size_um,
                   int terms, double* gpu_ms, const char** reason, int* ncomp) {
    std::vector<spk::DiffusionGaussian> comps;
    double p_s = 0.0;
    int radius = 0;
    if (!spk::build_diffusion_mixture(p, pixel_size_um, w, h, terms, &comps, &p_s, &radius))
        return false;
    *ncomp = static_cast<int>(comps.size());

    std::vector<double> sigma(comps.size());
    std::vector<double> weight(comps.size() * 3u);
    for (size_t i = 0; i < comps.size(); ++i) {
        sigma[i] = comps[i].sigma_px;
        for (int c = 0; c < 3; ++c) weight[i * 3u + static_cast<size_t>(c)] = comps[i].weight[c];
    }

    spk::gpu::HalationScatterRequest r{};
    r.raw_rgb = img.data();
    r.width = w;
    r.height = h;
    r.pixel_size_um = pixel_size_um;
    r.scatter_spatial_scale = 1.0;   // sigmas are already in pixels
    r.scatter_amount = p_s;
    r.mixture_sigma_px = sigma.data();
    r.mixture_weight = weight.data();
    r.mixture_count = static_cast<int>(comps.size());
    r.halation_amount = 0.0;
    r.halation_n_bounces = 0;

    std::vector<double> out(img.size());
    spk::gpu::HalationScatterDiagnostics d{};
    const auto t0 = Clock::now();
    const bool ok = spk::gpu::halation_scatter(r, out.data(), &d);
    *gpu_ms = ms_since(t0);
    *reason = d.reason ? d.reason : "(none)";
    // A refusal is fail-closed and therefore silent from the outside; the counters are
    // the only way to tell "declined before dispatching" from "died mid-submit".
    std::printf("  [diag] attempted=%d engaged=%d bands=%u dispatches=%u "
                "upload=%.1fms gpu=%.1fms readback=%.1fms\n",
                d.attempted ? 1 : 0, d.engaged ? 1 : 0, d.bands, d.dispatches,
                d.upload_ms, d.gpu_ms, d.readback_ms);
    if (!ok || !d.engaged) return false;
    img.swap(out);
    return true;
}

void compare(const std::vector<double>& a, const std::vector<double>& b,
             double* max_abs, double* rms, double* max_rel) {
    double worst = 0.0, sq = 0.0, wrel = 0.0;
    for (size_t i = 0; i < a.size(); ++i) {
        const double d = std::fabs(a[i] - b[i]);
        if (d > worst) worst = d;
        const double denom = std::fabs(a[i]) > 1e-9 ? std::fabs(a[i]) : 1e-9;
        const double rel = d / denom;
        if (rel > wrel) wrel = rel;
        sq += d * d;
    }
    *max_abs = worst;
    *rms = a.empty() ? 0.0 : std::sqrt(sq / static_cast<double>(a.size()));
    *max_rel = wrel;
}

}  // namespace

int main(int argc, char** argv) {
    const int w = argc > 1 ? std::atoi(argv[1]) : 1920;
    const int h = argc > 2 ? std::atoi(argv[2]) : 1080;
    const int terms = argc > 3 ? std::atoi(argv[3]) : 7;
    const double strength = argc > 4 ? std::atof(argv[4]) : 0.5;

    const double pixel_size_um = 36.0 * 1000.0 / static_cast<double>(w > h ? w : h);
    std::printf("== camera diffusion: CPU (exact FFT) vs GPU (mixture) ==\n");
    std::printf("frame %dx%d  pixel_size_um=%.4f  terms=%d  strength=%.2f\n",
                w, h, pixel_size_um, terms, strength);
    std::printf("gpu available: %s\n\n", spk::gpu::available() ? "yes" : "NO");

    spk::DiffusionFilterParams p;
    p.active = true;
    p.family = spk::DiffusionFamily::kBlackProMist;
    p.strength = strength;
    p.spatial_scale = 1.0;

    std::vector<double> base;
    fill_scene(base, w, h);

    // --- CPU, the exact path -------------------------------------------------
    std::vector<double> cpu = base;
    spk::diffusion_reset_fft_fallbacks();
    const auto t0 = Clock::now();
    spk::apply_diffusion_filter_um(cpu.data(), w, h, p, pixel_size_um);
    const double cpu_ms = ms_since(t0);
    const unsigned long long fallbacks = spk::diffusion_fft_fallbacks();

    // --- GPU, the mixture ----------------------------------------------------
    std::vector<double> gpu = base;
    double gpu_ms = 0.0;
    const char* reason = "(none)";
    int ncomp = 0;
    const bool engaged = gpu_diffusion(gpu, w, h, p, pixel_size_um, terms,
                                       &gpu_ms, &reason, &ncomp);

    std::printf("CPU  exact FFT      %9.1f ms   (fft_fallbacks=%llu -- nonzero means the\n"
                "                                DIRECT O(w*h*ks^2) loop ran, not the FFT)\n",
                cpu_ms, fallbacks);
    if (!engaged) {
        std::printf("GPU  mixture        REFUSED  reason=%s  components=%d\n", reason, ncomp);
        std::printf("\nThe pass is fail-closed, so this is a correct outcome, not a crash --\n"
                    "but it means there is no GPU result to compare and no speedup.\n");
        return 1;
    }
    std::printf("GPU  mixture        %9.1f ms   components=%d  reason=%s\n",
                gpu_ms, ncomp, reason);
    std::printf("                    %9.2fx\n\n", cpu_ms > 0 ? cpu_ms / gpu_ms : 0.0);

    double max_abs = 0.0, rms = 0.0, max_rel = 0.0;
    compare(cpu, gpu, &max_abs, &rms, &max_rel);
    // The scene peaks at 1e3, so an absolute difference is only meaningful next to it.
    std::printf("GPU vs CPU on the rendered image:\n");
    std::printf("  max_abs %.4e   rms %.4e   max_rel %.4e   (scene peak 1.0e+03)\n",
                max_abs, rms, max_rel);
    std::printf("\nOracle band for reference: max_abs 1e-4 / rms 1e-5. This route is Fast GPU\n"
                "and is NOT held to it; the export keeps the exact FFT.\n");
    return 0;
}
