/*
 * Spektrafilm for Android — fp16 (half-float) SIMD conversion implementation. GPLv3.
 * Film modeling powered by spektrafilm.
 */
#include "kernels/half.h"

#include <cstring>

// NEON FP16 convert: baseline on arm64; on armv7 it needs VFPv4 (__ARM_FP & 2).
#if defined(__ARM_NEON) && (defined(__aarch64__) || (defined(__ARM_FP) && (__ARM_FP & 2)))
#include <arm_neon.h>
#define SPK_HALF_NEON 1
#else
#define SPK_HALF_NEON 0
#endif

namespace spk {

uint16_t float_to_half(float f) {
    uint32_t x;
    std::memcpy(&x, &f, sizeof(x));
    const uint32_t sign = (x >> 16) & 0x8000u;
    const int32_t exp = static_cast<int32_t>((x >> 23) & 0xFF) - 127 + 15;
    const uint32_t mant = x & 0x7FFFFFu;
    if (((x >> 23) & 0xFF) == 0xFF) {            // Inf / NaN
        return static_cast<uint16_t>(sign | 0x7C00u | (mant ? 0x200u : 0u));
    }
    if (exp >= 0x1F) return static_cast<uint16_t>(sign | 0x7C00u);  // overflow -> Inf
    if (exp <= 0) {                               // subnormal half / underflow
        if (exp < -10) return static_cast<uint16_t>(sign);  // below half the smallest subnormal -> 0
        // Round-to-nearest-ties-to-even with a full sticky bit. The 24-bit
        // significand (implicit leading 1) is shifted right by `sh` to reach the
        // 10-bit subnormal field; the discarded low bits decide the rounding. The
        // previous `m & 0x1000` rounded half-up AND dropped the bits the `>> (1-exp)`
        // shift discarded (no sticky), so it diverged from NEON vcvt_f16_f32 (RNE).
        const uint32_t S = mant | 0x800000u;       // 24-bit significand (implicit 1)
        const int sh = (1 - exp) + 13;             // denormalize (1-exp) + drop low 13
        uint32_t result = S >> sh;
        const uint32_t discarded = S & ((1u << sh) - 1u);
        const uint32_t halfway = 1u << (sh - 1);
        if (discarded > halfway || (discarded == halfway && (result & 1u))) ++result;
        return static_cast<uint16_t>(sign | result);
    }
    uint16_t h = static_cast<uint16_t>(sign | (static_cast<uint32_t>(exp) << 10) | (mant >> 13));
    // Round to nearest, ties to even. The low 13 bits of `mant` are discarded; round
    // up when they exceed half an ULP, or are exactly half AND the kept LSB is odd
    // (so the result ends even). Matches NEON vcvt_f16_f32 (FPCR RNE); the previous
    // `mant & 0x1000` was round-half-up and diverged from NEON on exact ties.
    const uint32_t round_bits = mant & 0x1FFFu;
    if (round_bits > 0x1000u || (round_bits == 0x1000u && (h & 1u))) {
        h = static_cast<uint16_t>(h + 1);
    }
    return h;
}

float half_to_float(uint16_t h) {
    const uint32_t sign = (static_cast<uint32_t>(h) & 0x8000u) << 16;
    const uint32_t exp = (h >> 10) & 0x1F;
    const uint32_t mant = h & 0x3FFu;
    uint32_t x;
    if (exp == 0) {
        if (mant == 0) {
            x = sign;                              // +/- 0
        } else {                                   // subnormal -> normalize
            int e = -1;
            uint32_t m = mant;
            do { m <<= 1; ++e; } while ((m & 0x400u) == 0);
            m &= 0x3FFu;
            x = sign | (static_cast<uint32_t>(127 - 15 - e) << 23) | (m << 13);
        }
    } else if (exp == 0x1F) {                      // Inf / NaN
        x = sign | 0x7F800000u | (mant << 13);
    } else {
        x = sign | (static_cast<uint32_t>(exp - 15 + 127) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &x, sizeof(f));
    return f;
}

bool half_is_simd() { return SPK_HALF_NEON != 0; }

void f32_to_f16(const float* src, uint16_t* dst, size_t n) {
    size_t i = 0;
#if SPK_HALF_NEON
    for (; i + 4 <= n; i += 4) {
        float32x4_t v = vld1q_f32(src + i);
        float16x4_t h = vcvt_f16_f32(v);
        vst1_f16(reinterpret_cast<__fp16*>(dst + i), h);
    }
#endif
    for (; i < n; ++i) dst[i] = float_to_half(src[i]);
}

void f16_to_f32(const uint16_t* src, float* dst, size_t n) {
    size_t i = 0;
#if SPK_HALF_NEON
    for (; i + 4 <= n; i += 4) {
        float16x4_t h = vld1_f16(reinterpret_cast<const __fp16*>(src + i));
        float32x4_t v = vcvt_f32_f16(h);
        vst1q_f32(dst + i, v);
    }
#endif
    for (; i < n; ++i) dst[i] = half_to_float(src[i]);
}

}  // namespace spk
