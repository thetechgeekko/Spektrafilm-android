#!/usr/bin/env bash
# CI export-benchmark trace for .github/workflows/ci.yml (export-benchmark job, #285).
# Lives in a file for the same reason as emulator_smoke.sh: the emulator action runs
# each `script:` line in its own `sh -c`.
#
# Runs the #177 harness (tools/baseline/run_bench.sh) against the debug-key-signed R8
# release candidate on the API 35 emulator: one bypassed-cache run of the BASE cell, so
# every format really decodes, simulates, grades and encodes. The capture is a TREND
# artifact only. Emulator numbers are not device numbers, a 1-run capture is a smoke
# capture that bench_report.py never gates, and the build type (release, R8, debug key,
# x86_64 emulator) is written next to it so no reader mistakes it for a phone baseline.
set -euo pipefail

APP_APK=bench/app-release-bench.apk
TEST_APK=bench/app-release-androidTest-bench.apk
test -f "$APP_APK"
test -f "$TEST_APK"
adb install -r "$APP_APK"
adb install -r "$TEST_APK"

mkdir -p build/ticket177-bench
cat > build/ticket177-bench/build-type.txt <<TXT
build_type=release (R8 minified, debug-key signed, not a store build)
device=API 35 x86_64 emulator (google_apis, pixel_6, -gpu swangle) - trend only, not a device number
runs=1 cells=BASE bypass_cache=1 (smoke capture, never gated)
source_sha=${GITHUB_SHA:-unknown}
TXT

SPK_BENCH_BYPASS_CACHE=1 SPK_BENCH_OUT=build/ticket177-bench \
  bash tools/baseline/run_bench.sh "$APP_APK" 1 BASE
test -s build/ticket177-bench/capture/capture.json
echo "export-benchmark: capture.json written"
