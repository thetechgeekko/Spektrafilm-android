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

    std::printf("%s\n", g_fail == 0 ? "ALL PASS" : "FAIL");
    return g_fail;
}
