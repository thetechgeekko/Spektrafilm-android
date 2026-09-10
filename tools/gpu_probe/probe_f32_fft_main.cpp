// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// f32 vs f64 FFT convolution, on device. Owner request (2026-09-10):
// "use f32 for the whole thing even if we are breaking parity, test it."
//
// WHY THIS STAGE FIRST. With Black Pro-Mist on, camera diffusion is 6.9 s of a
// 10.9 s 12.5 MP export -- 63%, the largest single cost in the engine by a wide
// margin, and entirely on the CPU. It is already an FFT convolution (the direct
// loop is O(w*h*ks^2) with ks = 1725 at export scale, i.e. 3.7e13 MACs per
// channel), so the question is not "should it be an FFT" but "what does running
// that FFT in f32 buy, and what does it cost the picture".
//
// Both sides run kernels/fft_convolve.h's SAME templated implementation, so this
// compares precision and nothing else. A hand-written float FFT benched against
// the engine's tuned double one would have measured the wrong thing.
//
// Two numbers come out:
//   1. TIME. f32 halves the memory traffic of a transform that is memory-bound at
//      these sizes, so this is where the speedup is expected to come from -- not
//      from the arithmetic.
//   2. ERROR, reported against the f64 result AND as 8-bit display codes, which
//      is the unit the accuracy question is actually asked in. The engine's
//      parity band (1e-4) is the wrong yardstick here: the owner has explicitly
//      accepted breaking it, so what matters is whether the picture changes.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "kernels/fft_convolve.h"

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// An exponential PSF of the shape model/diffusion.cpp builds for Black Pro-Mist:
// sum-normalised on the truncated grid, sharply peaked, long tailed.
template <typename T>
std::vector<T> make_psf(int ks, double lambda_px) {
    std::vector<T> k(static_cast<size_t>(ks) * ks);
    const int r = (ks - 1) / 2;
    double total = 0.0;
    std::vector<double> acc(k.size());
    for (int y = 0; y < ks; ++y)
        for (int x = 0; x < ks; ++x) {
            const double dy = y - r, dx = x - r;
            const double rad = std::sqrt(dx * dx + dy * dy);
            const double v = std::exp(-rad / lambda_px) /
                             (2.0 * M_PI * lambda_px * lambda_px);
            acc[static_cast<size_t>(y) * ks + x] = v;
            total += v;
        }
    for (size_t i = 0; i < k.size(); ++i)
        k[i] = static_cast<T>(acc[i] / total);   // normalise in f64, store as T
    return k;
}

// Bright speculars on a dim field -- what a diffusion filter is bought for, and
// where a precision loss shows.
template <typename T>
std::vector<T> make_padded(int pw, int ph, int r) {
    std::vector<T> p(static_cast<size_t>(pw) * ph, T(0));
    for (int y = 0; y < ph; ++y)
        for (int x = 0; x < pw; ++x) {
            const double t = static_cast<double>(x) / (pw > 1 ? pw - 1 : 1);
            p[static_cast<size_t>(y) * pw + x] = static_cast<T>(0.02 + 0.08 * t);
        }
    const int pts[][2] = {{1, 3}, {2, 2}, {3, 4}, {5, 5}, {7, 2}};
    for (const auto& q : pts) {
        const int cx = r + (pw - 2 * r) * q[0] / 8;
        const int cy = r + (ph - 2 * r) * q[1] / 8;
        for (int dy = -2; dy <= 2; ++dy)
            for (int dx = -2; dx <= 2; ++dx) {
                const int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= pw || y >= ph) continue;
                p[static_cast<size_t>(y) * pw + x] = static_cast<T>(1.0e3);
            }
    }
    return p;
}

// sRGB encode, so the error can be quoted in the unit the eye works in.
double srgb_code(double v) {
    if (v <= 0.0) return 0.0;
    if (v > 1.0) v = 1.0;
    const double e = (v <= 0.0031308) ? (12.92 * v)
                                      : (1.055 * std::pow(v, 1.0 / 2.4) - 0.055);
    return e * 255.0;
}

void run_case(int w, int h, int ks, double lambda_px) {
    const int r = (ks - 1) / 2;
    const int pw = w + 2 * r, ph = h + 2 * r;
    std::printf("frame %dx%d  ks=%d (radius %d)  lambda=%.1f px  padded %dx%d\n",
                w, h, ks, r, lambda_px, pw, ph);

    auto pad64 = make_padded<double>(pw, ph, r);
    auto k64 = make_psf<double>(ks, lambda_px);
    std::vector<double> out64(static_cast<size_t>(w) * h, 0.0);

    const auto t0 = Clock::now();
    const bool ok64 = spk::fft_convolve_same<double>(pad64.data(), pw, ph, k64.data(), ks,
                                                     w, h, out64.data(), 1, 0);
    const double ms64 = ms_since(t0);

    std::vector<float> pad32(pad64.size()), k32(k64.size());
    for (size_t i = 0; i < pad64.size(); ++i) pad32[i] = static_cast<float>(pad64[i]);
    for (size_t i = 0; i < k64.size(); ++i) k32[i] = static_cast<float>(k64[i]);
    std::vector<float> out32(static_cast<size_t>(w) * h, 0.0f);

    const auto t1 = Clock::now();
    const bool ok32 = spk::fft_convolve_same<float>(pad32.data(), pw, ph, k32.data(), ks,
                                                    w, h, out32.data(), 1, 0);
    const double ms32 = ms_since(t1);

    if (!ok64 || !ok32) {
        std::printf("  REFUSED (f64=%d f32=%d)\n\n", ok64 ? 1 : 0, ok32 ? 1 : 0);
        return;
    }

    double max_abs = 0.0, sq = 0.0, max_rel = 0.0, max_code = 0.0;
    double peak = 0.0;
    for (size_t i = 0; i < out64.size(); ++i) if (std::fabs(out64[i]) > peak) peak = std::fabs(out64[i]);
    for (size_t i = 0; i < out64.size(); ++i) {
        const double a = out64[i], b = static_cast<double>(out32[i]);
        const double d = std::fabs(a - b);
        if (d > max_abs) max_abs = d;
        sq += d * d;
        const double den = std::fabs(a) > 1e-9 ? std::fabs(a) : 1e-9;
        if (d / den > max_rel) max_rel = d / den;
        // Display codes: normalise by the scene peak, then sRGB-encode both.
        const double cd = std::fabs(srgb_code(a / peak) - srgb_code(b / peak));
        if (cd > max_code) max_code = cd;
    }
    std::printf("  f64 %8.1f ms   f32 %8.1f ms   %5.2fx\n", ms64, ms32,
                ms32 > 0 ? ms64 / ms32 : 0.0);
    std::printf("  vs f64: max_abs %.3e  rms %.3e  max_rel %.3e  (scene peak %.3e)\n",
                max_abs, std::sqrt(sq / static_cast<double>(out64.size())), max_rel, peak);
    std::printf("  worst 8-bit display-code difference: %.3f codes\n\n", max_code);
}

}  // namespace

int main(int argc, char** argv) {
    std::printf("== camera diffusion FFT convolution: f64 vs f32 ==\n");
    std::printf("same templated implementation both sides (kernels/fft_convolve.h)\n\n");
    if (argc > 3) {
        run_case(std::atoi(argv[1]), std::atoi(argv[2]), std::atoi(argv[3]),
                 argc > 4 ? std::atof(argv[4]) : 40.0);
        return 0;
    }
    // Preview-scale and a tile of export scale. The full 12.5 MP frame at
    // ks = 1725 is convolved in tiles by the real code path, so a tile is the
    // honest unit here.
    run_case(640, 480, 273, 34.0);     // 640 px preview, Black Pro-Mist default
    run_case(1024, 1024, 273, 34.0);
    run_case(1024, 1024, 513, 64.0);   // an export-scale tile
    return 0;
}
