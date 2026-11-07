/*
 * Spektrafilm for Android — display pack: float RGB -> 8-bit RGBA. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * See kernels/pack_display.h for the byte-identity contract with the Kotlin
 * loop this replaces.
 */
#include "kernels/pack_display.h"

// The engine ships with -ffast-math, which lets clang contract
// `c * 255.0f + 0.5f` into one FMA, while the JVM rounds the product and the
// sum separately. An exhaustive sweep found that a fused multiply-add happens to
// give the same byte for every float32 in [0, 1], so contraction is harmless
// today; the pragma keeps this file matching the JVM by construction rather
// than by that measurement. tests/test_pack_display.cpp gates either way.
#if defined(__clang__)
#pragma clang fp contract(off)
#endif

namespace spk {

uint8_t pack_display_channel(float v) {
    // maxOf(0f, v): NaN fails the comparison and becomes 0, exactly as the
    // Kotlin chain ends at NaN.toInt() == 0.
    float c = v > 0.0f ? v : 0.0f;
    // min(1f, c)
    c = c < 1.0f ? c : 1.0f;
    const float scaled = c * 255.0f;
    const float biased = scaled + 0.5f;
    // c is in [0, 1], so biased is in [0.5, 255.5] and the truncation is in range.
    return static_cast<uint8_t>(static_cast<int>(biased));
}

void pack_rgbf_to_rgba8(const float* src, int width, int height,
                        uint8_t* dst, std::size_t dst_stride) {
    if (src == nullptr || dst == nullptr || width <= 0 || height <= 0) return;
    const std::size_t row_floats = static_cast<std::size_t>(width) * 3u;
    for (int y = 0; y < height; ++y) {
        const float* s = src + static_cast<std::size_t>(y) * row_floats;
        uint8_t* d = dst + static_cast<std::size_t>(y) * dst_stride;
        for (int x = 0; x < width; ++x) {
            d[0] = pack_display_channel(s[0]);
            d[1] = pack_display_channel(s[1]);
            d[2] = pack_display_channel(s[2]);
            d[3] = 255u;
            s += 3;
            d += 4;
        }
    }
}

}  // namespace spk
