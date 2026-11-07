/*
 * Spektrafilm for Android — whole-file read from a descriptor (#287). GPLv3.
 *
 * The RAW decode used to pull the source through stdio in 64 KiB fread chunks into
 * a vector that grew by doubling. On a cold MediaStore/SAF descriptor (FUSE on
 * Android 11+) every small read is its own provider round trip, and #258's log put
 * that `io` phase at ~1.2 s of a ~1.4 s first decode. This reads the same bytes
 * with the size known up front (fstat) and 1 MiB read(2) calls straight into the
 * final buffer, after telling the kernel the access is sequential. Bytes, limit
 * semantics (a file of exactly maxBytes is accepted, one byte more is refused) and
 * the shared file offset (the read starts wherever the descriptor is, like the
 * fread it replaces) are unchanged.
 */
#ifndef SFRAW_FD_READ_ALL_H
#define SFRAW_FD_READ_ALL_H

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace sfraw {

enum class FdReadStatus { Ok, TooLarge, IoError, Cancelled };

inline constexpr std::size_t kFdReadChunkBytes = 1U << 20;

template <typename CancelledFn>
FdReadStatus readAllFromFd(int fd, std::size_t maxBytes, std::vector<uint8_t>& out,
                           CancelledFn cancelled) {
    out.clear();
    std::size_t expected = 0;
    struct stat st {};
    if (::fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {
        const off_t pos = ::lseek(fd, 0, SEEK_CUR);
        const uint64_t remaining = pos >= 0 && pos <= st.st_size
            ? static_cast<uint64_t>(st.st_size - pos)
            : static_cast<uint64_t>(st.st_size);
        if (remaining > maxBytes) return FdReadStatus::TooLarge;
        expected = static_cast<std::size_t>(remaining);
#if defined(POSIX_FADV_SEQUENTIAL)
        (void)::posix_fadvise(fd, 0, 0, POSIX_FADV_SEQUENTIAL);
#endif
    }
    // One allocation when the size is known; +1 so a file that grew since fstat is
    // still read to EOF (and refused if it crossed the limit) without a regrow.
    out.reserve(expected > 0 ? expected + 1 : kFdReadChunkBytes);
    for (;;) {
        if (cancelled()) return FdReadStatus::Cancelled;
        const std::size_t have = out.size();
        // Read at most one byte past the limit, which is enough to refuse it.
        const std::size_t room = maxBytes + 1 - have;
        std::size_t want = room < kFdReadChunkBytes ? room : kFdReadChunkBytes;
        // With the size known, never ask past the reserved capacity: the last read is
        // a 1-byte EOF probe, so the buffer is never reallocated and copied.
        if (expected > 0) {
            const std::size_t spare = out.capacity() - have;
            if (spare >= 1 && spare < want) want = spare;
        }
        out.resize(have + want);
        ssize_t n;
        do {
            n = ::read(fd, out.data() + have, want);
        } while (n < 0 && errno == EINTR);
        if (n < 0) {
            out.resize(have);
            return FdReadStatus::IoError;
        }
        out.resize(have + static_cast<std::size_t>(n));
        if (out.size() > maxBytes) return FdReadStatus::TooLarge;
        if (n == 0) break;
        if (cancelled()) return FdReadStatus::Cancelled;
    }
    return FdReadStatus::Ok;
}

}  // namespace sfraw

#endif  // SFRAW_FD_READ_ALL_H
