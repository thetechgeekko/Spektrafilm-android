// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// Embedded camera-JPEG extraction. See raw_preview.h for the contract.
#include "raw_preview.h"

#include <algorithm>
#include <memory>
#include <new>

#if !defined(SFRAW_FORCE_NO_LIBRAW) && defined(__has_include)
#  if __has_include(<libraw/libraw.h>)
#    include "pread_datastream.h"
#    define SFRAW_PREVIEW_HAVE_LIBRAW 1
#  endif
#endif

namespace spectrafilm {

namespace {

bool eligible(const PreviewCandidate& c, int64_t fileSize) {
    if (!c.isJpeg || c.length < 4 || c.length > kMaxEmbeddedPreviewBytes) return false;
    if (c.offset < 0 || fileSize <= 0 || c.offset >= fileSize) return false;
    return static_cast<uint64_t>(c.length) <= static_cast<uint64_t>(fileSize - c.offset);
}

uint32_t longEdge(const PreviewCandidate& c) { return std::max(c.width, c.height); }

}  // namespace

std::vector<int> orderPreviewCandidates(
    const std::vector<PreviewCandidate>& candidates, int64_t fileSize,
    int minLongEdge) {
    std::vector<int> sized;
    std::vector<int> unsized;
    for (size_t i = 0; i < candidates.size(); ++i) {
        const auto& c = candidates[i];
        if (!eligible(c, fileSize)) continue;
        (c.width > 0 && c.height > 0 ? sized : unsized).push_back(static_cast<int>(i));
    }
    const uint32_t want = minLongEdge > 0 ? static_cast<uint32_t>(minLongEdge) : UINT32_MAX;
    // Sharp enough first (smallest such), then the rest largest first.
    std::stable_sort(sized.begin(), sized.end(), [&](int a, int b) {
        const uint32_t ea = longEdge(candidates[a]);
        const uint32_t eb = longEdge(candidates[b]);
        const bool okA = ea >= want;
        const bool okB = eb >= want;
        if (okA != okB) return okA;
        return okA ? ea < eb : ea > eb;
    });
    std::stable_sort(unsized.begin(), unsized.end(), [&](int a, int b) {
        return candidates[a].length > candidates[b].length;
    });
    sized.insert(sized.end(), unsized.begin(), unsized.end());
    return sized;
}

int choosePreviewCandidate(const std::vector<PreviewCandidate>& candidates,
                           int64_t fileSize, int minLongEdge) {
    const auto order = orderPreviewCandidates(candidates, fileSize, minLongEdge);
    return order.empty() ? -1 : order.front();
}

#if defined(SFRAW_PREVIEW_HAVE_LIBRAW)

EmbeddedPreview extractEmbeddedPreview(int fd, int minLongEdge) try {
    EmbeddedPreview out;
    PreadDatastream stream(fd);
    if (!stream.valid()) {
        out.status = SFRAW_PREVIEW_INPUT;
        return out;
    }
    // Heap: LibRaw's object is several hundred KiB, too big for a worker stack.
    auto raw = std::make_unique<LibRaw>();
    // No pixel data is unpacked here; the cap only bounds metadata-side buffers.
    raw->imgdata.rawparams.max_raw_memory_mb = 128;
    const int rc = raw->open_datastream(&stream);
    if (rc != LIBRAW_SUCCESS) {
        out.status = (rc == LIBRAW_UNSUFFICIENT_MEMORY || rc == LIBRAW_TOO_BIG)
            ? SFRAW_PREVIEW_NO_MEMORY
            : (stream.ioError() ? SFRAW_PREVIEW_INPUT : SFRAW_PREVIEW_OPEN);
        return out;
    }
    const auto& list = raw->imgdata.thumbs_list;
    const int count = std::clamp(list.thumbcount, 0, LIBRAW_THUMBNAIL_MAXCOUNT);
    std::vector<PreviewCandidate> candidates;
    candidates.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        const auto& item = list.thumblist[i];
        PreviewCandidate c;
        c.isJpeg = item.tformat == LIBRAW_INTERNAL_THUMBNAIL_JPEG;
        c.width = item.twidth;
        c.height = item.theight;
        c.length = item.tlength;
        c.offset = item.toffset;
        candidates.push_back(c);
    }
    const int64_t fileSize = stream.size();
    for (const int index : orderPreviewCandidates(candidates, fileSize, minLongEdge)) {
        const auto& c = candidates[static_cast<size_t>(index)];
        std::vector<uint8_t> bytes(c.length);
        if (!stream.readAt(c.offset, bytes.data(), bytes.size())) {
            if (stream.ioError()) {
                out.status = SFRAW_PREVIEW_INPUT;
                return out;
            }
            continue;
        }
        // SOI + the first marker prefix; anything else is not a baseline JFIF/EXIF JPEG.
        if (bytes[0] != 0xFF || bytes[1] != 0xD8 || bytes[2] != 0xFF) continue;
        out.jpeg = std::move(bytes);
        out.width = c.width;
        out.height = c.height;
        const int flip = raw->imgdata.sizes.flip;
        out.flip = (flip == 3 || flip == 5 || flip == 6) ? flip : 0;
        out.status = SFRAW_PREVIEW_OK;
        return out;
    }
    out.status = SFRAW_PREVIEW_NONE;
    return out;
} catch (const std::bad_alloc&) {
    EmbeddedPreview out;
    out.status = SFRAW_PREVIEW_NO_MEMORY;
    return out;
} catch (...) {
    EmbeddedPreview out;
    out.status = SFRAW_PREVIEW_OPEN;
    return out;
}

#else  // !SFRAW_PREVIEW_HAVE_LIBRAW

EmbeddedPreview extractEmbeddedPreview(int, int) {
    EmbeddedPreview out;
    out.status = SFRAW_PREVIEW_OPEN;
    return out;
}

#endif

}  // namespace spectrafilm
