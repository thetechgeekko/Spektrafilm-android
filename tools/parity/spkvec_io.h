/*
 * Spektrafilm for Android — golden-vector parity harness.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * --------------------------------------------------------------------------------
 * Header-only reader/writer for the portable .spkvec golden-vector container
 * (see spkvec_format.md). Single dense float32 array, little-endian, row-major.
 * The canonical NumPy implementation is spkvec.py; the two are kept byte
 * compatible by compare_main.cpp --selftest.
 *
 * Assumes a little-endian host (all CI hosts and Android ARM targets are LE).
 * --------------------------------------------------------------------------------
 */
#ifndef SPKVEC_IO_H
#define SPKVEC_IO_H

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace spkvec {

inline constexpr char kMagic[6] = {'S', 'P', 'K', 'V', 'E', 'C'};
inline constexpr uint16_t kVersion = 1;
inline constexpr uint8_t kDtypeF32 = 1;
inline constexpr uint8_t kMaxNdim = 8;

/* A dense float32 array: flat row-major data plus its shape. */
struct Array {
    std::vector<uint32_t> shape;
    std::vector<float> data;

    size_t count() const {
        size_t n = shape.empty() ? 0 : 1;
        for (uint32_t d : shape) n *= static_cast<size_t>(d);
        return n;
    }
};

namespace detail {

template <typename T>
inline T read_scalar(std::istream& in, const std::string& path) {
    T value{};
    in.read(reinterpret_cast<char*>(&value), sizeof(T));
    if (!in) throw std::runtime_error(path + ": truncated header");
    return value;  /* little-endian host assumed */
}

template <typename T>
inline void write_scalar(std::ostream& out, T value) {
    out.write(reinterpret_cast<const char*>(&value), sizeof(T));
}

}  // namespace detail

/* Read a .spkvec file. Throws std::runtime_error on any malformed input. */
inline Array read(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error(path + ": cannot open for reading");

    char magic[6];
    in.read(magic, sizeof(magic));
    if (!in || std::memcmp(magic, kMagic, sizeof(magic)) != 0)
        throw std::runtime_error(path + ": not a spkvec file (bad magic)");

    uint16_t version = detail::read_scalar<uint16_t>(in, path);
    if (version != kVersion)
        throw std::runtime_error(path + ": unsupported version " + std::to_string(version));

    uint8_t dtype = detail::read_scalar<uint8_t>(in, path);
    if (dtype != kDtypeF32)
        throw std::runtime_error(path + ": unsupported dtype code " + std::to_string(dtype));

    uint8_t ndim = detail::read_scalar<uint8_t>(in, path);
    if (ndim < 1 || ndim > kMaxNdim)
        throw std::runtime_error(path + ": bad ndim " + std::to_string(ndim));

    Array arr;
    arr.shape.resize(ndim);
    for (uint8_t i = 0; i < ndim; ++i)
        arr.shape[i] = detail::read_scalar<uint32_t>(in, path);

    size_t count = arr.count();
    arr.data.resize(count);
    in.read(reinterpret_cast<char*>(arr.data.data()),
            static_cast<std::streamsize>(count * sizeof(float)));
    if (!in) throw std::runtime_error(path + ": truncated payload");

    in.peek();
    if (!in.eof()) throw std::runtime_error(path + ": trailing bytes after payload");
    return arr;
}

/* Write a flat float32 buffer with the given shape as a .spkvec file. */
inline void write(const std::string& path, const std::vector<uint32_t>& shape,
                  const float* data, size_t count) {
    if (shape.empty() || shape.size() > kMaxNdim)
        throw std::runtime_error("spkvec::write: ndim out of range");

    std::ofstream out(path, std::ios::binary);
    if (!out) throw std::runtime_error(path + ": cannot open for writing");

    out.write(kMagic, sizeof(kMagic));
    detail::write_scalar<uint16_t>(out, kVersion);
    detail::write_scalar<uint8_t>(out, kDtypeF32);
    detail::write_scalar<uint8_t>(out, static_cast<uint8_t>(shape.size()));
    for (uint32_t d : shape) detail::write_scalar<uint32_t>(out, d);
    out.write(reinterpret_cast<const char*>(data),
              static_cast<std::streamsize>(count * sizeof(float)));
    if (!out) throw std::runtime_error(path + ": write failed");
}

inline void write(const std::string& path, const Array& arr) {
    write(path, arr.shape, arr.data.data(), arr.count());
}

}  // namespace spkvec

#endif  // SPKVEC_IO_H
