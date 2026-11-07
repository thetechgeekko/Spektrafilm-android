/*
 * Spektrafilm for Android — host gate for kernels/pack_display (#289). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The native display pack replaces the Kotlin `packToArgb` loop
 * (app/.../EngineHelpers.kt) and must produce the SAME bytes. The reference
 * below transcribes the JVM semantics of
 *
 *     (min(1f, maxOf(0f, v)) * 255f + 0.5f).toInt()
 *
 * independently of the kernel: Math.max/Math.min propagate NaN, each float
 * operation rounds separately (forced through volatile, so no FMA can form),
 * and Java's (int) of NaN is 0. It is compared on EVERY float32 in [+0, 1]
 * (the only inputs whose result is not a constant 0 or 255) plus every
 * 2^12-th bit pattern across the whole space, which covers negatives, values
 * above 1, ±Inf, NaN payloads and subnormals.
 *
 * Build (host): g++ -std=c++17 -O2 -I <cpp_root> tests/test_pack_display.cpp kernels/pack_display.cpp
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

#include "kernels/pack_display.h"

namespace {
int g_fail = 0;
void chk(bool c, const char* m) {
    std::printf("  [%s] %s\n", c ? "ok" : "FAIL", m);
    if (!c) g_fail = 1;
}

float from_bits(uint32_t b) {
    float f;
    std::memcpy(&f, &b, sizeof f);
    return f;
}

// java.lang.Math.max(float, float) / min: NaN if either argument is NaN.
float java_max(float a, float b) {
    if (std::isnan(a) || std::isnan(b)) return std::numeric_limits<float>::quiet_NaN();
    return a >= b ? a : b;  // operands here are never +0/-0 ties that matter: a == 0f
}
float java_min(float a, float b) {
    if (std::isnan(a) || std::isnan(b)) return std::numeric_limits<float>::quiet_NaN();
    return a <= b ? a : b;
}
// Java (int) float: NaN -> 0, saturating, truncation toward zero.
int java_to_int(float f) {
    if (std::isnan(f)) return 0;
    if (f >= 2147483647.0f) return std::numeric_limits<int>::max();
    if (f <= -2147483648.0f) return std::numeric_limits<int>::min();
    return static_cast<int>(f);
}

int reference(float v) {
    volatile float c = java_min(1.0f, java_max(0.0f, v));
    volatile float scaled = c * 255.0f;
    volatile float biased = scaled + 0.5f;
    return java_to_int(biased);
}
}  // namespace

int main() {
    std::printf("pack_display (#289):\n");

    // 1) Exhaustive over [+0, 1]: 0x00000000 .. 0x3F800000.
    uint64_t mismatches = 0;
    uint32_t first_bad = 0;
    for (uint32_t b = 0; b <= 0x3F800000u; ++b) {
        const float v = from_bits(b);
        if (spk::pack_display_channel(v) != reference(v)) {
            if (mismatches++ == 0) first_bad = b;
        }
    }
    if (mismatches) std::printf("    first mismatch at bits 0x%08x\n", first_bad);
    chk(mismatches == 0, "every float32 in [+0, 1] packs identically to the JVM expression");

    // 2) Strided sweep over all 2^32 patterns (negatives, >1, Inf, NaN, -0).
    mismatches = 0;
    for (uint64_t b = 0; b <= 0xFFFFFFFFull; b += 4096) {
        const float v = from_bits(static_cast<uint32_t>(b));
        if (spk::pack_display_channel(v) != reference(v)) ++mismatches;
    }
    chk(mismatches == 0, "strided sweep over all bit patterns matches");

    // 3) Specials, named.
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    chk(spk::pack_display_channel(nan) == 0, "NaN -> 0");
    chk(spk::pack_display_channel(-nan) == 0, "-NaN -> 0");
    chk(spk::pack_display_channel(inf) == 255, "+Inf -> 255");
    chk(spk::pack_display_channel(-inf) == 0, "-Inf -> 0");
    chk(spk::pack_display_channel(-0.0f) == 0, "-0 -> 0");
    chk(spk::pack_display_channel(0.5f) == 128, "0.5 -> 128");
    chk(spk::pack_display_channel(2.0f) == 255, "2.0 -> 255");

    // 4) Image layout: RGBA byte order, opaque alpha, stride padding untouched.
    const int w = 3, h = 2;
    const std::size_t stride = w * 4 + 5;  // padded rows, as AndroidBitmapInfo.stride may be
    std::vector<float> src = {
        0.0f, 0.5f, 1.0f,   0.1f, 0.2f, 0.3f,   nan, -1.0f, 7.0f,
        1.0f, 1.0f, 1.0f,   0.25f, 0.75f, 0.0f, 0.9f, 0.5f, 0.1f,
    };
    std::vector<uint8_t> dst(stride * h, 0xAB);
    spk::pack_rgbf_to_rgba8(src.data(), w, h, dst.data(), stride);
    bool layout_ok = true;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const uint8_t* p = &dst[y * stride + x * 4];
            const float* s = &src[(y * w + x) * 3];
            for (int c = 0; c < 3; ++c) layout_ok &= p[c] == reference(s[c]);
            layout_ok &= p[3] == 255;
        }
        for (std::size_t pad = w * 4; pad < stride; ++pad) layout_ok &= dst[y * stride + pad] == 0xAB;
    }
    chk(layout_ok, "RGBA order, alpha 255, row padding untouched");

    // 5) Degenerate inputs are no-ops (no crash, no write).
    std::vector<uint8_t> untouched(16, 0xCD);
    spk::pack_rgbf_to_rgba8(nullptr, 2, 2, untouched.data(), 8);
    spk::pack_rgbf_to_rgba8(src.data(), 0, 2, untouched.data(), 8);
    bool noop = true;
    for (uint8_t b : untouched) noop &= b == 0xCD;
    chk(noop, "null src / zero width write nothing");

    std::printf("%s\n", g_fail ? "FAIL" : "PASS");
    return g_fail;
}
