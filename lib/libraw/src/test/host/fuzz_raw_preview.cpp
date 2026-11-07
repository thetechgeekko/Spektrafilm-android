/*
 * Spektrafilm Android -- embedded-preview extraction libFuzzer target (GPLv3).
 *
 * Drives the production preview route (raw_preview.cpp) exactly as the app
 * does: a seekable regular-file descriptor read lazily through
 * PreadDatastream, LibRaw open_datastream(), then candidate validation and
 * the payload read. The input lives in a memfd so no fuzz case touches disk.
 */
#include "raw_preview.h"

#include <cstddef>
#include <cstdint>
#include <sys/mman.h>
#include <unistd.h>

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data,
                                      std::size_t size) {
  if (data == nullptr || size == 0U || size > 16U * 1024U * 1024U) return 0;
  const int fd = ::memfd_create("sfraw-preview-fuzz", MFD_CLOEXEC);
  if (fd < 0) return 0;
  std::size_t done = 0;
  while (done < size) {
    const ssize_t n = ::write(fd, data + done, size - done);
    if (n <= 0) {
      ::close(fd);
      return 0;
    }
    done += static_cast<std::size_t>(n);
  }
  // The requested edge varies with the input so selection paths are explored.
  const int minEdge = static_cast<int>(data[size - 1]) * 16;
  const auto preview = spectrafilm::extractEmbeddedPreview(fd, minEdge);
  if (preview.status == spectrafilm::SFRAW_PREVIEW_OK) {
    // Contract: an OK preview is a non-empty, bounded JPEG from the input.
    if (preview.jpeg.size() < 4 || preview.jpeg.size() > size ||
        preview.jpeg[0] != 0xFF || preview.jpeg[1] != 0xD8) {
      __builtin_trap();
    }
  }
  ::close(fd);
  return 0;
}
