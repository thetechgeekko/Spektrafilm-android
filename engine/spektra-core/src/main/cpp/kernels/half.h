/*
 * Spektrafilm for Android — fp16 (half-float) SIMD conversion. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * fp16 storage for the interactive proxy (docs/PERF_ROADMAP.md #3): Lightroom carries
 * fp16 through its pipeline; half-precision storage halves memory + bandwidth on the
 * per-pixel buffers. These convert between float32 and IEEE-754 binary16, vectorised
 * with NEON `vcvt` on arm (the FP16 convert instructions are baseline on arm64 and on
 * armv7 + VFPv4), with a portable scalar fallback elsewhere (and for the tail).
 *
 * PARITY: fp16 is lossy, so it is a PREVIEW-only storage option; the export + parity-
 * gated path stays float32. The scalar fallback is exact IEEE-754 round-to-nearest so
 * host tests validate the same values the device produces.
 */
#ifndef SPK_KERNELS_HALF_H
#define SPK_KERNELS_HALF_H

#include <cstddef>
#include <cstdint>

namespace spk {

// Scalar IEEE-754 float32 -> binary16 (round to nearest even), with Inf/NaN/denormal
// handling. Used as the portable path and for the vector tail.
uint16_t float_to_half(float f);
// Scalar binary16 -> float32 (exact).
float half_to_float(uint16_t h);

// Vectorised bulk conversion of `n` elements. NEON `vcvt` on arm, scalar elsewhere.
void f32_to_f16(const float* src, uint16_t* dst, size_t n);
void f16_to_f32(const uint16_t* src, float* dst, size_t n);

// True when this build converts via NEON FP16 instructions (vs the scalar fallback).
bool half_is_simd();

}  // namespace spk

#endif  // SPK_KERNELS_HALF_H
