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

// Three box passes approximate a Gaussian of standard deviation sigma (Wells 1986).
// A pass of integer radius r has variance r(r+1)/3. To reach any sigma, including the
// sub-pixel ones a preview asks for at low strength, a pass blends the box of radius r0
// with the box of radius r0+1 by weight t; its variance is then exactly the blend of
// the two. Returns r0 + t, the "extended radius" that boxPassRow and boxPassColumns take.
inline float boxRadiusForSigma(float sigma) {
    if (!(sigma > 0.0f)) return 0.0f;
    const float perPass = sigma * sigma;  // 3 passes of variance sigma^2/3; times 3 below
    int r0 = 0;
    while (static_cast<float>((r0 + 1) * (r0 + 2)) <= perPass) ++r0;
    const float t = (perPass - static_cast<float>(r0 * (r0 + 1))) /
                    static_cast<float>(2 * (r0 + 1));
    return static_cast<float>(r0) + std::clamp(t, 0.0f, 1.0f);
}

// One clamped-edge running-sum pass over a contiguous row: the box of radius
// floor(radius) blended with the next one up by the fractional part. The row is copied
// into `padded` with its end samples repeated, so the loop needs no per-sample clamp.
inline void boxPassRow(const float* in, float* out, int count, float radius,
                       std::vector<float>& padded) {
    if (!(radius > 0.0f) || count <= 1) {
        std::copy(in, in + count, out);
        return;
    }
    const int r0 = static_cast<int>(radius);
    const int pad = r0 + 1;
    padded.resize(static_cast<std::size_t>(count + 2 * pad));
    std::fill(padded.begin(), padded.begin() + pad, in[0]);
    std::copy(in, in + count, padded.begin() + pad);
    std::fill(padded.begin() + pad + count, padded.end(), in[count - 1]);
    const float* p = padded.data() + pad;  // p[i] == clamped in[i] for -pad <= i < count+pad
    const double t = static_cast<double>(radius) - r0;
    const double inner = (1.0 - t) / (2 * r0 + 1);
    const double outer = t / (2 * r0 + 3);
    double sum = 0.0;
    for (int i = -r0; i <= r0; ++i) sum += p[i];
    for (int i = 0; i < count; ++i) {
        out[i] = static_cast<float>(sum * inner + (sum + p[i - r0 - 1] + p[i + r0 + 1]) * outer);
        sum += p[i + r0 + 1] - p[i - r0];
    }
}

// The same pass down the columns of a row-major plane, all columns at once: each step
// touches whole contiguous rows, which is several times faster than walking one
// column at a time with a row stride. `sums` is caller scratch.
inline void boxPassColumns(const float* in, float* out, int width, int rows, float radius,
                           std::vector<double>& sums) {
    const std::size_t w = static_cast<std::size_t>(width);
    if (!(radius > 0.0f) || rows <= 1) {
        std::copy(in, in + w * static_cast<std::size_t>(rows), out);
        return;
    }
    const int r0 = static_cast<int>(radius);
    const double t = static_cast<double>(radius) - r0;
    const double inner = (1.0 - t) / (2 * r0 + 1);
    const double outer = t / (2 * r0 + 3);
    const auto row = [&](int i) { return in + static_cast<std::size_t>(std::clamp(i, 0, rows - 1)) * w; };
    sums.assign(w, 0.0);
    for (int i = -r0; i <= r0; ++i) {
        const float* src = row(i);
        for (std::size_t x = 0; x < w; ++x) sums[x] += src[x];
    }
    for (int i = 0; i < rows; ++i) {
        const float* before = row(i - r0 - 1);
        const float* after = row(i + r0 + 1);
        const float* leaving = row(i - r0);
        float* dst = out + static_cast<std::size_t>(i) * w;
        for (std::size_t x = 0; x < w; ++x) {
            const double sum = sums[x];
            dst[x] = static_cast<float>(sum * inner + (sum + before[x] + after[x]) * outer);
            sums[x] = sum + after[x] - leaving[x];
        }
    }
}

// Sigma at strength 100, as a fraction of the image's long edge: 0.4% is ~24 px on a
// 6000 px export and ~4 px on a 1024 px preview.
constexpr float kChromaSigmaAtFull = 0.004f;
// Scratch budget in floats. The image is processed in horizontal bands, each carrying
// enough halo rows above and below that its interior is exactly what a whole-image pass
// would produce; this bounds the extra memory near 32 MiB (plus a stash of halo rows)
// for any export size, at full resolution, so no work-size step adds blur of its own.
constexpr std::size_t kChromaBandFloats = 8U * 1024U * 1024U;

// Blurs only colour, never luminance: each pixel is split into ProPhoto luminance Y and
// the chroma differences (R-Y, G-Y, B-Y); the differences are low-passed and added back
// to the pixel's own, untouched Y. `strength` is 0..100; 0 is an exact no-op.
// Returns false if cancelled (the image may then be partly processed).
// `bandFloats` exists for tests; production callers keep the default.
inline bool reduceChromaNoise(float* rgb, int width, int height, float strength,
                              const std::atomic<bool>* cancel = nullptr,
                              std::size_t bandFloats = kChromaBandFloats) {
    if (rgb == nullptr || width <= 0 || height <= 0 || !(strength > 0.0f)) return true;
    const float s = std::min(strength, 100.0f) / 100.0f;
    const float sigma =
        s * kChromaSigmaAtFull * static_cast<float>(std::max(width, height));
    if (sigma < 0.05f) return true;  // under a twentieth of a pixel: nothing visible
    // ProPhoto RGB (D50) luminance row of RGB->XYZ.
    constexpr float kYr = 0.2880402f, kYg = 0.7118741f, kYb = 0.0000857f;
    const auto luma = [](const float* px) {
        return kYr * px[0] + kYg * px[1] + kYb * px[2];
    };
    const float radius = boxRadiusForSigma(sigma);
    // Three vertical passes each reach floor(radius)+1 rows: beyond that a band edge
    // cannot influence the band's interior.
    const int halo = 3 * (static_cast<int>(radius) + 1);
    const std::size_t w = static_cast<std::size_t>(width);
    // Two planes of one channel per band (the chroma plane and its blur scratch) plus
    // the three channel planes: five floats per band pixel.
    const std::size_t budgetRows = std::max<std::size_t>(1, bandFloats / (5U * w));
    // At least `halo` interior rows, so a band's halo above never reaches further back
    // than the previous band, whose rows are the only ones still held back.
    const int interior = std::max({16, halo, static_cast<int>(
        std::min<std::size_t>(budgetRows, static_cast<std::size_t>(height) + 2U * halo)) -
        2 * halo});

    // A band's halo above is the previous band's last `halo` interior rows. Those must
    // still hold the ORIGINAL pixels when the next band reads them, so the previous band
    // keeps its smoothed values for them in `stash` and writes them only after the next
    // band has taken its copy.
    std::vector<float> planes, a, b, stash;
    std::vector<double> sums;
    std::vector<float> padded;
    int stashY0 = 0, stashRows = 0;
    const auto flushStash = [&]() {
        for (int r = 0; r < stashRows; ++r) {
            float* row = rgb + static_cast<std::size_t>(stashY0 + r) * w * 3U;
            const float* smooth = stash.data() + static_cast<std::size_t>(r) * w * 3U;
            for (std::size_t x = 0; x < w; ++x) {
                const float yl = luma(row + x * 3U);
                for (int c = 0; c < 3; ++c) row[x * 3U + c] = yl + smooth[x * 3U + c];
            }
        }
        stashRows = 0;
    };
    for (int top = 0; top < height; top += interior) {
        if (cancelled(cancel)) return false;
        const int bottom = std::min(height, top + interior);  // exclusive
        const int y0 = std::max(0, top - halo);
        const int y1 = std::min(height, bottom + halo);  // exclusive
        const int rows = y1 - y0;
        const std::size_t bandPixels = static_cast<std::size_t>(rows) * w;
        planes.assign(bandPixels * 3U, 0.0f);
        a.resize(bandPixels);
        b.resize(bandPixels);
        for (int y = y0; y < y1; ++y) {
            const float* row = rgb + static_cast<std::size_t>(y) * w * 3U;
            float* out = planes.data() + static_cast<std::size_t>(y - y0) * w * 3U;
            for (std::size_t x = 0; x < w; ++x) {
                const float yl = luma(row + x * 3U);
                for (int c = 0; c < 3; ++c) out[x * 3U + c] = row[x * 3U + c] - yl;
            }
        }
        flushStash();  // this band has its copy of the previous band's rows now
        for (int c = 0; c < 3; ++c) {
            for (std::size_t p = 0; p < bandPixels; ++p) a[p] = planes[p * 3U + c];
            for (int pass = 0; pass < 3; ++pass) {
                for (int r = 0; r < rows; ++r) {
                    const std::size_t base = static_cast<std::size_t>(r) * w;
                    boxPassRow(a.data() + base, b.data() + base, width, radius, padded);
                }
                boxPassColumns(b.data(), a.data(), width, rows, radius, sums);
                if (cancelled(cancel)) return false;
            }
            for (std::size_t p = 0; p < bandPixels; ++p) planes[p * 3U + c] = a[p];
        }
        // Write back the interior rows, keeping each pixel's own Y; the last `halo` of
        // them wait in the stash while another band follows.
        const int direct = bottom < height ? std::max(top, bottom - halo) : bottom;
        stashY0 = direct;
        stashRows = bottom - direct;
        stash.resize(static_cast<std::size_t>(stashRows) * w * 3U);
        std::copy(planes.begin() + static_cast<std::ptrdiff_t>(
                      static_cast<std::size_t>(direct - y0) * w * 3U),
                  planes.begin() + static_cast<std::ptrdiff_t>(
                      static_cast<std::size_t>(bottom - y0) * w * 3U),
                  stash.begin());
        for (int y = top; y < direct; ++y) {
            float* row = rgb + static_cast<std::size_t>(y) * w * 3U;
            const float* smooth = planes.data() + static_cast<std::size_t>(y - y0) * w * 3U;
            for (std::size_t x = 0; x < w; ++x) {
                const float yl = luma(row + x * 3U);
                for (int c = 0; c < 3; ++c) row[x * 3U + c] = yl + smooth[x * 3U + c];
            }
        }
    }
    flushStash();
    return true;
}

}  // namespace cleanup
}  // namespace spectrafilm

#endif
