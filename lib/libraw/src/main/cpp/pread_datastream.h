// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// A LibRaw datastream over pread() on a file descriptor, with a small read
// cache. Position semantics (clamping seek, short reads, gets, scanf_one)
// mirror LibRaw_buffer_datastream so that open_datastream() over this stream
// identifies a file exactly as open_buffer() over the same bytes does; the host
// test sfraw.raw-preview.contract checks that equivalence.
//
// Only used for metadata-level work (open + embedded preview extraction). It is
// not wired into the full decode, which keeps its audited open_buffer path.
#ifndef SPECTRAFILM_PREAD_DATASTREAM_H
#define SPECTRAFILM_PREAD_DATASTREAM_H

#include <libraw/libraw.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace spectrafilm {

class PreadDatastream final : public LibRaw_abstract_datastream {
 public:
    static constexpr size_t kCacheBytes = 64U * 1024U;

    // Duplicates fd; the caller keeps its own descriptor. Check valid() after
    // construction: only seekable regular files are accepted.
    explicit PreadDatastream(int fd) : cache_(kCacheBytes) {
        if (fd < 0) return;
        struct stat st{};
        if (::fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0)
            return;
        fd_ = ::fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (fd_ < 0) return;
        size_ = static_cast<size_t>(st.st_size);
    }
    ~PreadDatastream() override {
        if (fd_ >= 0) ::close(fd_);
    }
    PreadDatastream(const PreadDatastream&) = delete;
    PreadDatastream& operator=(const PreadDatastream&) = delete;

    int valid() override { return fd_ >= 0 && size_ > 0 && !ioError_ ? 1 : 0; }
    bool ioError() const { return ioError_; }

    int read(void* ptr, size_t sz, size_t nmemb) override {
        if (sz == 0 || nmemb == 0) return 0;
        size_t toRead;
        if (__builtin_mul_overflow(sz, nmemb, &toRead)) toRead = SIZE_MAX;
        if (toRead > size_ - pos_) toRead = size_ - pos_;
        if (toRead < 1) return 0;
        const size_t got = copyOut(static_cast<uint8_t*>(ptr), pos_, toRead);
        pos_ += got;
        return static_cast<int>((got + sz - 1) / sz);
    }

    int seek(INT64 o, int whence) override {
        switch (whence) {
            case SEEK_SET:
                if (o < 0) pos_ = 0;
                else if (static_cast<uint64_t>(o) > size_) pos_ = size_;
                else pos_ = static_cast<size_t>(o);
                return 0;
            case SEEK_CUR:
                if (o < 0) {
                    const uint64_t back = static_cast<uint64_t>(-(o + 1)) + 1U;
                    pos_ = back >= pos_ ? 0 : pos_ - static_cast<size_t>(back);
                } else if (o > 0) {
                    const uint64_t fwd = static_cast<uint64_t>(o);
                    pos_ = fwd > size_ - pos_ ? size_ : pos_ + static_cast<size_t>(fwd);
                }
                return 0;
            case SEEK_END:
                if (o > 0) {
                    pos_ = size_;
                } else {
                    const uint64_t back = static_cast<uint64_t>(-(o + 1)) + 1U;
                    pos_ = (o == 0) ? size_ : (back > size_ ? 0 : size_ - static_cast<size_t>(back));
                }
                return 0;
            default:
                return 0;
        }
    }

    INT64 tell() override { return static_cast<INT64>(pos_); }
    INT64 size() override { return static_cast<INT64>(size_); }

    int get_char() override {
        if (pos_ >= size_) return -1;
        uint8_t c = 0;
        if (copyOut(&c, pos_, 1) != 1) return -1;
        ++pos_;
        return c;
    }

    // As LibRaw_buffer_datastream::gets: copies through the first '\n'
    // (inclusive) or sz-1 bytes, then consumes one more byte (the '\n', or --
    // a quirk kept for equivalence -- the byte after a full line buffer), and
    // NUL-terminates. Unlike the buffer stream, a line cut by EOF is terminated
    // right after its last byte instead of one byte later.
    char* gets(char* s, int sz) override {
        if (sz < 1 || pos_ >= size_) return nullptr;
        int n = 0;
        bool newline = false;
        while (pos_ < size_ && n < sz - 1) {
            const int c = byteAt(pos_);
            if (c < 0) break;
            s[n] = static_cast<char>(c);
            if (c == '\n') {
                newline = true;
                break;
            }
            ++pos_;
            ++n;
        }
        if (pos_ < size_) ++pos_;
        if (newline) s[n + 1] = 0;
        else if (n < sz - 1) s[n] = 0;
        else s[sz - 1] = 0;
        return s;
    }

    int scanf_one(const char* fmt, void* val) override {
        if (size_ < 24 || pos_ > size_ - 24) return 0;
        char window[25];
        if (copyOut(reinterpret_cast<uint8_t*>(window), pos_, 24) != 24) return 0;
        window[24] = 0;
        const int res = std::sscanf(window, fmt, val);
        if (res > 0) {
            // Skip the token exactly as the buffer stream does: advance at least
            // one byte, stop at a separator, never more than 25 bytes.
            int advanced = 0;
            while (pos_ < size_ - 1) {
                ++pos_;
                ++advanced;
                const int c = byteAt(pos_);
                if (c <= 0 || c == ' ' || c == '\t' || c == '\n' || advanced > 24) break;
            }
        }
        return res;
    }

    int eof() override { return pos_ >= size_ ? 1 : 0; }

    // Reads [offset, offset + length) without moving the stream position.
    bool readAt(int64_t offset, uint8_t* out, size_t length) {
        if (offset < 0 || static_cast<uint64_t>(offset) > size_ ||
            length > size_ - static_cast<size_t>(offset)) {
            return false;
        }
        return copyOut(out, static_cast<size_t>(offset), length) == length;
    }

 private:
    int byteAt(size_t at) {
        uint8_t c = 0;
        return copyOut(&c, at, 1) == 1 ? c : -1;
    }

    // Copies up to length bytes at absolute offset `at` through the cache.
    // Returns the count copied; a short count means EOF or an I/O error.
    size_t copyOut(uint8_t* out, size_t at, size_t length) {
        size_t done = 0;
        while (done < length) {
            const size_t cur = at + done;
            if (cur >= size_) break;
            if (cur >= cacheStart_ && cur < cacheStart_ + cacheLen_) {
                const size_t n = std::min(length - done, cacheStart_ + cacheLen_ - cur);
                std::memcpy(out + done, cache_.data() + (cur - cacheStart_), n);
                done += n;
                continue;
            }
            const size_t want = length - done;
            if (want >= cache_.size()) {
                // Large reads (the JPEG payload itself) bypass the cache.
                const ssize_t n = preadFully(out + done, want, cur);
                if (n <= 0) break;
                done += static_cast<size_t>(n);
                continue;
            }
            const ssize_t n = preadFully(cache_.data(),
                                         std::min(cache_.size(), size_ - cur), cur);
            if (n <= 0) {
                cacheLen_ = 0;
                break;
            }
            cacheStart_ = cur;
            cacheLen_ = static_cast<size_t>(n);
        }
        return done;
    }

    ssize_t preadFully(uint8_t* out, size_t length, size_t at) {
        size_t got = 0;
        while (got < length) {
            const ssize_t n = ::pread(fd_, out + got, length - got,
                                      static_cast<off_t>(at + got));
            if (n < 0 && errno == EINTR) continue;
            if (n < 0) {
                ioError_ = true;
                break;
            }
            if (n == 0) break;  // file shrank under us: behave like EOF
            got += static_cast<size_t>(n);
        }
        return static_cast<ssize_t>(got);
    }

    int fd_ = -1;
    size_t size_ = 0;
    size_t pos_ = 0;
    bool ioError_ = false;
    std::vector<uint8_t> cache_;
    size_t cacheStart_ = 0;
    size_t cacheLen_ = 0;
};

}  // namespace spectrafilm

#endif  // SPECTRAFILM_PREAD_DATASTREAM_H
