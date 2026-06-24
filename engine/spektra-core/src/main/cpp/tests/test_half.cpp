/*
 * Spektrafilm for Android — host test for fp16 conversion (kernels/half). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Validates the scalar IEEE-754 binary16 round-trip (exact for representable values,
 * within half precision elsewhere, correct Inf/NaN/subnormal) and that the bulk
 * f32<->f16 path agrees with the scalar reference. The scalar path is what the host
 * runs; on arm the bulk path uses NEON `vcvt` and must produce identical bits.
 *
 * Build (host): g++ -std=c++17 -O2 -I <cpp_root> tests/test_half.cpp kernels/half.cpp -o /tmp/test_half
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "kernels/half.h"

namespace {
int g_fail = 0;
void chk(bool c, const char* m) { std::printf("  [%s] %s\n", c ? "ok" : "FAIL", m); if (!c) g_fail = 1; }

// Authoritative round-to-nearest-ties-to-even check, INDEPENDENT of how
// float_to_half computes its result: h must be the nearest binary16 to v, and on
// an exact tie the even-LSB candidate must win. Uses half_to_float (exact) on h
// and its two neighbours, so it cannot share a bug with float_to_half. This is
// what catches round-half-up (the old `mant & 0x1000` tie-break). Callers keep v
// in the finite normal+subnormal range so the neighbours stay finite.
bool is_rne(float v, uint16_t h) {
    const double dv = static_cast<double>(v);
    const double dh = std::fabs(static_cast<double>(spk::half_to_float(h)) - dv);
    for (int delta = -1; delta <= 1; delta += 2) {
        const uint16_t n = static_cast<uint16_t>(h + delta);
        const double dn = std::fabs(static_cast<double>(spk::half_to_float(n)) - dv);
        if (dn < dh) return false;                                      // a neighbour is strictly closer
        if (dn == dh && (n & 1u) == 0u && (h & 1u) == 1u) return false;  // tie not broken to even
    }
    return true;
}
}

int main() {
    using namespace spk;
    std::printf("fp16 (simd=%d):\n", half_is_simd() ? 1 : 0);

    // Exactly representable in binary16 -> lossless round-trip.
    for (float v : {0.0f, 1.0f, -1.0f, 0.5f, 2.0f, 0.25f, -0.75f, 100.0f}) {
        float r = half_to_float(float_to_half(v));
        chk(r == v, "exact value round-trips");
    }
    // General values within half precision (rel err <= 2^-10).
    double worst = 0.0;
    for (float v : {0.1f, 0.3333f, 0.987f, 12.34f, 0.001f, 3.14159f}) {
        float r = half_to_float(float_to_half(v));
        worst = std::fmax(worst, std::fabs((double)r - v) / std::fabs(v));
    }
    chk(worst <= 1.0 / 1024.0, "values within half precision");
    std::printf("    worst rel err = %.2e\n", worst);

    // Inf / NaN.
    chk(std::isinf(half_to_float(float_to_half(INFINITY))), "Inf round-trips");
    chk(std::isnan(half_to_float(float_to_half(NAN))), "NaN round-trips");

    // Bulk path agrees with scalar reference (covers NEON tail + lanes).
    std::vector<float> in(1000);
    for (size_t i = 0; i < in.size(); ++i) in[i] = (float)((i % 200) - 100) * 0.137f;
    std::vector<uint16_t> h(in.size());
    std::vector<float> back(in.size());
    f32_to_f16(in.data(), h.data(), in.size());
    f16_to_f32(h.data(), back.data(), in.size());
    bool match = true;
    for (size_t i = 0; i < in.size(); ++i) {
        if (h[i] != float_to_half(in[i])) { match = false; break; }
        if (back[i] != half_to_float(h[i])) { match = false; break; }
    }
    chk(match, "bulk f32<->f16 matches scalar reference");

    // Round-to-nearest-ties-to-even (the float_to_half fix). NEON vcvt_f16_f32 uses
    // RNE; the scalar host path must match it bit-for-bit, including on exact ties,
    // or the fp16 preview proxy diverges between the NEON bulk and scalar tail. The
    // values below land EXACTLY halfway between two binary16 values, where the old
    // round-half-up tie-break rounded the wrong way; 0x1pNf hex floats are exact.
    {
        bool rne_ok = true;
        const float ties[] = {
            1.0f + 0x1p-11f,    // halfway 1.0 <-> 1.0+2^-10 ; even (1.0) must win
            1.0f + 0x3p-11f,    // halfway 1.0+2^-10 <-> 1.0+2^-9 ; even (upper) must win
            2.0f + 0x1p-10f, 0.5f + 0x1p-12f, 4.0f + 0x1p-9f, 0.25f + 0x1p-13f,
            8.0f + 0x1p-8f, 100.0f + 0x1p-3f, 1023.5f, 2048.0f + 0x1p1f,
            -1.0f - 0x1p-11f, -2.0f - 0x1p-10f, -0.5f - 0x1p-12f, -1024.0f - 0x1p0f,
            0x1p-14f + 0x1p-25f,   // smallest normal + half a subnormal ulp (tie)
            0x1p-24f * 1.5f,       // halfway 0 <-> smallest subnormal ; even (0) must win
        };
        for (float v : ties)
            if (!is_rne(v, float_to_half(v))) { rne_ok = false; }
        chk(rne_ok, "float_to_half rounds exact ties to even");

        // Broad deterministic sweep over the finite range (normals + subnormals).
        bool sweep_ok = true;
        for (int i = 0; i < 300000 && sweep_ok; ++i) {
            uint32_t k = (static_cast<uint32_t>(i) * 2654435761u) & 0x1FFFFu;  // 0..131071
            float mantissa = static_cast<float>(static_cast<int>(k) - 65536) * (1.0f / 64.0f);
            float v = std::ldexp(mantissa, (i % 30) - 16);  // exponents spanning subnormal..large
            float a = std::fabs(v);
            if (a < 0x1p-25f || a > 60000.0f) continue;     // skip underflow-to-0 / overflow edges
            if (!is_rne(v, float_to_half(v))) sweep_ok = false;
        }
        chk(sweep_ok, "float_to_half is RNE across the finite range");
    }

    std::printf("%s\n", g_fail == 0 ? "ALL PASS" : "FAIL");
    return g_fail;
}
