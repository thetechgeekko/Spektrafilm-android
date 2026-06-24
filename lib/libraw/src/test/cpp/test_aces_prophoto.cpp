/*
 * Host unit test for the lib:libraw ACES2065-1 -> linear ProPhoto RGB conversion
 * (raw_decoder.cpp :: spectrafilm::aces2065ToProPhotoRGB).
 *
 * The decode path produces the engine's input space (linear ProPhoto RGB) by
 * converting LibRaw's ACES2065-1 output with a baked CAT02 matrix, mirroring
 * raw_file_processor.py's final
 *   colour.RGB_to_RGB(rgb, ACES2065-1, "ProPhoto RGB",
 *                     apply_cctf_decoding=False, apply_cctf_encoding=False)
 * step. The libraw module is not part of the host parity suite (it needs LibRaw to
 * build the real decode path), but this conversion is plain matrix math that
 * compiles WITHOUT LibRaw (SFRAW_HAVE_LIBRAW == 0), so we verify it on the host
 * against reference vectors captured from colour-science 0.4.7 — the oracle's
 * pinned colour version.
 *
 * Build (from this directory):
 *   g++ -std=c++17 -I../../main/cpp \
 *       -include ../../main/cpp/raw_decoder.cpp \
 *       test_aces_prophoto.cpp -o /tmp/test_aces_prophoto
 *   /tmp/test_aces_prophoto
 *
 * raw_decoder.cpp is -include'd ahead of this TU, so spectrafilm::aces2065ToProPhotoRGB
 * (declared in raw_decoder.h) is already defined and linked into this binary.
 */
#include <cmath>
#include <cstdio>
#include <vector>

#include "raw_decoder.h"

namespace {

// {in_r,in_g,in_b,  expect_r,expect_g,expect_b}, captured from colour-science 0.4.7:
//   colour.RGB_to_RGB(in, RGB_COLOURSPACES["ACES2065-1"], RGB_COLOURSPACES["ProPhoto RGB"],
//                     apply_cctf_decoding=False, apply_cctf_encoding=False)  # default CAT02
// Includes neutral (white maps to ~1 but NOT exactly 1 — that is colour's CAT02),
// black (exact zero), primary-ish saturated colours, an over-1 (HDR) triple, and a
// wide-gamut colour that lands slightly OUT of the ProPhoto gamut (negative red) —
// the conversion must NOT clamp it.
struct Case { float in[3]; float expect[3]; };
const Case kCases[] = {
    {{1.00000000f,1.00000000f,1.00000000f}, {1.00017914f,0.99995922f,1.00027431f}},
    {{0.18000000f,0.18000000f,0.18000000f}, {0.18003224f,0.17999266f,0.18004938f}},
    {{0.00000000f,0.00000000f,0.00000000f}, {0.00000000f,0.00000000f,0.00000000f}},
    {{0.50000000f,0.10000000f,0.10000000f}, {0.59577005f,0.10144047f,0.09920356f}},
    {{0.10000000f,0.50000000f,0.10000000f}, {0.03443078f,0.53584138f,0.09912680f}},
    {{0.10000000f,0.10000000f,0.50000000f}, {0.06992456f,0.06268961f,0.50186166f}},
    {{0.90000000f,0.70000000f,0.20000000f}, {0.98561815f,0.74732662f,0.19748729f}},
    {{0.05000000f,0.20000000f,0.60000000f}, {-0.01596458f,0.16214382f,0.60219804f}},
    {{2.00000000f,2.00000000f,2.00000000f}, {2.00035827f,1.99991844f,2.00054862f}},
    {{0.33340000f,0.12780000f,0.90010000f}, {0.32453675f,0.05650811f,0.90325303f}},
};
constexpr int kNumCases = sizeof(kCases) / sizeof(kCases[0]);

// Project parity max_abs tolerance is 1e-4; the float32 round-trip of a float64
// matrix multiply lands ~1e-7, so hold a far tighter bound to actually catch a
// wrong matrix / transposed row / clamped output.
constexpr float kTol = 1e-5f;

}  // namespace

int main() {
    // Pack inputs into one interleaved buffer and convert in place (the real call shape).
    std::vector<float> buf(static_cast<size_t>(kNumCases) * 3);
    for (int i = 0; i < kNumCases; ++i) {
        buf[i * 3 + 0] = kCases[i].in[0];
        buf[i * 3 + 1] = kCases[i].in[1];
        buf[i * 3 + 2] = kCases[i].in[2];
    }
    spectrafilm::aces2065ToProPhotoRGB(buf.data(), static_cast<size_t>(kNumCases));

    float maxAbs = 0.0f;
    int fails = 0;
    for (int i = 0; i < kNumCases; ++i) {
        for (int c = 0; c < 3; ++c) {
            const float got = buf[i * 3 + c];
            const float want = kCases[i].expect[c];
            const float d = std::fabs(got - want);
            if (d > maxAbs) maxAbs = d;
            if (d > kTol) {
                std::printf("FAIL case %d ch %d: got %.8f want %.8f (|d|=%.2e > %.1e)\n",
                            i, c, got, want, d, kTol);
                ++fails;
            }
        }
    }

    if (fails == 0) {
        std::printf("ALL PASS: %d cases, ACES2065-1 -> ProPhoto RGB max_abs=%.3e (tol %.1e)\n",
                    kNumCases, maxAbs, kTol);
        return 0;
    }
    std::printf("FAIL: %d/%d channels out of tolerance (max_abs=%.3e)\n",
                fails, kNumCases * 3, maxAbs);
    return 1;
}
