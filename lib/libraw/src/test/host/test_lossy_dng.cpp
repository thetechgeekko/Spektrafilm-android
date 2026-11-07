/*
 * Spektrafilm Android -- lossy-JPEG DNG (Compression 0x884C) contract.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * Samsung / Pixel "Expert RAW" can write DNG 1.4 lossy JPEG LinearRaw. The
 * build links the pinned libjpeg-turbo (USE_JPEG) so LibRaw decodes these, and
 * LibRaw patch 0026 hardens that loader. This test builds lossy DNGs in-process
 * (tiles encoded with the same libjpeg-turbo) and checks:
 *   - a baseline file decodes natively and is labelled LossyCompressed, with
 *     the decoder's 16-bit linear level domain, never as lossless sensor data;
 *   - progressive and corrupt tiles fail closed with the typed fallback status
 *     (and, under ASan/LSan, without leaking libjpeg's decompressor);
 *   - a hostile MapPolynomial cannot reach an undefined float->ushort cast
 *     (UBSan float-cast-overflow traps if patch 0026's clamp is missing);
 *   - a lossy file with a BlackLevel (left in the 8-bit domain by LibRaw)
 *     fails closed instead of being mis-normalised.
 */
#include "raw_decoder.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

#include <jpeglib.h>

namespace {

int failures = 0;

void check(bool condition, const std::string& label,
           const std::string& detail = {}) {
  std::cout << (condition ? "ok   " : "FAIL ") << label;
  if (!detail.empty()) std::cout << " (" << detail << ')';
  std::cout << '\n';
  if (!condition) ++failures;
}

std::string describe(const spectrafilm::DecodeResult& result) {
  return "status=" + std::to_string(result.status) +
      " libraw=" + std::to_string(result.librawCode) +
      " error=" + result.error;
}

constexpr int kWidth = 512;
constexpr int kHeight = 384;
constexpr int kTile = 256;

// Encodes one RGB tile (kTile x kTile, edge tiles padded by clamping) of a
// deterministic gradient: R rises with x, G with y, B is a coarse ramp.
std::vector<std::uint8_t> encodeTile(int tileX, int tileY, bool progressive) {
  std::vector<JSAMPLE> pixels(static_cast<std::size_t>(kTile) * kTile * 3U);
  for (int y = 0; y < kTile; ++y) {
    for (int x = 0; x < kTile; ++x) {
      const int gx = std::min(tileX + x, kWidth - 1);
      const int gy = std::min(tileY + y, kHeight - 1);
      JSAMPLE* p = &pixels[(static_cast<std::size_t>(y) * kTile + x) * 3U];
      p[0] = static_cast<JSAMPLE>(255 * gx / (kWidth - 1));
      p[1] = static_cast<JSAMPLE>(255 * gy / (kHeight - 1));
      p[2] = static_cast<JSAMPLE>(255 * ((gx + gy) % 97) / 96);
    }
  }
  jpeg_compress_struct cinfo;
  jpeg_error_mgr err;
  cinfo.err = jpeg_std_error(&err);
  jpeg_create_compress(&cinfo);
  unsigned char* out = nullptr;
  unsigned long outSize = 0;
  jpeg_mem_dest(&cinfo, &out, &outSize);
  cinfo.image_width = kTile;
  cinfo.image_height = kTile;
  cinfo.input_components = 3;
  cinfo.in_color_space = JCS_RGB;
  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, 95, TRUE);
  if (progressive) jpeg_simple_progression(&cinfo);
  jpeg_start_compress(&cinfo, TRUE);
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = &pixels[static_cast<std::size_t>(cinfo.next_scanline) *
                           kTile * 3U];
    jpeg_write_scanlines(&cinfo, &row, 1);
  }
  jpeg_finish_compress(&cinfo);
  jpeg_destroy_compress(&cinfo);
  std::vector<std::uint8_t> bytes(out, out + outSize);
  std::free(out);
  return bytes;
}

struct Entry {
  std::uint16_t tag;
  std::uint16_t type;
  std::uint32_t count;
  std::vector<std::uint8_t> payload;  // little-endian TIFF payload
};

void put16(std::vector<std::uint8_t>* b, std::uint16_t v) {
  b->push_back(static_cast<std::uint8_t>(v));
  b->push_back(static_cast<std::uint8_t>(v >> 8));
}
void put32(std::vector<std::uint8_t>* b, std::uint32_t v) {
  for (int i = 0; i < 4; ++i) b->push_back(static_cast<std::uint8_t>(v >> (8 * i)));
}
void putBe32(std::vector<std::uint8_t>* b, std::uint32_t v) {
  for (int i = 3; i >= 0; --i) b->push_back(static_cast<std::uint8_t>(v >> (8 * i)));
}
void putBeDouble(std::vector<std::uint8_t>* b, double v) {
  std::uint64_t bits = 0;
  std::memcpy(&bits, &v, sizeof bits);
  for (int i = 7; i >= 0; --i) b->push_back(static_cast<std::uint8_t>(bits >> (8 * i)));
}

Entry shorts(std::uint16_t tag, std::vector<std::uint16_t> values) {
  Entry e{tag, 3, static_cast<std::uint32_t>(values.size()), {}};
  for (std::uint16_t v : values) put16(&e.payload, v);
  return e;
}
Entry longs(std::uint16_t tag, std::vector<std::uint32_t> values) {
  Entry e{tag, 4, static_cast<std::uint32_t>(values.size()), {}};
  for (std::uint32_t v : values) put32(&e.payload, v);
  return e;
}
Entry bytes(std::uint16_t tag, std::vector<std::uint8_t> values) {
  return Entry{tag, 1, static_cast<std::uint32_t>(values.size()), values};
}
Entry ascii(std::uint16_t tag, const std::string& text) {
  std::vector<std::uint8_t> p(text.begin(), text.end());
  p.push_back(0);
  return Entry{tag, 2, static_cast<std::uint32_t>(p.size()), p};
}
Entry undefined(std::uint16_t tag, std::vector<std::uint8_t> values) {
  return Entry{tag, 7, static_cast<std::uint32_t>(values.size()), values};
}
Entry srationals(std::uint16_t tag, int count, bool identity) {
  Entry e{tag, 10, static_cast<std::uint32_t>(count), {}};
  for (int i = 0; i < count; ++i) {
    const bool diagonal = identity && (i % 4 == 0);
    put32(&e.payload, diagonal ? 10000U : 0U);
    put32(&e.payload, 10000U);
  }
  return e;
}
Entry rationals(std::uint16_t tag, int count) {
  Entry e{tag, 5, static_cast<std::uint32_t>(count), {}};
  for (int i = 0; i < count; ++i) {
    put32(&e.payload, 1U);
    put32(&e.payload, 1U);
  }
  return e;
}

// MapPolynomial (DNG opcode 8) for one plane, big-endian as the spec requires.
std::vector<std::uint8_t> mapPolynomial(std::uint32_t plane,
                                        const std::vector<double>& coefficients) {
  std::vector<std::uint8_t> list;
  putBe32(&list, 1U);                       // opcode count
  putBe32(&list, 8U);                       // MapPolynomial
  putBe32(&list, 0x01030000U);              // DNG version 1.3
  putBe32(&list, 0U);                       // flags
  const std::uint32_t degree =
      static_cast<std::uint32_t>(coefficients.size() - 1U);
  putBe32(&list, 36U + 8U * static_cast<std::uint32_t>(coefficients.size()));
  for (std::uint32_t v : {0U, 0U, std::uint32_t(kHeight), std::uint32_t(kWidth)})
    putBe32(&list, v);                      // Top, Left, Bottom, Right
  putBe32(&list, plane);
  putBe32(&list, 1U);                       // Planes
  putBe32(&list, 1U);                       // RowPitch
  putBe32(&list, 1U);                       // ColPitch
  putBe32(&list, degree);
  for (double c : coefficients) putBeDouble(&list, c);
  return list;
}

struct LossyOptions {
  bool progressive = false;
  bool garbageTiles = false;
  std::vector<std::uint8_t> opcodeList2;
  std::uint32_t blackLevel = 0U;
  bool withBlackLevel = false;
};

std::vector<std::uint8_t> makeLossyDng(const LossyOptions& options) {
  std::vector<std::vector<std::uint8_t>> tiles;
  for (int ty = 0; ty < kHeight; ty += kTile) {
    for (int tx = 0; tx < kWidth; tx += kTile) {
      if (options.garbageTiles) {
        tiles.push_back(std::vector<std::uint8_t>(4096, 0x5a));
      } else {
        tiles.push_back(encodeTile(tx, ty, options.progressive));
      }
    }
  }
  std::vector<std::uint32_t> tileSizes;
  for (const auto& t : tiles) tileSizes.push_back(static_cast<std::uint32_t>(t.size()));

  std::vector<Entry> entries = {
      longs(254, {0U}),
      longs(256, {kWidth}),
      longs(257, {kHeight}),
      shorts(258, {8, 8, 8}),
      shorts(259, {0x884C}),
      shorts(262, {34892}),
      ascii(271, "Spektrafilm"),
      ascii(272, "Lossy Fixture"),
      shorts(274, {1}),
      shorts(277, {3}),
      shorts(284, {1}),
      longs(322, {kTile}),
      longs(323, {kTile}),
      longs(324, std::vector<std::uint32_t>(tiles.size(), 0U)),  // patched below
      longs(325, tileSizes),
      bytes(50706, {1, 4, 0, 0}),
      ascii(50708, "Spektrafilm Lossy Fixture"),
      srationals(50721, 9, true),
      srationals(50722, 9, true),
      rationals(50728, 3),
      shorts(50778, {21}),
      shorts(50779, {17}),
  };
  if (options.withBlackLevel) {
    entries.push_back(longs(50714, {options.blackLevel, options.blackLevel,
                                    options.blackLevel}));
  }
  if (!options.opcodeList2.empty()) {
    entries.push_back(undefined(51009, options.opcodeList2));
  }
  std::sort(entries.begin(), entries.end(),
            [](const Entry& a, const Entry& b) { return a.tag < b.tag; });

  std::vector<std::uint8_t> out = {'I', 'I', 42, 0, 8, 0, 0, 0};
  const std::size_t ifdSize = 2U + 12U * entries.size() + 4U;
  std::size_t extraAt = 8U + ifdSize;
  std::vector<std::uint8_t> extra;
  std::vector<std::uint32_t> payloadOffsets(entries.size(), 0U);
  for (std::size_t i = 0; i < entries.size(); ++i) {
    if (entries[i].payload.size() > 4U) {
      if ((extraAt + extra.size()) % 2U) extra.push_back(0);
      payloadOffsets[i] = static_cast<std::uint32_t>(extraAt + extra.size());
      extra.insert(extra.end(), entries[i].payload.begin(), entries[i].payload.end());
    }
  }
  if ((extraAt + extra.size()) % 2U) extra.push_back(0);
  std::uint32_t tileAt = static_cast<std::uint32_t>(extraAt + extra.size());
  std::vector<std::uint32_t> tileOffsets;
  for (const auto& t : tiles) {
    tileOffsets.push_back(tileAt);
    tileAt += static_cast<std::uint32_t>(t.size());
  }
  for (std::size_t i = 0; i < entries.size(); ++i) {
    if (entries[i].tag != 324) continue;
    std::vector<std::uint8_t> p;
    for (std::uint32_t v : tileOffsets) put32(&p, v);
    if (payloadOffsets[i] != 0U) {
      std::copy(p.begin(), p.end(), extra.begin() + (payloadOffsets[i] - extraAt));
    } else {
      entries[i].payload = p;
    }
  }
  put16(&out, static_cast<std::uint16_t>(entries.size()));
  for (std::size_t i = 0; i < entries.size(); ++i) {
    put16(&out, entries[i].tag);
    put16(&out, entries[i].type);
    put32(&out, entries[i].count);
    if (payloadOffsets[i] != 0U) {
      put32(&out, payloadOffsets[i]);
    } else {
      std::vector<std::uint8_t> inl = entries[i].payload;
      inl.resize(4U, 0U);
      out.insert(out.end(), inl.begin(), inl.end());
    }
  }
  put32(&out, 0U);
  out.insert(out.end(), extra.begin(), extra.end());
  for (const auto& t : tiles) out.insert(out.end(), t.begin(), t.end());
  return out;
}

spectrafilm::DecodeResult decode(const std::vector<std::uint8_t>& dng) {
  spectrafilm::DecodeOptions options;
  options.whiteBalance = spectrafilm::WhiteBalanceMode::Daylight;
  return spectrafilm::decodeFromBuffer(dng.data(), dng.size(), options);
}

double meanChannel(const spectrafilm::DecodeResult& result, int x0, int x1,
                   int channel) {
  double sum = 0.0;
  std::size_t n = 0U;
  for (int y = 0; y < result.height; ++y) {
    for (int x = x0; x < x1; ++x) {
      sum += result.rgb[(static_cast<std::size_t>(y) * result.width + x) * 3U +
                        channel];
      ++n;
    }
  }
  return n == 0U ? 0.0 : sum / static_cast<double>(n);
}

}  // namespace

int main() {
  {
    const spectrafilm::DecodeResult result = decode(makeLossyDng({}));
    check(result.ok && result.status == spectrafilm::SFRAW_OK,
          "baseline lossy-JPEG LinearRaw DNG decodes natively", describe(result));
    if (result.ok) {
      const auto& d = result.descriptor;
      check(d.packing == spectrafilm::RawPacking::LossyCompressed,
            "descriptor labels the samples LossyCompressed");
      check(d.containerCompression == 0x884C,
            "descriptor keeps the container compression 0x884C");
      check(d.declaredBitsPerSample == 16 && d.effectiveBitsPerSample == 16,
            "level domain is the decoder's 16-bit linearised domain",
            "declared=" + std::to_string(d.declaredBitsPerSample) +
                " effective=" + std::to_string(d.effectiveBitsPerSample));
      check(d.whiteLevelProvenance ==
                spectrafilm::RawLevelProvenance::DecoderMetadata,
            "WhiteLevel provenance is the decoder, not DNG metadata");
      check(d.pixelLayout == spectrafilm::RawPixelLayout::Linear &&
                d.colorChannels == 3,
            "lossy LinearRaw reports a three-plane linear layout");
      check(result.width == kWidth && result.height == kHeight,
            "lossy decode is full resolution",
            std::to_string(result.width) + "x" + std::to_string(result.height));
      const double left = meanChannel(result, 0, kWidth / 4, 0);
      const double right = meanChannel(result, kWidth * 3 / 4, kWidth, 0);
      check(right > left + 0.05,
            "red ramp survives the JPEG tiles and the 16-bit curve",
            "left=" + std::to_string(left) + " right=" + std::to_string(right));
    }
  }
  {
    LossyOptions options;
    options.progressive = true;
    const spectrafilm::DecodeResult result = decode(makeLossyDng(options));
    check(!result.ok &&
              result.status == spectrafilm::SFRAW_ERR_LOSSY_JPEG_DNG,
          "progressive JPEG tiles fail closed with the platform fallback status",
          describe(result));
  }
  {
    LossyOptions options;
    options.garbageTiles = true;
    const spectrafilm::DecodeResult result = decode(makeLossyDng(options));
    check(!result.ok &&
              result.status == spectrafilm::SFRAW_ERR_LOSSY_JPEG_DNG,
          "non-JPEG tile data fails closed with the platform fallback status",
          describe(result));
  }
  {
    LossyOptions options;
    options.opcodeList2 = mapPolynomial(
        0U, {std::numeric_limits<double>::quiet_NaN(), 1e300, -1e300});
    const spectrafilm::DecodeResult result = decode(makeLossyDng(options));
    // Reaching this line under UBSan (halt_on_error) is the real assertion:
    // the clamp keeps NaN / out-of-range polynomial values defined.
    check(result.ok || result.status != spectrafilm::SFRAW_OK,
          "hostile MapPolynomial coefficients stay defined", describe(result));
  }
  {
    LossyOptions options;
    options.opcodeList2 = mapPolynomial(0U, {0.0, 1.0});
    const spectrafilm::DecodeResult identity = decode(makeLossyDng(options));
    const spectrafilm::DecodeResult standard = decode(makeLossyDng({}));
    check(identity.ok && standard.ok,
          "a linear MapPolynomial on one plane decodes", describe(identity));
    if (identity.ok && standard.ok) {
      bool differs = false;
      for (std::size_t i = 0U; i < identity.rgb.size() && !differs; i += 3U) {
        differs = identity.rgb[i] != standard.rgb[i];
      }
      check(differs,
            "the file's MapPolynomial replaces the default curve for its plane");
    }
  }
  {
    LossyOptions options;
    options.withBlackLevel = true;
    options.blackLevel = 16U;
    const spectrafilm::DecodeResult result = decode(makeLossyDng(options));
    check(!result.ok &&
              result.status == spectrafilm::SFRAW_ERR_PRECISION_METADATA,
          "lossy BlackLevel (left in LibRaw's 8-bit domain) fails closed",
          describe(result));
  }

  if (failures != 0) {
    std::cout << "TESTS FAILED (" << failures << " failures)\n";
    return 1;
  }
  std::cout << "ALL TESTS PASSED\n";
  return 0;
}
