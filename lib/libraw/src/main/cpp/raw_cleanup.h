// Spektrafilm for Android -- opt-in RAW clean-up: hot pixels and chroma noise (#259). GPLv3.
//
// Both steps are off by default and, when off, leave every decoded byte unchanged.
// Hot-pixel correction works on the Bayer mosaic before demosaic, where a stuck photosite
// is still one sample; after demosaic it would already be a coloured 3x3 blob. Chroma
// noise reduction works on the final linear ProPhoto float image, so its radius is a
// fraction of that image's long edge and a preview and its export get the same look.
#ifndef SPECTRAFILM_RAW_CLEANUP_H
#define SPECTRAFILM_RAW_CLEANUP_H

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace spectrafilm {
namespace cleanup {

inline bool cancelled(const std::atomic<bool>* flag) {
    return flag != nullptr && flag->load(std::memory_order_acquire);
}

// A photosite is hot when, above black, it is brighter than kHotFloor of the usable
// range AND more than kHotRatio times the brightest of its four same-colour neighbours
// (two photosites away in a 2x2 Bayer mosaic). Conservative on purpose: real point
// highlights are spread by the lens and the AA filter onto their same-colour neighbours,
// a stuck photosite is not. A hot photosite is replaced by the mean of the middle two of
// those four neighbours (their median), read from the uncorrected mosaic so that one
// correction can never feed the next.
constexpr float kHotFloor = 0.05f;
constexpr float kHotRatio = 4.0f;

// Returns the number of photosites corrected, or -1 if cancelled. `pitch` is in
// samples. `black` and `maximum` are code values; a frame without usable range is a no-op.
inline long correctHotPixelsBayer(std::uint16_t* mosaic, int width, int height,
                                  std::size_t pitch, unsigned black, unsigned maximum,
                                  const std::atomic<bool>* cancel = nullptr) {
    if (mosaic == nullptr || width < 5 || height < 5 || maximum <= black ||
        pitch < static_cast<std::size_t>(width)) {
        return 0;
    }
    const float range = static_cast<float>(maximum - black);
    const float floor = kHotFloor * range;
    const auto above = [black](std::uint16_t v) {
        return v > black ? static_cast<float>(v - black) : 0.0f;
    };
    std::vector<std::pair<std::size_t, std::uint16_t>> fixes;
    for (int y = 2; y < height - 2; ++y) {
        if (((y - 2) & 63) == 0 && cancelled(cancel)) return -1;
        const std::uint16_t* row = mosaic + static_cast<std::size_t>(y) * pitch;
        for (int x = 2; x < width - 2; ++x) {
            const float v = above(row[x]);
            if (v <= floor) continue;
            std::uint16_t n[4] = {
                row[x - 2], row[x + 2],
                row[static_cast<std::ptrdiff_t>(x) - static_cast<std::ptrdiff_t>(2 * pitch)],
                row[x + 2 * pitch],
            };
            const float brightest = above(std::max(std::max(n[0], n[1]),
                                                   std::max(n[2], n[3])));
            if (v <= kHotRatio * brightest) continue;
            std::sort(n, n + 4);
            const unsigned median = (static_cast<unsigned>(n[1]) + n[2] + 1U) / 2U;
            fixes.emplace_back(static_cast<std::size_t>(y) * pitch + x,
                               static_cast<std::uint16_t>(median));
        }
    }
    for (const auto& fix : fixes) mosaic[fix.first] = fix.second;
    return static_cast<long>(fixes.size());
}

// Three box passes approximate a Gaussian of standard deviation sigma (Wells 1986):
// each pass of radius r has variance r(r+1)/3.
inline int boxRadiusForSigma(float sigma) {
    const float r = std::sqrt(sigma * sigma + 1.0f) - 1.0f;  // 3 * r(r+1)/3 == sigma^2
    return std::max(0, static_cast<int>(std::lround(r)));
}

// One clamped-edge running-sum box pass over `count` samples of stride `stride`.
inline void boxPass(const float* in, float* out, int count, std::ptrdiff_t stride,
                    int radius) {
    if (radius <= 0 || count <= 1) {
        for (int i = 0; i < count; ++i) out[i * stride] = in[i * stride];
        return;
    }
    const float scale = 1.0f / static_cast<float>(2 * radius + 1);
    const auto at = [&](int i) { return in[std::clamp(i, 0, count - 1) * stride]; };
    double sum = 0.0;
    for (int i = -radius; i <= radius; ++i) sum += at(i);
    for (int i = 0; i < count; ++i) {
        out[i * stride] = static_cast<float>(sum) * scale;
        sum += at(i + radius + 1) - at(i - radius);
    }
}

// Sigma at strength 100, as a fraction of the image's long edge: 0.4% is ~24 px on a
// 6000 px export and ~4 px on a 1024 px preview.
constexpr float kChromaSigmaAtFull = 0.004f;
// The chroma planes are worked on at most this many pixels; larger frames are
// box-averaged down by an integer factor first and bilinearly sampled back. Chroma
// noise reduction is a low-pass, so this loses nothing it would have kept, and it
// bounds the scratch memory (six floats per work pixel: three chroma planes, the cell
// weights and two blur buffers) at 48 MiB for any export size.
constexpr std::size_t kChromaWorkPixels = 2U * 1024U * 1024U;

// Blurs only colour, never luminance: each pixel is split into ProPhoto luminance Y and
// the chroma differences (R-Y, G-Y, B-Y); the differences are low-passed and added back
// to the pixel's own, untouched Y. `strength` is 0..100; 0 is an exact no-op.
// Returns false if cancelled (the image may then be partly processed).
inline bool reduceChromaNoise(float* rgb, int width, int height, float strength,
                              const std::atomic<bool>* cancel = nullptr) {
    if (rgb == nullptr || width <= 0 || height <= 0 || !(strength > 0.0f)) return true;
    const float s = std::min(strength, 100.0f) / 100.0f;
    const float sigma =
        s * kChromaSigmaAtFull * static_cast<float>(std::max(width, height));
    if (sigma < 0.5f) return true;
    // ProPhoto RGB (D50) luminance row of RGB->XYZ.
    constexpr float kYr = 0.2880402f, kYg = 0.7118741f, kYb = 0.0000857f;
    const auto luma = [&](const float* px) {
        return kYr * px[0] + kYg * px[1] + kYb * px[2];
    };
    const std::size_t pixels = static_cast<std::size_t>(width) * height;
    int factor = 1;
    while (pixels / (static_cast<std::size_t>(factor) * factor) > kChromaWorkPixels) {
        ++factor;
    }
    const int lw = (width + factor - 1) / factor;
    const int lh = (height + factor - 1) / factor;
    const std::size_t lowPixels = static_cast<std::size_t>(lw) * lh;
    const int radius = boxRadiusForSigma(sigma / static_cast<float>(factor));

    // Low-resolution chroma differences, box-averaged over each factor x factor cell.
    std::vector<float> planes(lowPixels * 3U, 0.0f);
    std::vector<float> weights(lowPixels, 0.0f);
    for (int y = 0; y < height; ++y) {
        if ((y & 63) == 0 && cancelled(cancel)) return false;
        const std::size_t lowRow = static_cast<std::size_t>(y / factor) * lw;
        for (int x = 0; x < width; ++x) {
            const float* px = rgb + (static_cast<std::size_t>(y) * width + x) * 3U;
            const float yl = luma(px);
            const std::size_t cell = lowRow + x / factor;
            for (int c = 0; c < 3; ++c) planes[cell * 3U + c] += px[c] - yl;
            weights[cell] += 1.0f;
        }
    }
    std::vector<float> a(lowPixels), b(lowPixels);
    for (int c = 0; c < 3; ++c) {
        for (std::size_t p = 0; p < lowPixels; ++p) {
            a[p] = planes[p * 3U + c] / weights[p];
        }
        for (int pass = 0; pass < 3 && radius > 0; ++pass) {
            for (int row = 0; row < lh; ++row) {
                const std::size_t base = static_cast<std::size_t>(row) * lw;
                boxPass(a.data() + base, b.data() + base, lw, 1, radius);
            }
            for (int col = 0; col < lw; ++col) {
                boxPass(b.data() + col, a.data() + col, lh, lw, radius);
            }
            if (cancelled(cancel)) return false;
        }
        for (std::size_t p = 0; p < lowPixels; ++p) planes[p * 3U + c] = a[p];
    }

    // Each full-resolution pixel keeps its own Y and takes the smoothed chroma,
    // bilinearly sampled at its position in the low-resolution grid.
    const float inv = 1.0f / static_cast<float>(factor);
    for (int y = 0; y < height; ++y) {
        if ((y & 63) == 0 && cancelled(cancel)) return false;
        const float fy = std::clamp((static_cast<float>(y) + 0.5f) * inv - 0.5f, 0.0f,
                                    static_cast<float>(lh - 1));
        const int y0 = static_cast<int>(fy);
        const int y1 = std::min(y0 + 1, lh - 1);
        const float ty = fy - static_cast<float>(y0);
        for (int x = 0; x < width; ++x) {
            const float fx = std::clamp((static_cast<float>(x) + 0.5f) * inv - 0.5f,
                                        0.0f, static_cast<float>(lw - 1));
            const int x0 = static_cast<int>(fx);
            const int x1 = std::min(x0 + 1, lw - 1);
            const float tx = fx - static_cast<float>(x0);
            float* px = rgb + (static_cast<std::size_t>(y) * width + x) * 3U;
            const float yl = luma(px);
            for (int c = 0; c < 3; ++c) {
                const auto at = [&](int xx, int yy) {
                    return planes[(static_cast<std::size_t>(yy) * lw + xx) * 3U + c];
                };
                const float top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx;
                const float bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx;
                px[c] = yl + top + (bottom - top) * ty;
            }
        }
    }
    return true;
}

}  // namespace cleanup
}  // namespace spectrafilm

#endif
