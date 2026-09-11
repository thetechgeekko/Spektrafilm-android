// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Gate for the f32 GPU FFT convolution (#216), run under a Vulkan ICD.
//
// The host parity suite CANNOT see this pass: tools/parity/run_engine_parity.sh
// compiles without SPK_ENABLE_VULKAN, so gpu::available() is false there and
// every line under gpu/ is dead. A 44/44 run is not evidence for this file.
//
// WHAT IS ASSERTED, and why in this order:
//
//   1. AGAINST THE DIRECT LOOP, on a case small enough to run it. This is the
//      only check that can catch a TRANSLATION: an FFT convolution that places
//      the kernel centred rather than at the origin, or reads the valid window
//      at the wrong offset, still produces a beautifully smooth image -- shifted
//      by the kernel radius. Comparing against another FFT implementation that
//      shares the convention would agree with it.
//   2. AGAINST kernels/fft_convolve.h's f64 path, single tile and multi tile.
//      This is the operator the engine actually runs, and multi-tile is where
//      overlap-save bookkeeping lives.
//   3. INTERLEAVED addressing on BOTH sides: the engine convolves one channel
//      of an interleaved RGB image at a time, so a stride bug reads or writes a
//      perfect result from or into the wrong channel. The three channels here
//      carry DIFFERENT images for exactly that reason.
//   3b. THE REFLECT PADDING, which is now the shader's job rather than the
//      caller's. The GPU is handed the unpadded image; the CPU reference is
//      handed a plane padded by the host's own reflect rule. If the shader's
//      fold disagrees anywhere -- including at the corners, where both axes
//      reflect -- the two answers differ at the border.
//   4. REFUSALS, byte-for-byte: on any refusal the caller's buffer must be
//      untouched, because the caller then runs the CPU path over the same
//      memory and a half-written output would silently blend two answers.
//
// TOLERANCE, not equality. This is Fast GPU: f32 against f64, and a full
// complex transform against the CPU's real-to-complex one, so the roundings
// differ by construction. The bar is the engine's parity band scaled by the
// scene peak -- max_abs <= 1e-4 * peak, rms <= 1e-5 * peak -- which is the same
// unit tools/gpu_probe/probe_f32_fft_main.cpp reported 0.000 display codes at.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "kernels/fft_convolve.h"
#include "model/diffusion.h"

namespace {

int g_failures = 0;
bool g_engaged = false;

void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "[PASS]" : "[FAIL]", what);
    if (!ok) ++g_failures;
}

// A sharply peaked, sum-normalised, long-tailed PSF -- the shape
// model/diffusion.cpp builds for Black Pro-Mist, at a size the direct loop can
// still be run against.
std::vector<double> make_psf(int ks, double lambda_px) {
    std::vector<double> k(static_cast<size_t>(ks) * ks);
    const int r = (ks - 1) / 2;
    double total = 0.0;
    for (int y = 0; y < ks; ++y)
        for (int x = 0; x < ks; ++x) {
            const double dy = y - r, dx = x - r;
            const double v = std::exp(-std::sqrt(dx * dx + dy * dy) / lambda_px);
            k[static_cast<size_t>(y) * ks + x] = v;
            total += v;
        }
    for (double& v : k) v /= total;
    return k;
}

// Bright speculars on a gradient: what a diffusion filter is bought for, and
// an image whose convolution is NOT translation-invariant to the eye, so a
// half-pixel misplacement shows up as error rather than as a plausible picture.
// Interleaved RGB, and the three channels differ so a channel mix-up shows.
std::vector<double> make_image(int w, int h) {
    std::vector<double> p(static_cast<size_t>(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double t = static_cast<double>(x) / (w > 1 ? w - 1 : 1);
            const double u = static_cast<double>(y) / (h > 1 ? h - 1 : 1);
            const size_t o = (static_cast<size_t>(y) * w + x) * 3;
            p[o + 0] = 0.02 + 0.08 * t + 0.05 * u;
            p[o + 1] = 0.30 + 0.40 * u - 0.10 * t;
            p[o + 2] = 0.70 - 0.50 * t * u;
        }
    // Speculars pushed hard against the edges, because that is where the
    // reflect fold decides the answer.
    const int pts[][2] = {{0, 0}, {1, 3}, {2, 2}, {3, 4}, {5, 5}, {7, 2}, {7, 7}};
    for (const auto& q : pts) {
        const int cx = (w - 1) * q[0] / 7, cy = (h - 1) * q[1] / 7;
        for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
                const int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                for (int c = 0; c < 3; ++c)
                    p[(static_cast<size_t>(y) * w + x) * 3 + c] = 40.0 + 5.0 * c;
            }
    }
    return p;
}

// numpy mode='reflect', the same map model/diffusion.cpp builds its padded
// plane with. The GPU reference must be padded by THIS, not by the shader's
// version of it, or the check compares the shader against itself.
int reflect_host(int p, int n) {
    if (n == 1) return 0;
    const int period = 2 * (n - 1);
    int m = p % period;
    if (m < 0) m += period;
    return m < n ? m : period - m;
}

std::vector<double> pad_channel(const std::vector<double>& img, int w, int h,
                                int ks, int channel) {
    const int radius = (ks - 1) / 2;
    const int pw = w + ks - 1, ph = h + ks - 1;
    std::vector<double> p(static_cast<size_t>(pw) * ph);
    for (int yy = 0; yy < ph; ++yy) {
        const int sy = reflect_host(yy - radius, h);
        for (int xx = 0; xx < pw; ++xx) {
            const int sx = reflect_host(xx - radius, w);
            p[static_cast<size_t>(yy) * pw + xx] =
                img[(static_cast<size_t>(sy) * w + sx) * 3 + channel];
        }
    }
    return p;
}

struct Error {
    double max_abs = 0.0;
    double rms = 0.0;
    double peak = 0.0;
};

Error compare(const std::vector<double>& ref, const std::vector<double>& got,
              int stride, int offset) {
    Error e;
    double sq = 0.0;
    for (size_t i = 0; i < ref.size(); ++i) {
        const double a = ref[i];
        const double b = got[i * static_cast<size_t>(stride) +
                             static_cast<size_t>(offset)];
        const double diff = std::fabs(a - b);
        if (diff > e.max_abs) e.max_abs = diff;
        if (std::fabs(a) > e.peak) e.peak = std::fabs(a);
        sq += diff * diff;
    }
    e.rms = std::sqrt(sq / static_cast<double>(ref.size()));
    return e;
}

// The operator, spelled out exactly as kernels/fft_convolve.h documents it.
std::vector<double> direct(const std::vector<double>& padded, int pw,
                           const std::vector<double>& kern, int ks, int w, int h) {
    std::vector<double> out(static_cast<size_t>(w) * h, 0.0);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            double acc = 0.0;
            for (int i = 0; i < ks; ++i)
                for (int j = 0; j < ks; ++j)
                    acc += padded[static_cast<size_t>(y + i) * pw + (x + j)] *
                           kern[static_cast<size_t>(ks - 1 - i) * ks + (ks - 1 - j)];
            out[static_cast<size_t>(y) * w + x] = acc;
        }
    return out;
}

spk::gpu::FftConvolveRequest make_request(const std::vector<double>& img,
                                          const std::vector<double>& kern,
                                          int w, int h, int ks, int max_transform) {
    spk::gpu::FftConvolveRequest r;
    r.src_rgb = img.data();
    r.width = w;
    r.height = h;
    r.channel = 0;
    r.kern = kern.data();
    r.ks = ks;
    r.max_transform = max_transform;
    return r;
}

// One case: build the scene, run the GPU pass, compare against `ref`.
void run_case(const char* label, int w, int h, int ks, double lambda,
              int max_transform, int out_stride, int out_offset, bool against_direct,
              int channel = 0) {
    const int pw = w + ks - 1, ph = h + ks - 1;
    const std::vector<double> img = make_image(w, h);
    const std::vector<double> pad64 = pad_channel(img, w, h, ks, channel);
    const std::vector<double> k64 = make_psf(ks, lambda);

    std::vector<double> ref;
    if (against_direct) {
        ref = direct(pad64, pw, k64, ks, w, h);
    } else {
        ref.assign(static_cast<size_t>(w) * h, 0.0);
        if (!spk::fft_convolve_same<double>(pad64.data(), pw, ph, k64.data(), ks, w, h,
                                            ref.data(), 1, 0, max_transform)) {
            check(false, "cpu reference refused");
            return;
        }
    }

    // A sentinel in every slot the pass must not touch, so a stride bug shows as
    // a surviving sentinel rather than as a plausible number.
    std::vector<double> got(static_cast<size_t>(w) * h * out_stride, -12345.0);
    spk::gpu::FftConvolveRequest r = make_request(img, k64, w, h, ks, max_transform);
    r.channel = channel;
    r.out_stride = out_stride;
    r.out_offset = out_offset;
    spk::gpu::FftConvolveDiagnostics d{};
    const bool ok = spk::gpu::fft_convolve(r, got.data(), &d);
    if (!ok) {
        std::printf("[FAIL] %s: refused (%s)\n", label, d.reason);
        ++g_failures;
        return;
    }
    if (d.engaged) g_engaged = true;

    const Error e = compare(ref, got, out_stride, out_offset);
    const double abs_bar = 1e-4 * e.peak, rms_bar = 1e-5 * e.peak;
    std::printf("  %s: n=%d tiles=%u dispatches=%u  peak %.4g  max_abs %.3e (bar %.3e)"
                "  rms %.3e (bar %.3e)  gpu %.1f ms\n",
                label, d.transform_size, d.tiles, d.dispatches, e.peak,
                e.max_abs, abs_bar, e.rms, rms_bar, d.gpu_ms);
    check(e.max_abs <= abs_bar && e.rms <= rms_bar, label);

    // Untouched components stay untouched.
    bool sentinels_ok = true;
    for (int slot = 0; slot < out_stride; ++slot) {
        if (slot == out_offset) continue;
        for (size_t i = 0; i < static_cast<size_t>(w) * h; ++i)
            if (got[i * out_stride + slot] != -12345.0) { sentinels_ok = false; break; }
    }
    if (out_stride > 1) check(sentinels_ok, "other components untouched");
}


// PAIR MODE (#227): two channels on one complex transform. The two channels get
// DIFFERENT source components AND DIFFERENT kernels on purpose -- a pass that
// reused one kernel for both, or read one component twice, or swapped the two
// outputs, would still produce plausible numbers and pass a test built on one
// kernel and one channel. Each half is compared against the same CPU f64
// operator the single-channel cases use.
void run_pair_case(const char* label, int w, int h, int ks, int max_transform,
                   int ch1, int ch2, int off1, int off2, int out_stride) {
    const int pw = w + ks - 1, ph = h + ks - 1;
    const std::vector<double> img = make_image(w, h);
    const std::vector<double> k1 = make_psf(ks, 2.0);
    const std::vector<double> k2 = make_psf(ks, 5.0);   // deliberately different

    auto cpu_ref = [&](int channel, const std::vector<double>& kern,
                       std::vector<double>* out) {
        const std::vector<double> pad = pad_channel(img, w, h, ks, channel);
        out->assign(static_cast<size_t>(w) * h, 0.0);
        return spk::fft_convolve_same<double>(pad.data(), pw, ph, kern.data(), ks, w, h,
                                              out->data(), 1, 0, max_transform);
    };
    std::vector<double> ref1, ref2;
    if (!cpu_ref(ch1, k1, &ref1) || !cpu_ref(ch2, k2, &ref2)) {
        check(false, "cpu reference refused (pair)");
        return;
    }

    std::vector<double> got(static_cast<size_t>(w) * h * out_stride, -12345.0);
    spk::gpu::FftConvolveRequest r = make_request(img, k1, w, h, ks, max_transform);
    r.channel = ch1;
    r.out_stride = out_stride;
    r.out_offset = off1;
    r.channel2 = ch2;
    r.kern2 = k2.data();
    r.out_offset2 = off2;
    spk::gpu::FftConvolveDiagnostics d{};
    if (!spk::gpu::fft_convolve(r, got.data(), &d)) {
        std::printf("[FAIL] %s: refused (%s)\n", label, d.reason);
        ++g_failures;
        return;
    }
    if (d.engaged) g_engaged = true;

    const Error e1 = compare(ref1, got, out_stride, off1);
    const Error e2 = compare(ref2, got, out_stride, off2);
    std::printf("  %s: n=%d tiles=%u dispatches=%u  ch%d max_abs %.3e  ch%d max_abs %.3e"
                "  gpu %.1f ms\n",
                label, d.transform_size, d.tiles, d.dispatches, ch1, e1.max_abs,
                ch2, e2.max_abs, d.gpu_ms);
    check(e1.max_abs <= 1e-4 * e1.peak && e1.rms <= 1e-5 * e1.peak, label);
    check(e2.max_abs <= 1e-4 * e2.peak && e2.rms <= 1e-5 * e2.peak, "pair: second channel");

    // The two halves must not be the same picture: if they were, a pass that
    // ignored kern2 and wrote channel 1 twice would satisfy both checks above
    // only because the reference was built the same wrong way. It is not -- the
    // references come from different kernels -- but assert the separation
    // directly so the intent survives an edit to the references.
    double spread = 0.0;
    for (size_t i = 0; i < ref1.size(); ++i)
        spread = std::max(spread, std::fabs(ref1[i] - ref2[i]));
    check(spread > 1e-3 * e1.peak, "pair: the two references actually differ");

    if (out_stride > 2) {
        bool sentinels_ok = true;
        for (int slot = 0; slot < out_stride; ++slot) {
            if (slot == off1 || slot == off2) continue;
            for (size_t i = 0; i < static_cast<size_t>(w) * h; ++i)
                if (got[i * out_stride + slot] != -12345.0) { sentinels_ok = false; break; }
        }
        check(sentinels_ok, "pair: other components untouched");
    }
}


// THE ELEMENT-BUDGET LADDER (#227). The transform is rectangular now, so the
// per-axis cap no longer bounds the allocation on its own and the caller walks
// `max_elements` DOWN when a device refuses. That ladder is the difference
// between a memory-tight phone getting a smaller GPU transform and getting no GPU
// transform at all, and nothing else exercises it: every case above runs at the
// default budget, where the chooser picks whatever it likes.
//
// A constrained budget must still produce the RIGHT ANSWER, not merely a
// different shape -- a chooser that returned a rectangle whose overlap-save block
// did not tile the image would produce a plausible, wrong picture. So each budget
// is compared against the same CPU f64 reference, and the tile count is printed
// so a budget that silently changed nothing is visible rather than assumed.
void run_budget_ladder(const char* label, int w, int h, int ks, int max_transform) {
    const int pw = w + ks - 1, ph = h + ks - 1;
    const std::vector<double> img = make_image(w, h);
    const std::vector<double> pad64 = pad_channel(img, w, h, ks, 0);
    const std::vector<double> k64 = make_psf(ks, 3.0);

    std::vector<double> ref(static_cast<size_t>(w) * h, 0.0);
    if (!spk::fft_convolve_same<double>(pad64.data(), pw, ph, k64.data(), ks, w, h,
                                        ref.data(), 1, 0, max_transform)) {
        check(false, "cpu reference refused (budget ladder)");
        return;
    }

    // Widest rectangle the axis cap allows, then successive halvings. The last
    // one is deliberately tight enough that only a near-square fits.
    const uint64_t widest = static_cast<uint64_t>(max_transform) * max_transform;
    uint32_t seen_tiles[4] = {0, 0, 0, 0};
    int idx = 0;
    bool any = false;
    for (uint64_t budget : {widest, widest / 2, widest / 4, widest / 8}) {
        std::vector<double> got(static_cast<size_t>(w) * h, -12345.0);
        spk::gpu::FftConvolveRequest r =
            make_request(img, k64, w, h, ks, max_transform);
        r.max_elements = budget;
        spk::gpu::FftConvolveDiagnostics d{};
        if (!spk::gpu::fft_convolve(r, got.data(), &d)) {
            // A budget too small for ANY admissible rectangle must refuse
            // cleanly and leave the buffer alone, not write a partial answer.
            bool untouched = true;
            for (double v : got) if (v != -12345.0) { untouched = false; break; }
            std::printf("  %s budget=%llu: refused (%s) untouched=%d\n", label,
                        static_cast<unsigned long long>(budget), d.reason,
                        untouched ? 1 : 0);
            check(untouched, "budget refusal leaves the output untouched");
            ++idx;
            continue;
        }
        if (d.engaged) g_engaged = true;
        any = true;
        seen_tiles[idx] = d.tiles;
        const Error e = compare(ref, got, 1, 0);
        std::printf("  %s budget=%llu: n=%d tiles=%u  max_abs %.3e (bar %.3e)\n",
                    label, static_cast<unsigned long long>(budget), d.transform_size,
                    d.tiles, e.max_abs, 1e-4 * e.peak);
        check(e.max_abs <= 1e-4 * e.peak && e.rms <= 1e-5 * e.peak,
              "constrained budget still matches the CPU operator");
        ++idx;
    }
    check(any, "at least one budget on the ladder ran");

    // A tighter budget can never buy FEWER tiles. If it did, the cost model and
    // the allocation bound disagree, which is how a "fallback" silently becomes
    // the faster path and the default becomes dead code.
    bool monotone = true;
    for (int i = 1; i < 4; ++i)
        if (seen_tiles[i] != 0 && seen_tiles[i - 1] != 0 &&
            seen_tiles[i] < seen_tiles[i - 1])
            monotone = false;
    check(monotone, "a tighter element budget never lowers the tile count");
}

void run_refusals() {
    const int w = 32, h = 24, ks = 7;
    const std::vector<double> pad = make_image(w, h);
    const std::vector<double> kern = make_psf(ks, 2.0);
    const std::vector<double> pristine(static_cast<size_t>(w) * h, -777.0);

    struct Case {
        const char* what;
        void (*mutate)(spk::gpu::FftConvolveRequest&);
    };
    const Case cases[] = {
        {"null source", [](spk::gpu::FftConvolveRequest& r) { r.src_rgb = nullptr; }},
        {"null kernel", [](spk::gpu::FftConvolveRequest& r) { r.kern = nullptr; }},
        {"even kernel", [](spk::gpu::FftConvolveRequest& r) { r.ks += 1; }},
        {"channel out of range", [](spk::gpu::FftConvolveRequest& r) { r.channel = 3; }},
        {"negative channel", [](spk::gpu::FftConvolveRequest& r) { r.channel = -1; }},
        {"empty frame", [](spk::gpu::FftConvolveRequest& r) { r.width = 0; }},
        {"offset >= stride", [](spk::gpu::FftConvolveRequest& r) {
             r.out_stride = 2; r.out_offset = 2; }},
    };
    for (const Case& cs : cases) {
        std::vector<double> out = pristine;
        spk::gpu::FftConvolveRequest r = make_request(pad, kern, w, h, ks,
                                                      spk::gpu::kFftGpuMaxTransform);
        cs.mutate(r);
        spk::gpu::FftConvolveDiagnostics d{};
        const bool ok = spk::gpu::fft_convolve(r, out.data(), &d);
        const bool untouched = (out == pristine);
        std::printf("  refusal '%s': ok=%d reason=%s untouched=%d\n",
                    cs.what, ok ? 1 : 0, d.reason, untouched ? 1 : 0);
        check(!ok && untouched && !d.engaged, cs.what);
    }

    // A kernel wider than the GPU's scratch ceiling is a REFUSAL, never a
    // truncated transform: fft_convolve_transform_size raises its own cap to
    // keep the usable block positive, so it hands back a size above the ceiling
    // rather than failing, and this is the check that turns that into a denial.
    {
        const int big_ks = 2 * spk::gpu::kFftGpuMaxTransform + 1;
        std::vector<double> bigkern(static_cast<size_t>(big_ks) * big_ks, 0.0);
        std::vector<double> out = pristine;
        spk::gpu::FftConvolveRequest r = make_request(pad, bigkern, w, h, big_ks,
                                                      spk::gpu::kFftGpuMaxTransform);
        spk::gpu::FftConvolveDiagnostics d{};
        const bool ok = spk::gpu::fft_convolve(r, out.data(), &d);
        std::printf("  refusal 'kernel past scratch ceiling': ok=%d reason=%s n=%d\n",
                    ok ? 1 : 0, d.reason, d.transform_size);
        check(!ok && out == pristine, "kernel past scratch ceiling refused");
    }
}

// THE WIRING CHECK. Every other assertion here calls gpu::fft_convolve
// directly, which proves the pass works and proves nothing about whether the
// engine ever reaches it. That distinction has cost this project twice: the GPU
// glare field was committed, gated and green while a missing line in
// build_print_scanning_params kept its latch clear, so it ran in no export at
// all and measured SLOWER than the CPU (the price of attempt-then-fallback).
//
// The observable is the OUTPUT. The device answers in f32 and the host in f64,
// so if the latch reaches the pass the two renders differ -- by ~1e-6, not by
// nothing. A byte-identical pair means the GPU route was never taken.
void run_wiring() {
    const int w = 128, h = 128;
    std::vector<double> base(static_cast<size_t>(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c)
                base[(static_cast<size_t>(y) * w + x) * 3 + c] =
                    0.05 + 0.4 * (static_cast<double>(x) / w) +
                    ((x % 37 == 0 && y % 29 == 0) ? 30.0 : 0.0);

    spk::DiffusionFilterParams p;
    p.active = true;
    p.strength = 1.0;
    p.spatial_scale = 1.0;
    // Force the transform branch rather than relying on the cost model landing
    // there for this geometry: the direct loop has no GPU route, so a case that
    // silently chose it would compare the CPU against itself and pass.
    setenv("SPK_DIFFUSION_FFT", "1", 1);

    std::vector<double> cpu = base, gpu = base;
    spk::apply_diffusion_filter_um(cpu.data(), w, h, p, 5.0, /*allow_gpu_fft=*/false);
    spk::apply_diffusion_filter_um(gpu.data(), w, h, p, 5.0, /*allow_gpu_fft=*/true);
    unsetenv("SPK_DIFFUSION_FFT");

    double max_abs = 0.0, peak = 0.0;
    bool differs = false;
    for (size_t i = 0; i < cpu.size(); ++i) {
        if (cpu[i] != gpu[i]) differs = true;
        max_abs = std::max(max_abs, std::fabs(cpu[i] - gpu[i]));
        peak = std::max(peak, std::fabs(cpu[i]));
    }
    std::printf("  wiring: differs=%d  max_abs %.3e  peak %.4g  bar %.3e\n",
                differs ? 1 : 0, max_abs, peak, 1e-4 * peak);
    check(differs, "latch reaches the GPU pass (renders are not identical)");
    check(max_abs <= 1e-4 * peak, "wired GPU render stays inside the parity band");

    // And the latch OFF must be exactly the CPU path, with no GPU influence.
    std::vector<double> again = base;
    spk::apply_diffusion_filter_um(again.data(), w, h, p, 5.0, /*allow_gpu_fft=*/false);
    check(again == cpu, "latch off is byte-identical to the CPU path");
}

}  // namespace

int main() {
    std::printf("== test_fft_convolve_gpu ==\n");
    std::printf("gpu available: %s\n", spk::gpu::available() ? "yes" : "NO");

    // 1. The translation check. Small enough for the O(w*h*ks^2) direct loop.
    run_case("vs direct loop 24x18 ks=5", 24, 18, 5, 1.5,
             spk::gpu::kFftGpuMaxTransform, 1, 0, /*against_direct=*/true);

    // 2. The engine's own operator, single tile then multi tile. The second case
    //    caps the transform at 128 so a 200x120 frame needs 6 overlap-save tiles;
    //    without the cap it would fit in one and the tiling would go untested.
    run_case("vs cpu f64 96x72 ks=11", 96, 72, 11, 3.0,
             spk::gpu::kFftGpuMaxTransform, 1, 0, false);
    run_case("vs cpu f64 200x120 ks=31 (6 tiles)", 200, 120, 31, 6.0, 128, 1, 0, false);

    // 3. Interleaved addressing on both sides: read channel 2 of the source,
    //    write component 1 of the destination. Deliberately mismatched, so a
    //    pass that quietly used one index for both would fail.
    // 3. Pair mode, single tile and multi tile, against the same CPU operator.
    run_pair_case("pair 96x72 ks=11", 96, 72, 11, 256, 0, 1, 0, 1, 3);
    run_pair_case("pair 200x120 ks=31 (6 tiles)", 200, 120, 31, 128, 0, 1, 0, 1, 3);
    // Crossed addressing: the channel order and the output order disagree, so a
    // pass that quietly assumes out_offset == channel is caught.
    run_pair_case("pair crossed: ch2,ch0 -> comp0,comp2", 96, 72, 11, 256, 2, 0, 0, 2, 3);

    // 4. The element-budget ladder the rectangular transform introduced.
    run_budget_ladder("budget 200x120 ks=31", 200, 120, 31, 128);

    run_case("interleaved: read ch2, write component 1", 96, 72, 11, 3.0,
             spk::gpu::kFftGpuMaxTransform, 3, 1, false, /*channel=*/2);

    run_refusals();
    run_wiring();

    std::printf("engaged=%d\n", g_engaged ? 1 : 0);
    if (g_failures == 0) std::printf("test_fft_convolve_gpu: ALL OK\n");
    else std::printf("test_fft_convolve_gpu: %d FAIL\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
