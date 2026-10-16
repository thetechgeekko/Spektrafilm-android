// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
// Bounded, caller-supplied scratch snapshot for LibRaw's open_buffer path.
#ifndef SPECTRAFILM_RAW_INPUT_SNAPSHOT_H
#define SPECTRAFILM_RAW_INPUT_SNAPSHOT_H

#include "raw_decoder.h"

#include <array>
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace spectrafilm {

class RawInputSnapshot final {
 public:
    RawInputSnapshot() = default;
    ~RawInputSnapshot() {
        if (mapped_ != MAP_FAILED) ::munmap(mapped_, length_);
        if (scratch_ >= 0) ::close(scratch_);
    }
    RawInputSnapshot(const RawInputSnapshot&) = delete;
    RawInputSnapshot& operator=(const RawInputSnapshot&) = delete;

    const uint8_t* data() const noexcept {
        return static_cast<const uint8_t*>(mapped_);
    }
    size_t size() const noexcept { return length_; }

    bool load(int sourceFd, int scratchFd, const DecodeOptions& options,
              DecodeResult* result) {
        constexpr size_t kLimit = sizeof(void*) >= 8
            ? 512U * 1024U * 1024U : 64U * 1024U * 1024U;
        auto fail = [&](const char* message, int status = SFRAW_ERR_INPUT) {
            result->status = status;
            result->error = message;
            return false;
        };
        struct stat sourceStat{}, scratchStat{};
        if (sourceFd < 0 || scratchFd < 0 ||
            ::fstat(sourceFd, &sourceStat) != 0 ||
            ::fstat(scratchFd, &scratchStat) != 0) {
            return fail("invalid RAW source or scratch descriptor");
        }
        if (!S_ISREG(scratchStat.st_mode) || scratchStat.st_nlink != 0 ||
            scratchStat.st_size != 0) {
            return fail("RAW scratch must be an empty unlinked regular file");
        }
        if (sourceStat.st_dev == scratchStat.st_dev &&
            sourceStat.st_ino == scratchStat.st_ino) {
            return fail("RAW source and scratch refer to the same file");
        }
        const int flags = ::fcntl(scratchFd, F_GETFL);
        if (flags < 0 || (flags & O_ACCMODE) != O_RDWR) {
            return fail("RAW scratch must be open for reading and writing");
        }
        // A regular file's remaining size is an early rejection only. Actual
        // read counts remain authoritative if the source grows while copying.
        if (S_ISREG(sourceStat.st_mode) && sourceStat.st_size >= 0) {
            const off_t position = ::lseek(sourceFd, 0, SEEK_CUR);
            if (position >= 0 && position <= sourceStat.st_size &&
                static_cast<uint64_t>(sourceStat.st_size - position) > kLimit) {
                return fail("RAW input exceeds scratch safety limit");
            }
        }
        scratch_ = ::dup(scratchFd);
        if (scratch_ < 0) return fail("failed to duplicate RAW scratch fd");
        const int source = ::dup(sourceFd);
        if (source < 0) return fail("failed to duplicate RAW source fd");
        if (::ftruncate(scratch_, 0) != 0 || ::lseek(scratch_, 0, SEEK_SET) != 0) {
            ::close(source);
            return fail("failed to reset RAW scratch fd");
        }
        std::array<uint8_t, 64U * 1024U> chunk{};
        for (;;) {
            if (options.cancelFlag != nullptr &&
                options.cancelFlag->load(std::memory_order_acquire)) {
                ::close(source);
                return fail("decode cancelled", SFRAW_ERR_CANCELLED);
            }
            const ssize_t count = ::read(source, chunk.data(), chunk.size());
            if (count < 0 && errno == EINTR) continue;
            if (count < 0) {
                ::close(source);
                return fail("failed to read RAW from fd");
            }
            if (count == 0) break;
            if (static_cast<size_t>(count) > kLimit - length_) {
                ::close(source);
                return fail("RAW input exceeds scratch safety limit");
            }
            size_t written = 0;
            while (written < static_cast<size_t>(count)) {
                if (options.cancelFlag != nullptr &&
                    options.cancelFlag->load(std::memory_order_acquire)) {
                    ::close(source);
                    return fail("decode cancelled", SFRAW_ERR_CANCELLED);
                }
                const ssize_t n = ::write(scratch_, chunk.data() + written,
                                          static_cast<size_t>(count) - written);
                if (n < 0 && errno == EINTR) continue;
                if (n <= 0) {
                    ::close(source);
                    return fail("failed to write RAW scratch fd");
                }
                written += static_cast<size_t>(n);
            }
            length_ += static_cast<size_t>(count);
        }
        ::close(source);
        if (options.cancelFlag != nullptr &&
            options.cancelFlag->load(std::memory_order_acquire)) {
            return fail("decode cancelled", SFRAW_ERR_CANCELLED);
        }
        if (length_ == 0) return fail("failed to read RAW from fd");
        if (::lseek(scratch_, 0, SEEK_SET) != 0) {
            return fail("failed to rewind RAW scratch fd");
        }
        mapped_ = ::mmap(nullptr, length_, PROT_READ, MAP_PRIVATE, scratch_, 0);
        if (mapped_ == MAP_FAILED) {
            return fail("failed to map RAW scratch input",
                        errno == ENOMEM ? SFRAW_ERR_NO_MEMORY : SFRAW_ERR_INPUT);
        }
        return true;
    }

 private:
    int scratch_ = -1;
    void* mapped_ = MAP_FAILED;
    size_t length_ = 0;
};

}  // namespace spectrafilm

#endif  // SPECTRAFILM_RAW_INPUT_SNAPSHOT_H
