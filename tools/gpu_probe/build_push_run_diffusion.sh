#!/usr/bin/env bash
#
# Spektrafilm for Android — camera-diffusion CPU-vs-GPU probe (#213). GPLv3.
# Film modeling powered by spektrafilm.
#
# Builds tools/gpu_probe/probe_diffusion_main.cpp against the UNMODIFIED engine,
# pushes it, waits for the phone to cool, and runs it. Engine sources are compiled
# as-is; nothing under engine/ is modified.
#
# Usage: bash tools/gpu_probe/build_push_run_diffusion.sh [W] [H] [TERMS] [STRENGTH]
# Env:   ANDROID_NDK, ADB_SERIAL, SKIP_COOL=1
set -euo pipefail
cd "$(dirname "$0")/../.."
export MSYS2_ARG_CONV_EXCL="*"
export MSYS_NO_PATHCONV=1

W="${1:-1920}"; H="${2:-1080}"; TERMS="${3:-7}"; STRENGTH="${4:-0.5}"

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
OUT=tools/gpu_probe/build
CAP=tools/gpu_probe/captures
mkdir -p "$OUT" "$CAP"

# The diffusion path pulls the FFT convolver, the parallel pool and the memory
# coordinator, so the whole engine model/kernel set comes along. -fno-fast-math:
# the CPU side here is the exact reference the GPU is measured against.
SRC="$CPP/spektra.cpp $CPP/kernels/*.cpp $CPP/io/*.cpp $CPP/model/*.cpp \
     $CPP/profiles/*.cpp $CPP/runtime/*.cpp $CPP/runtime/stages/*.cpp $CPP/gpu/*.cpp"
FLAGS="--target=aarch64-linux-android30 -std=c++17 -O2 -fno-fast-math \
  -DSPK_ENABLE_VULKAN -I$CPP -I tools/parity -static-libstdc++"

echo "== build =="
# shellcheck disable=SC2086
# -landroid: spektra.cpp reaches AAssetManager_* for profile loading and ATrace_* for
# systrace sections. This probe uses neither, but they are link-time references in the
# same translation units the diffusion path pulls in.
"$CXX" $FLAGS tools/gpu_probe/probe_diffusion_main.cpp $SRC -lvulkan -llog -landroid \
  -o "$OUT/gpu_probe_diffusion"

echo "== push =="
adbw push "$OUT/gpu_probe_diffusion" /data/local/tmp/gpu_probe_diffusion >/dev/null
adbw shell chmod 755 /data/local/tmp/gpu_probe_diffusion

if [[ -z "${SKIP_COOL:-}" && -f tools/baseline/wait_cool.sh ]]; then
  SERIAL="${ADB_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
  if [[ -n "$SERIAL" ]]; then
    echo "== thermal gate ($SERIAL) =="
    bash tools/baseline/wait_cool.sh "$SERIAL" || echo "(gate did not settle; numbers are HOT)"
  fi
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$CAP/diffusion_${W}x${H}_t${TERMS}_${STAMP}.txt"
echo "== run =="
adbw shell /data/local/tmp/gpu_probe_diffusion "$W" "$H" "$TERMS" "$STRENGTH" | tee "$LOG"
echo
echo "capture: $LOG"
