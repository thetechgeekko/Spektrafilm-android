#!/usr/bin/env bash
# Exact-candidate device smoke for .github/workflows/release.yml.
# See tools/ci/emulator_smoke.sh for why this is a file and not inline YAML.
set -euo pipefail

mkdir -p build/device-smoke
adb logcat -c

# On any failure, retain the whole device log and process/memory state. The
# instrumentation stream alone cannot explain a provider-side death: release run
# 34841467586 attempt 2 failed with "MediaStore pending-row discovery returned no
# cursor" (ContentResolver.query returns null only when the provider's binder is
# gone) after the same query had succeeded earlier in the same process, and no
# logcat had been kept to say why MediaProvider went away. Every call is bounded:
# a wedged guest answers nothing, and 34859171425/1 sat 26 minutes in this trap
# before the job cap killed it.
retain_failure_evidence() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    timeout 120 adb logcat -d -v threadtime > build/device-smoke/failure-logcat.txt 2>/dev/null || true
    timeout 60 adb shell dumpsys meminfo > build/device-smoke/failure-meminfo.txt 2>/dev/null || true
    timeout 60 adb shell dumpsys activity processes > build/device-smoke/failure-processes.txt 2>/dev/null || true
  fi
}
trap retain_failure_evidence EXIT

adb install --no-streaming -r signed/app-release.apk
adb install --no-streaming -r signed/app-release-gate-androidTest.apk
adb install --no-streaming -r candidate/engine-boundary-debug-androidTest.apk

set +e
adb shell am instrument -w -r \
  com.spectrafilm.engine.test/com.spectrafilm.engine.EngineBoundaryInstrumentation \
  | tee build/device-smoke/engine-boundary-instrumentation.txt
ENGINE_INSTRUMENT_STATUS=${PIPESTATUS[0]}
set -e
if [ "$ENGINE_INSTRUMENT_STATUS" -ne 0 ]; then
  echo "::error::Engine boundary instrumentation transport failed"
  exit 1
fi
grep -Fq 'ENGINE_BOUNDARY_INSTRUMENTATION: PASS' \
  build/device-smoke/engine-boundary-instrumentation.txt
grep -Fx 'INSTRUMENTATION_CODE: -1' \
  build/device-smoke/engine-boundary-instrumentation.txt
if grep -Fq 'ENGINE_BOUNDARY_INSTRUMENTATION: FAIL' \
    build/device-smoke/engine-boundary-instrumentation.txt; then
  echo "::error::Engine boundary instrumentation reported failure"
  exit 1
fi
printf 'PASS\n' \
  > build/device-smoke/engine-boundary-instrumentation-api35.txt

mapfile -t INSTALLED_PATHS < <(
  adb shell pm path com.spectrafilm.app \
    | tr -d '\r' | sed 's/^package://'
)
mapfile -t BASE_PATHS < <(
  printf '%s\n' "${INSTALLED_PATHS[@]}" | grep '/base\.apk$'
)
if [ "${#BASE_PATHS[@]}" -ne 1 ]; then
  echo "::error::Installed package did not expose exactly one base APK"
  exit 1
fi
adb pull "${BASE_PATHS[0]}" build/device-smoke/installed-base.apk
cmp signed/app-release.apk build/device-smoke/installed-base.apk
sha256sum signed/app-release.apk \
  build/device-smoke/installed-base.apk \
  > build/device-smoke/installed-base.sha256

set +e
adb shell am instrument -w -r \
  com.spectrafilm.app.test/com.spectrafilm.app.ReleaseCandidateSmokeInstrumentation \
  | tee build/device-smoke/instrumentation.txt
INSTRUMENT_STATUS=${PIPESTATUS[0]}
set -e
if [ "$INSTRUMENT_STATUS" -ne 0 ]; then
  echo "::error::adb instrumentation transport failed"
  exit 1
fi
grep -Fq 'RELEASE_CANDIDATE_INSTRUMENTATION: PASS' \
  build/device-smoke/instrumentation.txt
grep -Fx 'INSTRUMENTATION_CODE: -1' \
  build/device-smoke/instrumentation.txt
if grep -Fq 'RELEASE_CANDIDATE_INSTRUMENTATION: FAIL' \
    build/device-smoke/instrumentation.txt; then
  echo "::error::Release-candidate instrumentation reported failure"
  exit 1
fi

adb shell am force-stop com.spectrafilm.app
adb shell am start -W -S \
  -n com.spectrafilm.app/.MainActivity \
  | tee build/device-smoke/activity-start.txt
grep -F 'Status: ok' build/device-smoke/activity-start.txt
adb shell dumpsys activity activities \
  > build/device-smoke/dumpsys-activities.txt
grep -E 'topResumedActivity=|ResumedActivity:' \
  build/device-smoke/dumpsys-activities.txt \
  | grep -F 'com.spectrafilm.app/.MainActivity'
APP_PID="$(adb shell pidof -s com.spectrafilm.app | tr -d '\r')"
if [[ ! "$APP_PID" =~ ^[0-9]+$ ]]; then
  echo "::error::Exact candidate process is not alive after cold launch"
  exit 1
fi
adb logcat -d --pid "$APP_PID" -v threadtime \
  > build/device-smoke/app-logcat.txt
if grep -E \
    'FATAL EXCEPTION|UnsatisfiedLinkError|JNI DETECTED ERROR|SIG(SEGV|ABRT)' \
    build/device-smoke/app-logcat.txt; then
  echo "::error::Fatal Java/JNI/native failure in exact candidate"
  exit 1
fi
printf 'PASS\n' > build/device-smoke/api35-device-smoke.txt

