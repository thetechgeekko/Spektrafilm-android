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
        # M3–M4: add per-stage sources as they are ported, e.g.
        #   model/density_curves.cpp model/couplers.cpp model/grain.cpp model/diffusion.cpp
        #   runtime/pipeline.cpp runtime/stages/filming.cpp runtime/stages/printing.cpp
        #   runtime/stages/scanning.cpp
        #   kernels/gaussian.cpp kernels/interp_lut.cpp kernels/stats.cpp
        #   kernels/spectral_upsampling.cpp
)

target_include_directories(spektra PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Android system libs: log for diagnostics, android + jnigraphics for AAssetManager/Bitmap.
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(spektra ${log-lib} ${android-lib} ${jnigraphics-lib})

# M2: link LibRaw here (or keep it in lib:libraw and pass decoded buffers in).
# target_link_libraries(spektra raw)
