#!/usr/bin/env bash
#
# Spektrafilm for Android — realtime falsifier probe (#208): build + push + run. GPLv3.
# Film modeling powered by spektrafilm.
#
# Builds tools/gpu_probe/probe_rt_main.cpp against the UNMODIFIED engine Vulkan
# host, pushes it, waits for the phone to be thermally cool, and runs it.
#
# The cool-down is not politeness. docs/research/perf-lab.md records a 74% swing
# in a phase whose code was byte-identical, purely from thermal state, which is
# why tools/baseline/wait_cool.sh exists and why a fixed sleep is not a
# substitute. A number taken on a hot phone is a number about the phone.
#
# Usage: bash tools/gpu_probe/build_push_run_rt.sh [W] [H] [RUNS] [SUSTAIN_S]
# Env:   ANDROID_NDK, ADB_SERIAL, SKIP_COOL=1 to bypass the thermal gate.
set -euo pipefail
cd "$(dirname "$0")/../.."
export MSYS2_ARG_CONV_EXCL="*"
export MSYS_NO_PATHCONV=1

W="${1:-1920}"; H="${2:-1080}"; RUNS="${3:-300}"; SUSTAIN="${4:-0}"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
  Darwin)               HOST=darwin-x86_64 ;;
  *)                    HOST=linux-x86_64 ;;
esac
NDK="${ANDROID_NDK:-$HOME/AppData/Local/Android/Sdk/ndk/28.2.13676358}"
CXX="$NDK/toolchains/llvm/prebuilt/$HOST/bin/clang++"
[[ -x "$CXX" || -x "$CXX.exe" ]] || { echo "NDK clang++ not found: $CXX (set ANDROID_NDK)"; exit 1; }

adbw() { if [[ -n "${ADB_SERIAL:-}" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi }

CPP=engine/spektra-core/src/main/cpp
OUT=tools/gpu_probe/build
CAP=tools/gpu_probe/captures
mkdir -p "$OUT" "$CAP"

# android30: the host queries Vulkan 1.1 entry points absent from the API-24
# libvulkan stub. Probe-only; the app's minSdk is untouched.
FLAGS="--target=aarch64-linux-android30 -std=c++17 -O2 -fno-fast-math \
  -DSPK_ENABLE_VULKAN -I$CPP -static-libstdc++"

# vulkan_compute.cpp's host-side upload/readback loops go through the engine's
# deterministic fork-join pool. The memory coordinator is header-only.
ENGINE_SRC="$CPP/kernels/parallel.cpp"

echo "== build: realtime falsifier =="
"$CXX" $FLAGS tools/gpu_probe/probe_rt_main.cpp "$CPP/gpu/vulkan_compute.cpp" \
  $ENGINE_SRC -lvulkan -o "$OUT/gpu_probe_rt"

echo "== push =="
adbw push "$OUT/gpu_probe_rt" /data/local/tmp/gpu_probe_rt >/dev/null
adbw shell chmod 755 /data/local/tmp/gpu_probe_rt

if [[ -z "${SKIP_COOL:-}" && -f tools/baseline/wait_cool.sh ]]; then
  # wait_cool.sh takes the serial as argv[1]; resolve it when ADB_SERIAL is unset
  # rather than letting it print its usage and fall through as if it had cooled.
  SERIAL="${ADB_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
  if [[ -n "$SERIAL" ]]; then
    echo "== wait for thermal gate ($SERIAL) =="
    bash tools/baseline/wait_cool.sh "$SERIAL" || echo "(gate did not settle; numbers below are HOT)"
  else
    echo "(no device for the thermal gate; numbers below are HOT)"
  fi
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$CAP/rt_${W}x${H}_${STAMP}.txt"
echo "== run =="
adbw shell /data/local/tmp/gpu_probe_rt "$W" "$H" "$RUNS" "$SUSTAIN" | tee "$LOG"
echo
echo "capture: $LOG"
