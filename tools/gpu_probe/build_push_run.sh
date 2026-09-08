#!/usr/bin/env bash
#
# Spektrafilm for Android — GPU device probe: build + push + run. GPLv3.
# Film modeling powered by spektrafilm.
#
# Cross-compiles the standalone probe (tools/gpu_probe/) for arm64 with the NDK,
# pushes it to /data/local/tmp on the connected device, runs Tier 0/1/2 (and the
# Tier 3 shader variants when glslc is available), and saves every capture under
# tools/gpu_probe/captures/. Engine sources are compiled AS-IS from engine/ —
# nothing under engine/ is modified. The f64 reference mandate: no -ffast-math
# anywhere in this build (enforced with -fno-fast-math).
#
# Usage: bash tools/gpu_probe/build_push_run.sh   (from anywhere; repo-root aware)
# Env:   ANDROID_NDK (default: ~/AppData/Local/Android/Sdk/ndk/28.2.13676358)
#        ADB_SERIAL  (default: the only connected device)
set -euo pipefail
cd "$(dirname "$0")/../.."
# Git-Bash/MSYS rewrites device paths like /data/local/tmp into Windows paths
# before adb sees them — disable that conversion for everything below.
export MSYS2_ARG_CONV_EXCL="*"
export MSYS_NO_PATHCONV=1

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
  Darwin)               HOST=darwin-x86_64 ;;
  *)                    HOST=linux-x86_64 ;;
esac
NDK="${ANDROID_NDK:-$HOME/AppData/Local/Android/Sdk/ndk/28.2.13676358}"
CXX="$NDK/toolchains/llvm/prebuilt/$HOST/bin/clang++"
GLSLC="$NDK/shader-tools/$HOST/glslc"
[[ -x "$CXX" || -x "$CXX.exe" ]] || { echo "NDK clang++ not found: $CXX (set ANDROID_NDK)"; exit 1; }

adbw() { if [[ -n "${ADB_SERIAL:-}" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi }

CPP=engine/spektra-core/src/main/cpp
OUT=tools/gpu_probe/build
CAP=tools/gpu_probe/captures
mkdir -p "$OUT" "$CAP"

PROBE_SRC="tools/gpu_probe/probe_main.cpp tools/gpu_probe/ref_scan_f64.cpp"
ENGINE_SRC="$CPP/profiles/profile.cpp $CPP/model/spectral.cpp $CPP/model/color_output.cpp"
# android30: the probe queries Vulkan 1.1 entry points (vkGetPhysicalDevice*2),
# absent from the API-24 libvulkan stub. Probe-only; the app's minSdk is untouched.
FLAGS="--target=aarch64-linux-android30 -std=c++17 -O2 -fno-fast-math \
  -DSPK_ENABLE_VULKAN -Itools/parity -Itools/gpu_probe -static-libstdc++"

echo "== build: base probe (vendored SPIR-V, unmodified host) =="
"$CXX" $FLAGS -I"$CPP" $PROBE_SRC $ENGINE_SRC "$CPP/gpu/vulkan_compute.cpp" \
  -lvulkan -o "$OUT/gpu_probe"

# ── Tier 3 shader variants (optional; skipped cleanly without glslc) ────────
BINS="gpu_probe"
if [[ -x "$GLSLC" || -x "$GLSLC.exe" ]]; then
  for V in precise mediump; do
    VDIR="$OUT/tier3_$V/gpu"
    mkdir -p "$VDIR"
    case "$V" in
      precise)  # NoContraction on the band-loop accumulation chain
        sed 's/^    float X = 0.0, Y = 0.0, Z = 0.0;/    precise float X = 0.0, Y = 0.0, Z = 0.0;/' \
          "$CPP/gpu/scan_spectral.comp" > "$VDIR/scan_spectral_$V.comp" ;;
      mediump)  # RelaxedPrecision everywhere (driver MAY evaluate as fp16)
        sed '1a precision mediump float;' \
          "$CPP/gpu/scan_spectral.comp" > "$VDIR/scan_spectral_$V.comp" ;;
    esac
    if "$GLSLC" -fshader-stage=compute --target-env=vulkan1.1 -mfmt=c \
         -o "$VDIR/scan_spectral_spv.inc" "$VDIR/scan_spectral_$V.comp"; then
      cat > "$VDIR/scan_spectral_spv.h" <<'EOF'
// Tier-3 probe variant SPIR-V (generated; shadows gpu/scan_spectral_spv.h).
#ifndef SPK_GPU_SCAN_SPECTRAL_SPV_H
#define SPK_GPU_SCAN_SPECTRAL_SPV_H
#include <cstdint>
static const uint32_t kScanSpectralSpv[] =
#include "scan_spectral_spv.inc"
;
#endif
EOF
      echo "== build: tier3 variant $V =="
      "$CXX" $FLAGS -I"$OUT/tier3_$V" -I"$CPP" $PROBE_SRC $ENGINE_SRC \
        "$CPP/gpu/vulkan_compute.cpp" -lvulkan -o "$OUT/gpu_probe_$V"
      BINS="$BINS gpu_probe_$V"
    else
      echo "tier3 $V: glslc failed — skipping variant"
    fi
  done
else
  echo "glslc not found — skipping Tier 3 variants"
fi

# ── M2 (#147): filming + printing kernels — probe-local shaders ─────────────
# Engine sources compiled ONCE into a static archive (they are identical for all
# three shader variants), then probe_m2_main.cpp is rebuilt per variant against
# that variant's SPIR-V. glslc is REQUIRED for M2 (the shaders live under
# tools/, no vendored SPIR-V); without it the M2 tier is skipped loudly.
M2BINS=""
if [[ -x "$GLSLC" || -x "$GLSLC.exe" ]]; then
  AR="$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-ar"
  ENGINE_A="$OUT/libspk_engine_m2.a"
  # Rebuild the archive when missing or when any probe-common source is newer
  # (engine edits: delete $ENGINE_A or set M2_REBUILD=1).
  if [[ ! -f "$ENGINE_A" || -n "${M2_REBUILD:-}" \
        || tools/gpu_probe/gpu_dispatch.cpp -nt "$ENGINE_A" \
        || tools/gpu_probe/ref_filming_f64.cpp -nt "$ENGINE_A" \
        || tools/gpu_probe/ref_printing_f64.cpp -nt "$ENGINE_A" ]]; then
    echo "== build: M2 engine archive (full engine, -fno-fast-math) =="
    M2OBJ="$OUT/m2_obj"
    rm -rf "$M2OBJ"; mkdir -p "$M2OBJ"
    i=0
    for SRC_F in "$CPP"/spektra.cpp "$CPP"/kernels/*.cpp "$CPP"/io/*.cpp \
                 "$CPP"/model/*.cpp "$CPP"/profiles/*.cpp "$CPP"/runtime/*.cpp \
                 "$CPP"/runtime/stages/*.cpp \
                 tools/gpu_probe/gpu_dispatch.cpp \
                 tools/gpu_probe/ref_filming_f64.cpp \
                 tools/gpu_probe/ref_printing_f64.cpp; do
      "$CXX" $FLAGS -I"$CPP" -c "$SRC_F" -o "$M2OBJ/$i-$(basename "${SRC_F%.cpp}").o" &
      i=$((i+1))
      if (( i % 8 == 0 )); then wait; fi
    done
    wait
    rm -f "$ENGINE_A"
    "$AR" rcs "$ENGINE_A" "$M2OBJ"/*.o
  fi

  build_m2_variant() { # variant-name (default|precise|mediump)
    local V="$1"
    local VDIR="$OUT/m2_$V"
    mkdir -p "$VDIR"
    case "$V" in
      default)
        cp tools/gpu_probe/filming.comp "$VDIR/filming.comp"
        cp tools/gpu_probe/printing.comp "$VDIR/printing.comp" ;;
      precise)  # NoContraction on the main accumulation chains
        sed 's/^    float acc0 = 0.0, acc1 = 0.0, acc2 = 0.0, wsum = 0.0;/    precise float acc0 = 0.0, acc1 = 0.0, acc2 = 0.0, wsum = 0.0;/' \
          tools/gpu_probe/filming.comp > "$VDIR/filming.comp"
        sed 's/^    float R0 = 0.0, R1 = 0.0, R2 = 0.0;/    precise float R0 = 0.0, R1 = 0.0, R2 = 0.0;/' \
          tools/gpu_probe/printing.comp > "$VDIR/printing.comp" ;;
      mediump)  # RelaxedPrecision everywhere (driver MAY evaluate as fp16)
        sed '1a precision mediump float;' tools/gpu_probe/filming.comp > "$VDIR/filming.comp"
        sed '1a precision mediump float;' tools/gpu_probe/printing.comp > "$VDIR/printing.comp" ;;
    esac
    "$GLSLC" -fshader-stage=compute --target-env=vulkan1.1 -mfmt=c \
      -o "$VDIR/filming_spv.inc" "$VDIR/filming.comp" || return 1
    "$GLSLC" -fshader-stage=compute --target-env=vulkan1.1 -mfmt=c \
      -o "$VDIR/printing_spv.inc" "$VDIR/printing.comp" || return 1
    cat > "$VDIR/filming_spv.h" <<'EOF'
// Probe M2 SPIR-V (generated by build_push_run.sh from filming.comp).
#ifndef SPK_GPU_PROBE_FILMING_SPV_H
#define SPK_GPU_PROBE_FILMING_SPV_H
#include <cstdint>
static const uint32_t kFilmingSpv[] =
#include "filming_spv.inc"
;
#endif
EOF
    cat > "$VDIR/printing_spv.h" <<'EOF'
// Probe M2 SPIR-V (generated by build_push_run.sh from printing.comp).
#ifndef SPK_GPU_PROBE_PRINTING_SPV_H
#define SPK_GPU_PROBE_PRINTING_SPV_H
#include <cstdint>
static const uint32_t kPrintingSpv[] =
#include "printing_spv.inc"
;
#endif
EOF
    local BIN="gpu_probe_m2"
    [[ "$V" != default ]] && BIN="gpu_probe_m2_$V"
    echo "== build: M2 variant $V =="
    "$CXX" $FLAGS -I"$VDIR" -Itools/gpu_probe -I"$CPP" \
      tools/gpu_probe/probe_m2_main.cpp "$ENGINE_A" -lvulkan -o "$OUT/$BIN" || return 1
    M2BINS="$M2BINS $BIN"
  }
  for V in default precise mediump; do
    build_m2_variant "$V" || echo "M2 variant $V failed — skipping"
  done
else
  echo "glslc not found — SKIPPING the M2 filming/printing kernels entirely"
fi

# ── push + run ──────────────────────────────────────────────────────────────
DEV=/data/local/tmp/spk_gpu_probe
ASSETS=engine/spektra-core/src/main/assets/spektra
PROFILE=$ASSETS/profiles/kodak_portra_400.json
GOLDEN=tools/parity/goldens/scan_portra/film_density_cmy.spkvec
adbw shell mkdir -p "$DEV" "$DEV/scan_portra" "$DEV/print_portra"
for B in $BINS $M2BINS; do adbw push "$OUT/$B" "$DEV/" >/dev/null; done
adbw push "$PROFILE" "$GOLDEN" "$DEV/" >/dev/null
if [[ -n "$M2BINS" ]]; then
  adbw push "$ASSETS/profiles/kodak_portra_endura.json" \
            "$ASSETS/filters/neutral_print_filters.json" \
            "$ASSETS/luts/spectral_upsampling/irradiance_xy_tc.npy" \
            engine/spektra-core/src/main/cpp/tests/scan_portra_input_rgb.f64 \
            "$DEV/" >/dev/null
  adbw push tools/parity/goldens/scan_portra/film_density_cmy.spkvec \
            tools/parity/goldens/scan_portra/film_log_raw.spkvec \
            "$DEV/scan_portra/" >/dev/null
  adbw push tools/parity/goldens/print_portra/film_density_cmy.spkvec \
            tools/parity/goldens/print_portra/print_density_cmy.spkvec \
            "$DEV/print_portra/" >/dev/null
fi
adbw shell chmod +x "$DEV"/gpu_probe*

echo "== Tier 0: caps =="
adbw shell "$DEV/gpu_probe caps" | tee "$CAP/caps.txt"
adbw shell cmd gpu vkjson > "$CAP/vkjson.txt" 2>&1 || echo "(cmd gpu vkjson unavailable)"

echo "== Tier 1: fp32 GPU vs f64 reference =="
adbw shell "$DEV/gpu_probe run $DEV/kodak_portra_400.json $DEV/film_density_cmy.spkvec" \
  | tee "$CAP/tier1.txt"

echo "== Tier 2: perf =="
adbw shell "$DEV/gpu_probe perf $DEV/kodak_portra_400.json $DEV/film_density_cmy.spkvec" \
  | tee "$CAP/tier2.txt"

for B in $BINS; do
  [[ "$B" == gpu_probe ]] && continue
  echo "== Tier 3: ${B#gpu_probe_} =="
  adbw shell "$DEV/$B run $DEV/kodak_portra_400.json $DEV/film_density_cmy.spkvec" \
    | tee "$CAP/tier3_${B#gpu_probe_}.txt"
done

# ── M2 (#147): filming + printing kernels ───────────────────────────────────
for B in $M2BINS; do
  SUF="${B#gpu_probe_m2}"; SUF="${SUF#_}"          # "", "precise", "mediump"
  TAG="${SUF:-default}"
  echo "== M2 filming ($TAG) =="
  adbw shell "$DEV/$B film $DEV/kodak_portra_400.json $DEV/irradiance_xy_tc.npy $DEV/scan_portra_input_rgb.f64 $DEV/scan_portra" \
    | tee "$CAP/m2_film_$TAG.txt"
  echo "== M2 printing ($TAG) =="
  adbw shell "$DEV/$B print $DEV/kodak_portra_400.json $DEV/kodak_portra_endura.json $DEV/neutral_print_filters.json $DEV/irradiance_xy_tc.npy $DEV/print_portra" \
    | tee "$CAP/m2_print_$TAG.txt"
done

echo "== done; captures in $CAP =="
