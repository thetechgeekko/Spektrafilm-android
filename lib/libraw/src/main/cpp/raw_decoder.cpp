/*
 * SpectraFilm for Android — lib:libraw native decoder (implementation).
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 * Uses LibRaw (LGPL-2.1).
 *
 * Reproduces spektrafilm/utils/raw_file_processor.py on-device with LibRaw:
 *   raw.postprocess(output_color=ACES, output_bps=16, no_auto_bright=True,
 *                   gamma=(1,1), use_camera_wb=<as_shot>)
 * then the colour-science white-balance path (Von-Kries adaptation + tint) for
 * the non-as-shot modes.
 *
 * The LibRaw include is guarded with __has_include so this translation unit still
 * compiles and links even if LibRaw is unavailable (e.g. a host build without the
 * source). In the normal build, CMake fetches LibRaw and adds its root to this
 * target's include path, so <libraw/libraw.h> resolves and the real decode path
 * (SFRAW_HAVE_LIBRAW) is compiled in.
 */
#include "raw_decoder.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <unistd.h>

#if defined(__has_include)
#  if __has_include(<libraw/libraw.h>)
#    include <libraw/libraw.h>
#    define SFRAW_HAVE_LIBRAW 1
#  endif
#endif

#ifndef SFRAW_HAVE_LIBRAW
#  define SFRAW_HAVE_LIBRAW 0
#endif

namespace spectrafilm {

namespace {

// Reference (target) white for the daylight-base modes. raw_file_processor.py:
//   _DAYLIGHT_REFERENCE_TEMPERATURE = 6504.0
constexpr double kDaylightReferenceTemperature = 6504.0;
// raw_file_processor.py: _TUNGSTEN_TEMPERATURE = 2850.0
constexpr double kTungstenTemperature = 2850.0;

// ACES2065-1 (AP0) <-> CIE XYZ (D60-adapted, the colour-science default for this
// colourspace). These are the standard AP0 RGB->XYZ and XYZ->RGB matrices used by
// `colour.RGB_COLOURSPACES["ACES2065-1"]`, which is what raw_file_processor.py uses
// for RGB_to_XYZ / XYZ_to_RGB with chromatic_adaptation_transform=None.
//
// Row-major 3x3.
constexpr double kAcesRgbToXyz[9] = {
    0.9525523959, 0.0000000000,  0.0000936786,
    0.3439664498, 0.7281660966, -0.0721325464,
    0.0000000000, 0.0000000000,  1.0088251844,
};
constexpr double kAcesXyzToRgb[9] = {
     1.0498110175, 0.0000000000, -0.0000974845,
    -0.4959030231, 1.3733130458,  0.0982400361,
     0.0000000000, 0.0000000000,  0.9912520182,
};

void mat3MulVec(const double m[9], const double v[3], double out[3]) {
    out[0] = m[0] * v[0] + m[1] * v[1] + m[2] * v[2];
    out[1] = m[3] * v[0] + m[4] * v[1] + m[5] * v[2];
    out[2] = m[6] * v[0] + m[7] * v[1] + m[8] * v[2];
}

// CIE daylight locus chromaticity for T >= 4000 K (matches colour's
// 'CIE Illuminant D Series' path used by _whitepoint_xyz_from_temperature).
void daylightXy(double t, double& x, double& y) {
    const double t1 = 1.0e3 / t;
    const double t2 = 1.0e6 / (t * t);
    const double t3 = 1.0e9 / (t * t * t);
    if (t <= 7000.0) {
        x = 0.244063 + 0.09911 * t1 + 2.9678 * t2 - 4.6070 * t3;
    } else {
        x = 0.237040 + 0.24748 * t1 + 1.9018 * t2 - 2.0064 * t3;
    }
    y = -3.000 * x * x + 2.870 * x - 0.275;
}

// Kang 2002 Planckian approximation for T < 4000 K (matches colour's 'Kang 2002').
void kang2002Xy(double t, double& x, double& y) {
    const double t2 = t * t;
    const double t3 = t2 * t;
    if (t <= 4000.0) {
        x = -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910;
    } else {
        x = -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390;
    }
    const double x2 = x * x;
    const double x3 = x2 * x;
    if (t <= 2222.0) {
        y = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683;
    } else if (t <= 4000.0) {
        y = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867;
    } else {
        y = 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;
    }
}

}  // namespace

void whitepointXyzFromTemperature(double temperatureK, double outXyz[3]) {
    double x, y;
    // raw_file_processor.py: 'CIE Illuminant D Series' if T >= 4000 else 'Kang 2002'.
    if (temperatureK >= 4000.0) {
        daylightXy(temperatureK, x, y);
    } else {
        kang2002Xy(temperatureK, x, y);
    }
    // xy -> XYZ with Y = 1 (colour.xy_to_XYZ default).
    outXyz[0] = x / y;
    outXyz[1] = 1.0;
    outXyz[2] = (1.0 - x - y) / y;
}

void buildAcesWbMultiplier(const DecodeOptions& options, float outMul[3]) {
    outMul[0] = outMul[1] = outMul[2] = 1.0f;

    // as-shot / daylight: no colour-science adaptation in raw_file_processor.py.
    if (options.whiteBalance == WhiteBalanceMode::AsShot ||
        options.whiteBalance == WhiteBalanceMode::Daylight) {
        return;
    }

    double targetTemp = options.temperatureK;
    double tint = options.tint;
    if (options.whiteBalance == WhiteBalanceMode::Tungsten) {
        targetTemp = kTungstenTemperature;  // adapt 2850 K -> reference
        tint = 1.0;
    }

    // Source/target whites, normalized so Y == 1 (raw_file_processor.py divides by [1]).
    double scene[3], reference[3];
    whitepointXyzFromTemperature(targetTemp, scene);
    whitepointXyzFromTemperature(kDaylightReferenceTemperature, reference);

    // Von-Kries adaptation collapses, for a neutral (R=G=B) pixel, to a per-channel
    // scale in ACES RGB. We derive that scale by adapting a unit-white ACES pixel:
    //   rgb=(1,1,1) -> XYZ -> (diag von-Kries scene->reference) -> XYZ -> rgb.
    // For non-neutral pixels the JNI/native path applies the full matrix per pixel;
    // exposing the multiplier here covers the common neutral case + unit tests.
    //
    // Von-Kries here uses XYZ directly (colour 'Von Kries' method with no cone
    // transform == identity sharpening), matching method='Von Kries' in the Python.
    double whiteXyz[3];
    const double unit[3] = {1.0, 1.0, 1.0};
    mat3MulVec(kAcesRgbToXyz, unit, whiteXyz);

    double adapted[3] = {
        whiteXyz[0] * (reference[0] / scene[0]),
        whiteXyz[1] * (reference[1] / scene[1]),
        whiteXyz[2] * (reference[2] / scene[2]),
    };
    double adaptedRgb[3];
    mat3MulVec(kAcesXyzToRgb, adapted, adaptedRgb);

    outMul[0] = static_cast<float>(adaptedRgb[0]);
    outMul[1] = static_cast<float>(adaptedRgb[1] * tint);  // green tint multiplier
    outMul[2] = static_cast<float>(adaptedRgb[2]);
}

#if SFRAW_HAVE_LIBRAW

namespace {

// Apply the rawpy-parity postprocess params (RAW_DNG.md):
//   output_color=6 (ACES), output_bps=16, no_auto_bright=1, gamm[0]=gamm[1]=1.0,
//   use_camera_wb for as-shot.
void applyParityParams(LibRaw& raw, const DecodeOptions& options) {
    auto& p = raw.imgdata.params;
    p.output_color   = 6;     // 6 == ACES, matches rawpy.ColorSpace.ACES
    p.output_bps     = 16;
    p.no_auto_bright = 1;
    p.gamm[0]        = 1.0;   // gamma (1,1) -> linear
    p.gamm[1]        = 1.0;

    if (options.whiteBalance == WhiteBalanceMode::AsShot) {
        p.use_camera_wb = 1;
    } else {
        // daylight / tungsten / custom: LibRaw daylight-balanced base output, then
        // the colour-science adaptation is applied below (matches the Python, which
        // leaves use_camera_wb off and adapts in ACES afterwards).
        p.use_camera_wb = 0;
        // TODO(libraw): if a vendored LibRaw build defaults to auto-WB, force the
        // daylight multipliers explicitly via p.user_mul / raw.imgdata.color.pre_mul
        // so the base matches rawpy's daylight default exactly.
    }
}

// Full per-pixel Von-Kries adaptation in ACES RGB (the non-neutral-accurate path),
// mirroring _apply_white_balance_adaptation + _apply_tint_adjustment.
void applyAcesAdaptation(float* rgb, size_t pixelCount, const DecodeOptions& options) {
    if (options.whiteBalance == WhiteBalanceMode::AsShot ||
        options.whiteBalance == WhiteBalanceMode::Daylight) {
        return;
    }

    double targetTemp = options.temperatureK;
    double tint = options.tint;
    if (options.whiteBalance == WhiteBalanceMode::Tungsten) {
        targetTemp = kTungstenTemperature;
        tint = 1.0;
    }

    double scene[3], reference[3];
    whitepointXyzFromTemperature(targetTemp, scene);
    whitepointXyzFromTemperature(kDaylightReferenceTemperature, reference);
    const double sx = reference[0] / scene[0];
    const double sy = reference[1] / scene[1];
    const double sz = reference[2] / scene[2];

    for (size_t i = 0; i < pixelCount; ++i) {
        double in[3] = {rgb[i * 3 + 0], rgb[i * 3 + 1], rgb[i * 3 + 2]};
        double xyz[3];
        mat3MulVec(kAcesRgbToXyz, in, xyz);
        xyz[0] *= sx; xyz[1] *= sy; xyz[2] *= sz;     // diagonal Von-Kries
        double out[3];
        mat3MulVec(kAcesXyzToRgb, xyz, out);
        rgb[i * 3 + 0] = static_cast<float>(out[0]);
        rgb[i * 3 + 1] = static_cast<float>(out[1] * tint);  // green tint
        rgb[i * 3 + 2] = static_cast<float>(out[2]);
    }
}

// Run unpack + dcraw_process + dcraw_make_mem_image and copy the 16-bit linear RGB
// into a normalized float result (value / 65535), matching the Python:
//   rgb = raw.postprocess(...).astype(float32) / 65535.0
DecodeResult finishDecode(LibRaw& raw, const DecodeOptions& options) {
    DecodeResult result;

    if (raw.unpack() != LIBRAW_SUCCESS) {
        result.error = "LibRaw unpack() failed";
        return result;
    }
    applyParityParams(raw, options);
    if (raw.dcraw_process() != LIBRAW_SUCCESS) {
        result.error = "LibRaw dcraw_process() failed";
        return result;
    }

    int status = LIBRAW_SUCCESS;
    libraw_processed_image_t* img = raw.dcraw_make_mem_image(&status);
    if (img == nullptr || status != LIBRAW_SUCCESS) {
        if (img) LibRaw::dcraw_clear_mem(img);
        result.error = "LibRaw dcraw_make_mem_image() failed";
        return result;
    }
    if (img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 16) {
        LibRaw::dcraw_clear_mem(img);
        result.error = "unexpected LibRaw image format (expected 16-bit 3-channel)";
        return result;
    }

    result.width  = img->width;
    result.height = img->height;
    const size_t pixelCount = static_cast<size_t>(img->width) * img->height;
    result.rgb.resize(pixelCount * 3);

    const auto* src = reinterpret_cast<const uint16_t*>(img->data);
    constexpr float kInv16 = 1.0f / 65535.0f;
    for (size_t i = 0; i < pixelCount * 3; ++i) {
        result.rgb[i] = static_cast<float>(src[i]) * kInv16;
    }
    LibRaw::dcraw_clear_mem(img);

    applyAcesAdaptation(result.rgb.data(), pixelCount, options);

    result.colorSpace = "ACES2065-1";
    result.ok = true;
    return result;
}

}  // namespace

DecodeResult decodeFromBuffer(const uint8_t* data, size_t length, const DecodeOptions& options) {
    DecodeResult result;
    if (data == nullptr || length == 0) {
        result.error = "empty input buffer";
        return result;
    }
    LibRaw raw;
    if (raw.open_buffer(const_cast<uint8_t*>(data), length) != LIBRAW_SUCCESS) {
        result.error = "LibRaw open_buffer() failed (unsupported or corrupt RAW)";
        return result;
    }
    return finishDecode(raw, options);
}

DecodeResult decodeFromFd(int fd, const DecodeOptions& options) {
    DecodeResult result;
    if (fd < 0) {
        result.error = "invalid file descriptor";
        return result;
    }
    LibRaw raw;
    // LibRaw can read from a FILE*; we dup the fd so the caller keeps ownership.
    int dup_fd = ::dup(fd);
    if (dup_fd < 0) {
        result.error = "dup(fd) failed";
        return result;
    }
    FILE* fp = ::fdopen(dup_fd, "rb");
    if (fp == nullptr) {
        ::close(dup_fd);
        result.error = "fdopen() failed";
        return result;
    }
    // open_datastream owns the FILE* lifecycle once the buffer is attached; simplest
    // robust path is to slurp into memory and reuse open_buffer.
    // TODO(libraw): for very large RAWs, switch to a LibRaw_abstract_datastream
    // backed by the fd to avoid the full read into RAM.
    std::vector<uint8_t> bytes;
    {
        std::fseek(fp, 0, SEEK_END);
        long size = std::ftell(fp);
        std::fseek(fp, 0, SEEK_SET);
        if (size > 0) {
            bytes.resize(static_cast<size_t>(size));
            size_t read = std::fread(bytes.data(), 1, bytes.size(), fp);
            bytes.resize(read);
        }
    }
    std::fclose(fp);  // closes dup_fd
    if (bytes.empty()) {
        result.error = "failed to read RAW from fd";
        return result;
    }
    if (raw.open_buffer(bytes.data(), bytes.size()) != LIBRAW_SUCCESS) {
        result.error = "LibRaw open_buffer() failed (unsupported or corrupt RAW)";
        return result;
    }
    return finishDecode(raw, options);
}

#else  // !SFRAW_HAVE_LIBRAW

// Fallback: LibRaw headers were not found at compile time (only happens if the
// FetchContent step was skipped without -DSFRAW_LIBRAW_SOURCE_DIR). The module
// still compiles and the .so links so the Kotlin facade can be wired; decode()
// returns a clear error instead of crashing.

DecodeResult decodeFromBuffer(const uint8_t*, size_t, const DecodeOptions&) {
    DecodeResult result;
    result.error = "LibRaw unavailable: configure with network (FetchContent) or -DSFRAW_LIBRAW_SOURCE_DIR";
    return result;
}

DecodeResult decodeFromFd(int, const DecodeOptions&) {
    DecodeResult result;
    result.error = "LibRaw unavailable: configure with network (FetchContent) or -DSFRAW_LIBRAW_SOURCE_DIR";
    return result;
}

#endif  // SFRAW_HAVE_LIBRAW

}  // namespace spectrafilm
