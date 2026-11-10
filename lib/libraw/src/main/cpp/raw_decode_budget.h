// Spektrafilm for Android — device-aware RAW decode admission. GPLv3.
#ifndef SPECTRAFILM_RAW_DECODE_BUDGET_H
#define SPECTRAFILM_RAW_DECODE_BUDGET_H

#include <cstdint>
#include <cmath>
#include <limits>

namespace spectrafilm {

// Peak decode memory includes LibRaw's unpacked image, processed bitmap,
// make_mem_image() copy, and the returned float RGB buffer. The raw-store
// limit alone cannot bound those allocations. Keep a conservative ceiling on
// small devices and use total/available RAM as a heuristic for larger decodes.
// This snapshot is not a memory reservation or a guarantee of export success.
constexpr std::uint64_t decodePixelLimitForMemory(
    bool is64Bit, std::uint64_t totalBytes,
    std::uint64_t availableBytes) noexcept {
    constexpr std::uint64_t kMiB = 1024ULL * 1024ULL;
    constexpr std::uint64_t kGiB = 1024ULL * kMiB;
    if (!is64Bit) return 8ULL * kMiB;
    // Missing or inconsistent telemetry must not raise the fixed baseline.
    if (totalBytes == 0 || availableBytes > totalBytes) return 12ULL * kMiB;
    // 50 MP phone sensors (Samsung's LinearRaw DNGs are 8160 x 6120, 47.6
    // MiPixels) need more than 32. A host decode of that file peaks at 1.9 GiB
    // RSS (16-bit store, LibRaw image, make_mem_image copy, float RGB). The
    // SM-S948W (11.35 GB) showed 2.93 GB MemAvailable with the editor open at
    // export time, so a 4 GiB gate refused the very phone that shoots these
    // files. 2.5 GiB covers the measured peak plus margin, and a 10 GiB phone
    // can also reclaim cached apps; everyone else keeps the lower tiers.
    // This tier was withdrawn once (#586): the whole-frame engine render after
    // the decode drove the phone into lmkd. It returns now that spk_simulate
    // renders frames above 16 MiPixels in bands (#588, ~2 GB peak at 50 MP on
    // the host including input and output frames, against 4.3 GB before).
    if (totalBytes >= 10ULL * kGiB &&
        availableBytes >= 5ULL * kGiB / 2ULL) {
        return 64ULL * kMiB;
    }
    if (totalBytes >= 8ULL * kGiB &&
        availableBytes >= 3ULL * kGiB) {
        return 32ULL * kMiB;
    }
    if (totalBytes >= 6ULL * kGiB &&
        availableBytes >= 2ULL * kGiB) {
        return 24ULL * kMiB;
    }
    return 12ULL * kMiB;
}

// Peak bytes per decoded pixel: the largest measured on the host (RSS over the
// decode, input snapshot included) plus ~12% margin. With the RAW store and
// LibRaw's image released before the float output: Lightroom LinearRaw
// panoramas peak at 1.00 GB for 41.2 MP and 1.11 GB for 48.1 MP (~24 B/px), a
// 25.5 MP Bayer DNG at 0.48 GB.
constexpr std::uint64_t kDecodePeakBytesPerPixel = 28ULL;

// The pixel limit for bytes the caller admitted (DecodeOptions::admittedBytes):
// never below the smallest 64-bit tier, never above 128 MiPixels.
constexpr std::uint64_t admittedDecodePixelLimit(std::uint64_t admittedBytes) noexcept {
    constexpr std::uint64_t kMi = 1024ULL * 1024ULL;
    const std::uint64_t pixels = admittedBytes / kDecodePeakBytesPerPixel;
    if (pixels < 12ULL * kMi) return 12ULL * kMi;
    if (pixels > 128ULL * kMi) return 128ULL * kMi;
    return pixels;
}
static_assert(admittedDecodePixelLimit(0) == 12ULL * 1024ULL * 1024ULL, "floor");
static_assert(admittedDecodePixelLimit(1400ULL * 1024ULL * 1024ULL) == 50ULL * 1024ULL * 1024ULL,
              "1400 MiB admits 50 MiPixels");

// A half-size Bayer image still needs its full single-plane unpacked store:
// at most 4x the output ceiling, at two bytes/sample, plus 64 MiB for loader
// metadata and aligned margins. 32 MiPixels -> 320 MiB (128 MiPixel source),
// 24 -> 256 MiB (96), 12 -> 160 MiB (48). Only the 64-bit tiers qualify.
constexpr unsigned largeBayerRawStoreMiB(std::uint64_t outputLimit) noexcept {
    constexpr std::uint64_t kMi = 1024ULL * 1024ULL;
    return static_cast<unsigned>((outputLimit * 4U * 2U) / kMi + 64U);
}
constexpr unsigned kLargeBayerRawStoreMiB =
    largeBayerRawStoreMiB(32ULL * 1024ULL * 1024ULL);
static_assert(kLargeBayerRawStoreMiB == 320U,
              "the 32 MiPixel tier's raw store is unchanged");

// The large-Bayer half-size path was sized for tiers up to 32 MiPixels; a
// higher tier keeps that path's bounds instead of quadrupling them.
constexpr std::uint64_t largeBayerHalfOutputLimit(
    std::uint64_t pixelLimit) noexcept {
    constexpr std::uint64_t kCap = 32ULL * 1024ULL * 1024ULL;
    return pixelLimit < kCap ? pixelLimit : kCap;
}

// A non-Bayer source (LinearRaw DNG, three- or four-colour) unpacks into up to
// four 16-bit planes per pixel, so the fixed 128 MiB store refused every such
// file over ~16 MP even when its geometry fit the tier. Size the store from the
// tier instead: 8 bytes per admitted pixel plus the same 64 MiB margin.
constexpr unsigned linearRawStoreMiB(std::uint64_t pixelLimit) noexcept {
    constexpr std::uint64_t kMi = 1024ULL * 1024ULL;
    return static_cast<unsigned>((pixelLimit * 8U) / kMi + 64U);
}
static_assert(linearRawStoreMiB(64ULL * 1024ULL * 1024ULL) == 576U,
              "64 MiPixel tier: 512 MiB of planes plus margin");

struct DecodeGeometry {
    std::uint64_t rawWidth, rawHeight, width, height;
    std::uint64_t leftMargin, topMargin;
    double pixelAspect;
};

inline bool areaExceeds(std::uint64_t w, std::uint64_t h,
                        std::uint64_t cap) noexcept {
    return w != 0 && h > cap / w;
}

inline bool qualifiedLargeBayerHalfGeometry(
        const DecodeGeometry& g, std::uint64_t outputLimit,
        bool halfSize, unsigned filters, unsigned colors,
        unsigned fujiWidth, bool singleRawPlane) noexcept {
    constexpr std::uint64_t kMi = 1024ULL * 1024ULL;
    // Every 64-bit tier (12/24/32 MiPixels) may half-size a Bayer source of up
    // to four times its output ceiling. #268: a 16 MP NEF on a 3.6 GB phone
    // (12 MiPixel tier) was refused even for a 1024 px preview, because only
    // the 32 MiPixel tier qualified. 32-bit (8 MiPixels) stays excluded.
    if (!halfSize || outputLimit < 12ULL * kMi ||
        outputLimit > 32ULL * kMi ||
        filters < 1000U || filters == UINT32_MAX || colors != 3U ||
        fujiWidth != 0U || !singleRawPlane ||
        !g.rawWidth || !g.rawHeight || !g.width || !g.height ||
        !std::isfinite(g.pixelAspect) || g.pixelAspect <= 0.0 ||
        g.leftMargin > UINT64_MAX - g.width ||
        g.topMargin > UINT64_MAX - g.height) return false;
    const std::uint64_t aw = g.rawWidth > g.width + g.leftMargin
        ? g.rawWidth : g.width + g.leftMargin;
    const std::uint64_t ah = g.rawHeight > g.height + g.topMargin
        ? g.rawHeight : g.height + g.topMargin;
    const std::uint64_t sourceLimit = outputLimit * 4U;
    if (areaExceeds(aw, ah, sourceLimit) ||
        areaExceeds(g.rawWidth, g.rawHeight, sourceLimit) ||
        areaExceeds(g.width, g.height, sourceLimit)) return false;
    // raw2image_ex allocates ceil(width/2)+2 by ceil(height/2)+2;
    // stretch() can then enlarge one dimension according to pixel_aspect.
    const std::uint64_t iw = (g.width + 1U) / 2U;
    const std::uint64_t ih = (g.height + 1U) / 2U;
    double projectedW = static_cast<double>(iw);
    double projectedH = static_cast<double>(ih);
    if (g.pixelAspect < 1.0) projectedH = projectedH / g.pixelAspect + 0.5;
    else if (g.pixelAspect > 1.0)
        projectedW = projectedW * g.pixelAspect + 0.5;
    if (!std::isfinite(projectedW) || !std::isfinite(projectedH) ||
        projectedW > 65535.0 || projectedH > 65535.0) return false;
    return !areaExceeds(iw + 2U, ih + 2U, outputLimit) &&
           !areaExceeds(static_cast<std::uint64_t>(projectedW),
                        static_cast<std::uint64_t>(projectedH), outputLimit);
}

}  // namespace spectrafilm

#endif
