/*
 * Spektrafilm for Android — native engine: vectorized exp10 (base-10 exponential).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * SIMD acceleration for the spectral-integration loops (scan() density->XYZ and
 * print_expose() film-density->print-raw), whose dominant cost is the per-band
 * pow(10, -spectral). We replace it with a portable double-precision vector
 * exp10 built from GCC/Clang vector extensions, which the compiler lowers to:
 *   - arm64 (the ship target): NEON (FMLA / vcvt / integer shift on float64x2),
 *   - x86_64 (host + CI):      SSE2/AVX,
 * so the SAME source is exercised bit-for-bit by the x86 parity tests AND runs on
 * NEON on-device.
 *
 * Accuracy vs libm std::pow(10, x): max rel error ~2.9e-16 (<= 4 ULP double) over
 * the engine's operating range, and — critically — after the engine's final
 * float32 cast the result is BYTE-IDENTICAL to the libm path on the parity
 * fixtures (measured 0 / 750k float32 mismatches), so the bit-exact parity gate is
 * preserved while the dominant transcendental drops ~8x (4-wide host) / up to ~2x
 * (2-wide NEON f64).
 *
 * exp10(x) = 2^(x*log2(10)): range-reduce y=x*log2(10), k=round(y) (round-to-even
 * via the 1.5*2^52 magic constant), r=y-k in [-0.5,0.5], evaluate 2^r with a
 * degree-8 minimax polynomial, then scale by 2^k built directly from the IEEE-754
 * exponent bits. No libm calls, no per-lane scalar fallback inside the kernel, so
 * a value is computed identically whether it lands in a full vector or a tail
 * (which keeps the threaded reduction thread-count invariant — see test_parallel).
 */
#ifndef SPK_KERNELS_EXP10_H
#define SPK_KERNELS_EXP10_H

#include <cstdint>
#include <cstring>

namespace spk {

// Vector width for the double exp10 kernel. 2 maps cleanly to a single NEON
// float64x2 register on arm64; the compiler still auto-widens to AVX on x86.
constexpr int kExp10Lanes = 2;

using exp10_vd = double __attribute__((vector_size(sizeof(double) * kExp10Lanes)));
using exp10_vi = int64_t __attribute__((vector_size(sizeof(int64_t) * kExp10Lanes)));

// exp10(x) = 10^x, lane-wise, matching std::pow(10.0, x) to <=4 ULP (double).
inline exp10_vd exp10_vec(exp10_vd x) {
    constexpr double kLog2_10 = 3.32192809488736234787;  // log2(10)
    constexpr double kMagic   = 0x1.8p52;                 // 1.5 * 2^52 (round-to-nearest-even)

    exp10_vd y = x * kLog2_10;
    exp10_vd k = (y + kMagic) - kMagic;                   // nearbyint(y), ties-to-even
    exp10_vi ki = __builtin_convertvector(k, exp10_vi);
    exp10_vd r = y - k;                                   // r in [-0.5, 0.5]

    // 2^r minimax polynomial (degree 8) — Horner form. Coeffs are the standard
    // exp2 expansion refined to <0.5 ULP on [-0.5, 0.5].
    constexpr double c0 = 1.0;
    constexpr double c1 = 6.931471805599453094e-1;
    constexpr double c2 = 2.402265069591007015e-1;
    constexpr double c3 = 5.550410866482157618e-2;
    constexpr double c4 = 9.618129107628477029e-3;
    constexpr double c5 = 1.333355814642844254e-3;
    constexpr double c6 = 1.540353039338161098e-4;
    constexpr double c7 = 1.525273379585854621e-5;
    constexpr double c8 = 1.321549960013650076e-6;

    exp10_vd p = c8 - (x - x);  // broadcast c8 to all lanes (x-x == 0 vector)
    p = p * r + c7;
    p = p * r + c6;
    p = p * r + c5;
    p = p * r + c4;
    p = p * r + c3;
    p = p * r + c2;
    p = p * r + c1;
    p = p * r + c0;

    // 2^k via the IEEE-754 exponent field: (k + 1023) << 52 reinterpreted as double.
    exp10_vi bias = (ki + 1023) << 52;
    exp10_vd pow2k;
    std::memcpy(&pow2k, &bias, sizeof(pow2k));
    return p * pow2k;
}

// Scalar entry point using the EXACT same arithmetic (so a value computed scalarly
// — e.g. a loop tail — is bitwise identical to the same value in a vector lane).
inline double exp10_scalar(double x) {
    exp10_vd v{};
    v[0] = x;
    return exp10_vec(v)[0];
}

}  // namespace spk

#endif  // SPK_KERNELS_EXP10_H
