/*
 * Spektrafilm for Android — native engine: film grain (AgX particle model).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements model/grain.h. Mirrors grain.py::layer_particle_model
 * (poisson_binomial) + apply_grain_to_density.
 *
 * Verification is statistical (see tests/test_grain.cpp): the model is
 * mean-preserving and injects noise whose per-channel std matches the Python
 * oracle in magnitude. Exact element-wise parity is impossible because the C++
 * std::mt19937 RNG stream differs from numpy/Numba — only the *distributions*
 * (Poisson, Binomial) and the algorithm structure are matched.
 */
#include "model/grain.h"

#include "gpu/vulkan_compute.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <array>
#include <vector>

#include "kernels/gaussian.h"
#include "kernels/parallel.h"
#include "kernels/stats.h"
#include "runtime/stage_timer.h"

namespace spk {

void layer_particle_model(const float* density, int npix, int width, int height,
                          double density_max, double n_particles_per_pixel,
                          double grain_uniformity, uint64_t seed, float* out,
                          StatsRng::Generator generator) {
    layer_particle_model(density, npix, width, height, density_max,
                         n_particles_per_pixel, grain_uniformity, seed,
                         /*blur_particle=*/0.0, out, generator);
}

// Pixels per independently-seeded grain block. FIXED — never derived from the worker
// count — because the block index is what seeds the RNG, so a block's output must not
// depend on how the work was distributed.
constexpr int kGrainBlockPixels = 8192;

// SplitMix64 finalizer over (seed, block). Decorrelates adjacent blocks so the grain
// field has no visible seam or periodicity at block boundaries.
static inline uint64_t grain_block_seed(uint64_t seed, int block) {
    uint64_t x = seed + 0x9E3779B97F4A7C15ULL * static_cast<uint64_t>(block + 1);
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBULL;
    return x ^ (x >> 31);
}

void layer_particle_model(const float* density, int npix, int width, int height,
                          double density_max, double n_particles_per_pixel,
                          double grain_uniformity, uint64_t seed,
                          double blur_particle, float* out,
                          StatsRng::Generator generator) {
    if (npix <= 0) return;
    if (!(n_particles_per_pixel > 0.0) ||
        !std::isfinite(n_particles_per_pixel)) {
        // Invalid imported geometry/particle scales can otherwise derive 0/Inf
        // here and feed non-representable rates into the statistical samplers.
        // Copying the shifted input makes this particle-sampling step an exact
        // no-op at the caller's density-min seam; no RNG is consumed here.
        if (out != density) std::copy(density, density + npix, out);
        return;
    }
    const double od_particle = density_max / n_particles_per_pixel;

    // PARALLEL, AND THREAD-INVARIANT. This stage was serial — the one per-pixel stage
    // that did not fan out — and it dominates a full-resolution render (measured: 66.8 s
    // of a 12 MP simulate on one core, vs ~1.3 s for everything else combined).
    //
    // It stayed serial because it walks a seeded RNG in pixel order, and parallel_for's
    // chunk boundaries depend on the WORKER COUNT — so seeding per chunk would make the
    // output depend on how many threads ran, breaking test_parallel.
    //
    // So the RNG is re-anchored to FIXED blocks instead. Block b always covers the same
    // pixels and is always seeded from b, whichever chunk happens to execute it; a block
    // is owned by the chunk containing its FIRST pixel, and chunks partition [0, npix),
    // so every block runs exactly once. Output is therefore identical for any worker
    // count — which is the property the parity gate actually requires.
    //
    // This DOES change the grain field versus the old serial stream. That is safe here:
    // the parity goldens are generated with grain_active = 0 (see test_simulate_e2e),
    // and the places that do enable grain assert REPRODUCIBILITY (an identical repeat
    // must produce identical bytes), which fixed block seeding preserves exactly.
    //
    // DYNAMIC scheduling, not the fixed chunking parallel_for uses. Grain's per-pixel
    // cost is wildly uneven: fast_binomial_one falls into an O(n) CDF-inversion walk
    // where density approaches its maximum, so a bright sky can cost hundreds of times
    // more per pixel than a shadow. With one contiguous chunk per thread, whichever
    // threads own the bright region do nearly all the work — measured 2.2x on 8 cores.
    // Handing out blocks from an atomic counter balances that.
    //
    // This is safe HERE and would not be elsewhere: a block's output depends only on its
    // INDEX (its seed) and its pixel range, never on which thread ran it or in what
    // order, so dynamic assignment cannot change the result.
    const int nblocks = (npix + kGrainBlockPixels - 1) / kGrainBlockPixels;
    std::atomic<int> next_block{0};
    auto worker = [&](const std::atomic<bool>* stop) {
        for (;;) {
            if (stop && stop->load(std::memory_order_relaxed)) break;
            const int b = next_block.fetch_add(1, std::memory_order_relaxed);
            if (b >= nblocks) break;
            const int i0 = b * kGrainBlockPixels;
            const int i1 = std::min(i0 + kGrainBlockPixels, npix);
            StatsRng rng(grain_block_seed(seed, b), generator);
            for (int i = i0; i < i1; ++i) {
                // Worker threads never touch the JNI-backed callback. They only
                // observe the caller-owned stop flag, at a granularity small
                // enough to bound even the expensive binomial inversion tail.
                if (stop && (i & 63) == 0 &&
                    stop->load(std::memory_order_relaxed)) {
                    return;
                }
                // probability_of_development = clip(density/density_max, 1e-6, 1-1e-6)
                double p = static_cast<double>(density[i]) / density_max;
                if (p < 1e-6) p = 1e-6;
                else if (p > 1.0 - 1e-6) p = 1.0 - 1e-6;

                double saturation = 1.0 - p * grain_uniformity * (1.0 - 1e-6);
                double lam = n_particles_per_pixel / saturation;
                int64_t seeds = fast_poisson_one(lam, rng);
                int64_t dev = fast_binomial_one(seeds, p, rng);
                out[i] = static_cast<float>(static_cast<double>(dev) * od_particle * saturation);
            }
        }
    };
    int nthreads = spk::parallel_num_threads();
    if (nthreads > nblocks) nthreads = nblocks < 1 ? 1 : nblocks;
    // The dynamic dispatcher preserves the fixed-block arithmetic above while
    // containing worker and partial thread-construction failures. With an
    // active JNI cancellation callback it keeps this caller as the sole polling
    // orchestrator; otherwise the caller participates in the work queue.
    {
        ScopedGrainPhase _p(GrainPhase::Sampler);
        spk::detail::parallel_dispatch_dynamic(nthreads, worker);
    }

    // Per-particle dye-cloud blur: grain.py uses
    //   grain = fast_gaussian_filter(grain, blur_particle*sqrt(od_particle))
    // unconditionally when blur_particle > 0 (no >0.4 sigma threshold here).
    if (blur_particle > 0.0) {
        ScopedGrainPhase _p(GrainPhase::ParticleBlur);
        double sigma = blur_particle * std::sqrt(od_particle);
        gaussian_blur_plane(out, width, height, static_cast<float>(sigma));
    }
}

void add_micro_structure(float* inout, int npix, int width, int height,
                         const double micro_structure[2], double pixel_size_um,
                         uint64_t seed, StatsRng::Generator generator) {
    // add_micro_structure(density_cmy_out, micro_structure, pixel_size_um).
    double blur_pixel = micro_structure[0] / pixel_size_um;
    double sigma = micro_structure[1] * 0.001 / pixel_size_um;  // nm -> µm -> px
    if (sigma <= 0.05) return;

    // clumping = fast_lognormal_from_mean_std(ones, ones*sigma) — independent per
    // pixel AND per channel (np.ones_like(density_cmy_out) is (H,W,3)). One
    // deterministic stream produces the whole (npix,3) clumping field.
    std::vector<float> clumping(static_cast<size_t>(npix) * 3);
    StatsRng rng(seed, generator);
    for (size_t i = 0; i < clumping.size(); ++i)
        clumping[i] = static_cast<float>(
            fast_lognormal_from_mean_std_one(1.0, sigma, rng));

    if (blur_pixel > 0.4) {
        gaussian_blur(clumping.data(), width, height, 3,
                      static_cast<float>(blur_pixel));
    }

    // Only the multiply: the clumping FILL above consumes one seeded stream in
    // order and is serial by nature -- splitting it would change which variate
    // lands on which pixel, which is a different image, not a faster one.
    {
        const float* cl = clumping.data();
        spk::parallel_for(0, static_cast<int>(clumping.size()), [&](int lo, int hi) {
            for (int i = lo; i < hi; ++i)
                inout[i] = static_cast<float>(static_cast<double>(inout[i]) * cl[i]);
        });
    }
}

void apply_grain_to_density(const float* density_cmy, int npix, int width,
                            int height, double pixel_size_um,
                            const GrainParams& grain, float* out) {
    // One choice per render, from the params: the Fast GPU export route may
    // use the cheaper generator (#180); everything else keeps mt19937.
    const StatsRng::Generator generator = grain.fast_sampler
                                             ? StatsRng::Generator::Fast
                                             : StatsRng::Generator::Exact;
    if (!grain.active) {
        if (out != density_cmy)
            std::copy(density_cmy, density_cmy + static_cast<size_t>(npix) * 3, out);
        return;
    }

    const double pixel_area_um2 = pixel_size_um * pixel_size_um;
    const int n_sub = grain.n_sub_layers > 1 ? grain.n_sub_layers : 1;

    double density_max[3], n_ppp[3];
    for (int c = 0; c < 3; ++c) {
        density_max[c] = grain.density_max_curves[c] + grain.density_min[c];
        double particle_area = grain.agx_particle_area_um2 * grain.agx_particle_scale[c];
        n_ppp[c] = pixel_area_um2 / particle_area;
        if (n_sub > 1) n_ppp[c] /= n_sub;
    }

    // Accumulator per channel-interleaved pixel (the Python code does
    // density_cmy += density_min before sampling, then out -= density_min after).
    std::vector<double> acc(static_cast<size_t>(npix) * 3, 0.0);
    std::vector<float> shifted(static_cast<size_t>(npix));   // one channel plane
    std::vector<float> layer_out(static_cast<size_t>(npix));

    for (int c = 0; c < 3; ++c) {
        // density_cmy[:,c] + density_min[c]. Parallel: disjoint writes, so bit-identical.
        spk::parallel_for(0, npix, [&](int i0, int i1) {
            for (int i = i0; i < i1; ++i)
                shifted[i] = static_cast<float>(
                    static_cast<double>(density_cmy[i * 3 + c]) + grain.density_min[c]);
        });

        for (int sl = 0; sl < n_sub; ++sl) {
            uint64_t seed = static_cast<uint64_t>(grain.seed_base[c] + sl * 10 +
                                                  grain.seed_offset);
            layer_particle_model(shifted.data(), npix, width, height,
                                 density_max[c], n_ppp[c], grain.uniformity[c],
                                 seed, layer_out.data(), generator);
            {
                ScopedGrainPhase _p(GrainPhase::Accum);
                spk::parallel_for(0, npix, [&](int i0, int i1) {
                    for (int i = i0; i < i1; ++i)
                        acc[static_cast<size_t>(i) * 3 + c] += layer_out[i];
                });
            }
        }
        // /= n_sub_layers, then -= density_min[c]
        for (int i = 0; i < npix; ++i) {
            double v = acc[static_cast<size_t>(i) * 3 + c] / n_sub - grain.density_min[c];
            out[i * 3 + c] = static_cast<float>(v);
        }
    }

    // Final per-channel Gaussian blur (sigma in pixels). Python threshold: > 0.4.
    if (grain.blur > 0.4) {
        gaussian_blur(out, width, height, 3, static_cast<float>(grain.blur));
    }
}

namespace {

// Would the CPU's per-particle dye-cloud blur actually change the plane?
// gaussian_kernel_1d uses radius = int(truncate*sigma + 0.5) with truncate 3,
// and gaussian_fir_plane returns immediately at radius 0 -- an exact identity,
// not an approximation of one. So radius 0 is the precise condition under which
// skipping the blur is not a difference at all.
//
// It is TESTED per cell rather than assumed, and the device says that was the
// right call. An earlier version of this comment claimed identity held "at every
// geometry this app reaches, sigma 0.077 px at 12.5 MP" -- that was the MIDDLE
// cell (sl=1, c=1). The binding cell is the coarsest one, the largest
// agx_particle_scale[c] * agx_particle_scale_layers[sl] product, because
// od_particle = dmax_layer / n_ppp grows as particles per pixel fall. Measured
// at 12.5 MP export pitch (8.82 um/px, tools/gpu_probe/probe_grain_main.cpp):
//
//   sl=1 c=1  n_ppp 129.76  sigma 0.0765 px  radius 0
//   sl=0 c=2  n_ppp  25.95  sigma 0.1726 px  radius 1   <-- refuses
//
// So the GPU route engages at video and preview pitch and declines at export
// pitch, missing the 1/6 px threshold by 3.5% on one cell out of nine. Export
// needs the dye-cloud blur in the shader, not a looser test here.
bool dye_cloud_blur_is_identity(double blur_particle, double od_particle) {
    if (!(blur_particle > 0.0)) return true;
    const float sigma = static_cast<float>(blur_particle * std::sqrt(od_particle));
    return static_cast<int>(kGaussianTruncate * sigma + 0.5f) == 0;
}

// The GPU sampler replaces the accumulate loop only. Returns true when `out`
// holds the accumulated grain; false leaves `out` untouched for the CPU loop.
bool gpu_grain_accumulate(const float* density_cmy_layers, int npix,
                          const double dmin_layers[3][3],
                          const double dmax_lay[3][3], const double n_ppp[3][3],
                          double pixel_size_um, const GrainParams& grain,
                          float* out) {
    if (!grain.allow_gpu_sampler || !gpu::available()) return false;

    // add_micro_structure runs on the ACCUMULATED grain, so if it is active the
    // GPU route would have to reproduce it before the tail; it does not, and
    // skipping it would silently drop a stage rather than approximate one.
    const double micro_sigma = grain.micro_structure[1] * 0.001 / pixel_size_um;
    if (micro_sigma > 0.05) return false;

    std::array<gpu::GrainCell, 9> cells{};
    int k = 0;
    for (int sl = 0; sl < 3; ++sl) {
        for (int c = 0; c < 3; ++c) {
            const double od = dmax_lay[sl][c] / n_ppp[sl][c];
            if (!(n_ppp[sl][c] > 0.0) || !(dmax_lay[sl][c] > 0.0)) return false;
            if (!dye_cloud_blur_is_identity(grain.blur_dye_clouds_um, od)) return false;
            gpu::GrainCell& cell = cells[static_cast<size_t>(k)];
            cell.density_min = dmin_layers[sl][c];
            cell.density_max = dmax_lay[sl][c];
            cell.n_particles_per_pixel = n_ppp[sl][c];
            cell.uniformity = grain.uniformity[c];
            cell.channel = c;
            // seed_base[c] + sublayer*10, WITHOUT seed_offset: the offset is
            // passed separately and mixed in the shader. Folding it in here
            // would rebuild the additive collision the shader exists to avoid.
            cell.seed_base = static_cast<uint32_t>(grain.seed_base[c] + sl * 10);
            ++k;
        }
    }
    // The cell order must match the buffer layout the caller already has:
    // density_cmy_layers is (npix, 3 sublayers, 3 channels) row-major, i.e.
    // index pix*9 + sl*3 + c -- exactly cell index sl*3 + c. No repack.
    gpu::GrainSampleRequest request;
    request.density_cells = density_cmy_layers;
    request.cells = cells.data();
    request.cell_count = 9;
    request.npix = static_cast<uint32_t>(npix);
    request.seed_offset = static_cast<uint32_t>(grain.seed_offset);

    // No stage_timer channel yet, deliberately: the route is off by default and
    // has no device measurement, so there is nothing for a production readout to
    // report. out_of_branch is read directly by gpu/tests/test_grain_gpu.cpp and
    // by the probe. Wire a channel when the route is enabled, not before.
    gpu::GrainSampleDiagnostics diagnostics{};
    const bool ok = gpu::grain_sample(request, out, &diagnostics);
    return ok && diagnostics.engaged;
}

}  // namespace

void apply_grain_to_density_layers(const float* density_cmy_layers, int npix,
                                   int width, int height,
                                   const double* density_max_layers,
                                   double pixel_size_um, const GrainParams& grain,
                                   float* out) {
    // One choice per render, from the params: the Fast GPU export route may
    // use the cheaper generator (#180); everything else keeps mt19937.
    const StatsRng::Generator generator = grain.fast_sampler
                                             ? StatsRng::Generator::Fast
                                             : StatsRng::Generator::Exact;
    // density_max_total[c] = sum over sublayers of density_max_layers[sl,c].
    double dmax_total[3] = {0, 0, 0};
    for (int sl = 0; sl < 3; ++sl)
        for (int c = 0; c < 3; ++c)
            dmax_total[c] += density_max_layers[sl * 3 + c];

    // Per-sublayer/channel derived quantities. `frac` (= density_max_fractions)
    // is consumed inline below for dmin_layers and n_ppp, so it is not stored.
    double dmin_layers[3][3];   // density_min_layers[sl][c]
    double dmax_lay[3][3];      // density_max_layers + density_min_layers
    double n_ppp[3][3];         // n_particles_per_pixel[sl][c]
    const double pixel_area_um2 = pixel_size_um * pixel_size_um;
    for (int sl = 0; sl < 3; ++sl) {
        for (int c = 0; c < 3; ++c) {
            double frac = density_max_layers[sl * 3 + c] / dmax_total[c];
            dmin_layers[sl][c] = frac * grain.density_min[c];
            dmax_lay[sl][c] = density_max_layers[sl * 3 + c] + dmin_layers[sl][c];
            double particle_area = grain.agx_particle_area_um2 *
                                   grain.agx_particle_scale[c] *
                                   grain.agx_particle_scale_layers[sl];
            n_ppp[sl][c] = pixel_area_um2 * frac / particle_area;
        }
    }

    // Try the GPU sampler BEFORE allocating the CPU scratch. `acc` alone is
    // npix*3 doubles -- 300 MB at 12.5 MP -- and allocating it only to leave it
    // untouched would hand the memory coordinator a peak that the GPU route was
    // supposed to remove.
    const bool gpu_done = gpu_grain_accumulate(density_cmy_layers, npix,
                                               dmin_layers, dmax_lay, n_ppp,
                                               pixel_size_um, grain, out);

    // Accumulator per channel-interleaved pixel (CPU route only).
    std::vector<double> acc;
    std::vector<float> shifted;    // one sublayer plane
    std::vector<float> layer_out;
    if (!gpu_done) {
        acc.assign(static_cast<size_t>(npix) * 3, 0.0);
        shifted.resize(static_cast<size_t>(npix));
        layer_out.resize(static_cast<size_t>(npix));
    }

    for (int c = 0; c < 3 && !gpu_done; ++c) {
        for (int sl = 0; sl < 3; ++sl) {
            // density_cmy_layers[:,sl,c] += density_min_layers[sl,c]
            ScopedGrainPhase _prep(GrainPhase::Prep);
            spk::parallel_for(0, npix, [&](int i0, int i1) {
                for (int i = i0; i < i1; ++i) {
                    float v = density_cmy_layers[static_cast<size_t>(i) * 9 + sl * 3 + c];
                    shifted[i] = static_cast<float>(static_cast<double>(v) +
                                                    dmin_layers[sl][c]);
                }
            });
            _prep.stop();
            uint64_t seed = static_cast<uint64_t>(grain.seed_base[c] + sl * 10 +
                                                  grain.seed_offset);
            layer_particle_model(shifted.data(), npix, width, height,
                                 dmax_lay[sl][c], n_ppp[sl][c], grain.uniformity[c],
                                 seed, grain.blur_dye_clouds_um, layer_out.data(),
                                 generator);
            {
                ScopedGrainPhase _p(GrainPhase::Accum);
                spk::parallel_for(0, npix, [&](int i0, int i1) {
                    for (int i = i0; i < i1; ++i)
                        acc[static_cast<size_t>(i) * 3 + c] += layer_out[i];
                });
            }
        }
    }

    if (!gpu_done) {
        ScopedGrainPhase _p(GrainPhase::Final);
        spk::parallel_for(0, static_cast<int>(acc.size()), [&](int i0, int i1) {
            for (int i = i0; i < i1; ++i) out[i] = static_cast<float>(acc[i]);
        });
    }

    // micro-structure clumping (operates on the accumulated grain, before the
    // density_min subtraction and final blur — matching grain.py order).
    uint64_t micro_seed = static_cast<uint64_t>(777 + grain.seed_offset);
    {
        ScopedGrainPhase _p(GrainPhase::Micro);
        add_micro_structure(out, npix, width, height, grain.micro_structure,
                            pixel_size_um, micro_seed, generator);
    }

    // density_cmy_out -= density_min
    {
        ScopedGrainPhase _p(GrainPhase::Final);
        // The sibling conversion ten lines above is already parallel; this one
        // was simply missed. Same shape, same 37.5 M elements at 12.5 MP.
        spk::parallel_for(0, npix, [&](int i0, int i1) {
            for (int i = i0; i < i1; ++i)
                for (int c = 0; c < 3; ++c)
                    out[i * 3 + c] = static_cast<float>(
                        static_cast<double>(out[i * 3 + c]) - grain.density_min[c]);
        });
    }

    // Final per-channel Gaussian blur. NOTE: the layers path threshold is > 0
    // (grain.py: `if grain_blur>0`), unlike the non-sublayer path's > 0.4.
    if (grain.blur > 0.0) {
        ScopedGrainPhase _p(GrainPhase::Final);
        gaussian_blur(out, width, height, 3, static_cast<float>(grain.blur));
    }
}

}  // namespace spk
