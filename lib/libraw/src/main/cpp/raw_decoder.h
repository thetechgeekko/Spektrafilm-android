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

// Linear scene-referred result. Pixels are interleaved RGB float32, row-major,
// normalized 16-bit -> [0,1] (value / 65535), in ACES2065-1 primaries.
struct DecodeResult {
    std::vector<float> rgb;       // size == width * height * 3
    int width = 0;
    int height = 0;
    std::string colorSpace = "ACES2065-1";
    bool ok = false;
    std::string error;            // populated when ok == false
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
