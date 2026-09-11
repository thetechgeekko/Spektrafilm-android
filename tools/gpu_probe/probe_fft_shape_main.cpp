// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Which transform SHAPE should the GPU diffusion convolution use? (#227)
//
// Overlap-save keeps an (nx-ks+1) x (ny-ks+1) block per tile, so a SQUARE
// transform is tied to the worse axis. At ks = 3059 on a 4080 x 3060 frame,
// 4096 x 4096 keeps 1038 x 1038 and needs twelve tiles; 8192 x 4096 keeps
// 5134 x 1038 and needs three. A work model and a memory-traffic model both
// score the rectangle ~1.9x better.
//
// THE FULL-PIPELINE A/B COULD NOT ANSWER THIS, and that is why this probe
// exists. Run through tools/stage_split the chooser picked n = 4096 under BOTH
// element budgets -- at that tool's geometry the kernel is small enough that the
// square genuinely wins the model -- so the measurement compared a configuration
// against itself. It looked like a 25% regression; it was the phone's own drift,
// and the same knob setting re-run gave 3134.9 ms then 2577.7 ms.
//
// So this drives gpu::fft_convolve DIRECTLY at a caller-chosen geometry and
// forces each shape by capping the cell budget, printing the shape it actually
// got. Nothing here infers: if the two budgets choose the same rectangle it says
// so, and the comparison is void rather than wrong.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "gpu/vulkan_compute.h"

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// A radial exponential, the shape the diffusion PSF actually is, sum-normalised
// the way apply_diffusion_filter_um normalises its truncated kernel.
std::vector<double> make_kernel(int ks, double lambda) {
    std::vector<double> k(static_cast<size_t>(ks) * ks);
    const int c = ks / 2;
    double sum = 0.0;
    for (int y = 0; y < ks; ++y)
        for (int x = 0; x < ks; ++x) {
            const double dx = x - c, dy = y - c;
            const double v = std::exp(-std::sqrt(dx * dx + dy * dy) / lambda);
            k[static_cast<size_t>(y) * ks + x] = v;
            sum += v;
        }
    if (sum > 0.0)
        for (double& v : k) v /= sum;
    return k;
}

std::vector<double> make_scene(int w, int h) {
    std::vector<double> img(static_cast<size_t>(w) * h * 3u, 0.0);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const size_t i = (static_cast<size_t>(y) * w + x) * 3u;
            const double base = 0.02 + 0.08 * (static_cast<double>(x) / w);
            img[i] = base;
            img[i + 1] = base * 0.9;
            img[i + 2] = base * 0.8;
        }
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
    return img;
}

struct Run {
    bool ok = false;
    int n = 0;
    uint32_t tiles = 0;
    bool host_visible = false;
    double ms = 0.0;
};

Run one_run(const std::vector<double>& img, const std::vector<double>& kern,
            int w, int h, int ks, uint64_t budget, std::vector<double>* out) {
    spk::gpu::FftConvolveRequest r;
    r.src_rgb = img.data();
    r.width = w;
    r.height = h;
    r.channel = 0;
    r.kern = kern.data();
    r.ks = ks;
    r.out_stride = 3;
    r.out_offset = 0;
    r.channel2 = 1;
    r.kern2 = kern.data();
    r.out_offset2 = 1;
    r.max_elements = budget;
    spk::gpu::FftConvolveDiagnostics d{};
    const auto t0 = Clock::now();
    const bool ok = spk::gpu::fft_convolve(r, out->data(), &d);
    Run run;
    run.ms = ms_since(t0);
    run.ok = ok && d.engaged;
    run.n = d.transform_size;
    run.tiles = d.tiles;
    run.host_visible = d.scratch_host_visible;
    if (!run.ok) std::printf("    refused: %s\n", d.reason ? d.reason : "?");
    return run;
}

}  // namespace

int main(int argc, char** argv) {
    const int w = argc > 1 ? std::atoi(argv[1]) : 4080;
    const int h = argc > 2 ? std::atoi(argv[2]) : 3060;
    const int ks = argc > 3 ? std::atoi(argv[3]) : 3059;
    const int reps = argc > 4 ? std::atoi(argv[4]) : 2;

    if (!spk::gpu::available()) {
        std::printf("gpu unavailable\n");
        return 1;
    }
    std::printf("frame %dx%d  ks=%d  reps=%d\n", w, h, ks, reps);
    std::printf("engaged=1\n");

    const std::vector<double> img = make_scene(w, h);
    const std::vector<double> kern = make_kernel(ks, ks / 16.0);
    std::vector<double> out(img.size(), 0.0);

    // Largest first, then halved: the same ladder model/diffusion.cpp walks.
    const uint64_t budgets[2] = {33554432ull, 16777216ull};
    const char* names[2] = {"wide (<=32Mi cells)", "square (<=16Mi cells)"};

    double best[2] = {0.0, 0.0};
    int shape_n[2] = {0, 0};
    uint32_t shape_tiles[2] = {0, 0};

    // Interleaved, so thermal drift lands on both arms instead of one. The phone
    // moves ~22% between identical runs, measured; a non-interleaved comparison
    // of two arms cannot see an effect smaller than that.
    for (int rep = 0; rep < reps; ++rep) {
        for (int arm = 0; arm < 2; ++arm) {
            const Run r = one_run(img, kern, w, h, ks, budgets[arm], &out);
            if (!r.ok) continue;
            std::printf("  rep%d %-22s n=%-5d tiles=%-3u hostvis=%d  %.1f ms\n",
                        rep, names[arm], r.n, r.tiles, r.host_visible ? 1 : 0, r.ms);
            if (best[arm] == 0.0 || r.ms < best[arm]) best[arm] = r.ms;
            shape_n[arm] = r.n;
            shape_tiles[arm] = r.tiles;
        }
    }

    std::printf("\n");
    if (shape_n[0] == shape_n[1] && shape_tiles[0] == shape_tiles[1]) {
        // The whole point of printing the shape: a comparison of a configuration
        // against itself must announce itself rather than be read as a result.
        std::printf("VOID: both budgets chose the same shape (n=%d tiles=%u).\n"
                    "At this geometry the chooser prefers that shape under either\n"
                    "cap, so there is nothing to compare. Pick a ks closer to the\n"
                    "frame width to exercise the rectangle.\n",
                    shape_n[0], shape_tiles[0]);
        return 0;
    }
    if (best[0] > 0.0 && best[1] > 0.0) {
        std::printf("best-of-%d:  wide %.1f ms (tiles %u)   square %.1f ms (tiles %u)"
                    "   wide is %.2fx\n",
                    reps, best[0], shape_tiles[0], best[1], shape_tiles[1],
                    best[1] / best[0]);
    }
    return 0;
}
