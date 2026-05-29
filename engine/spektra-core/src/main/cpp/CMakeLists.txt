# SpectraFilm for Android — native engine build.
# GPLv3. Builds libspektra.so (engine + JNI bridge).
cmake_minimum_required(VERSION 3.22.1)
project(spektra LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Release: optimize for the per-pixel spectral loops. -ffast-math is acceptable here
# (no NaN/Inf semantics relied upon) and helps the 441-band integration; revisit if a
# golden-vector tolerance ever requires strict IEEE.
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -fno-finite-math-only")

add_library(spektra SHARED
        spektra.cpp
        spektra_jni.cpp
        # Ported pure-math + kernels (M3 wave 1):
        kernels/interp.cpp
        kernels/gaussian.cpp
        model/spectral.cpp
        model/conversions.cpp
        model/density_curves.cpp
        model/emulsion.cpp
        model/glare.cpp
        # M3–M4: remaining stages to add as ported, e.g.
        #   model/couplers.cpp model/grain.cpp model/diffusion.cpp
        #   runtime/pipeline.cpp runtime/stages/filming.cpp runtime/stages/printing.cpp
        #   runtime/stages/scanning.cpp
        #   kernels/interp_lut.cpp kernels/stats.cpp kernels/spectral_upsampling.cpp
)

target_include_directories(spektra PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Android system libs: log for diagnostics, android + jnigraphics for AAssetManager/Bitmap.
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(spektra ${log-lib} ${android-lib} ${jnigraphics-lib})

# M2: link LibRaw here (or keep it in lib:libraw and pass decoded buffers in).
# target_link_libraries(spektra raw)

# --- New files (M3 spectral upsampling port) ---------------------------------
# Hanatos2025 RGB->spectrum upsampling + dependency-free LUT loaders. Add these
# to the add_library(spektra ...) source list when wiring the runtime stage:
#   io/npy_lut.cpp
#   kernels/spectral_upsampling.cpp
# Host parity test (not part of libspektra.so; built standalone with g++ -std=c++17
# -I . -I tools/parity): tests/test_spectral_upsampling.cpp
