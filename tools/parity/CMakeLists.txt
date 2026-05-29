# SpectraFilm for Android — golden-vector parity harness.
# Copyright (C) 2026 SpectraFilm Android contributors.
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See <https://www.gnu.org/licenses/>.
#
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
# spektrafilm.
#
# Standalone HOST build of the .spkvec comparator. This is intentionally
# independent of the Android `spektra-core` module / NDK toolchain: it builds a
# plain command-line tool that CI runs on the host to gate stage-by-stage parity.
#
#   cmake -S . -B build && cmake --build build
#   ./build/spkvec_compare --selftest
#   ctest --test-dir build        # runs the self-test
#
cmake_minimum_required(VERSION 3.16)
project(spektra_parity LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

add_executable(spkvec_compare compare_main.cpp)
# spkvec_io.h is header-only and lives next to this CMakeLists.
target_include_directories(spkvec_compare PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if(MSVC)
  target_compile_options(spkvec_compare PRIVATE /W4)
else()
  target_compile_options(spkvec_compare PRIVATE -Wall -Wextra)
endif()

enable_testing()
# Self-test keeps spkvec_io.h byte-compatible with spkvec.py.
add_test(NAME spkvec_selftest COMMAND spkvec_compare --selftest)
