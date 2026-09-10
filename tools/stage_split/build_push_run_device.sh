#!/usr/bin/env bash
#
# Spektrafilm for Android — on-device per-stage export split. GPLv3.
# Film modeling powered by spektrafilm.
#
# stage_split's own header says its absolute milliseconds are HOST numbers that
# do not transfer to the phone. This builds the same source for arm64 with the
# NDK and the shipping flags, so the numbers are the device's, and runs it twice:
# once on the CPU route and once on the Fast GPU export route. That A/B is the
# only thing that answers "what does the GPU actually save on a real export".
#
# Usage: bash tools/stage_split/build_push_run_device.sh [SIDE] [REPS]
# Env:   ANDROID_NDK, ADB_SERIAL, SKIP_COOL=1
set -euo pipefail
cd "$(dirname "$0")/../.."
export MSYS2_ARG_CONV_EXCL="*"
export MSYS_NO_PATHCONV=1

SIDE="${1:-3534}"   # 3534^2 = 12.49 MP, the export size the ladders use
REPS="${2:-2}"

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

SRC="$CPP/spektra.cpp $CPP/kernels/*.cpp $CPP/io/*.cpp $CPP/model/*.cpp \
     $CPP/profiles/*.cpp $CPP/runtime/*.cpp $CPP/runtime/stages/*.cpp $CPP/gpu/*.cpp"
# The SHIPPING flags: an export runs -O3 -ffast-math -fno-finite-math-only, and
# a stage split taken at -O2 ranks the stages differently (that is the whole
# reason stage_split exists).
FLAGS="--target=aarch64-linux-android30 -std=c++17 -O3 -ffast-math -fno-finite-math-only \
  -DSPK_ENABLE_VULKAN -I$CPP -I tools/parity -static-libstdc++"

echo "== build =="
# shellcheck disable=SC2086
"$CXX" $FLAGS tools/stage_split/stage_split.cpp $SRC -lvulkan -llog -landroid \
  -o "$OUT/stage_split_device"

echo "== push =="
adbw push "$OUT/stage_split_device" /data/local/tmp/stage_split_device >/dev/null
adbw shell chmod 755 /data/local/tmp/stage_split_device
# The engine needs its asset tree on the device.
adbw shell mkdir -p /data/local/tmp/spk_assets
adbw push "$CPP/../assets/spektra" /data/local/tmp/spk_assets >/dev/null 2>&1 || \
  adbw push engine/spektra-core/src/main/assets/spektra /data/local/tmp/spk_assets >/dev/null

if [[ -z "${SKIP_COOL:-}" && -f tools/baseline/wait_cool.sh ]]; then
  SERIAL="${ADB_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
  [[ -n "$SERIAL" ]] && { echo "== thermal gate =="; bash tools/baseline/wait_cool.sh "$SERIAL" || echo "(hot)"; }
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$CAP/stage_split_${SIDE}_${STAMP}.txt"
ASSETS=/data/local/tmp/spk_assets/spektra
echo "== run: CPU route ==" | tee "$LOG"
adbw shell "SPK_STAGE_SPLIT_GPU=0 /data/local/tmp/stage_split_device $ASSETS $SIDE $REPS" | tee -a "$LOG"
echo | tee -a "$LOG"
echo "== run: Fast GPU export route ==" | tee -a "$LOG"
adbw shell "SPK_STAGE_SPLIT_GPU=1 SPK_GPU_DEBUG=1 /data/local/tmp/stage_split_device $ASSETS $SIDE $REPS 2>&1" | tee -a "$LOG"
echo
echo "capture: $LOG"
