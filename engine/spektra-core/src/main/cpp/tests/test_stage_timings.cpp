/*
 * Spektrafilm for Android — host test for per-stage render timings (#146/#152).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Proves the diagnostic timing facility (a) populates a non-empty breakdown for
 * a real render, (b) names the stages that actually ran, and (c) is INERT — a
 * render with timing read is byte-identical to one without, so it cannot touch
 * the parity contract. Local-only (not a parity gate). Build with the full
 * source set (no SPK_ENABLE_VULKAN needed).
 */
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "spektra.h"
#include "runtime/stage_timer.h"

namespace {
int g_fail = 0;
void check(bool ok, const char* what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what);
    if (!ok) g_fail = 1;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: %s <asset_dir>\n", argv[0]); return 2; }
    spk_engine* eng = nullptr;
    if (spk_engine_create(argv[1], &eng) != SPK_OK) {
        std::fprintf(stderr, "engine create failed\n"); return 2;
    }

    const int W = 48, H = 32;
    std::vector<float> in(static_cast<size_t>(W) * H * 3);
    for (size_t i = 0; i < in.size(); ++i) in[i] = 0.05f + 0.9f * ((i * 7) % 101) / 100.0f;
    spk_image img{in.data(), W, H, 0};

    spk_params p;
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    spk_default_params(&p);
    p.scan_film = 0;  // print route exercises the most stages

    // Render 1 — read timings.
    spk_image o1{};
    if (spk_simulate(eng, &img, &p, &o1) != SPK_OK) { std::printf("FAIL sim1\n"); return 1; }
    char tbuf[512];
    int n = spk_stage_timings(tbuf, sizeof(tbuf));
    std::printf("timings: [%s]\n", tbuf);
    check(n > 0, "timing breakdown is non-empty after a render");
    check(std::strstr(tbuf, "scan=") != nullptr, "scan stage timed");
    check(std::strstr(tbuf, "filming_expose=") != nullptr, "filming_expose stage timed");
    check(std::strstr(tbuf, "print_expose=") != nullptr, "print_expose stage timed (print route)");
#if defined(__GLIBC__)
    // #300: per-stage live-heap line. glibc reports mallinfo2, so it must be
    // populated here; the peak can never be below the start or any stage exit.
    char hbuf[384];
    const int hn = spk::stage_heap_format(hbuf, sizeof(hbuf));
    std::printf("heap: [%s]\n", hbuf);
    check(hn > 0 && std::strstr(hbuf, "peak=") != nullptr, "heap line is non-empty after a render");
    check(std::strstr(hbuf, " scan=") != nullptr, "scan stage heap sampled");
    {
        const spk::StageTimingSnapshot& s = spk::stage_timing_snapshot();
        bool bounded = s.heap_peak_bytes >= s.heap_start_bytes;
        for (int i = 0; i < spk::STG_COUNT; ++i)
            bounded = bounded && s.heap_after_bytes[i] <= s.heap_peak_bytes;
        check(bounded, "heap peak bounds the start and every stage exit");
    }
#endif
    std::vector<float> r1(o1.data, o1.data + static_cast<size_t>(o1.width) * o1.height * 3);
    spk_image_free(&o1);

    // Render 2 — identical params, no timing read between. Output must be
    // byte-identical (the timers are pure observation).
    spk_image o2{};
    if (spk_simulate(eng, &img, &p, &o2) != SPK_OK) { std::printf("FAIL sim2\n"); return 1; }
    std::vector<float> r2(o2.data, o2.data + static_cast<size_t>(o2.width) * o2.height * 3);
    spk_image_free(&o2);
    check(r1.size() == r2.size() &&
              std::memcmp(r1.data(), r2.data(), r1.size() * sizeof(float)) == 0,
          "timing instrumentation is output-inert (byte-identical renders)");

    std::printf(g_fail ? "test_stage_timings: FAIL\n" : "test_stage_timings: ALL OK\n");
    return g_fail;
}
