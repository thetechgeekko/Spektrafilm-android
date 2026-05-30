/*
 * SpectraFilm for Android — lib:libraw native decoder.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 * Uses LibRaw (LGPL-2.1).
 *
 * Decodes a camera RAW / DNG buffer into a linear, scene-referred RGB image with
 * bit-parity to spektrafilm's desktop rawpy settings:
 *   output_color = ACES (LibRaw code 6, ACES2065-1 primaries)
 *   output_bps   = 16
 *   no_auto_bright = 1
 *   gamm[0] = gamm[1] = 1.0   (linear)
 * White balance mirrors raw_file_processor.py: as-shot (camera WB), daylight
 * (LibRaw daylight base), tungsten / custom (temperature+tint -> Von-Kries
 * chromatic adaptation in linear ACES + green/magenta tint multiplier).
 */
#ifndef SPECTRAFILM_RAW_DECODER_H
#define SPECTRAFILM_RAW_DECODER_H

#include <cstdint>
#include <string>
#include <vector>

namespace spectrafilm {

// Mirrors RawDecoder.WhiteBalance in Kotlin and raw_file_processor.py modes.
enum class WhiteBalanceMode {
    AsShot,    // 'as_shot'  -> use_camera_wb
    Daylight,  // 'daylight' -> LibRaw daylight base, no adaptation
    Tungsten,  // 'tungsten' -> adapt 2850 K -> 6504 K, tint = 1.0
    Custom,    // 'custom'   -> adapt <temperature> K -> 6504 K, tint = <tint>
};

struct DecodeOptions {
    WhiteBalanceMode whiteBalance = WhiteBalanceMode::AsShot;
    // Custom mode only. temperatureK in kelvin; tint multiplies both green
    // channels (1.0 = neutral). Mirrors raw_file_processor.py.
    double temperatureK = 6504.0;
    double tint = 1.0;
};

// Stable decode status codes. These cross to Kotlin (RawDecoder.DecodeStatus)
// so callers can branch on the failure kind (notably to pick a fallback path
// for compressed Samsung "Expert RAW" DNGs). Distinct from LibRaw's own
// LIBRAW_* codes, which are preserved separately in DecodeResult.librawCode.
enum DecodeStatus {
    SFRAW_OK = 0,
    SFRAW_ERR_UNKNOWN = 1,
    SFRAW_ERR_INPUT = 2,            // null/empty/unreadable input
    SFRAW_ERR_OPEN = 3,            // open_buffer/open_file failed
    SFRAW_ERR_FILE_UNSUPPORTED = 4,// not a recognized RAW/DNG
    SFRAW_ERR_UNPACK = 5,         // generic unpack() failure
    SFRAW_ERR_PROCESS = 6,        // dcraw_process / make_mem_image failure
    SFRAW_ERR_NO_MEMORY = 7,
    SFRAW_ERR_FORMAT = 8,         // unexpected processed-image format

    // ---- Compressed-DNG specific (Samsung Expert RAW et al.) ----
    // DEFLATE-compressed DNG but this build lacks zlib (USE_ZLIB). Should not
    // occur in the default build (zlib is enabled); indicates a misbuild.
    SFRAW_ERR_DEFLATE_DNG = 10,

    // Lossy-JPEG-compressed DNG (the common Expert RAW case) but this build
    // lacks libjpeg (USE_JPEG). The NDK has no libjpeg, so this is the expected
    // residual limitation. App should fall back to platform ImageDecoder.
    SFRAW_ERR_LOSSY_JPEG_DNG = 11,
};

// Linear scene-referred result. Pixels are interleaved RGB float32, row-major,
// normalized 16-bit -> [0,1] (value / 65535), in ACES2065-1 primaries.
struct DecodeResult {
    std::vector<float> rgb;       // size == width * height * 3
    int width = 0;
    int height = 0;
    std::string colorSpace = "ACES2065-1";
    bool ok = false;
    std::string error;            // populated when ok == false
    // Stable SpectraFilm status (DecodeStatus). SFRAW_OK on success.
    int status = SFRAW_ERR_UNKNOWN;
    // Underlying LibRaw error code (LIBRAW_*), for diagnostics. 0 if N/A.
    int librawCode = 0;
};

// Decode from an in-memory RAW/DNG buffer (e.g. a SAF InputStream read fully).
DecodeResult decodeFromBuffer(const uint8_t* data, size_t length, const DecodeOptions& options);

// Decode directly from a file descriptor (e.g. a SAF ParcelFileDescriptor).
// The fd is duplicated internally; the caller retains ownership.
DecodeResult decodeFromFd(int fd, const DecodeOptions& options);

// --- White-balance math (exposed for unit testing / parity checks) ---

// CCT (kelvin) -> normalized XYZ whitepoint (Y == 1), matching
// _whitepoint_xyz_from_temperature in raw_file_processor.py: CIE daylight locus
// for >= 4000 K, Kang 2002 Planckian approximation below.
void whitepointXyzFromTemperature(double temperatureK, double outXyz[3]);

// Build a per-channel linear-RGB multiplier that reproduces the Von-Kries
// chromatic adaptation (source white -> 6504 K reference) plus the green tint,
// applied in ACES2065-1 RGB. Result mirrors the colour-science path in
// raw_file_processor.py for daylight-base WB modes.
void buildAcesWbMultiplier(const DecodeOptions& options, float outMul[3]);

}  // namespace spectrafilm

#endif  // SPECTRAFILM_RAW_DECODER_H
