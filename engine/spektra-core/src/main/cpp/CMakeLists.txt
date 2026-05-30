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
        kernels/exponential_filter.cpp      # float64 Gaussian + exponential mixture (spatial)
        kernels/spectral_upsampling.cpp     # Hanatos2025 RGB->spectrum (M3)
        kernels/stats.cpp                   # Poisson + Binomial samplers (grain, M6)
        kernels/lut3d.cpp                   # opt-in PCHIP 3D LUT accel (M7, default OFF)
        # LUT/asset loaders:
        io/npy_lut.cpp                       # .npy / .lut binary loaders (M3)
        # Model (photographic math):
        model/spectral.cpp
        model/conversions.cpp
        model/density_curves.cpp
        model/emulsion.cpp
        model/glare.cpp
        model/diffusion.cpp                  # in-emulsion scatter + halation (spatial)
        model/grain.cpp                      # AgX particle grain model (M6)
        model/color_output.cpp               # XYZ->output RGB + CCTF (M3)
        # Profiles + runtime stages:
        profiles/profile.cpp                 # profile JSON loader (M3)
        runtime/stages/scanning.cpp          # scan_film negative -> RGB (M3)
        # Filming stage (rgb -> raw -> negative density), ported in M4:
        model/couplers.cpp                   # DIR couplers (M4)
        runtime/params.cpp                   # minimal digested filming params (M4)
        runtime/stages/filming.cpp           # rgb -> raw -> film density CMY (M4)
        # Printing (enlarger) stage + dichroic filters, ported in M4
        # (negative -> enlarger expose -> print develop -> scan print):
        model/color_filters.cpp              # dichroic Y/M/C enlarger filters (M4)
        runtime/stages/printing.cpp          # film density -> print density CMY (M4)
        runtime/print_digest.cpp             # native neutral CC + midgray digest (M5)
        runtime/stages/crop_resize.cpp       # crop + cubic resize geometry stage (M7)
        runtime/stages/autoexposure.cpp      # center-weighted metering -> EV gain (M7)
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
#   tests/test_scanning.cpp              (scan_portra final_rgb sRGB: max_abs ~6e-8)
#   tests/test_output_spaces.cpp         (scan_portra final_rgb for Adobe RGB /
#                                         ProPhoto / Rec.2020 / ACES2065-1 /
#                                         linear sRGB vs oracle: max_abs ~7e-8;
#                                         refs from tests/gen_output_spaces_ref.py)
#   tests/test_filming.cpp               (scan_portra film_log_raw/density: max_abs ~2e-7)
#   tests/test_spatial.cpp               (scan_portra_spatial film_density_cmy +
#                                         final_rgb: spatial branch, max_abs ~2e-7)
#   tests/test_crop_resize.cpp           (scan_portra_crop: non-default crop +
#                                         cubic upscale geometry stage; geometry
#                                         64x64 -> 66x56 + film_density_cmy/final_rgb
#                                         vs oracle: max_abs ~2e-7)
#   tests/test_autoexposure.cpp          (scan_portra_autoexp: NON-default
#                                         auto_exposure ON, center_weighted
#                                         metering -> EV gain; film_log_raw /
#                                         film_density_cmy / final_rgb vs oracle:
#                                         max_abs ~1e-7; + AE-off==baseline no-op)
# Statistical grain gates (RNG streams differ; verified by distribution + stats):
#   tests/test_grain.cpp                 (non-sublayer apply_grain_to_density:
#                                         mean preservation, noise std ±15%)
#   tests/test_grain_sublayer.cpp        (sublayer apply_grain_to_density_layers +
#                                         add_micro_structure: mean preservation,
#                                         noise std ±15%, distinct noise character
#                                         vs the non-sublayer path). Build:
#     g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
#       tests/test_grain_sublayer.cpp model/grain.cpp model/density_curves.cpp \
#       kernels/stats.cpp kernels/gaussian.cpp kernels/interp.cpp \
#       profiles/profile.cpp -o /tmp/test_grain_sublayer
#   References regenerated by tests/gen_grain_sublayer_ref.py (oracle, fixed seed).
# See tests/ and tools/parity/ for the build/run commands.
