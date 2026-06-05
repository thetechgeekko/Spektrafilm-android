/*
 * Spektrafilm for Android — native engine: .npy / .lut binary loaders.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements npy_lut.h.
 */
#include "io/npy_lut.h"

#include <cmath>
#include <cstring>
#include <fstream>
#include <stdexcept>

namespace spk {
namespace {

std::vector<char> read_all(const std::string& path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) throw std::runtime_error(path + ": cannot open for reading");
    std::streamsize size = in.tellg();
    if (size < 0) throw std::runtime_error(path + ": cannot determine size");
    in.seekg(0, std::ios::beg);
    std::vector<char> buf(static_cast<size_t>(size));
    if (size > 0 && !in.read(buf.data(), size))
        throw std::runtime_error(path + ": read failed");
    return buf;
}

uint16_t rd_u16le(const char* p) {
    return static_cast<uint16_t>((static_cast<uint8_t>(p[0])) |
                                 (static_cast<uint8_t>(p[1]) << 8));
}

uint32_t rd_u32le(const char* p) {
    return static_cast<uint32_t>(static_cast<uint8_t>(p[0])) |
           (static_cast<uint32_t>(static_cast<uint8_t>(p[1])) << 8) |
           (static_cast<uint32_t>(static_cast<uint8_t>(p[2])) << 16) |
           (static_cast<uint32_t>(static_cast<uint8_t>(p[3])) << 24);
}

int32_t rd_i32le(const char* p) { return static_cast<int32_t>(rd_u32le(p)); }

float rd_f32le(const char* p) {
    uint32_t bits = rd_u32le(p);
    float f;
    std::memcpy(&f, &bits, sizeof(f));
    return f;
}

// IEEE-754 half (binary16) little-endian -> double. EXACT (every binary16 value is
// representable in binary64). Builds the result by integer bit-manipulation
// (half -> binary32 bit pattern, then the exact binary32 -> binary64 widening) instead
// of std::ldexp, which the previous version called once PER element — ~3M libm calls
// when loading the 192x192x81 spectra LUT, the bulk of that one-time load. The value is
// bit-identical to the old ldexp path: half -> binary32 is exact (no binary16 overflows
// binary32's range or precision) and binary32 -> binary64 is exact, so the spectra LUT
// parses to the same f64 bits and parity is unchanged. Verified bit-identical over ALL
// 65536 half patterns AND the asset's ~3M elements, and gated end-to-end: the
// engine-parity goldens load the spectra LUT through this path, so any deviation would
// break them. Handles subnormals and inf/nan.
double rd_f16le(const char* p) {
    const uint16_t h = rd_u16le(p);
    const uint32_t sign = static_cast<uint32_t>(h & 0x8000u) << 16;  // -> binary32 bit 31
    uint32_t exp = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x3FFu;
    uint32_t fbits;  // assembled IEEE-754 binary32 bit pattern
    if (exp == 0u) {
        if (mant == 0u) {
            fbits = sign;  // signed zero
        } else {
            // subnormal half -> normalized binary32. Shift the mantissa up until its
            // implicit leading 1 appears, adjusting the exponent (== mant * 2^-24).
            exp = 1u;
            while ((mant & 0x400u) == 0u) { mant <<= 1; --exp; }
            mant &= 0x3FFu;  // drop the now-implicit leading 1
            fbits = sign | ((exp - 15u + 127u) << 23) | (mant << 13);
        }
    } else if (exp == 0x1Fu) {
        // inf (mant==0) or nan -> binary32 inf/nan (payload preserved in the high bits).
        fbits = sign | 0x7F800000u | (mant << 13);
    } else {
        // normal: rebias the 5-bit excess-15 exponent to 8-bit excess-127; widen mant.
        fbits = sign | ((exp - 15u + 127u) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &fbits, sizeof(f));
    return static_cast<double>(f);
}

// Extract the value of a key like 'shape' or 'descr' from the .npy header dict
// string (a Python literal). Minimal parser: finds "key':" then reads until the
// matching value delimiter.
std::string header_value(const std::string& hdr, const std::string& key) {
    std::string needle = "'" + key + "'";
    size_t k = hdr.find(needle);
    if (k == std::string::npos) throw std::runtime_error(".npy: missing key " + key);
    size_t colon = hdr.find(':', k);
    if (colon == std::string::npos) throw std::runtime_error(".npy: malformed header");
    size_t i = colon + 1;
    while (i < hdr.size() && (hdr[i] == ' ' || hdr[i] == '\t')) ++i;
    if (i < hdr.size() && hdr[i] == '(') {  // tuple value (shape)
        size_t close = hdr.find(')', i);
        return hdr.substr(i + 1, close - i - 1);
    }
    if (i < hdr.size() && hdr[i] == '\'') {  // quoted value (descr)
        size_t close = hdr.find('\'', i + 1);
        return hdr.substr(i + 1, close - i - 1);
    }
    // bareword (e.g. True/False)
    size_t end = hdr.find_first_of(",}", i);
    return hdr.substr(i, end - i);
}

}  // namespace

NdArray parse_npy(const char* data, size_t len, const std::string& path) {
    if (len < 12) throw std::runtime_error(path + ": too small for .npy");

    static const char kMagic[6] = {'\x93', 'N', 'U', 'M', 'P', 'Y'};
    if (std::memcmp(data, kMagic, 6) != 0)
        throw std::runtime_error(path + ": not a .npy file (bad magic)");

    uint8_t major = static_cast<uint8_t>(data[6]);
    size_t header_len;
    size_t data_off;
    if (major == 1) {
        header_len = rd_u16le(data + 8);
        data_off = 10 + header_len;
    } else if (major == 2) {
        header_len = rd_u32le(data + 8);
        data_off = 12 + header_len;
    } else {
        throw std::runtime_error(path + ": unsupported .npy version");
    }
    if (data_off > len) throw std::runtime_error(path + ": truncated header");

    std::string hdr(data + (data_off - header_len), header_len);

    std::string descr = header_value(hdr, "descr");
    std::string fortran = header_value(hdr, "fortran_order");
    if (fortran.find("True") != std::string::npos)
        throw std::runtime_error(path + ": fortran_order not supported");

    // Parse shape tuple, e.g. "192, 192, 81" or "192, 192, 81," (trailing comma).
    std::string shp = header_value(hdr, "shape");
    NdArray arr;
    {
        size_t i = 0;
        while (i < shp.size()) {
            while (i < shp.size() && (shp[i] == ' ' || shp[i] == ',')) ++i;
            if (i >= shp.size()) break;
            size_t j = i;
            while (j < shp.size() && shp[j] >= '0' && shp[j] <= '9') ++j;
            if (j > i) arr.shape.push_back(std::stoi(shp.substr(i, j - i)));
            i = j;
        }
    }
    if (arr.shape.empty()) throw std::runtime_error(path + ": empty/scalar shape");

    size_t n = arr.count();
    arr.data.resize(n);
    const char* p = data + data_off;

    // Supported little-endian float dtypes. '<f2'/'|f2', '<f4', '<f8'.
    int itemsize;
    int kind;  // 2=f16, 4=f32, 8=f64
    if (descr == "<f2" || descr == "|f2" || descr == "f2") { itemsize = 2; kind = 2; }
    else if (descr == "<f4" || descr == "=f4" || descr == "f4") { itemsize = 4; kind = 4; }
    else if (descr == "<f8" || descr == "=f8" || descr == "f8") { itemsize = 8; kind = 8; }
    else throw std::runtime_error(path + ": unsupported dtype '" + descr + "'");

    if (data_off + n * static_cast<size_t>(itemsize) > len)
        throw std::runtime_error(path + ": truncated payload");

    for (size_t idx = 0; idx < n; ++idx) {
        const char* q = p + idx * static_cast<size_t>(itemsize);
        if (kind == 2) {
            arr.data[idx] = rd_f16le(q);
        } else if (kind == 4) {
            arr.data[idx] = static_cast<double>(rd_f32le(q));
        } else {
            uint64_t lo = rd_u32le(q);
            uint64_t hi = rd_u32le(q + 4);
            uint64_t bits = lo | (hi << 32);
            double d;
            std::memcpy(&d, &bits, sizeof(d));
            arr.data[idx] = d;
        }
    }
    return arr;
}

NdArray load_npy(const std::string& path) {
    std::vector<char> buf = read_all(path);
    return parse_npy(buf.data(), buf.size(), path);
}

NdArray load_coeffs_lut(const std::string& path) {
    std::vector<char> buf = read_all(path);
    if (buf.size() < 16) throw std::runtime_error(path + ": too small for .lut");

    // header: 4 x int32 (magic0, magic1, width, height)
    int32_t width = rd_i32le(buf.data() + 8);
    int32_t height = rd_i32le(buf.data() + 12);
    if (width <= 0 || height <= 0)
        throw std::runtime_error(path + ": bad lut dimensions");

    const size_t pixel_bytes = 16;  // 4 x float32
    size_t need = 16 + static_cast<size_t>(width) * height * pixel_bytes;
    if (need > buf.size())
        throw std::runtime_error(path + ": truncated lut payload");

    NdArray arr;
    arr.shape = {width, height, 4};
    arr.data.resize(static_cast<size_t>(width) * height * 4);

    // Pixels iterated as: for j in [0,height): for i in [0,width): read pixel,
    // stored as px[i][j]. Output array indexed [i][j][c] (C-order: i*H*4+j*4+c).
    const char* p = buf.data() + 16;
    for (int j = 0; j < height; ++j) {
        for (int i = 0; i < width; ++i) {
            const char* q = p + (static_cast<size_t>(j) * width + i) * pixel_bytes;
            size_t base = (static_cast<size_t>(i) * height + j) * 4;
            for (int c = 0; c < 4; ++c)
                arr.data[base + c] = static_cast<double>(rd_f32le(q + c * 4));
        }
    }
    return arr;
}

}  // namespace spk
