/*
 * Spektrafilm for Android — lib:pngwriter 16-bit PNG writer implementation.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * Writes a valid 16-bit-per-channel RGB PNG stream. Format details:
 *
 *   PNG signature  (8 bytes, always \x89PNG\r\n\x1a\n)
 *   IHDR chunk     (13 data bytes: width, height, bit_depth=16, color_type=2 RGB,
 *                   compression=0, filter=0, interlace=0)
 *   iCCP chunk     (optional; written when PngMetadata::iccProfile is non-empty;
 *                   profile name "ICC Profile\0", compression method 0 = zlib deflate,
 *                   compressed profile bytes — zlib compress2() or deflate)
 *   tEXt chunk     (optional "Software\0<value>" keyword:value pair)
 *   IDAT chunk     (one chunk containing the zlib-deflated filtered scanline data)
 *   IEND chunk     (zero-length end-of-file marker)
 *
 * 16-bit byte order:
 *   The PNG spec (RFC 2083 §2.3 / ISO 15948:2003 §2.1) requires multi-byte
 *   samples to be stored big-endian. The engine delivers little-endian uint16
 *   (native ARM/x86). We byte-swap each sample before deflating. No libpng is
 *   used; byte-swap is hand-rolled (high = v>>8, low = v&0xFF, emitted in that
 *   order into the filtered-row buffer).
 *
 * CRC32:
 *   Every PNG chunk has a 4-byte CRC32 covering the chunk-type bytes plus the
 *   chunk data bytes. We use zlib's crc32() initialised with crc32(0,Z_NULL,0).
 *   This is correct per the PNG spec which mandates ISO 3309 CRC32 — exactly
 *   the polynomial zlib implements.
 *
 * IDAT deflate:
 *   We use zlib's deflate (compress2 / deflateInit2 with windowBits=15 for zlib
 *   wrapper, level Z_DEFAULT_COMPRESSION). The entire filtered scanline buffer is
 *   compressed in one shot. The resulting bytes become a single IDAT chunk.
 *
 * Per-scanline filter byte:
 *   Each scanline is prefixed with a 1-byte filter type. We use filter 0 (None)
 *   throughout — the sample bytes are passed through unchanged. Filter 0 is
 *   always correct per the PNG spec; for 16-bit RGB the benefit from Paeth/Sub
 *   is marginal enough that simplicity wins here.
 *
 * iCCP chunk:
 *   The raw ICC bytes are zlib-compressed (compress2) and stored as:
 *     profile_name  NUL  compression_method(0)  compressed_data
 *   Profile name is "ICC Profile" (the conventional value; any name ≤79 bytes
 *   works). Compression method 0 is the only defined value per PNG spec §11.3.3.2.
 *
 * zlib on Android:
 *   The NDK sysroot provides libz.so (dynamically linked into any Android process)
 *   and libz.a for static linking. CMakeLists.txt uses find_library(z-lib z) and
 *   target_link_libraries(sfpng ${z-lib}) — on Android this resolves to the
 *   system-provided /system/lib[64]/libz.so, exactly as libraw does for its own
 *   zlib dependency.
 *
 * Host build:
 *   g++ -std=c++17 -O2 -I<dir> png_writer.cpp -lz  (system zlib1g-dev)
 *   The iCCP / IDAT / CRC paths are 100% portable POSIX/C++17; no Android headers.
 */
#include "png_writer.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <zlib.h>

namespace spectrafilm {
namespace {

// ---- big-endian emitters ---------------------------------------------------
// PNG is big-endian for all multi-byte integers in chunk headers and IHDR data.

static void putBE32(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    b.push_back(static_cast<uint8_t>(v & 0xFF));
}

// Append a complete PNG chunk:  length(4) + type(4) + data(n) + crc32(4).
// The CRC covers type bytes + data bytes (per PNG spec §5.3).
static void appendChunk(std::vector<uint8_t>& out,
                        const char type[4],
                        const uint8_t* data, uint32_t len) {
    putBE32(out, len);
    out.push_back(static_cast<uint8_t>(type[0]));
    out.push_back(static_cast<uint8_t>(type[1]));
    out.push_back(static_cast<uint8_t>(type[2]));
    out.push_back(static_cast<uint8_t>(type[3]));
    if (data && len > 0)
        out.insert(out.end(), data, data + len);

    // CRC32 over the 4 type bytes + data bytes.
    uLong crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, reinterpret_cast<const Bytef*>(type), 4);
    if (data && len > 0)
        crc = crc32(crc, reinterpret_cast<const Bytef*>(data), len);
    putBE32(out, static_cast<uint32_t>(crc));
}

// Convenience overload for vector data.
static void appendChunk(std::vector<uint8_t>& out,
                        const char type[4],
                        const std::vector<uint8_t>& data) {
    appendChunk(out, type, data.empty() ? nullptr : data.data(),
                static_cast<uint32_t>(data.size()));
}

// ---- zlib compress (wrap) --------------------------------------------------
// Returns false on failure and sets errOut.
static bool zlibCompress(const uint8_t* src, size_t srcLen,
                         std::vector<uint8_t>& dst, std::string& errOut) {
    // Upper bound for compressed output per zlib docs: compressBound().
    uLong bound = compressBound(static_cast<uLong>(srcLen));
    dst.resize(static_cast<size_t>(bound));

    uLong dstLen = bound;
    int rc = compress2(reinterpret_cast<Bytef*>(dst.data()), &dstLen,
                       reinterpret_cast<const Bytef*>(src),
                       static_cast<uLong>(srcLen),
                       Z_DEFAULT_COMPRESSION);
    if (rc != Z_OK) {
        errOut = "zlib compress2 failed (rc=" + std::to_string(rc) + ")";
        return false;
    }
    dst.resize(static_cast<size_t>(dstLen));
    return true;
}

}  // namespace

// ---- writePng16ToMemory ----------------------------------------------------

PngWriteResult writePng16ToMemory(const uint16_t* rgb16, int width, int height,
                                  const PngMetadata& meta,
                                  std::vector<uint8_t>& outBytes) {
    PngWriteResult res;
    outBytes.clear();

    if (rgb16 == nullptr) { res.error = "null pixel buffer"; return res; }
    if (width <= 0 || height <= 0) { res.error = "invalid dimensions"; return res; }
    // Reject images whose filtered-scanline buffer would overflow size_t on a
    // 32-bit ABI (armeabi-v7a). Computed in uint64; matches the TIFF writer's
    // >4 GiB guard. (Security review F6.)
    {
        const uint64_t rowBytes64 = static_cast<uint64_t>(width) * 3u * 2u + 1u;
        const uint64_t bufBytes64 = rowBytes64 * static_cast<uint64_t>(height);
        if (bufBytes64 > static_cast<uint64_t>(SIZE_MAX)) {
            res.error = "image too large (scanline buffer exceeds addressable size)";
            return res;
        }
    }

    // ---- 1. PNG signature --------------------------------------------------
    // \x89 P N G \r \n \x1a \n  (RFC 2083 §5.2)
    static const uint8_t kSig[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    outBytes.insert(outBytes.end(), kSig, kSig + 8);

    // ---- 2. IHDR chunk (13 data bytes) -------------------------------------
    // width(4BE) height(4BE) bit_depth(1) color_type(1) compression(1) filter(1) interlace(1)
    // bit_depth=16, color_type=2 (RGB), compression=0, filter=0, interlace=0
    {
        uint8_t ihdr[13];
        // width big-endian
        ihdr[0] = static_cast<uint8_t>((static_cast<uint32_t>(width) >> 24) & 0xFF);
        ihdr[1] = static_cast<uint8_t>((static_cast<uint32_t>(width) >> 16) & 0xFF);
        ihdr[2] = static_cast<uint8_t>((static_cast<uint32_t>(width) >>  8) & 0xFF);
        ihdr[3] = static_cast<uint8_t>( static_cast<uint32_t>(width)        & 0xFF);
        // height big-endian
        ihdr[4] = static_cast<uint8_t>((static_cast<uint32_t>(height) >> 24) & 0xFF);
        ihdr[5] = static_cast<uint8_t>((static_cast<uint32_t>(height) >> 16) & 0xFF);
        ihdr[6] = static_cast<uint8_t>((static_cast<uint32_t>(height) >>  8) & 0xFF);
        ihdr[7] = static_cast<uint8_t>( static_cast<uint32_t>(height)        & 0xFF);
        ihdr[8]  = 16;  // bit_depth
        ihdr[9]  = 2;   // color_type = RGB (no alpha)
        ihdr[10] = 0;   // compression method 0 = deflate (only defined value)
        ihdr[11] = 0;   // filter method 0 (only defined value)
        ihdr[12] = 0;   // interlace = 0 (no interlace)
        appendChunk(outBytes, "IHDR", ihdr, 13);
    }

    // ---- 3. iCCP chunk (optional: non-empty iccProfile) --------------------
    // Format: profile_name NUL compression_method(0) compressed_profile_data
    if (!meta.iccProfile.empty()) {
        // Compress the raw ICC bytes with zlib.
        std::vector<uint8_t> compressedIcc;
        std::string zlibErr;
        if (!zlibCompress(meta.iccProfile.data(), meta.iccProfile.size(),
                          compressedIcc, zlibErr)) {
            res.error = "iCCP: " + zlibErr;
            return res;
        }

        // Assemble iCCP chunk data.
        static const char kProfileName[] = "ICC Profile";  // including NUL
        const size_t nameLen = sizeof(kProfileName);       // strlen + NUL = 12
        std::vector<uint8_t> iccpData;
        iccpData.reserve(nameLen + 1 + compressedIcc.size());
        iccpData.insert(iccpData.end(),
                        reinterpret_cast<const uint8_t*>(kProfileName),
                        reinterpret_cast<const uint8_t*>(kProfileName) + nameLen);
        iccpData.push_back(0);  // compression method = 0 (zlib)
        iccpData.insert(iccpData.end(), compressedIcc.begin(), compressedIcc.end());
        appendChunk(outBytes, "iCCP", iccpData);
    }

    // ---- 4. tEXt chunk (optional: non-empty software) ----------------------
    // Format: keyword NUL value  (no compression for tEXt; use iTXt for UTF-8)
    if (!meta.software.empty()) {
        static const char kKeyword[] = "Software";  // including NUL = 9 bytes
        std::vector<uint8_t> txtData;
        txtData.reserve(sizeof(kKeyword) + meta.software.size());
        txtData.insert(txtData.end(),
                       reinterpret_cast<const uint8_t*>(kKeyword),
                       reinterpret_cast<const uint8_t*>(kKeyword) + sizeof(kKeyword));
        txtData.insert(txtData.end(), meta.software.begin(), meta.software.end());
        appendChunk(outBytes, "tEXt", txtData);
    }

    // ---- 5. IDAT chunk: build filtered scanline buffer, then deflate -------
    //
    // For a 16-bit RGB image with filter 0 (None):
    //   Each scanline is: 1 filter-type byte (0x00) followed by
    //   width * 3 * 2 raw big-endian sample bytes.
    // All scanlines are concatenated into one buffer, then zlib-compressed.
    // The compressed bytes form a single IDAT chunk.
    //
    // Big-endian byte-swap: PNG samples are stored high-byte first.
    // Input rgb16 samples are native uint16 (little-endian machine words on
    // ARM/x86); we emit (v>>8) then (v&0xFF) to get big-endian.
    {
        const size_t rowBytes = static_cast<size_t>(width) * 3u * 2u;  // bytes per scanline (no filter)
        const size_t filteredRowBytes = 1u + rowBytes;                  // +1 for filter byte
        const size_t filtBufSize = filteredRowBytes * static_cast<size_t>(height);

        std::vector<uint8_t> filtBuf(filtBufSize);
        {
            uint8_t* dst = filtBuf.data();
            const uint16_t* src = rgb16;
            for (int y = 0; y < height; ++y) {
                *dst++ = 0;  // filter byte = 0 (None)
                for (int x = 0; x < width * 3; ++x) {
                    uint16_t v = *src++;
                    *dst++ = static_cast<uint8_t>(v >> 8);    // high byte first (big-endian)
                    *dst++ = static_cast<uint8_t>(v & 0xFF);  // low byte second
                }
            }
        }

        // Compress the filtered buffer.
        std::vector<uint8_t> idatData;
        std::string zlibErr;
        if (!zlibCompress(filtBuf.data(), filtBuf.size(), idatData, zlibErr)) {
            res.error = "IDAT: " + zlibErr;
            return res;
        }

        appendChunk(outBytes, "IDAT", idatData);
    }

    // ---- 6. IEND chunk (zero-length) ---------------------------------------
    appendChunk(outBytes, "IEND", nullptr, 0);

    res.ok = true;
    res.bytesWritten = outBytes.size();
    return res;
}

// ---- writePng16ToFile ------------------------------------------------------

PngWriteResult writePng16ToFile(const uint16_t* rgb16, int width, int height,
                                const PngMetadata& meta,
                                const std::string& path) {
    std::vector<uint8_t> bytes;
    PngWriteResult res = writePng16ToMemory(rgb16, width, height, meta, bytes);
    if (!res.ok) return res;

    FILE* f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) {
        res.ok = false;
        res.error = "cannot open output path: " + path;
        return res;
    }
    size_t wrote = std::fwrite(bytes.data(), 1, bytes.size(), f);
    int closeErr = std::fclose(f);
    if (wrote != bytes.size() || closeErr != 0) {
        res.ok = false;
        res.error = "short write to " + path;
        return res;
    }
    res.bytesWritten = bytes.size();
    return res;
}

// ---- writePngFloatToFile ---------------------------------------------------

PngWriteResult writePngFloatToFile(const float* rgbFloat, int width, int height,
                                   const PngMetadata& meta,
                                   const std::string& path) {
    PngWriteResult res;
    if (rgbFloat == nullptr) { res.error = "null float buffer"; return res; }
    if (width <= 0 || height <= 0) { res.error = "invalid dimensions"; return res; }

    const size_t n = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    std::vector<uint16_t> q(n);
    for (size_t i = 0; i < n; ++i) {
        float v = rgbFloat[i];
        if (v <= 0.0f) { q[i] = 0; continue; }
        if (v >= 1.0f) { q[i] = 65535; continue; }
        q[i] = static_cast<uint16_t>(v * 65535.0f + 0.5f);
    }
    return writePng16ToFile(q.data(), width, height, meta, path);
}

}  // namespace spectrafilm
