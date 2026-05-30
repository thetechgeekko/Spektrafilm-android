/*
 * Spektrafilm for Android — host unit test for the 16-bit baseline TIFF writer.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * Pure host test (no Android / no Gradle). Writes a small known 16-bit RGB buffer
 * to /tmp with an embedded ICC blob + metadata, then parses the file back from
 * scratch (a minimal independent TIFF/IFD reader implemented here) and asserts:
 *   - TIFF magic + little-endian byte order ("II", 42)
 *   - ImageWidth / ImageLength match
 *   - BitsPerSample = {16,16,16}
 *   - SamplesPerPixel = 3
 *   - PhotometricInterpretation = 2 (RGB)
 *   - Compression matches the requested scheme
 *   - ICCProfile (34675) tag present and bytes round-trip exactly
 *   - Software (305) string present
 *   - EXIF sub-IFD (34665) present with ColorSpace + pixel dims
 *   - every pixel sample round-trips bit-exact (both uncompressed and PackBits)
 *
 * Build:
 *   g++ -std=c++17 -O2 -I.. test_tiff_writer.cpp ../tiff_writer.cpp -o /tmp/test_tiff_writer
 *   /tmp/test_tiff_writer
 */
#include "tiff_writer.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>

using namespace spectrafilm;

namespace {

int g_failures = 0;
#define CHECK(cond, msg)                                                       \
    do {                                                                       \
        if (!(cond)) { std::printf("  FAIL: %s\n", msg); g_failures++; }       \
        else         { std::printf("  ok:   %s\n", msg); }                     \
    } while (0)

// ---- minimal little-endian TIFF reader (independent of the writer) ---------
uint16_t rdU16(const std::vector<uint8_t>& b, size_t off) {
    return static_cast<uint16_t>(b[off]) | (static_cast<uint16_t>(b[off + 1]) << 8);
}
uint32_t rdU32(const std::vector<uint8_t>& b, size_t off) {
    return static_cast<uint32_t>(b[off]) | (static_cast<uint32_t>(b[off + 1]) << 8) |
           (static_cast<uint32_t>(b[off + 2]) << 16) | (static_cast<uint32_t>(b[off + 3]) << 24);
}

struct IfdEntry {
    uint16_t type;
    uint32_t count;
    uint32_t valueOrOffset;  // raw 4 bytes from the entry
    size_t entryOffset;      // where this entry's value field lives in the file
};

size_t typeSize(uint16_t t) {
    switch (t) {
        case 1: case 2: case 7: return 1;  // BYTE/ASCII/UNDEFINED
        case 3: return 2;                  // SHORT
        case 4: return 4;                  // LONG
        case 5: return 8;                  // RATIONAL
        default: return 1;
    }
}

// Parse an IFD at `ifdOff` into a tag->entry map; returns next-IFD offset.
uint32_t parseIfd(const std::vector<uint8_t>& b, size_t ifdOff,
                  std::map<uint16_t, IfdEntry>& out) {
    uint16_t n = rdU16(b, ifdOff);
    size_t p = ifdOff + 2;
    for (uint16_t i = 0; i < n; ++i) {
        uint16_t tag = rdU16(b, p);
        IfdEntry e;
        e.type = rdU16(b, p + 2);
        e.count = rdU32(b, p + 4);
        e.valueOrOffset = rdU32(b, p + 8);
        e.entryOffset = p + 8;
        out[tag] = e;
        p += 12;
    }
    return rdU32(b, p);
}

// Read the byte offset where an entry's value data lives (inline if it fits in 4).
size_t valueDataOffset(const IfdEntry& e) {
    size_t total = typeSize(e.type) * e.count;
    return (total <= 4) ? e.entryOffset : e.valueOrOffset;
}

// Read the i-th SHORT of an entry.
uint16_t readShort(const std::vector<uint8_t>& b, const IfdEntry& e, uint32_t i) {
    return rdU16(b, valueDataOffset(e) + i * 2);
}
// Read a scalar LONG/SHORT value.
uint32_t readScalar(const std::vector<uint8_t>& b, const IfdEntry& e) {
    if (e.type == 3) return rdU16(b, valueDataOffset(e));
    return rdU32(b, valueDataOffset(e));
}

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    out.resize(static_cast<size_t>(sz));
    size_t got = std::fread(out.data(), 1, out.size(), f);
    std::fclose(f);
    return got == out.size();
}

// Decode the image strip back to interleaved uint16 RGB.
void decodeStrip(const std::vector<uint8_t>& file, uint16_t compression,
                 uint32_t stripOff, uint32_t stripBytes, uint64_t expectSamples,
                 std::vector<uint16_t>& out) {
    std::vector<uint8_t> raw;
    if (compression == 32773) {  // PackBits
        size_t i = stripOff;
        size_t end = stripOff + stripBytes;
        while (i < end) {
            int8_t n = static_cast<int8_t>(file[i++]);
            if (n >= 0) {
                int cnt = n + 1;
                for (int k = 0; k < cnt && i < end; ++k) raw.push_back(file[i++]);
            } else if (n != -128) {
                int cnt = 1 - n;
                uint8_t v = file[i++];
                for (int k = 0; k < cnt; ++k) raw.push_back(v);
            }
        }
    } else {
        raw.assign(file.begin() + stripOff, file.begin() + stripOff + stripBytes);
    }
    out.resize(static_cast<size_t>(expectSamples));
    for (uint64_t s = 0; s < expectSamples; ++s)
        out[s] = static_cast<uint16_t>(raw[s * 2]) | (static_cast<uint16_t>(raw[s * 2 + 1]) << 8);
}

// Run the full write+readback+assert cycle for one compression mode.
void runCase(const char* label, TiffCompression comp, const std::vector<uint8_t>& icc) {
    std::printf("[%s]\n", label);

    const int W = 5, H = 3;
    std::vector<uint16_t> pixels(static_cast<size_t>(W) * H * 3);
    // Deterministic gradient with full 16-bit range exercised.
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            size_t base = (static_cast<size_t>(y) * W + x) * 3;
            pixels[base + 0] = static_cast<uint16_t>((x * 65535) / (W - 1));        // R ramp
            pixels[base + 1] = static_cast<uint16_t>((y * 65535) / (H - 1));        // G ramp
            pixels[base + 2] = static_cast<uint16_t>(((x + y) & 1) ? 0xFFFF : 0x0001); // B run-friendly
        }
    }

    TiffMetadata meta;
    meta.software = "Spektrafilm-test";
    meta.dateTime = "2026:05:29 12:00:00";
    meta.xResolution = 300.0;
    meta.yResolution = 300.0;
    meta.exifColorSpace = 0xFFFF;
    meta.iccProfile = icc;
    meta.writeExifIfd = true;

    std::string path = std::string("/tmp/sf_tiff_test_") + label + ".tiff";
    TiffWriteResult wr = writeTiff16ToFile(pixels.data(), W, H, meta, comp, path);
    CHECK(wr.ok, "writer returned ok");
    if (!wr.ok) { std::printf("    error: %s\n", wr.error.c_str()); return; }

    std::vector<uint8_t> file;
    CHECK(readFile(path, file), "file readable from disk");
    CHECK(file.size() == wr.bytesWritten, "bytesWritten matches file size");
    if (file.size() < 8) return;

    CHECK(file[0] == 'I' && file[1] == 'I', "byte order = little-endian (II)");
    CHECK(rdU16(file, 2) == 42, "TIFF magic = 42");
    uint32_t ifd0 = rdU32(file, 4);
    CHECK(ifd0 >= 8 && ifd0 < file.size(), "IFD0 offset in range");

    std::map<uint16_t, IfdEntry> ifd;
    parseIfd(file, ifd0, ifd);

    CHECK(ifd.count(256) && readScalar(file, ifd[256]) == (uint32_t)W, "ImageWidth = 5");
    CHECK(ifd.count(257) && readScalar(file, ifd[257]) == (uint32_t)H, "ImageLength = 3");

    bool bps16 = ifd.count(258) && ifd[258].count == 3 &&
                 readShort(file, ifd[258], 0) == 16 &&
                 readShort(file, ifd[258], 1) == 16 &&
                 readShort(file, ifd[258], 2) == 16;
    CHECK(bps16, "BitsPerSample = {16,16,16}");

    CHECK(ifd.count(277) && readScalar(file, ifd[277]) == 3, "SamplesPerPixel = 3");
    CHECK(ifd.count(262) && readScalar(file, ifd[262]) == 2, "Photometric = RGB (2)");
    CHECK(ifd.count(284) && readScalar(file, ifd[284]) == 1, "PlanarConfig = chunky (1)");

    bool sf = ifd.count(339) && ifd[339].count == 3 && readShort(file, ifd[339], 0) == 1;
    CHECK(sf, "SampleFormat = unsigned int");

    uint16_t comptag = ifd.count(259) ? (uint16_t)readScalar(file, ifd[259]) : 0;
    uint16_t want = (comp == TiffCompression::PackBits) ? 32773 : 1;
    // PackBits may have fallen back to none on incompressible data; accept either
    // when PackBits was requested.
    if (comp == TiffCompression::PackBits)
        CHECK(comptag == 32773 || comptag == 1, "Compression = PackBits or none-fallback");
    else
        CHECK(comptag == want, "Compression = none (1)");

    // Software string round-trip.
    bool sw = false;
    if (ifd.count(305)) {
        size_t off = valueDataOffset(ifd[305]);
        std::string s(reinterpret_cast<const char*>(&file[off]));
        sw = (s == "Spektrafilm-test");
    }
    CHECK(sw, "Software tag = 'Spektrafilm-test'");

    // ICC presence + byte round-trip.
    if (!icc.empty()) {
        bool iccOk = false;
        if (ifd.count(34675) && ifd[34675].count == icc.size()) {
            size_t off = valueDataOffset(ifd[34675]);
            iccOk = std::memcmp(&file[off], icc.data(), icc.size()) == 0;
        }
        CHECK(iccOk, "ICCProfile (34675) present + bytes round-trip");
    } else {
        CHECK(ifd.count(34675) == 0, "no ICCProfile tag when none provided");
    }

    // EXIF sub-IFD.
    if (ifd.count(34665)) {
        uint32_t exifOff = readScalar(file, ifd[34665]);
        std::map<uint16_t, IfdEntry> exif;
        parseIfd(file, exifOff, exif);
        CHECK(exif.count(40961) && readScalar(file, exif[40961]) == 0xFFFF,
              "EXIF ColorSpace = Uncalibrated");
        CHECK(exif.count(40962) && readScalar(file, exif[40962]) == (uint32_t)W,
              "EXIF PixelXDimension = width");
        CHECK(exif.count(40963) && readScalar(file, exif[40963]) == (uint32_t)H,
              "EXIF PixelYDimension = height");
        CHECK(exif.count(36864) && exif[36864].count == 4, "EXIF ExifVersion present");
    } else {
        CHECK(false, "EXIF sub-IFD (34665) present");
    }

    // Pixel round-trip.
    uint32_t stripOff = readScalar(file, ifd[273]);
    uint32_t stripBytes = readScalar(file, ifd[279]);
    std::vector<uint16_t> back;
    decodeStrip(file, comptag, stripOff, stripBytes,
                (uint64_t)W * H * 3, back);
    bool pixOk = (back.size() == pixels.size()) &&
                 std::memcmp(back.data(), pixels.data(), pixels.size() * 2) == 0;
    CHECK(pixOk, "all pixel samples round-trip bit-exact");

    std::printf("    file: %s (%zu bytes)\n", path.c_str(), wr.bytesWritten);
}

}  // namespace

int main() {
    // A fake but structurally-irrelevant ICC blob (odd length to exercise word-align
    // padding of the following strip). Round-trip must be byte-exact regardless.
    std::vector<uint8_t> icc;
    for (int i = 0; i < 137; ++i) icc.push_back(static_cast<uint8_t>(i * 7 + 3));

    runCase("uncompressed", TiffCompression::None, icc);
    runCase("packbits", TiffCompression::PackBits, icc);
    runCase("no_icc", TiffCompression::None, {});

    std::printf("\n%s (%d failure%s)\n",
                g_failures == 0 ? "PASS" : "FAIL",
                g_failures, g_failures == 1 ? "" : "s");
    return g_failures == 0 ? 0 : 1;
}
