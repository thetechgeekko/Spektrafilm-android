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

// A half-size Bayer image still needs its full single-plane unpacked store.
// The 320 MiB LibRaw raw-store ceiling covers at most 128 MiPixels at two
// bytes/sample, with room for loader metadata and aligned margins. It is only
// selected for the 32 MiPixel, 64-bit device class.
constexpr unsigned kLargeBayerRawStoreMiB = 320U;

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
    if (!halfSize || outputLimit != 32ULL * kMi ||
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
