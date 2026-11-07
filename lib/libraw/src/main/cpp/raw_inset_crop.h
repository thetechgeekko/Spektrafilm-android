// LibRaw inset-crop placement for the processed bitmap. GPLv3.
//
// Some cameras hand LibRaw pixels outside the usable image: the Sony A1 II, for one, delivers a
// masked optical-black band along two edges of an otherwise valid frame. LibRaw reports the
// usable region as sizes.raw_inset_crops[0] (also where a DNG's DefaultCrop lands) but
// dcraw_process()/dcraw_make_mem_image() still return the whole visible frame, so the band was
// rendered as a dark stripe. This places that inset box in the processed bitmap so the copy out
// of it can skip the band.
//
// The idea and the four flip cases come from Jing Yu's spektrafilm fork (arigatou1985, commits
// 8374846 and 7e35f37), which measured where the band lands for each LibRaw flip code. Here the
// mapping is derived from LibRaw's own flip_index() instead and the tests pin it to those
// observations.
//
// Pure arithmetic on plain integers, so the host tests can drive it without a RAW file. Every
// guard fails toward "no crop": a box that does not fit, covers the whole frame, or would remove
// more than LibRaw's own adjust_to_raw_inset_crop() allows (45 %) leaves the bitmap untouched.
#pragma once

#include <cstdint>

namespace spectrafilm::rawcrop {

struct InsetGeometry {
    // libraw_image_sizes_t at the end of unpack(), before dcraw_process().
    std::int64_t width = 0;       // visible frame, sensor orientation
    std::int64_t height = 0;
    std::int64_t leftMargin = 0;  // visible frame origin in the raw frame
    std::int64_t topMargin = 0;
    std::int64_t rawWidth = 0;
    std::int64_t rawHeight = 0;
    int flip = 0;                 // LibRaw flip: bit 4 transpose, 2 rows reversed, 1 cols reversed
    bool fujiRotated = false;     // ioparams.fuji_width != 0: 45-degree layout, never cropped
    // raw_inset_crops[0], raw-frame coordinates; 0xffff origin means "not reported".
    std::int64_t cropLeft = 0xffff;
    std::int64_t cropTop = 0xffff;
    std::int64_t cropWidth = 0;
    std::int64_t cropHeight = 0;
};

struct OutputCrop {
    bool active = false;  // false: copy the whole bitmap
    int x = 0, y = 0, width = 0, height = 0;
};

// Minimum fraction of each visible edge an inset box must keep (LibRaw's maxcrop default).
inline constexpr double kMinInsetFraction = 0.55;

// Where raw_inset_crops[0] lands in a processed bitmap of bitmapWidth x bitmapHeight (after
// LibRaw's flip, half_size shrink and pixel-aspect stretch).
inline OutputCrop insetCropForBitmap(const InsetGeometry& g, int bitmapWidth,
                                     int bitmapHeight) {
    OutputCrop none;
    if (g.fujiRotated || g.width <= 0 || g.height <= 0 || bitmapWidth <= 0 ||
        bitmapHeight <= 0)
        return none;
    if (g.cropLeft >= 0xffff || g.cropTop >= 0xffff || g.cropWidth <= 0 ||
        g.cropHeight <= 0)
        return none;
    if (g.cropLeft + g.cropWidth > g.rawWidth || g.cropTop + g.cropHeight > g.rawHeight)
        return none;
    // The box in the visible frame. It must sit inside it: dcraw_process() never produced
    // the pixels outside, so a box reaching past the frame cannot be honoured.
    const std::int64_t x0 = g.cropLeft - g.leftMargin;
    const std::int64_t y0 = g.cropTop - g.topMargin;
    const std::int64_t x1 = x0 + g.cropWidth;
    const std::int64_t y1 = y0 + g.cropHeight;
    if (x0 < 0 || y0 < 0 || x1 > g.width || y1 > g.height) return none;
    if (x0 == 0 && y0 == 0 && x1 == g.width && y1 == g.height) return none;
    if (g.cropWidth < kMinInsetFraction * static_cast<double>(g.width) ||
        g.cropHeight < kMinInsetFraction * static_cast<double>(g.height))
        return none;

    // The bitmap before LibRaw's flip, in sensor orientation.
    const bool transpose = (g.flip & 4) != 0;
    const std::int64_t srcW = transpose ? bitmapHeight : bitmapWidth;
    const std::int64_t srcH = transpose ? bitmapWidth : bitmapHeight;
    // half_size and the pixel-aspect stretch scale each axis by its own ratio. Round the box
    // inward so no pixel of the band survives on either side.
    auto scaledStart = [](std::int64_t v, std::int64_t num, std::int64_t den) {
        return (v * num + den - 1) / den;
    };
    auto scaledEnd = [](std::int64_t v, std::int64_t num, std::int64_t den) {
        return (v * num) / den;
    };
    std::int64_t sx0 = scaledStart(x0, srcW, g.width);
    std::int64_t sx1 = scaledEnd(x1, srcW, g.width);
    std::int64_t sy0 = scaledStart(y0, srcH, g.height);
    std::int64_t sy1 = scaledEnd(y1, srcH, g.height);
    if (sx1 <= sx0 || sy1 <= sy0) return none;

    // LibRaw::flip_index(): output (row, col) reads source (r, c) with
    //   if (flip & 4) swap(row, col); if (flip & 2) r = srcH-1-r; if (flip & 1) c = srcW-1-c;
    // so the source rows [sy0, sy1) and cols [sx0, sx1) come out as:
    if (g.flip & 2) {
        const std::int64_t a = srcH - sy1, b = srcH - sy0;
        sy0 = a;
        sy1 = b;
    }
    if (g.flip & 1) {
        const std::int64_t a = srcW - sx1, b = srcW - sx0;
        sx0 = a;
        sx1 = b;
    }
    OutputCrop out;
    out.active = true;
    if (transpose) {
        out.y = static_cast<int>(sx0);
        out.height = static_cast<int>(sx1 - sx0);
        out.x = static_cast<int>(sy0);
        out.width = static_cast<int>(sy1 - sy0);
    } else {
        out.x = static_cast<int>(sx0);
        out.width = static_cast<int>(sx1 - sx0);
        out.y = static_cast<int>(sy0);
        out.height = static_cast<int>(sy1 - sy0);
    }
    if (out.x < 0 || out.y < 0 || out.x + out.width > bitmapWidth ||
        out.y + out.height > bitmapHeight)
        return none;
    return out;
}

}  // namespace spectrafilm::rawcrop
