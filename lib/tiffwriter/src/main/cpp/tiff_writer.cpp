/*
 * SpectraFilm for Android — lib:tiffwriter 16-bit baseline TIFF writer.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 *
 * Self-contained, dependency-free implementation. See tiff_writer.h for the API
 * and the rationale for hand-rolling rather than reusing LibRaw (LibRaw exposes
 * no clean public TIFF-writing entry point and is compiled here NO_LCMS/NO_JPEG
 * as a decoder only).
 *
 * Format produced: baseline TIFF 6.0, little-endian ("II", 0x002A), a single IFD,
 * RGB chunky (PlanarConfiguration=1), 16 bits/sample x 3 samples, optional
 * PackBits (Compression=32773) or uncompressed (Compression=1). 16-bit samples
 * are stored in the file's byte order (little-endian) per the TIFF spec.
 *
 * IFD layout strategy (single pass, deterministic offsets):
 *   [0]              header (8 bytes): II, 42, offset-to-IFD0
 *   [ifd0]           IFD0: entry count + N entries (12 bytes each) + next-IFD (=0)
 *   [after IFD0]     out-of-line value blobs referenced by IFD0 entries
 *                    (BitsPerSample[3], resolutions, strip offsets/counts, strings,
 *                     ICC blob, EXIF IFD + its value blobs)
 *   [...]            image strips (one strip per file here; SAFE for host + app)
 *
 * We compute every blob's final offset up front (a layout pass) so we can emit
 * the bytes in one forward sweep with no back-patching beyond what the layout
 * already fixed.
 */
#include "tiff_writer.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace spectrafilm {
namespace {

// ---- TIFF tag + type constants --------------------------------------------
constexpr uint16_t T_IMAGE_WIDTH        = 256;
constexpr uint16_t T_IMAGE_LENGTH       = 257;
constexpr uint16_t T_BITS_PER_SAMPLE    = 258;
constexpr uint16_t T_COMPRESSION        = 259;
constexpr uint16_t T_PHOTOMETRIC        = 262;
constexpr uint16_t T_IMAGE_DESCRIPTION  = 270;
constexpr uint16_t T_STRIP_OFFSETS      = 273;
constexpr uint16_t T_SAMPLES_PER_PIXEL  = 277;
constexpr uint16_t T_ROWS_PER_STRIP     = 278;
constexpr uint16_t T_STRIP_BYTE_COUNTS  = 279;
constexpr uint16_t T_X_RESOLUTION       = 282;
constexpr uint16_t T_Y_RESOLUTION       = 283;
constexpr uint16_t T_PLANAR_CONFIG      = 284;
constexpr uint16_t T_RESOLUTION_UNIT    = 296;
constexpr uint16_t T_SOFTWARE           = 305;
constexpr uint16_t T_DATETIME           = 306;
constexpr uint16_t T_ARTIST             = 315;
constexpr uint16_t T_SAMPLE_FORMAT      = 339;
constexpr uint16_t T_COPYRIGHT          = 33432;
constexpr uint16_t T_EXIF_IFD           = 34665;
constexpr uint16_t T_ICC_PROFILE        = 34675;

// EXIF sub-IFD tags
constexpr uint16_t E_EXIF_VERSION       = 36864;  // UNDEFINED[4], e.g. "0230"
constexpr uint16_t E_COLOR_SPACE        = 40961;  // SHORT
constexpr uint16_t E_PIXEL_X_DIM        = 40962;  // SHORT or LONG
constexpr uint16_t E_PIXEL_Y_DIM        = 40963;  // SHORT or LONG

// Field types
constexpr uint16_t TY_BYTE      = 1;
constexpr uint16_t TY_ASCII     = 2;
constexpr uint16_t TY_SHORT     = 3;
constexpr uint16_t TY_LONG      = 4;
constexpr uint16_t TY_RATIONAL  = 5;
constexpr uint16_t TY_UNDEFINED = 7;

constexpr uint16_t COMPRESSION_NONE     = 1;
constexpr uint16_t COMPRESSION_PACKBITS = 32773;
constexpr uint16_t PHOTOMETRIC_RGB      = 2;

// ---- little-endian byte emitters ------------------------------------------
void putU16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}
void putU32(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
}
// Convert a positive double to a TIFF RATIONAL (num/den). Uses den=1000 so common
// resolutions (72, 300, 96, 150 dpi …) and fractional values round-trip exactly.
void doubleToRational(double v, uint32_t& num, uint32_t& den) {
    if (v < 0) v = 0;
    den = 1000;
    double scaled = v * static_cast<double>(den);
    if (scaled > 4294967295.0) {  // clamp to LONG range
        num = 4294967295u; den = 1; return;
    }
    num = static_cast<uint32_t>(scaled + 0.5);
}

// PackBits RLE encode one strip of raw bytes (TIFF "PackBits" / Apple variant).
std::vector<uint8_t> packBits(const uint8_t* src, size_t n) {
    std::vector<uint8_t> out;
    out.reserve(n + n / 128 + 16);
    size_t i = 0;
    while (i < n) {
        // Try a run of >= 2 identical bytes.
        size_t runLen = 1;
        while (i + runLen < n && runLen < 128 && src[i + runLen] == src[i]) runLen++;
        if (runLen >= 2) {
            out.push_back(static_cast<uint8_t>(257 - runLen));  // -(runLen-1) as int8
            out.push_back(src[i]);
            i += runLen;
        } else {
            // Literal run: collect until a >=3 run begins or 128 reached.
            size_t litStart = i;
            size_t litLen = 0;
            while (i < n && litLen < 128) {
                size_t look = 1;
                while (i + look < n && look < 3 && src[i + look] == src[i]) look++;
                if (look >= 3) break;  // a run is starting; stop the literal
                i++; litLen++;
            }
            out.push_back(static_cast<uint8_t>(litLen - 1));
            for (size_t k = 0; k < litLen; ++k) out.push_back(src[litStart + k]);
        }
    }
    return out;
}

// One IFD entry described abstractly during the layout pass.
struct Entry {
    uint16_t tag;
    uint16_t type;
    uint32_t count;
    // Either an inline value (<= 4 bytes packed) OR a pointer into the value pool.
    bool inlineVal = false;
    uint32_t inlineBytes = 0;   // up to 4 bytes, little-endian packed, when inlineVal
    uint32_t valueOffset = 0;   // file offset of out-of-line value, when !inlineVal
};

}  // namespace

TiffWriteResult writeTiff16ToMemory(const uint16_t* rgb16, int width, int height,
                                    const TiffMetadata& meta, TiffCompression compression,
                                    std::vector<uint8_t>& outBytes) {
    TiffWriteResult res;
    outBytes.clear();
    if (rgb16 == nullptr) { res.error = "null pixel buffer"; return res; }
    if (width <= 0 || height <= 0) { res.error = "invalid dimensions"; return res; }

    const uint64_t pixels = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    const uint64_t rawStripBytes = pixels * 3ull * 2ull;  // 3 samples * 16-bit
    if (rawStripBytes > 0xFFFFFFFFull) {
        res.error = "image too large for a single-strip baseline TIFF (>4GiB)";
        return res;
    }

    // --- Build the raw strip (RGB, 16-bit, little-endian samples) -----------
    std::vector<uint8_t> raw(static_cast<size_t>(rawStripBytes));
    {
        uint8_t* d = raw.data();
        const uint64_t nSamples = pixels * 3ull;
        for (uint64_t s = 0; s < nSamples; ++s) {
            uint16_t v = rgb16[s];
            *d++ = static_cast<uint8_t>(v & 0xFF);
            *d++ = static_cast<uint8_t>((v >> 8) & 0xFF);
        }
    }

    const bool usePackBits = (compression == TiffCompression::PackBits);
    std::vector<uint8_t> strip;
    if (usePackBits) {
        strip = packBits(raw.data(), raw.size());
        // PackBits can expand on incompressible data; fall back to none if so.
        if (strip.size() >= raw.size()) {
            strip.swap(raw);
            // (now "strip" holds uncompressed; flag below)
        }
    }
    const bool stripIsPacked = usePackBits && strip.size() < rawStripBytes;
    if (!usePackBits) strip.swap(raw);
    const uint16_t compTag = stripIsPacked ? COMPRESSION_PACKBITS : COMPRESSION_NONE;
    const uint32_t stripByteCount = static_cast<uint32_t>(strip.size());

    // --- Decide which baseline entries we emit (sorted by tag below) --------
    struct PendingString { uint16_t tag; std::string s; };
    std::vector<PendingString> strings;
    auto addString = [&](uint16_t tag, const std::string& s) {
        if (!s.empty()) strings.push_back({tag, s});
    };
    addString(T_IMAGE_DESCRIPTION, meta.imageDescription);
    addString(T_SOFTWARE, meta.software);
    addString(T_DATETIME, meta.dateTime);
    addString(T_ARTIST, meta.artist);
    addString(T_COPYRIGHT, meta.copyright);

    const bool hasIcc = !meta.iccProfile.empty();
    const bool hasExif = meta.writeExifIfd;

    // Count IFD0 entries.
    // Mandatory: ImageWidth, ImageLength, BitsPerSample, Compression, Photometric,
    //   StripOffsets, SamplesPerPixel, RowsPerStrip, StripByteCounts, XRes, YRes,
    //   PlanarConfig, ResolutionUnit, SampleFormat = 14
    uint16_t ifd0Count = 14;
    ifd0Count += static_cast<uint16_t>(strings.size());
    if (hasIcc)  ifd0Count++;
    if (hasExif) ifd0Count++;

    // --- LAYOUT PASS: compute every offset ----------------------------------
    // header = 8 bytes; IFD0 starts at 8.
    const uint32_t ifd0Offset = 8;
    const uint32_t ifd0Bytes  = 2 + static_cast<uint32_t>(ifd0Count) * 12u + 4u;  // count+entries+next
    uint32_t cursor = ifd0Offset + ifd0Bytes;  // value pool starts here

    auto alignEven = [](uint32_t& c) { if (c & 1u) c++; };  // TIFF: word-aligned offsets

    // BitsPerSample[3] (SHORT) -> 6 bytes out of line.
    alignEven(cursor);
    const uint32_t bitsOffset = cursor; cursor += 6;
    // SampleFormat[3] (SHORT) -> 6 bytes out of line (1=unsigned int).
    alignEven(cursor);
    const uint32_t sampleFmtOffset = cursor; cursor += 6;
    // XResolution / YResolution (RATIONAL) -> 8 bytes each.
    alignEven(cursor);
    const uint32_t xresOffset = cursor; cursor += 8;
    alignEven(cursor);
    const uint32_t yresOffset = cursor; cursor += 8;

    // Strings (ASCII, NUL-terminated). <=4 bytes incl. NUL go inline.
    struct StringLoc { uint16_t tag; std::string s; uint32_t offset; bool inlineVal; };
    std::vector<StringLoc> strLocs;
    strLocs.reserve(strings.size());
    for (auto& ps : strings) {
        uint32_t len = static_cast<uint32_t>(ps.s.size()) + 1;  // include NUL
        StringLoc loc{ps.tag, ps.s, 0, len <= 4};
        if (!loc.inlineVal) { alignEven(cursor); loc.offset = cursor; cursor += len; }
        strLocs.push_back(loc);
    }

    // ICC profile blob.
    uint32_t iccOffset = 0;
    if (hasIcc) { alignEven(cursor); iccOffset = cursor; cursor += static_cast<uint32_t>(meta.iccProfile.size()); }

    // EXIF sub-IFD: lay out its own entries + value pool.
    // EXIF entries: ExifVersion(UNDEFINED[4] inline), ColorSpace(SHORT inline),
    //   PixelXDimension(LONG inline), PixelYDimension(LONG inline) = 4 entries,
    // all inline -> no EXIF value pool needed.
    uint32_t exifIfdOffset = 0;
    const uint16_t exifCount = 4;
    if (hasExif) {
        alignEven(cursor);
        exifIfdOffset = cursor;
        cursor += 2 + static_cast<uint32_t>(exifCount) * 12u + 4u;
    }

    // Image strip last (word-aligned).
    alignEven(cursor);
    const uint32_t stripOffset = cursor; cursor += stripByteCount;

    const uint32_t fileSize = cursor;

    // --- EMIT PASS ----------------------------------------------------------
    outBytes.reserve(fileSize);

    // Header.
    outBytes.push_back('I'); outBytes.push_back('I');  // little-endian
    putU16(outBytes, 42);                              // TIFF magic
    putU32(outBytes, ifd0Offset);

    // Assemble IFD0 entries (must be written in ascending tag order).
    std::vector<Entry> entries;
    auto addInline = [&](uint16_t tag, uint16_t type, uint32_t count, uint32_t packed) {
        Entry e; e.tag = tag; e.type = type; e.count = count;
        e.inlineVal = true; e.inlineBytes = packed; entries.push_back(e);
    };
    auto addOutline = [&](uint16_t tag, uint16_t type, uint32_t count, uint32_t off) {
        Entry e; e.tag = tag; e.type = type; e.count = count;
        e.inlineVal = false; e.valueOffset = off; entries.push_back(e);
    };

    addInline(T_IMAGE_WIDTH,  TY_LONG, 1, static_cast<uint32_t>(width));
    addInline(T_IMAGE_LENGTH, TY_LONG, 1, static_cast<uint32_t>(height));
    addOutline(T_BITS_PER_SAMPLE, TY_SHORT, 3, bitsOffset);
    addInline(T_COMPRESSION, TY_SHORT, 1, compTag);
    addInline(T_PHOTOMETRIC, TY_SHORT, 1, PHOTOMETRIC_RGB);
    // (ImageDescription tag 270 inserted via strings, sorted later)
    addInline(T_STRIP_OFFSETS, TY_LONG, 1, stripOffset);
    addInline(T_SAMPLES_PER_PIXEL, TY_SHORT, 1, 3);
    addInline(T_ROWS_PER_STRIP, TY_LONG, 1, static_cast<uint32_t>(height));
    addInline(T_STRIP_BYTE_COUNTS, TY_LONG, 1, stripByteCount);
    addOutline(T_X_RESOLUTION, TY_RATIONAL, 1, xresOffset);
    addOutline(T_Y_RESOLUTION, TY_RATIONAL, 1, yresOffset);
    addInline(T_PLANAR_CONFIG, TY_SHORT, 1, 1);  // chunky
    addInline(T_RESOLUTION_UNIT, TY_SHORT, 1, meta.resolutionUnit);
    addOutline(T_SAMPLE_FORMAT, TY_SHORT, 3, sampleFmtOffset);

    for (auto& sl : strLocs) {
        uint32_t len = static_cast<uint32_t>(sl.s.size()) + 1;
        if (sl.inlineVal) {
            uint32_t packed = 0;
            for (uint32_t k = 0; k < sl.s.size(); ++k)
                packed |= static_cast<uint32_t>(static_cast<uint8_t>(sl.s[k])) << (8 * k);
            // NUL terminator already implied by zero-fill of remaining bytes.
            addInline(sl.tag, TY_ASCII, len, packed);
        } else {
            addOutline(sl.tag, TY_ASCII, len, sl.offset);
        }
    }
    if (hasIcc)  addOutline(T_ICC_PROFILE, TY_UNDEFINED,
                            static_cast<uint32_t>(meta.iccProfile.size()), iccOffset);
    if (hasExif) addOutline(T_EXIF_IFD, TY_LONG, 1, exifIfdOffset);

    // Sort by tag (TIFF requires ascending tag order within an IFD).
    std::sort(entries.begin(), entries.end(),
              [](const Entry& a, const Entry& b) { return a.tag < b.tag; });

    // Emit IFD0.
    putU16(outBytes, static_cast<uint16_t>(entries.size()));
    for (const auto& e : entries) {
        putU16(outBytes, e.tag);
        putU16(outBytes, e.type);
        putU32(outBytes, e.count);
        if (e.inlineVal) putU32(outBytes, e.inlineBytes);
        else             putU32(outBytes, e.valueOffset);
    }
    putU32(outBytes, 0);  // next IFD = none

    // Pad to bitsOffset and emit out-of-line value blobs in offset order.
    auto padTo = [&](uint32_t off) { while (outBytes.size() < off) outBytes.push_back(0); };

    padTo(bitsOffset);
    putU16(outBytes, 16); putU16(outBytes, 16); putU16(outBytes, 16);  // BitsPerSample
    padTo(sampleFmtOffset);
    putU16(outBytes, 1); putU16(outBytes, 1); putU16(outBytes, 1);     // SampleFormat=unsigned
    padTo(xresOffset);
    { uint32_t n, d; doubleToRational(meta.xResolution, n, d); putU32(outBytes, n); putU32(outBytes, d); }
    padTo(yresOffset);
    { uint32_t n, d; doubleToRational(meta.yResolution, n, d); putU32(outBytes, n); putU32(outBytes, d); }

    for (auto& sl : strLocs) {
        if (sl.inlineVal) continue;
        padTo(sl.offset);
        outBytes.insert(outBytes.end(), sl.s.begin(), sl.s.end());
        outBytes.push_back(0);  // NUL
    }

    if (hasIcc) {
        padTo(iccOffset);
        outBytes.insert(outBytes.end(), meta.iccProfile.begin(), meta.iccProfile.end());
    }

    if (hasExif) {
        padTo(exifIfdOffset);
        // EXIF sub-IFD: 4 inline entries, ascending tag order.
        putU16(outBytes, exifCount);
        // ExifVersion (36864) UNDEFINED[4] = "0230"
        putU16(outBytes, E_EXIF_VERSION); putU16(outBytes, TY_UNDEFINED); putU32(outBytes, 4);
        outBytes.push_back('0'); outBytes.push_back('2'); outBytes.push_back('3'); outBytes.push_back('0');
        // ColorSpace (40961) SHORT
        putU16(outBytes, E_COLOR_SPACE); putU16(outBytes, TY_SHORT); putU32(outBytes, 1);
        putU16(outBytes, meta.exifColorSpace); putU16(outBytes, 0);
        // PixelXDimension (40962) LONG
        putU16(outBytes, E_PIXEL_X_DIM); putU16(outBytes, TY_LONG); putU32(outBytes, 1);
        putU32(outBytes, static_cast<uint32_t>(width));
        // PixelYDimension (40963) LONG
        putU16(outBytes, E_PIXEL_Y_DIM); putU16(outBytes, TY_LONG); putU32(outBytes, 1);
        putU32(outBytes, static_cast<uint32_t>(height));
        putU32(outBytes, 0);  // next IFD = none
    }

    padTo(stripOffset);
    outBytes.insert(outBytes.end(), strip.begin(), strip.end());

    res.ok = true;
    res.bytesWritten = outBytes.size();
    return res;
}

TiffWriteResult writeTiff16ToFile(const uint16_t* rgb16, int width, int height,
                                  const TiffMetadata& meta, TiffCompression compression,
                                  const std::string& path) {
    std::vector<uint8_t> bytes;
    TiffWriteResult res = writeTiff16ToMemory(rgb16, width, height, meta, compression, bytes);
    if (!res.ok) return res;

    FILE* f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) {
        res.ok = false; res.error = "cannot open output path: " + path; return res;
    }
    size_t wrote = std::fwrite(bytes.data(), 1, bytes.size(), f);
    int closeErr = std::fclose(f);
    if (wrote != bytes.size() || closeErr != 0) {
        res.ok = false; res.error = "short write to " + path; return res;
    }
    res.bytesWritten = bytes.size();
    return res;
}

TiffWriteResult writeTiffFloatToFile(const float* rgbFloat, int width, int height,
                                     const TiffMetadata& meta, TiffCompression compression,
                                     const std::string& path) {
    TiffWriteResult res;
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
    return writeTiff16ToFile(q.data(), width, height, meta, compression, path);
}

}  // namespace spectrafilm
