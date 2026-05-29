# SpectraFilm for Android — native engine build.
# GPLv3. Builds libspektra.so (engine + JNI bridge).
cmake_minimum_required(VERSION 3.22.1)
project(spektra LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Release: optimize for the per-pixel spectral loops. -ffast-math speeds the 81-band
# integration; -fno-finite-math-only is REQUIRED — the scanning stage relies on NaN
# propagation through density_to_light to match spektrafilm's profile null handling.
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -fno-finite-math-only")

add_library(spektra SHARED
        spektra.cpp
        spektra_jni.cpp
        # Kernels:
        kernels/interp.cpp
        kernels/gaussian.cpp
        kernels/spectral_upsampling.cpp     # Hanatos2025 RGB->spectrum (M3)
        # LUT/asset loaders:
        io/npy_lut.cpp                       # .npy / .lut binary loaders (M3)
        # Model (photographic math):
        model/spectral.cpp
        model/conversions.cpp
        model/density_curves.cpp
        model/emulsion.cpp
        model/glare.cpp
        model/color_output.cpp               # XYZ->output RGB + CCTF (M3)
        # Profiles + runtime stages:
        profiles/profile.cpp                 # profile JSON loader (M3)
        runtime/stages/scanning.cpp          # scan_film negative -> RGB (M3)
        # M3–M4: remaining stages to add as ported, e.g.
        #   model/grain.cpp model/diffusion.cpp
        #   runtime/pipeline.cpp runtime/stages/printing.cpp
        #   kernels/interp_lut.cpp kernels/stats.cpp
        # Filming stage (rgb -> raw -> negative density), ported in M4:
        model/couplers.cpp                   # DIR couplers (M4)
        runtime/params.cpp                   # minimal digested filming params (M4)
        runtime/stages/filming.cpp           # rgb -> raw -> film density CMY (M4)
        # Printing (enlarger) stage + dichroic filters, ported in M4
        # (negative -> enlarger expose -> print develop -> scan print):
        model/color_filters.cpp              # dichroic Y/M/C enlarger filters (M4)
        runtime/stages/printing.cpp          # film density -> print density CMY (M4)
        runtime/print_digest.cpp             # native neutral CC + midgray digest (M5)
)

target_include_directories(spektra PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Android system libs: log for diagnostics, android + jnigraphics for AAssetManager/Bitmap.
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(spektra ${log-lib} ${android-lib} ${jnigraphics-lib})

# M2: link LibRaw here (or keep it in lib:libraw and pass decoded buffers in).
# target_link_libraries(spektra raw)

# --- Host parity tests (NOT part of libspektra.so) ---------------------------
# Standalone bit-exact gates vs the committed goldens (need spektrafilm's LUT/profile
# data, available locally or after asset-bundling at M1):
#   tests/test_spectral_upsampling.cpp   (max_abs ~1e-7 vs oracle)
#   tests/test_scanning.cpp              (scan_portra final_rgb: max_abs ~6e-8)
# See tests/ and tools/parity/ for the build/run commands.
