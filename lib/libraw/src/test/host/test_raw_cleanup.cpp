// Host contract for the opt-in RAW clean-up steps in raw_cleanup.h (#259). GPLv3.
#include "raw_cleanup.h"

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL %s\n", what);
        ++failures;
    } else {
        std::printf("ok   %s\n", what);
    }
}

constexpr float kYr = 0.2880402f, kYg = 0.7118741f, kYb = 0.0000857f;

float luma(const float* px) { return kYr * px[0] + kYg * px[1] + kYb * px[2]; }

void hotPixels() {
    using spectrafilm::cleanup::correctHotPixelsBayer;
    constexpr int w = 32, h = 24;
    constexpr std::size_t pitch = 40;  // padded rows, like LibRaw's raw_pitch
    const unsigned black = 512, maximum = 16383;
    std::vector<std::uint16_t> flat(pitch * h, 0xBEEF);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            flat[y * pitch + x] = static_cast<std::uint16_t>(black + 1000 + ((x & 1) + (y & 1)) * 200);
        }
    }

    auto mosaic = flat;
    check(correctHotPixelsBayer(mosaic.data(), w, h, pitch, black, maximum) == 0 &&
              mosaic == flat,
          "flat Bayer mosaic is unchanged");

    mosaic[11 * pitch + 13] = 16000;
    const long fixed = correctHotPixelsBayer(mosaic.data(), w, h, pitch, black, maximum);
    check(fixed == 1, "one stuck photosite is corrected");
    check(mosaic == flat, "stuck photosite takes its same-colour neighbours' median");

    // A genuine highlight spread over its same-colour neighbours is kept.
    mosaic = flat;
    for (int dy = -2; dy <= 2; dy += 2) {
        for (int dx = -2; dx <= 2; dx += 2) mosaic[(11 + dy) * pitch + 13 + dx] = 9000;
    }
    mosaic[11 * pitch + 13] = 16000;
    auto spread = mosaic;
    check(correctHotPixelsBayer(mosaic.data(), w, h, pitch, black, maximum) == 0 &&
              mosaic == spread,
          "spread highlight is not treated as a hot pixel");

    // Below the floor nothing changes, however large the ratio.
    mosaic = flat;
    for (auto& v : mosaic) v = static_cast<std::uint16_t>(black);
    mosaic[11 * pitch + 13] = static_cast<std::uint16_t>(black + 300);
    spread = mosaic;
    check(correctHotPixelsBayer(mosaic.data(), w, h, pitch, black, maximum) == 0 &&
              mosaic == spread,
          "dim isolated sample below the floor is kept");

    // Padding past the row width is never read or written.
    mosaic = flat;
    mosaic[11 * pitch + 13] = 16000;
    correctHotPixelsBayer(mosaic.data(), w, h, pitch, black, maximum);
    bool paddingIntact = true;
    for (int y = 0; y < h; ++y) {
        for (std::size_t x = w; x < pitch; ++x) paddingIntact &= mosaic[y * pitch + x] == 0xBEEF;
    }
    check(paddingIntact, "row padding is untouched");

    std::atomic<bool> cancel{true};
    mosaic = flat;
    check(correctHotPixelsBayer(mosaic.data(), w, h, pitch, black, maximum, &cancel) == -1,
          "cancelled hot-pixel pass reports -1");
    check(correctHotPixelsBayer(mosaic.data(), w, h, pitch, maximum, black) == 0,
          "frame without usable range is a no-op");
}

// Flat grey-ish colour plus independent per-channel noise, so all chroma variance is noise.
std::vector<float> noisyImage(int w, int h) {
    std::mt19937 rng(259);
    std::normal_distribution<float> noise(0.0f, 0.04f);
    std::vector<float> rgb(static_cast<std::size_t>(w) * h * 3U);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float* px = &rgb[(static_cast<std::size_t>(y) * w + x) * 3U];
            const float base = 0.4f;
            px[0] = base * 0.9f + noise(rng);
            px[1] = base + noise(rng);
            px[2] = base * 1.1f + noise(rng);
        }
    }
    return rgb;
}

double chromaVariance(const std::vector<float>& rgb) {
    double sum = 0.0, sq = 0.0;
    const std::size_t n = rgb.size() / 3U;
    for (std::size_t p = 0; p < n; ++p) {
        const float d = rgb[p * 3U] - rgb[p * 3U + 2U];
        sum += d;
        sq += static_cast<double>(d) * d;
    }
    const double mean = sum / static_cast<double>(n);
    return sq / static_cast<double>(n) - mean * mean;
}

void chroma(int w, int h, const char* label) {
    using spectrafilm::cleanup::reduceChromaNoise;
    const auto original = noisyImage(w, h);

    auto image = original;
    check(reduceChromaNoise(image.data(), w, h, 0.0f) && image == original,
          "strength 0 is an exact no-op");
    check(reduceChromaNoise(image.data(), w, h, -5.0f) && image == original,
          "negative strength is a no-op");
    check(reduceChromaNoise(image.data(), w, h, std::nanf("")) && image == original,
          "NaN strength is a no-op");

    image = original;
    check(reduceChromaNoise(image.data(), w, h, 100.0f), label);
    float worstLuma = 0.0f;
    bool finite = true;
    for (std::size_t p = 0; p < image.size() / 3U; ++p) {
        worstLuma = std::max(worstLuma, std::fabs(luma(&image[p * 3U]) - luma(&original[p * 3U])));
        for (int c = 0; c < 3; ++c) finite &= std::isfinite(image[p * 3U + c]);
    }
    std::printf("     luma max_abs %.3g, chroma variance %.4g -> %.4g\n", worstLuma,
                chromaVariance(original), chromaVariance(image));
    check(finite, "chroma NR output is finite");
    check(worstLuma < 1e-5f, "chroma NR preserves each pixel's luminance");
    check(chromaVariance(image) < 0.25 * chromaVariance(original),
          "chroma NR cuts chroma noise variance by more than 4x");

    std::atomic<bool> cancel{true};
    image = original;
    check(!reduceChromaNoise(image.data(), w, h, 100.0f, &cancel),
          "cancelled chroma NR reports false");
}

}  // namespace

void extendedBox() {
    using spectrafilm::cleanup::boxPassColumns;
    using spectrafilm::cleanup::boxPassRow;
    using spectrafilm::cleanup::boxRadiusForSigma;
    // Three extended-box passes reach the requested sigma exactly, sub-pixel included.
    bool exact = true;
    for (float sigma : {0.1f, 0.3f, 0.8f, 1.0f, 1.7f, 4.2f, 9.5f}) {
        const float r = boxRadiusForSigma(sigma);
        constexpr int n = 401;
        std::vector<float> x(n, 0.0f), y(n), padded;
        x[n / 2] = 1.0f;
        std::vector<float> column = x, scratch(n);
        std::vector<double> sums;
        for (int pass = 0; pass < 3; ++pass) {
            boxPassRow(x.data(), y.data(), n, r, padded);
            x.swap(y);
            boxPassColumns(column.data(), scratch.data(), 1, n, r, sums);
            column.swap(scratch);
        }
        for (int i = 0; i < n; ++i) exact &= std::fabs(x[i] - column[i]) < 1e-7f;
        double mass = 0.0, var = 0.0;
        for (int i = 0; i < n; ++i) {
            mass += x[i];
            var += x[i] * static_cast<double>(i - n / 2) * (i - n / 2);
        }
        exact &= std::fabs(mass - 1.0) < 1e-5 && std::fabs(std::sqrt(var) - sigma) < 1e-3;
    }
    check(exact, "row and column extended-box passes agree, reach sigma and keep mass");
}

void banding() {
    using spectrafilm::cleanup::reduceChromaNoise;
    // Banded processing must equal one whole-image pass: the halo carries every row
    // that can influence a band's interior, and later bands read original pixels.
    constexpr int w = 300, h = 517;
    const auto original = noisyImage(w, h);
    bool same = true;
    for (float strength : {5.0f, 40.0f, 100.0f}) {
        auto whole = original;
        auto banded = original;
        reduceChromaNoise(whole.data(), w, h, strength, nullptr, std::size_t{1} << 30);
        reduceChromaNoise(banded.data(), w, h, strength, nullptr, 5U * w * 40U);
        for (std::size_t i = 0; i < whole.size(); ++i) {
            same &= std::fabs(whole[i] - banded[i]) < 2e-6f;
        }
    }
    check(same, "banded chroma NR matches a whole-image pass");
}

void scaleConsistency() {
    using spectrafilm::cleanup::reduceChromaNoise;
    // A low strength is a light touch at any size: no work-size step adds blur.
    constexpr int w = 2000, h = 1300;
    const auto original = noisyImage(w, h);
    auto image = original;
    reduceChromaNoise(image.data(), w, h, 1.0f);
    const double kept = chromaVariance(image) / chromaVariance(original);
    std::printf("     strength 1 on %dx%d keeps %.3f of chroma variance\n", w, h, kept);
    check(kept > 0.9, "strength 1 on a large frame keeps over 90% of chroma variance");
}

int main() {
    extendedBox();
    banding();
    scaleConsistency();
    hotPixels();
    chroma(640, 427, "chroma NR on a preview-sized frame");
    chroma(2000, 1300, "chroma NR on a frame larger than one band");
    if (failures != 0) {
        std::printf("%d failure(s)\n", failures);
        return EXIT_FAILURE;
    }
    std::printf("all raw clean-up checks passed\n");
    return EXIT_SUCCESS;
}
