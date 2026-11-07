/*
 * Spektrafilm Android -- embedded RAW preview extraction contract.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * Covers raw_preview.cpp and pread_datastream.h:
 *  1. PreadDatastream is differentially equivalent to LibRaw_buffer_datastream
 *     (read/seek/tell/eof/get_char/gets/scanf_one) over random operation
 *     sequences, including a cache-sized stride and reads straddling EOF.
 *  2. open_datastream(PreadDatastream) identifies a project-owned DNG exactly as
 *     open_buffer() over the same bytes does.
 *  3. Candidate selection: smallest sharp-enough JPEG, else largest; non-JPEG,
 *     empty, oversized and out-of-file entries are never chosen.
 *  4. extractEmbeddedPreview(): the DNG's JPEG preview comes back byte-exact,
 *     a payload without an SOI marker is refused, and pipes, garbage and bad
 *     descriptors fail with typed statuses instead of crashing.
 *  5. Optional: every file in $SFRAW_PREVIEW_SAMPLES opens identically through
 *     both streams (real camera files are not committed).
 */
#include "pread_datastream.h"
#include "raw_preview.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <memory>
#include <random>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

using namespace spectrafilm;

namespace {

int failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                      \
        }                                                                    \
    } while (0)

class TempFile {
 public:
    explicit TempFile(const std::vector<uint8_t>& bytes) {
        char name[] = "/tmp/sfraw-preview-XXXXXX";
        fd_ = ::mkstemp(name);
        if (fd_ < 0) {
            std::perror("mkstemp");
            std::abort();
        }
        ::unlink(name);
        size_t done = 0;
        while (done < bytes.size()) {
            const ssize_t n = ::write(fd_, bytes.data() + done, bytes.size() - done);
            if (n <= 0) std::abort();
            done += static_cast<size_t>(n);
        }
    }
    ~TempFile() { ::close(fd_); }
    int fd() const { return fd_; }

 private:
    int fd_ = -1;
};

// --- minimal little-endian TIFF/DNG writer ---------------------------------

struct Entry {
    uint16_t tag;
    uint16_t type;  // 1 BYTE, 2 ASCII, 3 SHORT, 4 LONG, 5 RATIONAL, 10 SRATIONAL
    uint32_t count;
    std::vector<uint8_t> payload;  // already little-endian
};

void put16(std::vector<uint8_t>& v, uint16_t x) {
    v.push_back(static_cast<uint8_t>(x));
    v.push_back(static_cast<uint8_t>(x >> 8));
}
void put32(std::vector<uint8_t>& v, uint32_t x) {
    for (int i = 0; i < 4; ++i) v.push_back(static_cast<uint8_t>(x >> (8 * i)));
}
void set32(std::vector<uint8_t>& v, size_t at, uint32_t x) {
    for (int i = 0; i < 4; ++i) v[at + i] = static_cast<uint8_t>(x >> (8 * i));
}

Entry shortEntry(uint16_t tag, std::vector<uint16_t> values) {
    Entry e{tag, 3, static_cast<uint32_t>(values.size()), {}};
    for (auto x : values) put16(e.payload, x);
    return e;
}
Entry longEntry(uint16_t tag, std::vector<uint32_t> values) {
    Entry e{tag, 4, static_cast<uint32_t>(values.size()), {}};
    for (auto x : values) put32(e.payload, x);
    return e;
}
Entry byteEntry(uint16_t tag, std::vector<uint8_t> values) {
    return Entry{tag, 1, static_cast<uint32_t>(values.size()), values};
}
Entry asciiEntry(uint16_t tag, const std::string& s) {
    Entry e{tag, 2, static_cast<uint32_t>(s.size() + 1), {}};
    e.payload.assign(s.begin(), s.end());
    e.payload.push_back(0);
    return e;
}
Entry srationalEntry(uint16_t tag, std::vector<int32_t> nums) {
    Entry e{tag, 10, static_cast<uint32_t>(nums.size()), {}};
    for (auto n : nums) {
        put32(e.payload, static_cast<uint32_t>(n));
        put32(e.payload, 10000);
    }
    return e;
}

// Writes an IFD at the end of `out` (entries sorted by the caller); payloads
// over four bytes go after the IFD. Returns the IFD offset. `patches` receives
// (absolute position of the value field, tag) for LONG fields the caller fixes.
uint32_t writeIfd(std::vector<uint8_t>& out, const std::vector<Entry>& entries,
                  std::vector<std::pair<size_t, uint16_t>>* valueFields) {
    if (out.size() & 1) out.push_back(0);
    const uint32_t ifd = static_cast<uint32_t>(out.size());
    put16(out, static_cast<uint16_t>(entries.size()));
    std::vector<std::pair<size_t, const Entry*>> deferred;
    for (const auto& e : entries) {
        put16(out, e.tag);
        put16(out, e.type);
        put32(out, e.count);
        const size_t field = out.size();
        if (valueFields) valueFields->push_back({field, e.tag});
        if (e.payload.size() <= 4) {
            std::vector<uint8_t> inl = e.payload;
            inl.resize(4, 0);
            out.insert(out.end(), inl.begin(), inl.end());
        } else {
            put32(out, 0);
            deferred.push_back({field, &e});
        }
    }
    put32(out, 0);  // no next IFD
    for (auto& [field, e] : deferred) {
        if (out.size() & 1) out.push_back(0);
        set32(out, field, static_cast<uint32_t>(out.size()));
        out.insert(out.end(), e->payload.begin(), e->payload.end());
    }
    return ifd;
}

std::vector<uint8_t> fakeJpeg(size_t bodyBytes, uint8_t seed) {
    std::vector<uint8_t> j = {0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0};
    for (size_t i = 0; i < bodyBytes; ++i) j.push_back(static_cast<uint8_t>(seed + i * 7));
    j.push_back(0xFF);
    j.push_back(0xD9);
    return j;
}

// A DNG whose IFD0 is a 96x64 JPEG preview and whose SubIFD is a 32x32
// uncompressed 16-bit RGGB CFA frame. `previewBytes` replaces the preview
// payload verbatim (so a test can plant a non-JPEG).
std::vector<uint8_t> makePreviewDng(const std::vector<uint8_t>& previewBytes,
                                    uint16_t orientation) {
    constexpr uint32_t kRawW = 32, kRawH = 32;
    std::vector<uint8_t> out = {'I', 'I', 42, 0, 0, 0, 0, 0};
    std::vector<uint8_t> rawStrip;
    for (uint32_t y = 0; y < kRawH; ++y)
        for (uint32_t x = 0; x < kRawW; ++x) put16(rawStrip, static_cast<uint16_t>(1024 + 37 * x + 11 * y));

    // Raw SubIFD first so IFD0 can point at it.
    std::vector<std::pair<size_t, uint16_t>> rawFields;
    const uint32_t rawIfd = writeIfd(out, {
        longEntry(254, {0}),
        longEntry(256, {kRawW}),
        longEntry(257, {kRawH}),
        shortEntry(258, {16}),
        shortEntry(259, {1}),
        shortEntry(262, {32803}),
        longEntry(273, {0}),
        shortEntry(277, {1}),
        longEntry(278, {kRawH}),
        longEntry(279, {static_cast<uint32_t>(rawStrip.size())}),
        shortEntry(284, {1}),
        shortEntry(33421, {2, 2}),
        byteEntry(33422, {0, 1, 1, 2}),
        longEntry(50717, {65535}),
    }, &rawFields);

    std::vector<std::pair<size_t, uint16_t>> mainFields;
    const uint32_t mainIfd = writeIfd(out, {
        longEntry(254, {1}),
        longEntry(256, {96}),
        longEntry(257, {64}),
        shortEntry(258, {8, 8, 8}),
        shortEntry(259, {7}),
        shortEntry(262, {6}),
        asciiEntry(271, "Spektrafilm"),
        asciiEntry(272, "Preview Fixture"),
        longEntry(273, {0}),
        shortEntry(274, {orientation}),
        shortEntry(277, {3}),
        longEntry(278, {64}),
        longEntry(279, {static_cast<uint32_t>(previewBytes.size())}),
        longEntry(330, {rawIfd}),
        byteEntry(50706, {1, 4, 0, 0}),
        asciiEntry(50708, "Spektrafilm Preview Fixture"),
        srationalEntry(50721, {10000, 0, 0, 0, 10000, 0, 0, 0, 10000}),
        shortEntry(50778, {21}),
    }, &mainFields);
    set32(out, 4, mainIfd);

    if (out.size() & 1) out.push_back(0);
    const uint32_t previewAt = static_cast<uint32_t>(out.size());
    out.insert(out.end(), previewBytes.begin(), previewBytes.end());
    if (out.size() & 1) out.push_back(0);
    const uint32_t rawAt = static_cast<uint32_t>(out.size());
    out.insert(out.end(), rawStrip.begin(), rawStrip.end());
    for (auto [field, tag] : mainFields)
        if (tag == 273) set32(out, field, previewAt);
    for (auto [field, tag] : rawFields)
        if (tag == 273) set32(out, field, rawAt);
    return out;
}

// --- 1. datastream differential -------------------------------------------

void datastreamDifferential() {
    std::mt19937 rng(20261107);
    for (size_t size : {size_t(1), size_t(23), size_t(24), size_t(25), size_t(4096),
                        size_t(PreadDatastream::kCacheBytes - 1),
                        size_t(PreadDatastream::kCacheBytes * 3 + 17)}) {
        std::vector<uint8_t> bytes(size);
        for (auto& b : bytes) {
            // Text-ish bytes so gets()/scanf_one() see digits, spaces and newlines.
            const uint32_t r = rng() % 16;
            b = r < 2 ? '\n' : r < 4 ? ' ' : r < 5 ? 0 : static_cast<uint8_t>('0' + rng() % 10);
        }
        TempFile file(bytes);
        LibRaw_buffer_datastream ref(bytes.data(), bytes.size());
        PreadDatastream dut(file.fd());
        CHECK(dut.valid());
        CHECK(dut.size() == ref.size());
        for (int op = 0; op < 4000; ++op) {
            switch (rng() % 7) {
                case 0: {
                    const size_t sz = 1 + rng() % 4;
                    const size_t n = rng() % (op % 50 == 0 ? PreadDatastream::kCacheBytes * 2 : 64);
                    std::vector<uint8_t> a(sz * n + 1, 0xAA), b(sz * n + 1, 0xAA);
                    CHECK(ref.read(a.data(), sz, n) == dut.read(b.data(), sz, n));
                    CHECK(a == b);
                    break;
                }
                case 1: {
                    const int whence = static_cast<int>(rng() % 3);
                    const INT64 o = static_cast<INT64>(rng() % (2 * size + 3)) - static_cast<INT64>(size) - 1;
                    CHECK(ref.seek(o, whence) == dut.seek(o, whence));
                    break;
                }
                case 2:
                    CHECK(ref.get_char() == dut.get_char());
                    break;
                case 3: {
                    // Lines that end with '\n', fill the buffer, or hit EOF.
                    const int sz = 1 + static_cast<int>(rng() % 12);
                    char a[16], b[16];
                    std::memset(a, 0x55, sizeof a);
                    std::memset(b, 0x55, sizeof b);
                    char* ra = ref.gets(a, sz);
                    char* rb = dut.gets(b, sz);
                    CHECK((ra == nullptr) == (rb == nullptr));
                    if (ra && rb) {
                        // On a line cut by EOF the buffer stream leaves one stale
                        // byte before its NUL; ours terminates at the line end.
                        const size_t lb = std::strlen(b);
                        CHECK(std::strncmp(a, b, lb) == 0);
                        CHECK(a[lb] == 0 || static_cast<unsigned char>(a[lb]) == 0x55);
                    }
                    break;
                }
                case 4: {
                    int va = -1, vb = -1;
                    if (size < 24) {
                        // LibRaw_buffer_datastream::scanf_one copies 24 bytes
                        // past the end of a shorter buffer (its bound check
                        // underflows); ours refuses instead. Not differential.
                        CHECK(dut.scanf_one("%d", &vb) == 0);
                        break;
                    }
                    CHECK(ref.scanf_one("%d", &va) == dut.scanf_one("%d", &vb));
                    CHECK(va == vb);
                    break;
                }
                case 5:
                    CHECK(ref.eof() == dut.eof());
                    break;
                default:
                    break;
            }
            CHECK(ref.tell() == dut.tell());
            if (failures > 20) return;
        }
    }
}

// --- 2. identify equivalence ------------------------------------------------

bool sameIdentify(LibRaw& a, LibRaw& b) {
    auto ia = a.imgdata.idata;
    auto ib = b.imgdata.idata;
    ia.xmpdata = ib.xmpdata = nullptr;  // owned pointers, compared by length
    return std::memcmp(&ia, &ib, sizeof ia) == 0 &&
           std::memcmp(&a.imgdata.sizes, &b.imgdata.sizes, sizeof a.imgdata.sizes) == 0 &&
           std::memcmp(&a.imgdata.thumbs_list, &b.imgdata.thumbs_list,
                       sizeof a.imgdata.thumbs_list) == 0 &&
           std::memcmp(&a.imgdata.thumbnail.tformat, &b.imgdata.thumbnail.tformat,
                       sizeof a.imgdata.thumbnail.tformat) == 0;
}

bool identifyMatches(const std::vector<uint8_t>& bytes, int fd, int* rcOut) {
    auto viaBuffer = std::make_unique<LibRaw>();
    auto viaFd = std::make_unique<LibRaw>();
    const int ra = viaBuffer->open_buffer(bytes.data(), bytes.size());
    PreadDatastream stream(fd);
    const int rb = viaFd->open_datastream(&stream);
    *rcOut = ra;
    return ra == rb && (ra != LIBRAW_SUCCESS || sameIdentify(*viaBuffer, *viaFd));
}

void identifyEquivalence() {
    const auto dng = makePreviewDng(fakeJpeg(3000, 9), 6);
    TempFile file(dng);
    int rc = -1;
    CHECK(identifyMatches(dng, file.fd(), &rc));
    CHECK(rc == LIBRAW_SUCCESS);
}

// --- 3. candidate selection -------------------------------------------------

void selection() {
    auto jpeg = [](uint32_t w, uint32_t h, uint32_t len, int64_t off) {
        PreviewCandidate c;
        c.isJpeg = true;
        c.width = w;
        c.height = h;
        c.length = len;
        c.offset = off;
        return c;
    };
    const int64_t file = 10'000'000;
    std::vector<PreviewCandidate> list = {
        jpeg(160, 120, 6000, 100),
        jpeg(5616, 3744, 2'000'000, 10'000),
        jpeg(1620, 1080, 300'000, 3'000'000),
    };
    CHECK(choosePreviewCandidate(list, file, 320) == 2);   // smallest sharp enough
    CHECK(choosePreviewCandidate(list, file, 1621) == 1);
    CHECK(choosePreviewCandidate(list, file, 0) == 1);     // largest
    CHECK(choosePreviewCandidate(list, file, 9000) == 1);  // none sharp: largest
    CHECK(choosePreviewCandidate(list, file, 100) == 0);
    auto order = orderPreviewCandidates(list, file, 320);
    CHECK((order == std::vector<int>{2, 1, 0}));

    std::vector<PreviewCandidate> hostile = {
        [&] { auto c = jpeg(4000, 3000, 10, 100); c.isJpeg = false; return c; }(),  // PPM etc.
        jpeg(4000, 3000, 0, 100),                                                   // empty
        jpeg(4000, 3000, kMaxEmbeddedPreviewBytes + 1, 0),                         // oversized
        jpeg(4000, 3000, 100, file - 99),                                           // past EOF
        jpeg(4000, 3000, 100, -5),                                                  // negative
        jpeg(4000, 3000, UINT32_MAX, INT64_MAX - 3),                                // overflow bait
    };
    CHECK(choosePreviewCandidate(hostile, file, 320) == -1);
    hostile.push_back(jpeg(0, 0, 50, 0));          // unsized: last resort
    hostile.push_back(jpeg(0, 0, 500, 1000));
    CHECK(choosePreviewCandidate(hostile, file, 320) == 7);  // larger payload first
    hostile.push_back(jpeg(64, 48, 100, 2000));    // sized beats unsized even if small
    CHECK(choosePreviewCandidate(hostile, file, 320) == 8);
    CHECK(choosePreviewCandidate({}, file, 320) == -1);
    CHECK(choosePreviewCandidate(list, 0, 320) == -1);
}

// --- 4. extraction ----------------------------------------------------------

void extraction() {
    const auto payload = fakeJpeg(5000, 3);
    {
        const auto dng = makePreviewDng(payload, 6);
        TempFile file(dng);
        const auto p = extractEmbeddedPreview(file.fd(), 64);
        CHECK(p.status == SFRAW_PREVIEW_OK);
        CHECK(p.jpeg == payload);
        CHECK(p.width == 96 && p.height == 64);
        CHECK(p.flip == 6);
        // The caller's descriptor is untouched and still usable.
        CHECK(::lseek(file.fd(), 0, SEEK_CUR) >= 0);
    }
    {
        // A preview IFD that does not hold a JPEG is never handed out.
        auto notJpeg = payload;
        notJpeg[0] = 0x00;
        const auto dng = makePreviewDng(notJpeg, 1);
        TempFile file(dng);
        const auto p = extractEmbeddedPreview(file.fd(), 64);
        CHECK(p.status == SFRAW_PREVIEW_NONE);
        CHECK(p.jpeg.empty());
    }
    {
        std::vector<uint8_t> garbage(4096);
        for (size_t i = 0; i < garbage.size(); ++i) garbage[i] = static_cast<uint8_t>(i * 131);
        TempFile file(garbage);
        CHECK(extractEmbeddedPreview(file.fd(), 64).status == SFRAW_PREVIEW_OPEN);
    }
    {
        TempFile empty({});
        CHECK(extractEmbeddedPreview(empty.fd(), 64).status == SFRAW_PREVIEW_INPUT);
    }
    {
        int pipes[2];
        CHECK(::pipe(pipes) == 0);
        CHECK(extractEmbeddedPreview(pipes[0], 64).status == SFRAW_PREVIEW_INPUT);
        ::close(pipes[0]);
        ::close(pipes[1]);
    }
    CHECK(extractEmbeddedPreview(-1, 64).status == SFRAW_PREVIEW_INPUT);
    // Every truncation of the fixture either opens or fails typed, never crashes.
    const auto dng = makePreviewDng(payload, 3);
    for (size_t cut = 1; cut < dng.size(); cut += 97) {
        std::vector<uint8_t> head(dng.begin(), dng.begin() + static_cast<long>(cut));
        TempFile file(head);
        const auto p = extractEmbeddedPreview(file.fd(), 64);
        CHECK(p.status != SFRAW_PREVIEW_OK || p.jpeg == payload);
    }
}

// --- 5. optional real camera files -----------------------------------------

void samples() {
    const char* dir = std::getenv("SFRAW_PREVIEW_SAMPLES");
    if (dir == nullptr) return;
    DIR* d = ::opendir(dir);
    if (d == nullptr) return;
    int seen = 0;
    while (dirent* e = ::readdir(d)) {
        if (e->d_name[0] == '.') continue;
        const std::string path = std::string(dir) + "/" + e->d_name;
        const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        struct stat st{};
        if (::fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
            ::close(fd);
            continue;
        }
        std::vector<uint8_t> bytes(static_cast<size_t>(st.st_size));
        const bool readOk = ::pread(fd, bytes.data(), bytes.size(), 0) == st.st_size;
        int rc = -1;
        const bool same = readOk && identifyMatches(bytes, fd, &rc);
        const auto p = extractEmbeddedPreview(fd, 320);
        std::printf("sample %-40s open=%d same=%d preview=%d %ux%u %zu bytes\n",
                    e->d_name, rc, same ? 1 : 0, p.status, p.width, p.height, p.jpeg.size());
        CHECK(same);
        ++seen;
        ::close(fd);
    }
    ::closedir(d);
    std::printf("samples checked: %d\n", seen);
}

}  // namespace

int main() {
    datastreamDifferential();
    identifyEquivalence();
    selection();
    extraction();
    samples();
    if (failures != 0) {
        std::fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("raw preview contract: OK\n");
    return 0;
}
