# SpectraFilm for Android — lib:libraw native build.
# Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
# This module uses LibRaw (LGPL-2.1), linked into the GPLv3 app.
#
# Builds libsfraw.so: the rawpy-parity RAW/DNG decoder + JNI bridge.
#
# --------------------------------------------------------------------------
# VENDORING LIBRAW
# --------------------------------------------------------------------------
# LibRaw is NOT bundled in this repo. Add it as a git submodule (preferred) or
# vendor a release tarball under:   src/main/cpp/libraw/upstream
#
#   git submodule add https://github.com/LibRaw/LibRaw \
#       lib/libraw/src/main/cpp/libraw/upstream
#
# LibRaw has no first-party CMake build that targets the NDK cleanly, so we
# compile its amalgamated/static sources directly here. The expected layout is
# the stock LibRaw checkout:
#     libraw/upstream/libraw/libraw.h        (public headers)
#     libraw/upstream/src/**/*.cpp           (implementation)
#     libraw/upstream/internal/*.cpp
# --------------------------------------------------------------------------

cmake_minimum_required(VERSION 3.22.1)
project(sfraw LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Release: the demosaic/colour loops are the hot path. -O3 is safe; we keep IEEE
# semantics here because the Von-Kries / white-balance multipliers must match the
# desktop rawpy output to within golden-vector tolerance.
set(CMAKE_CXX_FLAGS_RELEASE "-O3")

set(LIBRAW_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libraw/upstream")

# --------------------------------------------------------------------------
# LibRaw static library (built only when vendored).
# --------------------------------------------------------------------------
if (EXISTS "${LIBRAW_DIR}/libraw/libraw.h")
    # Sensible source glob for a stock LibRaw checkout. LibRaw groups its sources
    # under src/ (demosaic, decoders, metadata, postprocessing, write, utils) and
    # a few helpers under internal/. Globbing keeps this resilient across LibRaw
    # versions; pin to an explicit list if reproducibility matters more than
    # convenience.
    file(GLOB_RECURSE LIBRAW_SOURCES
            "${LIBRAW_DIR}/src/*.cpp"
            "${LIBRAW_DIR}/internal/*.cpp"
    )
    # demosaic/decoder packs are #included by other TUs (LibRaw "*_common" pattern);
    # excluding them avoids duplicate-symbol errors. Adjust to the version vendored.
    list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*(_common|placeholder)\\.cpp$")

    add_library(raw STATIC ${LIBRAW_SOURCES})
    target_include_directories(raw PUBLIC "${LIBRAW_DIR}")
    # LibRaw build knobs. We disue the heavy optional deps (jasper/jpeg/lcms) to
    # keep the .so small; baseline DNG + common RAW decode without them. Revisit
    # in M2 if lossy-DNG / embedded-JPEG-in-DNG support is required.
    target_compile_definitions(raw PUBLIC
            NO_JASPER
            NO_JPEG
            NO_LCMS
            LIBRAW_NODLL
    )
    target_compile_options(raw PRIVATE -w)  # silence LibRaw's own warnings
    set(SFRAW_HAVE_LIBRAW TRUE)
else ()
    message(WARNING
            "LibRaw not found at ${LIBRAW_DIR}. "
            "libsfraw.so will build but decode() will return an error at runtime. "
            "Vendor LibRaw (see the header of this file) to enable RAW decoding.")
    set(SFRAW_HAVE_LIBRAW FALSE)
endif ()

# --------------------------------------------------------------------------
# Our decoder + JNI bridge.
# --------------------------------------------------------------------------
add_library(sfraw SHARED
        raw_decoder.cpp
        raw_decoder_jni.cpp
)

target_include_directories(sfraw PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if (SFRAW_HAVE_LIBRAW)
    target_link_libraries(sfraw raw)
    target_include_directories(sfraw PRIVATE "${LIBRAW_DIR}")
endif ()

# Android system libs: log for diagnostics; jnigraphics for optional Bitmap output;
# android for AAssetManager / fd helpers.
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(sfraw ${log-lib} ${android-lib} ${jnigraphics-lib})
