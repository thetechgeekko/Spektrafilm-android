// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Realtime falsifier (#208). Can the Vulkan host sustain a video frame at all?
//
// Every framerate number in the video map so far is a SCALING of one 12.48 MP
// still capture, and this repo's notebook records four separate occasions where
// such a number described a condition nobody had written down. So before any
// framerate is claimed, measure the two things that are actually in tension:
//
//   * the kernels look fast enough  — one qualified stage measured ~106-152
//     Mpix/s marginal on this device class, against a 62.21 Mpix/s 1080p30 bar;
//   * the host looks fatal          — the same call carries ~23-46 ms of FIXED
//     per-call cost, which alone exceeds a whole 30 fps frame.
//
// Those two numbers come from the SAME table (docs/research/gpu-device-probe.md
// Tier 2: 24.8 ms at 0.307 MP, 101.5 ms at 12 MP on a cool run). Solving them as
// fixed + marginal*MP gives 6.56 ms/MP and 22.8 ms fixed. The fixed term is the
// whole question, because it does not shrink when the frame does.
//
// The experiment that separates them is a comparison, not a single timing:
//
//   scan_spectral            — rebuilds buffers and pipeline EVERY call and
//                              round-trips host-visible memory. The status quo.
//   render_pointwise_chain   — persistent pipelines, grow-only buffers, keyed
//                              static f32 tables reused on warm calls, and
//                              device-local ping-pong between three dispatches.
//
// Same device, same frame size, back to back. If the persistent host still
// carries tens of milliseconds of fixed cost, no shader work rescues realtime and
// the host rebuild (#212) is the whole project. If it does not, the fixed cost is
// an artefact of the non-persistent entry points and the kernels can be trusted.
//
// The chain's own diagnostics are the proof that "warm" means warm:
// pipeline_creates and buffer_allocations must read 0 on a warm call, and
// interstage_host_bytes must read 0 always (three dispatches, no host hop between
// them). A timing taken while those are nonzero is measuring cold-start, not
// steady state.
//
// This probe deliberately uses SYNTHETIC tables lifted from
// gpu/tests/test_pointwise_chain.cpp. It measures host and dispatch COST, not
// numeric accuracy — accuracy is already gated elsewhere, and real profile tables
// would change the timing by nothing while dragging in the whole engine.
//
// Usage: gpu_probe_rt [width] [height] [runs] [sustain_seconds]
//        defaults      1920    1080     300    0
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "gpu/vulkan_compute.h"

namespace {

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

struct Stats {
    double min = 0, median = 0, p95 = 0, max = 0, mean = 0;
    size_t n = 0;
};

Stats summarise(std::vector<double> v) {
    Stats s{};
    if (v.empty()) return s;
    std::sort(v.begin(), v.end());
    s.n = v.size();
    s.min = v.front();
    s.max = v.back();
    s.median = v[v.size() / 2];
    s.p95 = v[static_cast<size_t>(static_cast<double>(v.size() - 1) * 0.95)];
    double sum = 0.0;
    for (double x : v) sum += x;
    s.mean = sum / static_cast<double>(v.size());
    return s;
}

void report(const char* label, const Stats& s, double mpix) {
    if (s.n == 0) {
        std::printf("%-26s  (no samples)\n", label);
        return;
    }
    // Mpix/s from the MEDIAN, because a mean over a thermally drifting run
    // describes neither the start nor the end of it.
    const double mpix_s = s.median > 0.0 ? mpix / (s.median / 1000.0) : 0.0;
    std::printf("%-26s n=%-4zu  min %7.2f  med %7.2f  p95 %7.2f  max %8.2f ms   %7.1f Mpix/s\n",
                label, s.n, s.min, s.median, s.p95, s.max, mpix_s);
}

// A frame budget is a deadline, so state the verdict as one rather than leaving
// the reader to divide.
void verdict(const char* label, double median_ms) {
    struct Bar { const char* name; double ms; };
    const Bar bars[] = {{"1080p30", 33.33}, {"1080p60", 16.67}, {"4K30", 33.33}};
    for (const Bar& b : bars) {
        if (std::strcmp(b.name, "4K30") == 0) continue;  // reported separately at 4K
        std::printf("    %s vs %-8s budget %5.2f ms: %s (%.2fx)\n", label, b.name, b.ms,
                    median_ms <= b.ms ? "MEETS" : "MISSES", median_ms / b.ms);
    }
}

}  // namespace

int main(int argc, char** argv) {
    using namespace spk::gpu;

    const int width = argc > 1 ? std::atoi(argv[1]) : 1920;
    const int height = argc > 2 ? std::atoi(argv[2]) : 1080;
    const int runs = argc > 3 ? std::atoi(argv[3]) : 300;
    const double sustain_s = argc > 4 ? std::atof(argv[4]) : 0.0;

    if (width <= 0 || height <= 0 || runs <= 0) {
        std::printf("FAIL: bad geometry/runs\n");
        return 2;
    }
    const uint32_t pixels = static_cast<uint32_t>(width) * static_cast<uint32_t>(height);
    const double mpix = static_cast<double>(pixels) / 1e6;

    std::printf("== realtime falsifier (#208) ==\n");
    std::printf("frame %dx%d = %.3f MP, runs %d, sustain %.0f s\n\n", width, height, mpix,
                runs, sustain_s);

    if (!available()) {
        // Fail loudly. A silent CPU fallback here would produce a number that
        // looks like a GPU measurement and is not one.
        std::printf("FAIL: spk::gpu::available() is false — no Vulkan on this device/build\n");
        return 1;
    }

    // ---- synthetic tables (shape-valid; see the header note on why) ----------
    constexpr uint32_t curvePoints = 5;
    std::vector<float> input(static_cast<size_t>(pixels) * 3u);
    for (uint32_t p = 0; p < pixels; ++p) {
        const float t = static_cast<float>(p % 4096u) / 4095.0f;
        input[p * 3u] = 0.03f + 1.17f * t;
        input[p * 3u + 1u] = 0.82f - 0.54f * t;
        input[p * 3u + 2u] = 0.11f + 0.43f * (1.0f - t);
    }
    const float tc[12] = {0.13f, 0.21f, 0.34f, 0.55f, 0.09f, 0.27f,
                          0.18f, 0.63f, 0.41f, 0.77f, 0.46f, 0.12f};
    const float developAxis[curvePoints * 3] = {-8.0f, -7.4f, -6.8f, -3.5f, -3.0f,
                                                -2.4f, -1.0f, -0.4f, 0.1f,  0.8f,
                                                1.3f,  1.9f,  4.0f,  4.6f,  5.2f};
    const float developCurve[curvePoints * 3] = {0.03f, 0.07f, 0.12f, 0.19f, 0.25f,
                                                 0.31f, 0.52f, 0.61f, 0.73f, 1.08f,
                                                 1.21f, 1.37f, 1.92f, 2.13f, 2.36f};
    const float dirAxis[curvePoints * 3] = {-7.7f, -7.0f, -6.4f, -3.2f, -2.7f,
                                            -2.0f, -0.8f, -0.2f, 0.4f,  1.0f,
                                            1.6f,  2.2f,  4.3f,  4.9f,  5.6f};
    const float dirCurve[curvePoints * 3] = {0.02f, 0.05f, 0.09f, 0.16f, 0.22f,
                                             0.29f, 0.48f, 0.58f, 0.69f, 1.02f,
                                             1.18f, 1.32f, 1.85f, 2.06f, 2.29f};
    const float paperAxis[curvePoints * 3] = {-6.9f, -6.3f, -5.8f, -3.0f, -2.5f,
                                              -1.9f, -0.7f, -0.1f, 0.5f,  1.1f,
                                              1.7f,  2.4f,  4.6f,  5.1f,  5.8f};
    const float paperCurve[curvePoints * 3] = {0.04f, 0.08f, 0.13f, 0.21f, 0.27f,
                                               0.34f, 0.56f, 0.66f, 0.78f, 1.12f,
                                               1.28f, 1.45f, 2.01f, 2.24f, 2.49f};
    std::vector<float> dye(81u * 3u), scanDye(81u * 3u);
    std::vector<float> printResponse(81u * 3u), scanResponse(81u * 3u);
    for (size_t band = 0; band < 81; ++band) {
        const float t = static_cast<float>(band + 1u) / 81.0f;
        dye[band * 3u] = 0.002f + 0.006f * t;
        dye[band * 3u + 1u] = 0.003f + 0.004f * t * t;
        dye[band * 3u + 2u] = 0.0015f + 0.007f * (1.0f - t);
        scanDye[band * 3u] = 0.004f + 0.003f * (1.0f - t);
        scanDye[band * 3u + 1u] = 0.001f + 0.008f * t * t;
        scanDye[band * 3u + 2u] = 0.0025f + 0.005f * t;
        printResponse[band * 3u] = (0.60f + 0.40f * t) / 81.0f;
        printResponse[band * 3u + 1u] = (0.72f - 0.31f * t) / 81.0f;
        printResponse[band * 3u + 2u] = (0.48f + 0.23f * t * t) / 81.0f;
        scanResponse[band * 3u] = (0.51f + 0.36f * t) / 81.0f;
        scanResponse[band * 3u + 1u] = (0.79f - 0.27f * t) / 81.0f;
        scanResponse[band * 3u + 2u] = (0.43f + 0.29f * t * t) / 81.0f;
    }

    PointwiseChainRequest request{};
    request.input_rgb = input.data();
    request.input_component_count = input.size();
    request.pixel_count = pixels;
    request.static_table_key = UINT64_C(0x1480000000000208);
    request.film.tc_lut = {tc, 12};
    request.film.tc_edge = 2;
    request.film.develop_axis = {developAxis, curvePoints * 3u};
    request.film.develop_curve = {developCurve, curvePoints * 3u};
    request.film.dir_axis = {dirAxis, curvePoints * 3u};
    request.film.dir_curve = {dirCurve, curvePoints * 3u};
    request.film.curve_points = curvePoints;
    request.film.exposure_multiplier = 0.83f;
    request.film.coupler_shift = 0.17f;
    const float couplerMatrix[9] = {0.041f, 0.012f, 0.007f, 0.009f, 0.053f,
                                    0.015f, 0.004f, 0.018f, 0.047f};
    std::memcpy(request.film.coupler_matrix, couplerMatrix, sizeof(couplerMatrix));
    request.print.dye = {dye.data(), dye.size()};
    request.print.illuminant_sensitivity = {printResponse.data(), printResponse.size()};
    request.print.paper_axis = {paperAxis, curvePoints * 3u};
    request.print.paper_curve = {paperCurve, curvePoints * 3u};
    request.print.curve_points = curvePoints;
    request.print.midgray = 0.91f;
    request.print.exposure_multiplier = 1.13f;
    request.print.preflash[0] = 0.011f;
    request.print.preflash[1] = 0.023f;
    request.print.preflash[2] = 0.006f;
    request.scan.dye = {scanDye.data(), scanDye.size()};
    request.scan.illuminant_cmf = {scanResponse.data(), scanResponse.size()};
    const float xyzToRgb[9] = {1.07f, 0.08f, 0.02f, 0.04f, 0.92f,
                               0.07f, 0.03f, 0.11f, 0.79f};
    std::memcpy(request.scan.xyz_to_rgb, xyzToRgb, sizeof(xyzToRgb));

    std::vector<float> out(input.size(), 0.0f);
    PointwiseChainOutput output{out.data(), out.size()};

    // ---- A: persistent resident chain ---------------------------------------
    PointwiseChainDiagnostics cold{};
    const auto t_cold = Clock::now();
    if (!render_pointwise_chain(request, &output, &cold)) {
        std::printf("FAIL: cold render_pointwise_chain refused: %s\n",
                    pointwise_fallback_reason_name(cold.fallback_reason));
        return 1;
    }
    const double cold_ms = ms_since(t_cold);
    std::printf("cold call            %8.2f ms  (dispatches %u, pipeline_creates %u, "
                "buffer_allocations %u, static_upload %llu B)\n",
                cold_ms, cold.dispatches, cold.pipeline_creates, cold.buffer_allocations,
                static_cast<unsigned long long>(cold.static_upload_bytes));

    for (int i = 0; i < 5; ++i) {  // settle
        PointwiseChainDiagnostics d{};
        render_pointwise_chain(request, &output, &d);
    }

    std::vector<double> chain_ms;
    chain_ms.reserve(static_cast<size_t>(runs));
    PointwiseChainDiagnostics warm{};
    for (int i = 0; i < runs; ++i) {
        PointwiseChainDiagnostics d{};
        const auto t0 = Clock::now();
        const bool ok = render_pointwise_chain(request, &output, &d);
        const double ms = ms_since(t0);
        if (!ok) {
            std::printf("FAIL: warm chain refused at run %d: %s\n", i,
                        pointwise_fallback_reason_name(d.fallback_reason));
            return 1;
        }
        chain_ms.push_back(ms);
        warm = d;
    }

    // Warmth is a claim about the diagnostics, so check it rather than assume it.
    std::printf("warm diagnostics     dispatches %u  pipeline_creates %u  "
                "buffer_allocations %u  input_uploads %u  final_readbacks %u  "
                "interstage_host_bytes %llu\n",
                warm.dispatches, warm.pipeline_creates, warm.buffer_allocations,
                warm.input_uploads, warm.final_readbacks,
                static_cast<unsigned long long>(warm.interstage_host_bytes));
    const bool truly_warm = warm.pipeline_creates == 0u && warm.buffer_allocations == 0u;
    std::printf("%s: warm calls reuse pipelines and buffers\n", truly_warm ? "ok" : "WARN");
    if (warm.interstage_host_bytes != 0u)
        std::printf("WARN: interstage_host_bytes != 0 — the chain is hopping through the host\n");

    // ---- B: non-persistent entry point, same frame --------------------------
    std::vector<float> scan_in(static_cast<size_t>(pixels) * 3u, 0.35f);
    std::vector<float> scan_out(static_cast<size_t>(pixels) * 3u, 0.0f);
    std::vector<double> scan_ms;
    const int scan_runs = std::min(runs, 30);  // it is slow by construction
    bool scan_ok = true;
    for (int i = 0; i < scan_runs + 1; ++i) {
        const auto t0 = Clock::now();
        const bool ok = scan_spectral_linear(scan_in.data(), scan_out.data(), pixels,
                                             scanDye.data(), scanResponse.data(), xyzToRgb);
        const double ms = ms_since(t0);
        if (!ok) { scan_ok = false; break; }
        if (i > 0) scan_ms.push_back(ms);  // drop the first, it is cold
    }

    std::printf("\n-- steady state --\n");
    const Stats chain = summarise(chain_ms);
    report("resident chain (3 disp)", chain, mpix);
    if (scan_ok) {
        report("scan_spectral_linear", summarise(scan_ms), mpix);
    } else {
        std::printf("%-26s  refused on this device\n", "scan_spectral_linear");
    }

    std::printf("\n-- verdict --\n");
    verdict("resident chain", chain.median);
    std::printf("    cold-to-warm ratio: %.1fx (cold %.2f ms, warm median %.2f ms)\n",
                chain.median > 0 ? cold_ms / chain.median : 0.0, cold_ms, chain.median);

    // ---- C: sustained, because peak throughput is not the constraint --------
    if (sustain_s > 0.0) {
        std::printf("\n-- sustained %.0f s --\n", sustain_s);
        std::vector<double> early, late;
        const auto start = Clock::now();
        size_t n = 0;
        std::vector<double> all;
        while (ms_since(start) < sustain_s * 1000.0) {
            PointwiseChainDiagnostics d{};
            const auto t0 = Clock::now();
            if (!render_pointwise_chain(request, &output, &d)) break;
            all.push_back(ms_since(t0));
            ++n;
        }
        if (n >= 20) {
            const size_t tenth = n / 10;
            early.assign(all.begin(), all.begin() + static_cast<long>(tenth));
            late.assign(all.end() - static_cast<long>(tenth), all.end());
            const Stats e = summarise(early), l = summarise(late);
            report("first 10%", e, mpix);
            report("last 10%", l, mpix);
            std::printf("    thermal drift: %+.1f%% (%.2f -> %.2f ms median)\n",
                        e.median > 0 ? (l.median / e.median - 1.0) * 100.0 : 0.0, e.median,
                        l.median);
            verdict("sustained", l.median);
        } else {
            std::printf("    too few frames (%zu) to split\n", n);
        }
    }

    std::printf("\ndone\n");
    return 0;
}
