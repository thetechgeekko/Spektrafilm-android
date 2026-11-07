/*
 * Spektrafilm for Android — display pack: float RGB -> 8-bit RGBA. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The last step of every preview frame and every 8-bit export: the engine's
 * display-referred float32 RGB (already CCTF-encoded, nominally 0..1) is
 * clamped, scaled, rounded and packed into the byte layout of an Android
 * ARGB_8888 Bitmap (R, G, B, A in memory, alpha opaque).
 *
 * This replaces the Kotlin `packToArgb` + `Bitmap.setPixels` loop (#289). The
 * per-channel arithmetic is the Kotlin expression, operation for operation:
 *
 *     (min(1f, maxOf(0f, v)) * 255f + 0.5f).toInt()
 *
 * so the bytes are identical, NaN included (Kotlin's maxOf propagates NaN,
 * NaN.toInt() is 0; here NaN fails `v > 0` and becomes 0 the same way). The
 * multiply and the add are two separately rounded float32 operations on the
 * JVM; kernels/pack_display.cpp disables FP contraction under clang so
 * -ffast-math cannot fuse them into an FMA. tests/test_pack_display.cpp gates
 * the result against a volatile-stepped JVM reference over every float32 in
 * [+0, 1] and a stride across all other bit patterns.
 */
#ifndef SPK_KERNELS_PACK_DISPLAY_H
#define SPK_KERNELS_PACK_DISPLAY_H

#include <cstddef>
#include <cstdint>

namespace spk {

// One channel: float -> 0..255, bit-identical to the Kotlin expression above.
uint8_t pack_display_channel(float v);

// Pack `width` x `height` interleaved RGB float32 pixels (row-major, tightly
// packed: row y starts at src + y*width*3) into RGBA8 rows of `dst_stride`
// bytes. Alpha is written as 255. dst_stride must be >= width*4.
void pack_rgbf_to_rgba8(const float* src, int width, int height,
                        uint8_t* dst, std::size_t dst_stride);

}  // namespace spk

#endif  // SPK_KERNELS_PACK_DISPLAY_H
