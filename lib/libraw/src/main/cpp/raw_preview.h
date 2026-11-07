// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Embedded camera-JPEG extraction for fast RAW thumbnails and placeholders.
//
// Almost every camera RAW carries one or more JPEGs the camera rendered itself
// (CR3 THMB/PRVW, NEF/ARW/ORF preview IFDs, DNG preview IFDs ...). Showing one is
// orders of magnitude cheaper than demosaicing the sensor data, and it is the
// only thumbnail route for containers Android's ExifInterface cannot parse
// (CR3, 3FR, IIQ, MOS, KDC ...).
//
// The route is deliberately narrow:
//  * LibRaw only *opens* the file (the same metadata parsers the full decode
//    already runs); unpack(), unpack_thumb() and every decoder stay unreached.
//  * The file is read lazily through pread() on a caller-owned descriptor, so a
//    60 MB RAW costs a few hundred KiB of I/O, never a full copy or an mmap
//    that a concurrent truncation could turn into SIGBUS.
//  * Only entries LibRaw classifies as JPEG are eligible. Their offset/length are
//    re-validated against the real file size and a JPEG SOI marker before the
//    bytes are handed to the platform JPEG decoder.
#ifndef SPECTRAFILM_RAW_PREVIEW_H
#define SPECTRAFILM_RAW_PREVIEW_H

#include <cstddef>
#include <cstdint>
#include <vector>

namespace spectrafilm {

enum PreviewStatus : int {
    SFRAW_PREVIEW_OK = 0,
    // The file opened but carries no usable embedded JPEG.
    SFRAW_PREVIEW_NONE = 1,
    // Bad descriptor, unseekable source (pipe), empty or unreadable file.
    SFRAW_PREVIEW_INPUT = 2,
    // LibRaw does not recognise the container.
    SFRAW_PREVIEW_OPEN = 3,
    SFRAW_PREVIEW_NO_MEMORY = 4,
};

// Upper bound on one extracted JPEG. Full-size camera JPEGs of 100 MP bodies
// stay well below this; anything larger is treated as hostile metadata.
constexpr uint32_t kMaxEmbeddedPreviewBytes = 48U * 1024U * 1024U;

// One embedded image as LibRaw's thumbnail list describes it. Dimensions are
// 0 when the container does not record them.
struct PreviewCandidate {
    bool isJpeg = false;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t length = 0;
    int64_t offset = 0;
};

// Picks the candidate to extract, or -1. Ineligible: not JPEG, empty, larger
// than kMaxEmbeddedPreviewBytes, or not wholly inside [0, fileSize).
// Among eligible entries with known dimensions, the smallest whose long edge
// reaches minLongEdge wins (cheapest that is sharp enough); failing that, the
// largest known one. Entries without dimensions are a last resort, largest
// payload first. minLongEdge <= 0 asks for the largest image.
int choosePreviewCandidate(const std::vector<PreviewCandidate>& candidates,
                           int64_t fileSize, int minLongEdge);

// Eligible candidate indices in the order they should be tried (the first is
// choosePreviewCandidate's pick). A candidate whose bytes turn out not to be a
// JPEG is skipped in favour of the next.
std::vector<int> orderPreviewCandidates(
    const std::vector<PreviewCandidate>& candidates, int64_t fileSize,
    int minLongEdge);

struct EmbeddedPreview {
    int status = SFRAW_PREVIEW_INPUT;
    std::vector<uint8_t> jpeg;
    uint32_t width = 0;   // as recorded by the container, 0 if unknown
    uint32_t height = 0;
    // LibRaw orientation of the frame (sizes.flip): 0 none, 3 = 180 degrees,
    // 5 = 90 degrees counter-clockwise, 6 = 90 degrees clockwise. The embedded
    // JPEGs of the supported formats are stored in sensor orientation.
    int flip = 0;
};

// The caller keeps ownership of fd; it is duplicated internally.
EmbeddedPreview extractEmbeddedPreview(int fd, int minLongEdge);

}  // namespace spectrafilm

#endif  // SPECTRAFILM_RAW_PREVIEW_H
