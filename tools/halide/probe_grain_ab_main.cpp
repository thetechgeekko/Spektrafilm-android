// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Halide-Vulkan grain vs the hand-written gpu/grain.comp, on device (#217).
//
// The maintainability argument for Halide is real -- one generator source targets
// CPU and GPU, instead of a GLSL shader plus a parallel C++ implementation kept
// in step by hand. It is only worth taking if the generated kernel is
// competitive, and Halide's own Vulkan doc lists "Performance tuning of CodeGen
// and Runtime" under Known TODO. So this measures it rather than assuming either
// way.
//
// BOTH SIDES COMPUTE THE SAME MODEL: the Normal-approximation Poisson -> Binomial
// draw, with the same PCG constants and the same per-cell constants. They do NOT
// produce the same pixels -- the RNG is keyed the same way but Halide's loop order
// and the shader's differ, so this compares DISTRIBUTIONS (mean, sd) and speed,
// which is the same contract gpu/tests/test_grain_gpu.cpp holds the shader to.
//
// ONE LAYOUT CAVEAT, stated because it would otherwise flatter Halide: the engine
// stores density cells INTERLEAVED (p * ncells + k) and Halide is given its
// natural PLANAR layout (x fastest, cell outermost). A real integration would
// need either a transpose or a stride-flexible build, and neither is free. The
// planar copy here is built once, outside the timed region.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "spk_grain_f32_arm_64_android_vulkan.h"

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

halide_buffer_t make_buf(float* data, halide_dimension_t* dims, int ndims) {
    halide_buffer_t b{};
    b.host = reinterpret_cast<uint8_t*>(data);
    b.dim = dims;
    b.dimensions = ndims;
    b.type = halide_type_t{halide_type_float, 32, 1};
    return b;
}

struct Moments { double mean = 0.0, sd = 0.0; };

// `count` is explicit: the two paths use different layouts (interleaved vs
// planar) and a stride-only walk would run off the end of a planar channel into
// the next one, which reads as a plausible mean rather than as an error.
Moments moments(const std::vector<float>& v, size_t first, size_t stride, size_t count) {
    double s = 0.0, q = 0.0;
    size_t n = 0;
    for (size_t j = 0; j < count; ++j) {
        const size_t i = first + j * stride;
        if (i >= v.size()) break;
        s += v[i]; q += static_cast<double>(v[i]) * v[i]; ++n;
    }
    Moments m;
    if (!n) return m;
    m.mean = s / static_cast<double>(n);
    const double var = q / static_cast<double>(n) - m.mean * m.mean;
    m.sd = var > 0 ? std::sqrt(var) : 0.0;
    return m;
}

// 1080p-class geometry, the same numbers gpu/tests/test_grain_gpu.cpp uses.
constexpr double kDmax = 0.76, kDmin = 0.0267, kNppp = 585.9, kUni = 0.97;

}  // namespace

int main(int argc, char** argv) {
    // Unbuffered: stdout is a pipe under adb, so it is FULLY buffered, and an
    // abort discards everything written so far. The first 1920x1080 run printed
    // nothing at all -- not even the header -- which read as "crashed instantly"
    // when it had in fact run and then lost its output.
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    const int w = argc > 1 ? std::atoi(argv[1]) : 1920;
    const int h = argc > 2 ? std::atoi(argv[2]) : 1080;
    const int ncells = 9;
    const size_t npix = static_cast<size_t>(w) * h;

    std::printf("== Halide-Vulkan grain vs hand-written gpu/grain.comp ==\n");
    std::printf("frame %dx%d  cells=%d\n", w, h, ncells);
    std::printf("engine gpu available: %s\n\n", spk::gpu::available() ? "yes" : "NO");

    // Per-cell constants, identical for both paths.
    std::vector<spk::gpu::GrainCell> cells(ncells);
    std::vector<float> cellbuf(static_cast<size_t>(8) * ncells, 0.0f);
    for (int k = 0; k < ncells; ++k) {
        const int ch = k % 3;
        cells[k].density_min = kDmin;
        cells[k].density_max = kDmax;
        cells[k].n_particles_per_pixel = kNppp;
        cells[k].uniformity = kUni;
        cells[k].channel = ch;
        cells[k].seed_base = static_cast<uint32_t>(ch + (k / 3) * 10);
        cells[k].blur_sigma_px = 0.0;   // isolate the sampler
        float* e = cellbuf.data() + static_cast<size_t>(k) * 8;
        e[0] = static_cast<float>(kDmin);
        e[1] = static_cast<float>(kDmax);
        e[2] = static_cast<float>(kNppp);
        e[3] = static_cast<float>(kUni);
        e[4] = static_cast<float>(kDmax / kNppp);
        e[5] = static_cast<float>(ch);
        const uint32_t sb = cells[k].seed_base;
        std::memcpy(&e[6], &sb, sizeof(uint32_t));
    }

    const float plane = static_cast<float>(0.5 * kDmax - kDmin);

    // --- hand-written shader ------------------------------------------------
    std::vector<float> dens_interleaved(npix * ncells, plane);
    std::vector<float> out_shader(npix * 3, 0.0f);
    spk::gpu::GrainSampleRequest req;
    req.density_cells = dens_interleaved.data();
    req.cells = cells.data();
    req.cell_count = ncells;
    req.width = static_cast<uint32_t>(w);
    req.height = static_cast<uint32_t>(h);
    req.npix = static_cast<uint32_t>(npix);
    req.seed_offset = 0;
    spk::gpu::GrainSampleDiagnostics diag{};
    spk::gpu::grain_sample(req, out_shader.data(), &diag);          // warm
    const auto t0 = Clock::now();
    const bool ok_shader = spk::gpu::grain_sample(req, out_shader.data(), &diag);
    const double ms_shader = ms_since(t0);

    // --- Halide, planar layout ----------------------------------------------
    std::vector<float> dens_planar(npix * ncells, plane);
    std::vector<float> out_halide(npix * 3, 0.0f);
    halide_dimension_t din[3] = {{0, w, 1, 0},
                                 {0, h, w, 0},
                                 {0, ncells, w * h, 0}};
    halide_dimension_t dcell[2] = {{0, 8, 1, 0}, {0, ncells, 8, 0}};
    halide_dimension_t dout[3] = {{0, w, 1, 0}, {0, h, w, 0}, {0, 3, w * h, 0}};
    halide_buffer_t bin = make_buf(dens_planar.data(), din, 3);
    halide_buffer_t bcell = make_buf(cellbuf.data(), dcell, 2);
    halide_buffer_t bout = make_buf(out_halide.data(), dout, 3);

    // The inputs must be marked host_dirty or Halide has no reason to upload
    // them, and the output must be copied back explicitly: with a GPU schedule
    // the pipeline leaves its result device-side, and the host allocation stays
    // whatever it was. Without this the run returns rc = 0 and every output pixel
    // is zero -- a silent pass, which is exactly the failure a speed-only probe
    // would have reported as a win.
    bin.set_host_dirty(true);
    bcell.set_host_dirty(true);

    auto halide_run = [&]() {
        const int r = spk_grain_f32(&bin, &bcell, ncells, 0u, &bout);
        if (r == 0) halide_copy_to_host(nullptr, &bout);
        return r;
    };
    int rc = halide_run();                                          // warm
    const auto t1 = Clock::now();
    rc = halide_run();
    const double ms_halide = ms_since(t1);   // includes the copy back, as the
                                             // shader's number includes readback

    std::printf("hand-written shader : ok=%d  %8.2f ms  (gpu %.2f up %.2f down %.2f)\n",
                ok_shader ? 1 : 0, ms_shader, diag.gpu_ms, diag.upload_ms, diag.readback_ms);
    std::printf("halide-vulkan       : rc=%d  %8.2f ms\n", rc, ms_halide);
    if (rc != 0) {
        std::printf("\nHalide returned %d -- non-zero means the pipeline failed (most often\n"
                    "no usable Vulkan device from Halide's own runtime, which is separate\n"
                    "from the engine's).\n", rc);
        return 1;
    }
    std::printf("                      %6.2fx %s\n\n",
                ms_halide > 0 ? ms_shader / ms_halide : 0.0,
                ms_halide < ms_shader ? "(halide faster)" : "(shader faster)");

    // Distributions must agree: same model, different realisation.
    // E[grain] per channel = 3 cells * density, since 3 of the 9 cells target it.
    std::printf("distribution (same model, different realisation):\n");
    for (int c = 0; c < 3; ++c) {
        const Moments a = moments(out_shader, static_cast<size_t>(c), 3, npix);
        const Moments b = moments(out_halide, static_cast<size_t>(c) * npix, 1, npix);
        std::printf("  ch%d  shader mean %.6f sd %.6f   halide mean %.6f sd %.6f"
                    "   dmean %.2e  sd ratio %.4f\n",
                    c, a.mean, a.sd, b.mean, b.sd, std::fabs(a.mean - b.mean),
                    a.sd > 0 ? b.sd / a.sd : 0.0);
    }
    return 0;
}
