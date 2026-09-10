// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// The AgX particle grain sampler, CPU vs GPU, on device (#214).
//
// Grain is the largest CPU stage left in an export (~4 s of an 8.7 s GPU-route
// render) and 94.6% of it is the Poisson -> Binomial draw. gpu/grain.comp ports
// that draw. The gate under lavapipe already proves the shader samples the right
// DISTRIBUTION -- mean and variance against a closed form -- so that is not what
// this probe is for. Two things can only be answered on real hardware:
//
//   1. What does it actually cost? lavapipe is a CPU rasterizer and says nothing
//      about speed. The diffusion port (#213) is the cautionary tale: a host
//      measurement suggested 109x and the device said 1.6x.
//   2. Does the pass ENGAGE at export resolution, or trip the ~2 s GPU watchdog
//      the way the diffusion mixture did at 12.5 MP?
//
// What this probe does NOT establish is the out-of-branch rate on real content.
// apply_grain_to_density_layers does not surface the pass diagnostics (there is
// no stage_timer channel yet, deliberately, while the route is off), and the
// density field here sweeps the range uniformly anyway -- the same prior the
// census already used, whereas a photograph concentrates its pixels. That
// question needs a real render, not this.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "model/grain.h"

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

struct Moments {
    double mean = 0.0;
    double sd = 0.0;
};

Moments moments(const std::vector<float>& v, int channel) {
    double s = 0.0, s2 = 0.0;
    size_t n = 0;
    for (size_t i = static_cast<size_t>(channel); i < v.size(); i += 3u) {
        const double x = v[i];
        s += x;
        s2 += x * x;
        ++n;
    }
    Moments m;
    if (n == 0) return m;
    m.mean = s / static_cast<double>(n);
    const double var = s2 / static_cast<double>(n) - m.mean * m.mean;
    m.sd = var > 0.0 ? std::sqrt(var) : 0.0;
    return m;
}


// Why would the host refuse? Replicated from grain.cpp's own test so a refusal
// reports its cause instead of leaving three possibilities open. The worst cell
// is the one with the LARGEST od_particle = dmax_layer / n_ppp, i.e. the fewest
// particles per pixel, i.e. the coarsest (agx_particle_scale[c] *
// agx_particle_scale_layers[sl]) product -- which is not the middle cell.
void report_refusal_margin(double pixel_size_um, const spk::GrainParams& g,
                           const double* dmax_layers) {
    double dmax_total[3] = {0, 0, 0};
    for (int sl = 0; sl < 3; ++sl)
        for (int c = 0; c < 3; ++c) dmax_total[c] += dmax_layers[sl * 3 + c];
    const double pixel_area = pixel_size_um * pixel_size_um;
    std::printf("dye-cloud blur per cell (identity iff radius == 0, "
                "radius = int(3*sigma + 0.5), so sigma < 1/6):\n");
    double worst_sigma = 0.0;
    int worst_sl = -1, worst_c = -1;
    for (int sl = 0; sl < 3; ++sl) {
        for (int c = 0; c < 3; ++c) {
            const double frac = dmax_layers[sl * 3 + c] / dmax_total[c];
            const double dmin_l = frac * g.density_min[c];
            const double dmax_l = dmax_layers[sl * 3 + c] + dmin_l;
            const double particle_area = g.agx_particle_area_um2 *
                                         g.agx_particle_scale[c] *
                                         g.agx_particle_scale_layers[sl];
            const double n_ppp = pixel_area * frac / particle_area;
            const double od = dmax_l / n_ppp;
            const double sigma = g.blur_dye_clouds_um * std::sqrt(od);
            const int radius = static_cast<int>(3.0f * static_cast<float>(sigma) + 0.5f);
            if (sigma > worst_sigma) { worst_sigma = sigma; worst_sl = sl; worst_c = c; }
            std::printf("  sl=%d c=%d  n_ppp %8.2f  od %.6f  sigma %.4f px  radius %d%s\n",
                        sl, c, n_ppp, od, sigma, radius, radius == 0 ? "" : "  <-- REFUSES");
        }
    }
    std::printf("  worst cell sl=%d c=%d sigma %.4f px (threshold %.4f)\n\n",
                worst_sl, worst_c, worst_sigma, 1.0 / 6.0);
}

}  // namespace

int main(int argc, char** argv) {
    const int w = argc > 1 ? std::atoi(argv[1]) : 1920;
    const int h = argc > 2 ? std::atoi(argv[2]) : 1080;
    const int npix = w * h;
    const double pixel_size_um = 36.0 * 1000.0 / static_cast<double>(w > h ? w : h);

    std::printf("== AgX particle grain: CPU vs GPU sampler ==\n");
    std::printf("frame %dx%d (%.2f MP)  pixel_size_um=%.4f\n", w, h,
                npix / 1.0e6, pixel_size_um);
    std::printf("gpu available: %s\n\n", spk::gpu::available() ? "yes" : "NO");

    // density_cmy_layers is (npix, 3 sublayers, 3 channels) row-major. Sweep the
    // density range across x so every part of it is exercised, and offset the
    // channels so they do not all sit at the same p.
    double dmax_layers[9];
    for (int i = 0; i < 9; ++i) dmax_layers[i] = 2.2 / 3.0;
    std::vector<float> layers(static_cast<size_t>(npix) * 9u);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const double t = static_cast<double>(x) / (w > 1 ? (w - 1) : 1);
            for (int sl = 0; sl < 3; ++sl) {
                for (int c = 0; c < 3; ++c) {
                    const double v = (2.2 / 3.0) * t * (0.8 + 0.1 * c);
                    layers[(static_cast<size_t>(y) * w + x) * 9u +
                           static_cast<size_t>(sl) * 3u + static_cast<size_t>(c)] =
                        static_cast<float>(v);
                }
            }
        }
    }

    spk::GrainParams gp;
    gp.active = true;
    gp.sublayers_active = true;
    gp.blur = 0.0;   // isolate the sampler: the final blur is identical either way

    report_refusal_margin(pixel_size_um, gp, dmax_layers);

    std::vector<float> cpu(static_cast<size_t>(npix) * 3u, 0.0f);
    std::vector<float> gpu(static_cast<size_t>(npix) * 3u, 0.0f);

    const auto t0 = Clock::now();
    spk::apply_grain_to_density_layers(layers.data(), npix, w, h, dmax_layers,
                                       pixel_size_um, gp, cpu.data());
    const double cpu_ms = ms_since(t0);

    // Warm the pipeline first: the first call builds the shader module,
    // pipeline, descriptor pool and buffers, and reporting that as the cost of
    // the pass is exactly the cold-start-read-as-per-call error of #208.
    spk::GrainParams gg = gp;
    gg.allow_gpu_sampler = true;
    std::vector<float> warm(static_cast<size_t>(npix) * 3u, 0.0f);
    const auto tw = Clock::now();
    spk::apply_grain_to_density_layers(layers.data(), npix, w, h, dmax_layers,
                                       pixel_size_um, gg, warm.data());
    const double cold_ms = ms_since(tw);

    const auto t1 = Clock::now();
    spk::apply_grain_to_density_layers(layers.data(), npix, w, h, dmax_layers,
                                       pixel_size_um, gg, gpu.data());
    const double gpu_ms = ms_since(t1);

    const bool engaged = (gpu != cpu);
    std::printf("CPU sampler          %9.1f ms\n", cpu_ms);
    std::printf("GPU sampler (cold)   %9.1f ms   (includes pipeline + buffer creation)\n",
                cold_ms);
    std::printf("GPU sampler (warm)   %9.1f ms   %s\n", gpu_ms,
                engaged ? "" : "<-- IDENTICAL TO CPU: the route did NOT engage");
    if (!engaged) {
        std::printf("\nThe pass refused and fell back, which is a correct outcome but means\n"
                    "there is no GPU result to compare and no speedup. Reasons it can refuse:\n"
                    "a non-degenerate dye-cloud blur, active micro-structure, or a dispatch\n"
                    "failure (which is what 12.5 MP diffusion hit in #213).\n");
        return 1;
    }
    std::printf("                     %9.2fx warm\n\n", gpu_ms > 0 ? cpu_ms / gpu_ms : 0.0);

    // Both are estimates of the SAME expectation from different realisations, so
    // the means must agree and the per-pixel values must not.
    std::printf("means (two realisations of the same distribution):\n");
    for (int c = 0; c < 3; ++c) {
        const Moments mc = moments(cpu, c);
        const Moments mg = moments(gpu, c);
        const double band =
            6.0 * std::sqrt(2.0) * mc.sd / std::sqrt(static_cast<double>(npix));
        std::printf("  ch%d cpu %.6f (sd %.6f)  gpu %.6f (sd %.6f)  |diff| %.3e  "
                    "6-sigma %.3e  %s\n",
                    c, mc.mean, mc.sd, mg.mean, mg.sd,
                    std::fabs(mg.mean - mc.mean), band,
                    std::fabs(mg.mean - mc.mean) < band ? "ok" : "OUT OF BAND");
    }
    return 0;
}
