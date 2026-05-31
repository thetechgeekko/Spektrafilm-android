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
 * print_expose() film-density->print-raw), whose dominant per-band cost is
 * pow(10, -spectral). We replace it with a portable double-precision vector exp10
 * built from GCC/Clang vector extensions, which the compiler lowers to:
 *   - arm64 (the ship target): NEON (fmla / fmul / integer shift on float64x2),
 *   - x86_64 (host + CI):      SSE2/AVX,
 * so the SAME source is exercised by the x86 parity tests AND runs on NEON
 * on-device. (armv7 has no float64 NEON, so it scalarises there — still correct.)
 *
 * exp10(x) = 2^(x*log2(10)): range-reduce y=x*log2(10), k=round(y), r=y-k in
 * [-0.5,0.5], evaluate 2^r with a degree-8 minimax polynomial, then scale by 2^k
 * built directly from the IEEE-754 exponent bits.
 *
 * IMPORTANT (fast-math safety): the round-to-integer is done by TRUNCATION
 * (__builtin_convertvector, which -ffast-math cannot cancel) plus a branchless
 * nearest correction. The classic (y + 1.5*2^52) - 1.5*2^52 trick is NOT used
 * because -ffast-math algebraically simplifies it back to y, which silently breaks
 * the range reduction in the engine's Release build.
 *
 * Domain: the engine feeds x = -spectral with spectral a bounded optical density,
 * so x stays in roughly [-8, +4] and k+1023 is always a valid IEEE exponent. NaN
 * inputs (from NaN profile entries) propagate to NaN through the polynomial, which
 * the callers' isnan() guard then maps to 0 — matching the libm pow() path.
 */
#ifndef SPK_KERNELS_EXP10_H
#define SPK_KERNELS_EXP10_H

#include <cstdint>
#include <cstring>

namespace spk {

// Vector width for the double exp10 kernel. 2 maps to a single NEON float64x2
// register on arm64; the compiler still widens to AVX on x86.
constexpr int kExp10Lanes = 2;

using exp10_vd = double __attribute__((vector_size(sizeof(double) * kExp10Lanes)));
using exp10_vi = int64_t __attribute__((vector_size(sizeof(int64_t) * kExp10Lanes)));

// exp10(x) = 10^x, lane-wise, matching std::pow(10.0, x) to ~1 ULP (double) on the
// engine's operating range.
inline exp10_vd exp10_vec(exp10_vd x) {
    constexpr double kLog2_10 = 3.32192809488736234787;  // log2(10)

    exp10_vd y = x * kLog2_10;

    // k = round-to-nearest(y), fast-math-safe: truncate, then nudge by +/-1 when
    // the fractional part is past +/-0.5. Vector relationals yield int64 masks
    // (-1 where true), so subtracting the mask adds 1.
    exp10_vi ki = __builtin_convertvector(y, exp10_vi);         // trunc toward zero
    exp10_vd kf = __builtin_convertvector(ki, exp10_vd);
    exp10_vd d  = y - kf;                                       // in (-1, 1)
    exp10_vi up = (d >=  0.5);                                  // -1 where true
    exp10_vi dn = (d <= -0.5);
    ki = ki - up + dn;                                          // +1 / -1 correction
    exp10_vd k  = __builtin_convertvector(ki, exp10_vd);
    exp10_vd r  = y - k;                                        // r in [-0.5, 0.5]

    // 2^r minimax polynomial (degree 8), Horner form — <1 ULP on [-0.5, 0.5].
    constexpr double c0 = 1.0;
    constexpr double c1 = 6.931471805599453094e-1;
    constexpr double c2 = 2.402265069591007015e-1;
    constexpr double c3 = 5.550410866482157618e-2;
    constexpr double c4 = 9.618129107628477029e-3;
    constexpr double c5 = 1.333355814642844254e-3;
    constexpr double c6 = 1.540353039338161098e-4;
    constexpr double c7 = 1.525273379585854621e-5;
    constexpr double c8 = 1.321549960013650076e-6;

    exp10_vd p = c8 - (x - x);  // broadcast c8 to all lanes (x - x == 0 vector)
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
