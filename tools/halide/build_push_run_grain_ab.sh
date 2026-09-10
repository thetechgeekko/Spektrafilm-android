#!/usr/bin/env bash
#
# Spektrafilm for Android — Halide-Vulkan grain vs the hand-written shader. GPLv3.
# Film modeling powered by spektrafilm.
#
# Regenerates the Halide arm64 library, links it beside the engine, and runs both
# grain implementations on device. Usage: bash tools/halide/build_push_run_grain_ab.sh [W] [H]
set -euo pipefail
cd "$(dirname "$0")/../.."
export MSYS2_ARG_CONV_EXCL="*"
export MSYS_NO_PATHCONV=1
W="${1:-1920}"; H="${2:-1080}"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
  Darwin)               HOST=darwin-x86_64 ;;
  *)                    HOST=linux-x86_64 ;;
esac
NDK="${ANDROID_NDK:-$HOME/AppData/Local/Android/Sdk/ndk/28.2.13676358}"
CXX="$NDK/toolchains/llvm/prebuilt/$HOST/bin/clang++"
[[ -x "$CXX" || -x "$CXX.exe" ]] || { echo "NDK clang++ not found: $CXX"; exit 1; }

adbw() { if [[ -n "${ADB_SERIAL:-}" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi }

CPP=engine/spektra-core/src/main/cpp
HB=tools/halide/build
OUT=tools/gpu_probe/build
CAP=tools/gpu_probe/captures
mkdir -p "$OUT" "$CAP"

LIB="$HB/spk_grain_f32_arm_64_android_vulkan.a"
[[ -f "$LIB" ]] || { echo "missing $LIB -- run gen_gpu_f32.py first"; exit 1; }

SRC="$CPP/spektra.cpp $CPP/kernels/*.cpp $CPP/io/*.cpp $CPP/model/*.cpp \
     $CPP/profiles/*.cpp $CPP/runtime/*.cpp $CPP/runtime/stages/*.cpp $CPP/gpu/*.cpp"
FLAGS="--target=aarch64-linux-android30 -std=c++17 -O2 -fno-fast-math \
  -DSPK_ENABLE_VULKAN -I$CPP -I $HB -I tools/parity -static-libstdc++"

echo "== build =="
# shellcheck disable=SC2086
"$CXX" $FLAGS tools/halide/probe_grain_ab_main.cpp $SRC "$LIB" \
  -lvulkan -llog -landroid -ldl -o "$OUT/grain_ab"

echo "== push =="
adbw push "$OUT/grain_ab" /data/local/tmp/grain_ab >/dev/null
adbw shell chmod 755 /data/local/tmp/grain_ab

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$CAP/grain_ab_${W}x${H}_${STAMP}.txt"
echo "== run =="
adbw shell /data/local/tmp/grain_ab "$W" "$H" 2>&1 | tee "$LOG"
echo
echo "capture: $LOG"
