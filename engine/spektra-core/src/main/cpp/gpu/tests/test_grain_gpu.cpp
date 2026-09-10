// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Gate for the GPU AgX particle grain sampler (#214), run under a Vulkan ICD.
//
// The two statistical grain gates in tests/ cannot see this pass: they exercise
// the CPU sampler, and this one draws a DIFFERENT realisation on purpose
// (counter-based RNG, so the result does not depend on dispatch order). Nothing
// pixel-wise can be asserted against the CPU. What CAN be asserted is that the
// shader samples the right DISTRIBUTION, and for this model the distribution has
// a closed form, so this gate needs no oracle at all:
//
//   grain = Binomial(Poisson(lam), p) * od_particle * saturation
//   lam = n_ppp / sat,  od_particle = dmax / n_ppp,  p = density / dmax
//
//   E[grain]   = lam * p * od * sat = p * dmax = density        <- mean preserved
//   Var[grain] = lam * p * (od * sat)^2 = p * dmax^2 * sat / n_ppp
//
// The mean identity is the important one: it is exactly the property the film
// model relies on (grain must not shift exposure), and it is independent of
// every constant in the cell, so a transposed or mis-scaled parameter breaks it.
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "model/grain.h"

namespace {

int g_failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "[PASS]" : "[FAIL]", what);
    if (!ok) ++g_failures;
}

struct Moments {
    double mean = 0.0;
    double sd = 0.0;
};

Moments moments(const std::vector<float>& v, int channel, int stride) {
    double s = 0.0, s2 = 0.0;
    size_t n = 0;
    for (size_t i = static_cast<size_t>(channel); i < v.size();
         i += static_cast<size_t>(stride)) {
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

// 1080p-class geometry: pixel_size_um 18.75, three sublayers sharing the density
// range equally. These are the numbers tools/gpu_probe/census_grain_branches.cpp
// reports as sitting squarely inside the Normal branches.
constexpr double kDmaxLayer = 0.76;
constexpr double kDminLayer = 0.0267;
constexpr double kNppp = 585.9;
constexpr double kUniformity = 0.97;

spk::gpu::GrainCell make_cell(int channel, uint32_t seed_base, double n_ppp) {
    spk::gpu::GrainCell cell;
    cell.density_min = kDminLayer;
    cell.density_max = kDmaxLayer;
    cell.n_particles_per_pixel = n_ppp;
    cell.uniformity = kUniformity;
    cell.channel = channel;
    cell.seed_base = seed_base;
    return cell;
}

}  // namespace

int main() {
    if (!spk::gpu::available()) {
        // Same rule as the other GPU gates: without a device this binary can only
        // check the fallback law, which is NOT this gate. Say so loudly rather
        // than reporting a green run that validated nothing.
        std::printf("no Vulkan device: engaged=0\n");
        std::printf("test_grain_gpu: NO DEVICE (gate did not run)\n");
        return 0;
    }

    const uint32_t npix = 256u * 256u;
    const int ncells = 3;   // one per channel, single sublayer

    // A flat field is the right stimulus here, and for once that is not a
    // weakness: the quantity under test is a per-pixel DISTRIBUTION, so a
    // constant density turns the frame into 65,536 independent draws from the
    // same one. (A flat field cannot gate a BLUR -- that lesson belongs to the
    // halation port -- but there is no spatial operator in this pass.)
    const double target_p = 0.5;
    const double density_total = target_p * kDmaxLayer;          // 0.38
    const float plane_value = static_cast<float>(density_total - kDminLayer);

    std::vector<spk::gpu::GrainCell> cells;
    for (int c = 0; c < ncells; ++c)
        cells.push_back(make_cell(c, static_cast<uint32_t>(c), kNppp));

    std::vector<float> density(static_cast<size_t>(npix) * ncells, plane_value);
    std::vector<float> out(static_cast<size_t>(npix) * 3u, -1.0f);

    spk::gpu::GrainSampleRequest req;
    req.density_cells = density.data();
    req.cells = cells.data();
    req.cell_count = ncells;
    req.width = 256u;
    req.height = 256u;
    req.npix = npix;
    req.seed_offset = 0;

    spk::gpu::GrainSampleDiagnostics diag;
    const bool ok = spk::gpu::grain_sample(req, out.data(), &diag);
    std::printf("grain: ok=%d engaged=%d reason=%s slices=%u samples=%llu "
                "out_of_branch=%llu gpu=%.2fms\n",
                ok ? 1 : 0, diag.engaged ? 1 : 0,
                diag.reason && *diag.reason ? diag.reason : "none", diag.slices,
                static_cast<unsigned long long>(diag.samples),
                static_cast<unsigned long long>(diag.out_of_branch), diag.gpu_ms);
    check(ok && diag.engaged, "grain sampler engaged");
    if (!ok || !diag.engaged) {
        std::printf("test_grain_gpu: %d FAILURES\n", g_failures);
        return 1;
    }

    check(diag.out_of_branch == 0,
          "every sample landed in the Normal branches at 1080p-class geometry");

    // --- the mean identity ---------------------------------------------------
    // Standard error of the mean over npix draws; 5 sigma is a wide enough band
    // that this cannot flake, and still far tighter than any plausible bug.
    const double sat = 1.0 - target_p * kUniformity * (1.0 - 1e-6);
    const double expect_sd = kDmaxLayer * std::sqrt(target_p * sat / kNppp);
    const double sem = expect_sd / std::sqrt(static_cast<double>(npix));
    for (int c = 0; c < 3; ++c) {
        const Moments m = moments(out, c, 3);
        const double err = std::fabs(m.mean - density_total);
        std::printf("  ch%d mean %.6f (expect %.6f, |err| %.2e, 5*sem %.2e)  "
                    "sd %.6f (expect %.6f, ratio %.4f)\n",
                    c, m.mean, density_total, err, 5.0 * sem, m.sd, expect_sd,
                    expect_sd > 0.0 ? m.sd / expect_sd : 0.0);
        check(err < 5.0 * sem, "mean is preserved (grain must not shift exposure)");
        // The variance identity is a much blunter instrument than the mean --
        // it is checked to 5% because Box-Muller truncates the tails near
        // +-5.6 sigma in f32 -- but it is what separates "samples the right
        // distribution" from "writes the right constant".
        check(std::fabs(m.sd / expect_sd - 1.0) < 0.05,
              "noise magnitude matches the closed-form variance");
    }

    // --- determinism ---------------------------------------------------------
    // Counter-based RNG: the same request must produce the same bytes, and it
    // must not depend on how the frame was scheduled.
    std::vector<float> again(out.size(), -1.0f);
    spk::gpu::GrainSampleDiagnostics d2;
    const bool ok2 = spk::gpu::grain_sample(req, again.data(), &d2);
    check(ok2 && again == out, "same request is byte-identical (same device)");

    // --- the per-frame offset actually moves the realisation -----------------
    // The CPU's seeding scheme ADDS its terms, so an offset of 1 aliases channel
    // 1 onto channel 2's previous frame. This pass mixes instead, and the point
    // of the check is that channel c of frame 1 must not equal channel c+1 of
    // frame 0.
    spk::gpu::GrainSampleRequest req2 = req;
    req2.seed_offset = 1;
    std::vector<float> frame1(out.size(), -1.0f);
    spk::gpu::GrainSampleDiagnostics d3;
    const bool ok3 = spk::gpu::grain_sample(req2, frame1.data(), &d3);
    check(ok3 && frame1 != out, "a new frame offset draws a new realisation");
    if (ok3) {
        size_t aliased = 0;
        for (uint32_t p = 0; p < npix; ++p) {
            for (int c = 0; c + 1 < 3; ++c) {
                if (frame1[static_cast<size_t>(p) * 3u + static_cast<size_t>(c)] ==
                    out[static_cast<size_t>(p) * 3u + static_cast<size_t>(c) + 1u])
                    ++aliased;
            }
        }
        // Chance collisions are possible (these are quantised sums), so the test
        // is that the streams are not the SAME stream, not that they never agree.
        const double frac = static_cast<double>(aliased) /
                            (static_cast<double>(npix) * 2.0);
        std::printf("  offset aliasing: %.4f%% of (ch c, frame 1) equal (ch c+1, frame 0)\n",
                    100.0 * frac);
        check(frac < 0.5, "an added-seed collision did not reproduce a neighbour channel");
    }

    // --- the host's radius reduction -----------------------------------------
    // radius = int(3*sigma + 0.5), so 1/6 px is the first radius-1 sigma. These
    // two sit either side of it, and the boundary is what decides whether a
    // 12.5 MP export engages: its coarsest cell lands at 0.1726 px.
    {
        std::vector<spk::gpu::GrainCell> sharp = cells, soft = cells;
        for (auto& cell : sharp) cell.blur_sigma_px = 0.16;   // radius 0
        for (auto& cell : soft) cell.blur_sigma_px = 0.1726;  // radius 1, as at 12.5 MP
        std::vector<float> tmp(out.size(), 0.0f);
        spk::gpu::GrainSampleRequest rs = req;
        rs.cells = sharp.data();
        spk::gpu::GrainSampleDiagnostics ds;
        const bool oks = spk::gpu::grain_sample(rs, tmp.data(), &ds);
        spk::gpu::GrainSampleRequest ro = req;
        ro.cells = soft.data();
        spk::gpu::GrainSampleDiagnostics dsoft;
        const bool oko = spk::gpu::grain_sample(ro, tmp.data(), &dsoft);
        std::printf("  radius: sigma 0.1600 -> %u   sigma 0.1726 -> %u\n",
                    ds.max_blur_radius, dsoft.max_blur_radius);
        check(oks && ds.max_blur_radius == 0, "sigma below 1/6 px reduces to radius 0");
        check(oko && dsoft.max_blur_radius == 1, "the 12.5 MP coarsest cell reduces to radius 1");
    }

    // --- the out-of-branch counter is real -----------------------------------
    // Starve the cell of particles so lam falls under the Knuth threshold. The
    // pass must still succeed, and must REPORT that it left its branches --
    // an approximation that cannot say when it is out of range is the thing
    // this counter exists to prevent.
    std::vector<spk::gpu::GrainCell> starved;
    for (int c = 0; c < ncells; ++c)
        starved.push_back(make_cell(c, static_cast<uint32_t>(c), 5.0));
    spk::gpu::GrainSampleRequest req3 = req;
    req3.cells = starved.data();
    std::vector<float> low(out.size(), -1.0f);
    spk::gpu::GrainSampleDiagnostics d4;
    const bool ok4 = spk::gpu::grain_sample(req3, low.data(), &d4);
    std::printf("  starved cell: ok=%d out_of_branch=%llu of %llu\n", ok4 ? 1 : 0,
                static_cast<unsigned long long>(d4.out_of_branch),
                static_cast<unsigned long long>(d4.samples));
    check(ok4 && d4.out_of_branch == d4.samples,
          "out-of-branch samples are counted, not silently approximated");

    // --- fail-closed on a bad request ----------------------------------------
    std::vector<float> untouched(out.size(), 42.0f);
    spk::gpu::GrainSampleRequest bad = req;
    bad.cell_count = spk::gpu::kGrainMaxCells + 1;
    spk::gpu::GrainSampleDiagnostics d5;
    const bool ok5 = spk::gpu::grain_sample(bad, untouched.data(), &d5);
    bool all42 = true;
    for (float v : untouched) if (v != 42.0f) { all42 = false; break; }
    check(!ok5 && all42, "a rejected request leaves the caller's buffer untouched");


    // --- end-to-end through apply_grain_to_density_layers --------------------
    // The checks above gate the PASS. This one gates the WIRING, which is a
    // separate thing to get wrong: cell order, the seed split, the tail that
    // still runs on the host, and above all the refusals -- a route that
    // silently skips the CPU's spatial work would look perfect in every
    // statistical check and be wrong on real film.
    {
        const int w = 256, h = 256;
        const int n = w * h;
        const double pixel_size_um = 18.75;   // 1080p-class
        std::vector<float> layers(static_cast<size_t>(n) * 9u, 0.35f);
        double dmax_layers[9];
        for (int i = 0; i < 9; ++i) dmax_layers[i] = 2.2 / 3.0;

        spk::GrainParams gp;
        gp.active = true;
        gp.sublayers_active = true;
        gp.blur = 0.0;    // isolate the sampler from the final blur

        std::vector<float> cpu(static_cast<size_t>(n) * 3u, 0.0f);
        spk::apply_grain_to_density_layers(layers.data(), n, w, h, dmax_layers,
                                           pixel_size_um, gp, cpu.data());

        spk::GrainParams gg = gp;
        gg.allow_gpu_sampler = true;
        std::vector<float> gpu(static_cast<size_t>(n) * 3u, 0.0f);
        spk::apply_grain_to_density_layers(layers.data(), n, w, h, dmax_layers,
                                           pixel_size_um, gg, gpu.data());

        check(gpu != cpu, "the GPU route actually ran (a different realisation)");
        for (int c = 0; c < 3; ++c) {
            const Moments mc = moments(cpu, c, 3);
            const Moments mg = moments(gpu, c, 3);
            // Two independent estimates of the same expectation. Combined
            // standard error is sqrt(2)*sd/sqrt(n); 6x that is a band no
            // correct implementation misses and no swapped cell survives.
            const double band = 6.0 * std::sqrt(2.0) * mc.sd / std::sqrt(static_cast<double>(n));
            std::printf("  e2e ch%d cpu %.6f  gpu %.6f  |diff| %.2e  band %.2e\n",
                        c, mc.mean, mg.mean, std::fabs(mg.mean - mc.mean), band);
            check(std::fabs(mg.mean - mc.mean) < band,
                  "end-to-end mean agrees with the CPU route");
        }

        // A REAL dye-cloud blur (radius 1 at this geometry) is now carried by
        // the shader, not refused. The sharp check is the standard deviation,
        // not the mean: blurring leaves the mean alone but cuts the noise by a
        // known factor, so a GPU that quietly skipped the blur would still pass
        // every mean test and fail here.
        spk::GrainParams gb = gg;
        gb.blur_dye_clouds_um = 5.0;
        std::vector<float> blur_gpu(static_cast<size_t>(n) * 3u, 0.0f);
        std::vector<float> blur_cpu(static_cast<size_t>(n) * 3u, 0.0f);
        spk::apply_grain_to_density_layers(layers.data(), n, w, h, dmax_layers,
                                           pixel_size_um, gb, blur_gpu.data());
        spk::GrainParams gb_cpu = gb;
        gb_cpu.allow_gpu_sampler = false;
        spk::apply_grain_to_density_layers(layers.data(), n, w, h, dmax_layers,
                                           pixel_size_um, gb_cpu, blur_cpu.data());
        check(blur_gpu != blur_cpu, "the blurred GPU route ran (a different realisation)");
        bool any_reduced = false;
        for (int c = 0; c < 3; ++c) {
            const Moments bc = moments(blur_cpu, c, 3);
            const Moments bg = moments(blur_gpu, c, 3);
            const Moments uc = moments(cpu, c, 3);
            std::printf("  blur ch%d cpu sd %.6f  gpu sd %.6f  (unblurred cpu sd %.6f)  "
                        "ratio %.4f\n",
                        c, bc.sd, bg.sd, uc.sd, bc.sd > 0.0 ? bg.sd / bc.sd : 0.0);
            if (bc.sd < uc.sd * 0.95) any_reduced = true;
            check(std::fabs(bg.sd / bc.sd - 1.0) < 0.03,
                  "GPU blurred noise matches the CPU's (the blur was not skipped)");
        }
        // Only the coarsest cells (largest od_particle) reach radius >= 1, so a
        // channel whose cells are all fine is barely touched -- ch2 moved 10%
        // here while ch0 moved 0.1%. What must hold is that the CPU reference
        // really did blur SOMETHING, otherwise the agreement above is agreement
        // between two no-ops.
        check(any_reduced, "the CPU reference actually applied a dye-cloud blur");

        // Too wide to carry: kGrainMaxBlurRadius is 3, and this asks for far
        // more. A truncated kernel is a different filter, so the only correct
        // answer is to decline -- which must be bit-identical to the CPU run.
        spk::GrainParams gw = gg;
        gw.blur_dye_clouds_um = 50.0;
        std::vector<float> wide_gpu(static_cast<size_t>(n) * 3u, 0.0f);
        std::vector<float> wide_cpu(static_cast<size_t>(n) * 3u, 0.0f);
        spk::apply_grain_to_density_layers(layers.data(), n, w, h, dmax_layers,
                                           pixel_size_um, gw, wide_gpu.data());
        spk::GrainParams gw_cpu = gw;
        gw_cpu.allow_gpu_sampler = false;
        spk::apply_grain_to_density_layers(layers.data(), n, w, h, dmax_layers,
                                           pixel_size_um, gw_cpu, wide_cpu.data());
        check(wide_gpu == wide_cpu,
              "a dye-cloud blur wider than the shader carries is refused, byte-for-byte");

        // Refusal 2: active micro-structure clumping. sigma = sigma_nm*1e-3 /
        // pixel_size_um must exceed 0.05 for add_micro_structure to do anything,
        // which needs sigma_nm > 50*pixel_size_um -- far above the schema
        // default of 30, but reachable, and it is a user-settable param.
        spk::GrainParams gm = gg;
        gm.micro_structure[1] = 2000.0;
        std::vector<float> micro_gpu(static_cast<size_t>(n) * 3u, 0.0f);
        std::vector<float> micro_cpu(static_cast<size_t>(n) * 3u, 0.0f);
        spk::apply_grain_to_density_layers(layers.data(), n, w, h, dmax_layers,
                                           pixel_size_um, gm, micro_gpu.data());
        spk::GrainParams gm_cpu = gm;
        gm_cpu.allow_gpu_sampler = false;
        spk::apply_grain_to_density_layers(layers.data(), n, w, h, dmax_layers,
                                           pixel_size_um, gm_cpu, micro_cpu.data());
        check(micro_gpu == micro_cpu,
              "active micro-structure is refused, byte-for-byte");
    }

    if (g_failures == 0) {
        std::printf("test_grain_gpu: ALL OK\n");
        return 0;
    }
    std::printf("test_grain_gpu: %d FAILURES\n", g_failures);
    return 1;
}
