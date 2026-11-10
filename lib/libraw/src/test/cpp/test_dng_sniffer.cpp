/*
 * Host unit test for the lib:libraw DNG compression sniffer and unpack-failure
 * classifier (raw_decoder.cpp :: spectrafilm::dngsniff).
 *
 * This compiles raw_decoder.cpp on the host WITHOUT LibRaw: the decoder guards
 * the LibRaw include with __has_include(<libraw/libraw.h>), so when LibRaw is
 * absent the file still compiles (SFRAW_HAVE_LIBRAW == 0) and the sniffer +
 * white-balance math are available. We only exercise the sniffer here; the
 * actual LibRaw decode path is not (and cannot be) run on the host.
 *
 * Because dngsniff is an internal namespace inside raw_decoder.cpp, this test is
 * #included AFTER raw_decoder.cpp via a tiny amalgamation TU; see the build
 * command in README.md ("Sniffer classification table"). To keep it standalone
 * we re-declare the functions we call through a thin shim instead.
 *
 * Build (from this directory):
 *   g++ -std=c++17 -I../../main/cpp \
 *       -DUSE_ZLIB=1 \
 *       -include ../../main/cpp/raw_decoder.cpp \
 *       test_dng_sniffer.cpp -o /tmp/test_dng_sniffer
 *   /tmp/test_dng_sniffer
 *
 * Synthesizes little-endian TIFF/DNG headers covering: uncompressed (1),
 * lossless-JPEG/LJ92 (7), deflate (8/0x80B2), lossy (0x884C), old-JPEG (6),
 * JPEG-XL (0xCD42), and a Pixel-style layout (JPEG preview in IFD0 + raw plane
 * in a SubIFD) to prove the preview is not mistaken for the raw compression.
 */
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

// raw_decoder.cpp is -include'd ahead of this TU, so spectrafilm::dngsniff and
// dngCompressionName are already defined. We just reference them.
namespace spectrafilm {
namespace dngsniff {
int compressionOf(const uint8_t* data, size_t len, bool* isDng);
int classifyUnpackFailure(const uint8_t* data, size_t len);
}  // namespace dngsniff
}  // namespace spectrafilm

using spectrafilm::dngsniff::classifyUnpackFailure;
using spectrafilm::dngsniff::compressionOf;
// The SFRAW_ERR_* DecodeStatus constants live inside namespace spectrafilm.
using namespace spectrafilm;

namespace {

struct Tag { uint16_t tag; uint16_t type; uint32_t count; uint32_t value; };

void put16(std::vector<uint8_t>& b, size_t o, uint16_t v) {
    b[o] = v & 0xff; b[o + 1] = (v >> 8) & 0xff;  // little-endian
}
void put32(std::vector<uint8_t>& b, size_t o, uint32_t v) {
    b[o] = v & 0xff; b[o + 1] = (v >> 8) & 0xff;
    b[o + 2] = (v >> 16) & 0xff; b[o + 3] = (v >> 24) & 0xff;
}

// Single-IFD little-endian TIFF/DNG, IFD0 at offset 8.
std::vector<uint8_t> buildSingle(const std::vector<Tag>& tags) {
    std::vector<uint8_t> b(8 + 2 + tags.size() * 12 + 4, 0);
    b[0] = 'I'; b[1] = 'I'; put16(b, 2, 42); put32(b, 4, 8);
    put16(b, 8, (uint16_t)tags.size());
    size_t e = 10;
    for (auto& t : tags) {
        put16(b, e, t.tag); put16(b, e + 2, t.type);
        put32(b, e + 4, t.count); put32(b, e + 8, t.value);
        e += 12;
    }
    put32(b, e, 0);
    return b;
}

// Pixel-style: IFD0 = preview (NewSubFileType=1, JPEG), SubIFD = raw plane
// (NewSubFileType=0, given raw compression). Both carry a DNGVersion tag so the
// sniffer recognizes it as a DNG.
std::vector<uint8_t> buildPixel(int previewComp, int rawComp,
                                uint32_t previewPx, uint32_t rawPx,
                                uint32_t rawSampleFormat = 1) {
    const size_t ifd0Off = 8;
    const int ifd0N = 6;  // NewSubFileType, Width, Length, Compression, DNGVersion, SubIFDs
    const size_t ifd0Size = 2 + ifd0N * 12 + 4;
    const size_t subOff = ifd0Off + ifd0Size;
    const int subN = 6;   // plus primary raw SampleFormat
    const size_t subSize = 2 + subN * 12 + 4;
    std::vector<uint8_t> b(subOff + subSize, 0);
    b[0] = 'I'; b[1] = 'I'; put16(b, 2, 42); put32(b, 4, (uint32_t)ifd0Off);

    size_t e = 0;
    auto emit = [&](uint16_t tag, uint16_t type, uint32_t count, uint32_t val) {
        put16(b, e, tag); put16(b, e + 2, type);
        put32(b, e + 4, count); put32(b, e + 8, val); e += 12;
    };

    put16(b, ifd0Off, ifd0N);
    e = ifd0Off + 2;
    emit(0x00FE, 4, 1, 1);                       // NewSubFileType = 1 (preview)
    emit(0x0100, 4, 1, previewPx);               // ImageWidth
    emit(0x0101, 4, 1, 1);                        // ImageLength
    emit(0x0103, 3, 1, (uint32_t)previewComp);    // Compression
    emit(0xC612, 1, 4, 0x00000401);               // DNGVersion (marks DNG)
    emit(0x014A, 4, 1, (uint32_t)subOff);         // SubIFDs -> raw IFD
    put32(b, e, 0);

    put16(b, subOff, subN);
    e = subOff + 2;
    emit(0x00FE, 4, 1, 0);                        // NewSubFileType = 0 (primary)
    emit(0x0100, 4, 1, rawPx);                    // ImageWidth
    emit(0x0101, 4, 1, 1);                        // ImageLength
    emit(0x0103, 3, 1, (uint32_t)rawComp);        // Compression
    emit(0x0153, 3, 1, rawSampleFormat);           // SampleFormat
    emit(0xC612, 1, 4, 0x00000401);               // DNGVersion
    put32(b, e, 0);
    return b;
}

// Attacker-controlled next-IFD chain. Only the last IFD names a DNG/compression;
// a bounded diagnostic walker must stop before it instead of recursing forever.
std::vector<uint8_t> buildLongNextIfdChain(size_t ifdCount) {
    constexpr size_t kEntriesPerIfd = 2;
    constexpr size_t kIfdBytes = 2 + kEntriesPerIfd * 12 + 4;
    std::vector<uint8_t> b(8 + ifdCount * kIfdBytes, 0);
    b[0] = 'I'; b[1] = 'I'; put16(b, 2, 42); put32(b, 4, 8);
    for (size_t i = 0; i < ifdCount; ++i) {
        const size_t off = 8 + i * kIfdBytes;
        put16(b, off, static_cast<uint16_t>(kEntriesPerIfd));
        size_t entry = off + 2;
        if (i + 1 == ifdCount) {
            put16(b, entry, 0xC612); put16(b, entry + 2, 1);
            put32(b, entry + 4, 4); put32(b, entry + 8, 0x00000401);
            entry += 12;
            put16(b, entry, 0x0103); put16(b, entry + 2, 3);
            put32(b, entry + 4, 1); put32(b, entry + 8, 0x884C);
        } else {
            put16(b, entry, 0x0001); put16(b, entry + 2, 4);
            put32(b, entry + 4, 1); put32(b, entry + 8, 0);
            entry += 12;
            put16(b, entry, 0x0002); put16(b, entry + 2, 4);
            put32(b, entry + 4, 1); put32(b, entry + 8, 0);
        }
        const uint32_t next = (i + 1 < ifdCount)
            ? static_cast<uint32_t>(off + kIfdBytes) : 0U;
        put32(b, off + 2 + kEntriesPerIfd * 12, next);
    }
    return b;
}

// General little-endian TIFF: IFD 0 is the root (IFD0), the others are its
// SubIFDs in order. An entry whose payload is non-empty stores it out of line.
struct Entry {
    uint16_t tag;
    uint16_t type;
    uint32_t count;
    uint32_t value;
    std::vector<uint8_t> payload;
};
std::vector<uint8_t> buildTiff(std::vector<std::vector<Entry>> ifds) {
    std::vector<size_t> ifdAt(ifds.size());
    size_t at = 8;
    for (size_t i = 0; i < ifds.size(); ++i) {
        ifdAt[i] = at;
        at += 2 + ifds[i].size() * 12 + 4;
    }
    std::vector<uint8_t> b(at, 0);
    b[0] = 'I'; b[1] = 'I'; put16(b, 2, 42); put32(b, 4, 8);
    for (size_t i = 0; i < ifds.size(); ++i) {
        put16(b, ifdAt[i], static_cast<uint16_t>(ifds[i].size()));
        size_t e = ifdAt[i] + 2;
        for (const Entry& t : ifds[i]) {
            uint32_t value = t.value;
            if (t.tag == 0x014A) {  // SubIFDs: offsets of IFDs 1..n-1
                std::vector<uint8_t> offs(4 * (ifds.size() - 1), 0);
                for (size_t s = 1; s < ifds.size(); ++s) put32(offs, 4 * (s - 1), (uint32_t)ifdAt[s]);
                value = static_cast<uint32_t>(b.size());
                b.insert(b.end(), offs.begin(), offs.end());
            } else if (!t.payload.empty()) {
                value = static_cast<uint32_t>(b.size());
                b.insert(b.end(), t.payload.begin(), t.payload.end());
            }
            put16(b, e, t.tag); put16(b, e + 2, t.type);
            put32(b, e + 4, t.tag == 0x014A ? (uint32_t)(ifds.size() - 1) : t.count);
            put32(b, e + 8, value);
            e += 12;
        }
        put32(b, e, 0);
    }
    return b;
}
std::vector<uint8_t> srationalMatrix() {
    std::vector<uint8_t> m(9 * 8, 0);
    for (int i = 0; i < 9; ++i) {
        put32(m, i * 8, (i % 4 == 0) ? 10000U : static_cast<uint32_t>(-1000));  // diagonal-dominant
        put32(m, i * 8 + 4, 10000U);
    }
    return m;
}
std::vector<uint8_t> shorts3(uint16_t v) {
    std::vector<uint8_t> s(6, 0);
    for (int i = 0; i < 3; ++i) put16(s, i * 2, v);
    return s;
}
// A Lightroom merged-panorama layout: IFD0 is the RGB preview and carries the
// DNG colour tags, SubIFD 1 the LinearRaw image, SubIFD 2 its transparency mask
// at the same size. `rootColour` drops the colour tags from IFD0; `rawColour`
// gives the RAW SubIFD a ColorMatrix1 of its own; `maskType` is the mask's
// NewSubFileType.
std::vector<uint8_t> buildPanorama(bool rootColour, bool rawColour, uint32_t maskType) {
    std::vector<Entry> ifd0 = {
        {0x00FE, 4, 1, 1, {}},  {0x0100, 4, 1, 256, {}}, {0x0101, 4, 1, 98, {}},
        {0x0103, 3, 1, 1, {}},  {0x0106, 3, 1, 2, {}},   {0x014A, 4, 0, 0, {}},
        {0xC612, 1, 4, 0x00000401, {}},
    };
    if (rootColour) {
        ifd0.push_back({0xC621, 10, 9, 0, srationalMatrix()});
        ifd0.push_back({0xC622, 10, 9, 0, srationalMatrix()});
        ifd0.push_back({0xC65A, 3, 1, 17, {}});
        ifd0.push_back({0xC65B, 3, 1, 21, {}});
    }
    std::vector<Entry> raw = {
        {0x00FE, 4, 1, 0, {}}, {0x0100, 4, 1, 10103, {}}, {0x0101, 4, 1, 4080, {}},
        {0x0102, 3, 3, 0, shorts3(16)}, {0x0103, 3, 1, 7, {}}, {0x0106, 3, 1, 34892, {}},
        {0x0115, 3, 1, 3, {}}, {0x011C, 3, 1, 1, {}},
    };
    if (rawColour) raw.push_back({0xC621, 10, 9, 0, srationalMatrix()});
    std::vector<Entry> mask = {
        {0x00FE, 4, 1, maskType, {}}, {0x0100, 4, 1, 10103, {}}, {0x0101, 4, 1, 4080, {}},
        {0x0102, 3, 1, 8, {}}, {0x0103, 3, 1, 8, {}}, {0x0106, 3, 1, 4, {}},
    };
    return buildTiff({ifd0, raw, mask});
}

int failures = 0;
void check(const char* name, int got, int want) {
    if (got != want) {
        printf("FAIL %-38s got=%d want=%d\n", name, got, want);
        failures++;
    } else {
        printf("ok   %-38s = %d\n", name, got);
    }
}

}  // namespace

int main() {
    // --- compressionOf: single-IFD primaries ---
    { auto b = buildSingle({{0x0103, 3, 1, 1}}); bool d;
      check("single/uncompressed", compressionOf(b.data(), b.size(), &d), 1); }
    { auto b = buildSingle({{0x0103, 3, 1, 7}}); bool d;
      check("single/lossless-jpeg(LJ92)", compressionOf(b.data(), b.size(), &d), 7); }
    { auto b = buildSingle({{0x0103, 3, 1, 8}}); bool d;
      check("single/deflate", compressionOf(b.data(), b.size(), &d), 8); }
    { auto b = buildSingle({{0x0103, 3, 1, 0x80B2}}); bool d;
      check("single/adobe-deflate", compressionOf(b.data(), b.size(), &d), 0x80B2); }
    { auto b = buildSingle({{0x0103, 3, 1, 0x884C}}); bool d;
      check("single/lossy-jpeg", compressionOf(b.data(), b.size(), &d), 0x884C); }
    { auto b = buildSingle({{0x0103, 3, 1, 6}}); bool d;
      check("single/old-jpeg", compressionOf(b.data(), b.size(), &d), 6); }
    { auto b = buildSingle({{0x0103, 3, 1, 0xCD42}}); bool d;
      check("single/jpeg-xl", compressionOf(b.data(), b.size(), &d), 0xCD42); }

    // --- compressionOf: Pixel-style preview must not win; raw plane wins ---
    { auto b = buildPixel(7, 7, 4080, 4080); bool d;
      check("pixel/LJ92 preview + LJ92 raw", compressionOf(b.data(), b.size(), &d), 7); }
    { auto b = buildPixel(0x884C, 1, 4080, 4080); bool d;
      check("pixel/JPEG preview, UNCOMP raw", compressionOf(b.data(), b.size(), &d), 1); }
    { auto b = buildPixel(0x884C, 7, 4080, 4080); bool d;
      check("pixel/JPEG preview, LJ92 raw", compressionOf(b.data(), b.size(), &d), 7); }
    { auto b = buildPixel(6, 8, 4080, 4080); bool d;
      check("pixel/oldJPEG preview, deflate raw", compressionOf(b.data(), b.size(), &d), 8); }
    { auto b = buildPixel(7, 0x884C, 4080, 4080); bool d;
      check("pixel/lossy raw plane", compressionOf(b.data(), b.size(), &d), 0x884C); }

    // --- compressionOf marks DNG via DNGVersion ---
    { auto b = buildPixel(7, 7, 4080, 4080); bool d = false;
      compressionOf(b.data(), b.size(), &d);
      check("pixel/isDng flagged", d ? 1 : 0, 1); }
    { auto b = buildSingle({{0x0103, 3, 1, 1}}); bool d = true;
      compressionOf(b.data(), b.size(), &d);
      check("plain-tiff/isDng false", d ? 1 : 0, 0); }

    // --- compressionOf: junk / non-DNG inputs -> unknown (-1) ---
    { uint8_t z[16] = {0}; bool d;
      check("junk/zeros", compressionOf(z, 16, &d), -1); }
    { const char* s = "not a tiff at all"; bool d;
      check("junk/text", compressionOf((const uint8_t*)s, strlen(s), &d), -1); }
    { bool d; check("junk/too-short", compressionOf((const uint8_t*)"II", 2, &d), -1); }
    { auto b = buildLongNextIfdChain(40); bool d = true;
      check("hostile/next-IFD work budget", compressionOf(b.data(), b.size(), &d), -1);
      check("hostile/budget stops late DNG tag", d ? 1 : 0, 0); }
    { std::vector<uint8_t> b(8, 0); b[0] = 'I'; b[1] = 'I';
      put16(b, 2, 42); put32(b, 4, UINT32_MAX); bool d = true;
      check("hostile/wrapping IFD offset", compressionOf(b.data(), b.size(), &d), -1);
      check("hostile/wrapping offset not DNG", d ? 1 : 0, 0); }

    // --- classifyUnpackFailure: only fallback codecs flagged ---
    // Host CMake defines USE_ZLIB to match the shipping decoder wrapper.
    { auto b = buildPixel(7, 0x884C, 4080, 4080);
      check("classify/lossy -> LOSSY_JPEG_DNG",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_LOSSY_JPEG_DNG); }
    { auto b = buildPixel(7, 6, 4080, 4080);
      check("classify/old-jpeg -> LOSSY_JPEG_DNG",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_LOSSY_JPEG_DNG); }
    { auto b = buildPixel(7, 0xCD42, 4080, 4080);
      check("classify/jpeg-xl -> JPEGXL_DNG",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_JPEGXL_DNG); }
    // LJ92 / uncompressed reaching the classifier means a genuine data error,
    // NOT an unsupported codec -> must stay generic UNPACK (no false fallback).
    { auto b = buildPixel(7, 7, 4080, 4080);
      check("classify/LJ92 -> UNPACK (no false fallback)",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_UNPACK); }
    { auto b = buildPixel(7, 1, 4080, 4080);
      check("classify/uncompressed -> UNPACK",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_UNPACK); }
    { auto b = buildPixel(7, 8, 4080, 4080, 1);
      check("classify/integer deflate -> DEFLATE_DNG",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_DEFLATE_DNG); }
    { auto b = buildPixel(7, 8, 4080, 4080, 3);
      check("classify/float deflate -> DEFLATE_DNG",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_DEFLATE_DNG); }
    { auto b = buildPixel(7, 0x80B2, 4080, 4080, 1);
      check("classify/integer 0x80B2 -> DEFLATE_DNG",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_DEFLATE_DNG); }
    { auto b = buildPixel(7, 0x80B2, 4080, 4080, 3);
      check("classify/float 0x80B2 -> DEFLATE_DNG",
            classifyUnpackFailure(b.data(), b.size()), SFRAW_ERR_DEFLATE_DNG); }
    { uint8_t z[16] = {0};
      check("classify/non-dng -> UNPACK",
            classifyUnpackFailure(z, 16), SFRAW_ERR_UNPACK); }

    // --- primaryImageInfoOf: a SubIFD LinearRaw image (Lightroom merged
    // panorama) takes the DNG colour tags from IFD0, and its transparency mask
    // is not a second RAW candidate ---
    using spectrafilm::dngsniff::primaryImageInfoOf;
    { auto b = buildPanorama(true, false, 4);
      const auto info = primaryImageInfoOf(b.data(), b.size());
      check("panorama/preflight safe", info.unsafeForLibRawOpen ? 1 : 0, 0);
      check("panorama/is DNG", info.isDng ? 1 : 0, 1);
      check("panorama/LinearRaw selected", info.photometric, 34892);
      check("panorama/RAW width", static_cast<int>(info.width), 10103);
      const auto bound = primaryImageInfoOf(b.data(), b.size(), 10103, 4080);
      check("panorama/bound to decoded size", bound.unsafeForLibRawOpen ? 1 : 0, 0);
      check("panorama/bound LinearRaw", bound.photometric, 34892); }
    { auto b = buildPanorama(false, false, 4);
      check("panorama/no colour tags anywhere -> unsafe",
            primaryImageInfoOf(b.data(), b.size()).unsafeForLibRawOpen ? 1 : 0, 1); }
    { auto b = buildPanorama(true, true, 4);
      check("panorama/RAW IFD with partial own colour -> unsafe",
            primaryImageInfoOf(b.data(), b.size()).unsafeForLibRawOpen ? 1 : 0, 1); }
    { auto b = buildPanorama(true, false, 0);
      check("panorama/unmarked same-size plane -> unsafe",
            primaryImageInfoOf(b.data(), b.size()).unsafeForLibRawOpen ? 1 : 0, 1); }

    printf("\n%s (%d failures)\n",
           failures ? "TESTS FAILED" : "ALL TESTS PASSED", failures);
    return failures ? 1 : 0;
}
