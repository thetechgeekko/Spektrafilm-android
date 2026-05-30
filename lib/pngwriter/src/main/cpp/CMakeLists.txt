# SpectraFilm for Android — lib:pngwriter native build.
# Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
#
# Builds libsfpng.so: a 16-bit-per-channel RGB PNG writer backed by zlib for
# deflate (IDAT) and CRC32 per chunk, plus its JNI bridge.
#
# zlib dependency:
#   The Android NDK sysroot ships zlib as both a static archive (libz.a) and a
#   shared stub (libz.so / system libz) for every ABI. find_library(z-lib z)
#   resolves to the NDK sysroot copy; at runtime on-device the system libz.so
#   is used (available since API 1). This mirrors how lib:libraw links its own
#   zlib dependency. No bundled zlib sources are needed.
#
# Host build (for the unit test — the test uses g++ directly, not CMake):
#   g++ -std=c++17 -O2 -I<src/main/cpp> \
#       png_writer.cpp tests/test_png_writer.cpp -lz \
#       -o /tmp/test_png_writer
#   The core writer (png_writer.cpp) is dependency-free except for <zlib.h>
#   and -lz, which are available via zlib1g-dev on any Debian/Ubuntu host.

cmake_minimum_required(VERSION 3.22.1)
project(sfpng LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT CMAKE_CXX_FLAGS_RELEASE)
    set(CMAKE_CXX_FLAGS_RELEASE "-O2")
endif ()

add_library(sfpng SHARED
        png_writer.cpp
        png_writer_jni.cpp
)

target_include_directories(sfpng PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# zlib: available in every Android API level. find_library resolves to the NDK
# sysroot stub; the runtime linker binds to /system/lib[64]/libz.so on-device.
find_library(z-lib z)
target_link_libraries(sfpng ${z-lib})

# Android system libs: log for diagnostics. Guarded so this CMakeLists can also
# be configured for a host build if ever desired.
if (ANDROID)
    find_library(log-lib log)
    target_link_libraries(sfpng ${log-lib})
endif ()
