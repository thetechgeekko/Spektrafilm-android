#!/usr/bin/env bash
# CI emulator smoke for .github/workflows/ci.yml (android-emulator job).
# Lives in a file because reactivecircus/android-emulator-runner runs every
# line of its `script:` input in a separate `sh -c` — variables, heredocs and
# multi-line `if` blocks never survive from one line to the next.
set -euo pipefail

# The body used to be fed to `bash -s` through a heredoc. `adb shell` forwards
# its stdin to the device, so the first `adb shell am instrument` swallowed the
# rest of the script: every run ended 4 s after the engine gate with exit 0,
# never launched MainActivity, never took the screenshot, and the artifact
# held one file (PR #248 run 34918974302). The script is executed from this
# file by both workflows, so it simply runs top-level now.
mkdir -p emulator-artifacts

APK=$(ls apk/*.apk | head -1)
echo "Installing APK: $APK"
adb install -r "$APK"

ENGINE_TEST_APK=engine-boundary/spektra-core-debug-androidTest.apk
test -f "$ENGINE_TEST_APK"
adb install -r "$ENGINE_TEST_APK"
set +e
adb shell am instrument -w -r \
  com.spectrafilm.engine.test/com.spectrafilm.engine.EngineBoundaryInstrumentation \
  | tee emulator-artifacts/engine-boundary-instrumentation.txt
ENGINE_INSTRUMENT_STATUS=${PIPESTATUS[0]}
set -e
if [ "$ENGINE_INSTRUMENT_STATUS" -ne 0 ]; then
  echo "ENGINE BOUNDARY FAILED: adb instrumentation transport failed"
  exit 1
fi
grep -Fq 'ENGINE_BOUNDARY_INSTRUMENTATION: PASS' \
  emulator-artifacts/engine-boundary-instrumentation.txt
grep -Fx 'INSTRUMENTATION_CODE: -1' \
  emulator-artifacts/engine-boundary-instrumentation.txt
if grep -Fq 'ENGINE_BOUNDARY_INSTRUMENTATION: FAIL' \
    emulator-artifacts/engine-boundary-instrumentation.txt; then
  echo "ENGINE BOUNDARY FAILED: runner reported failure"
  exit 1
fi
printf 'PASS\n' > emulator-artifacts/engine-boundary-instrumentation-api35.txt

echo "--- Launching MainActivity ---"
# -W waits until onResume; captures ShortMsg/LongMsg on crash.
adb shell am start -W \
  -n "com.spectrafilm.app/.MainActivity" \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER

echo "--- Waiting 8 s for the app to fully initialise ---"
sleep 8

# The release smoke recreates MainActivity (two activities rendering at once),
# and the legacy swiftshader_indirect renderer segfaulted its host RenderThreads
# exactly there (release run 34913971651, host dmesg) while this single-launch
# smoke stayed green. MainActivity does not handle uiMode in configChanges, so a
# uiMode flip recreates it the same way; a dead emulator fails the adb calls below.
# The events buffer records each relaunch (wm_relaunch_*), which the large-screen
# check below relies on, so it is cleared first and this toggle doubles as proof
# that relaunch detection works on this image.
adb logcat -b events -c || true
echo "--- Forcing an Activity recreate via a night-mode toggle ---"
adb shell cmd uimode night yes
sleep 4
adb shell cmd uimode night no
sleep 4
adb logcat -b events -d > emulator-artifacts/events-uimode.txt || true
if ! grep -i "relaunch" emulator-artifacts/events-uimode.txt | grep -q "com.spectrafilm.app"; then
  echo "SMOKE TEST FAILED: the uiMode toggle left no wm_relaunch event for com.spectrafilm.app,"
  echo "so the no-relaunch check below would pass vacuously on this image."
  grep -i "relaunch" emulator-artifacts/events-uimode.txt || true
  exit 1
fi

# Large screens (#350): MainActivity handles window-shape changes in place, so a
# tablet-sized display, a rotation and a return to the phone size must NOT relaunch
# it (a relaunch tears down the GL preview and re-reads the editor session).
echo "--- Large-screen: resize to tablet, rotate, restore; expect no relaunch ---"
adb logcat -b events -c || true
adb shell wm size 1600x2560
sleep 3
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3
adb exec-out screencap -p > emulator-artifacts/screenshot-tablet-landscape.png || true
adb shell settings put system user_rotation 0
sleep 2
adb shell wm size reset
sleep 3
adb logcat -b events -d > emulator-artifacts/events-large-screen.txt || true
if grep -i "relaunch" emulator-artifacts/events-large-screen.txt | grep -q "com.spectrafilm.app"; then
  echo "SMOKE TEST FAILED: MainActivity was relaunched by a resize/rotation (see configChanges)."
  grep -i "relaunch" emulator-artifacts/events-large-screen.txt || true
  exit 1
fi

echo "--- Capturing screenshot ---"
adb exec-out screencap -p > emulator-artifacts/screenshot.png || true

echo "--- Dumping logcat ---"
adb logcat -d > emulator-artifacts/logcat.txt || true

echo "--- Asserting no FATAL EXCEPTION in com.spectrafilm.app ---"
# Android prints "FATAL EXCEPTION: main" and "Process: com.spectrafilm.app"
# on separate lines, so scan the lines *following* each fatal marker for
# our package. UnsatisfiedLinkError lines name our .so directly.
if grep -A4 -E "FATAL EXCEPTION" emulator-artifacts/logcat.txt \
     | grep -q "com.spectrafilm.app" \
   || grep -E "UnsatisfiedLinkError" emulator-artifacts/logcat.txt \
     | grep -q -E "libspektra|libsfraw|libsftiff|com.spectrafilm.app"; then
  echo "SMOKE TEST FAILED: fatal crash or UnsatisfiedLinkError detected in com.spectrafilm.app"
  grep -A6 -E "FATAL EXCEPTION|UnsatisfiedLinkError" emulator-artifacts/logcat.txt || true
  exit 1
fi

echo "--- Asserting MainActivity is the resumed/focused activity ---"
ACTIVITIES=$(adb shell dumpsys activity activities 2>/dev/null || true)
if echo "$ACTIVITIES" | grep -q "com.spectrafilm.app/.MainActivity"; then
  echo "SMOKE TEST PASSED: MainActivity is present in the activity stack."
else
  echo "SMOKE TEST FAILED: com.spectrafilm.app/.MainActivity not found in activity stack."
  echo "$ACTIVITIES" | grep -E "mResumedActivity|mFocusedActivity|TopResumedActivity" || true
  exit 1
fi

echo "--- Smoke test complete ---"

