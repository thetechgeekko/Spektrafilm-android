#!/usr/bin/env bash
# Spektrafilm — on-device smoke test (reconciles issue #5; validates the v0.3.0 wave).
#
# The build sandbox has no /dev/kvm, so the app was never run on a real device.
# Run this on a machine with `adb` and a connected device/emulator (USB-debugging on)
# to do the on-device verification the CI/sandbox couldn't.
#
#   Usage:  tools/device_smoke_test.sh [path/to/app-release.apk]
#   Default APK: dist/Spektrafilm-v0.3.0.apk
#
# It installs the APK, launches MainActivity, and checks logcat for crashes /
# UnsatisfiedLinkError on the native libs. Then MANUALLY verify the checklist printed
# at the end (rotate->export, EXIF-orientation, 16-bit PNG/TIFF, Ultra HDR, Expert RAW).
set -euo pipefail

APK="${1:-dist/Spektrafilm-v0.3.0.apk}"
PKG="com.spectrafilm.app"
ACT="$PKG/.MainActivity"

command -v adb >/dev/null || { echo "adb not found on PATH"; exit 1; }
[ -f "$APK" ] || { echo "APK not found: $APK"; exit 1; }

echo "== waiting for a device =="
adb wait-for-device
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') / API $(adb shell getprop ro.build.version.sdk | tr -d '\r') / $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

echo "== installing $APK =="
adb install -r "$APK"

echo "== clearing logcat + launching =="
adb logcat -c
adb shell am start -W -n "$ACT" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
sleep 6

echo "== capturing screenshot -> /tmp/spectra_device.png =="
adb exec-out screencap -p > /tmp/spectra_device.png 2>/dev/null || echo "(screencap failed; continue)"

echo "== logcat scan for fatal crashes / native-lib load failures =="
LOG="$(adb logcat -d)"
FAIL=0
if echo "$LOG" | grep -A4 -E "FATAL EXCEPTION" | grep -q "$PKG"; then
  echo "FAIL: FATAL EXCEPTION in $PKG"; echo "$LOG" | grep -A8 "FATAL EXCEPTION" | tail -20; FAIL=1
fi
if echo "$LOG" | grep -E "UnsatisfiedLinkError" | grep -q -E "libspektra|libsfraw|libsftiff|libsfpng|$PKG"; then
  echo "FAIL: native lib failed to load (UnsatisfiedLinkError)"; echo "$LOG" | grep -B1 -A3 "UnsatisfiedLinkError" | tail -20; FAIL=1
fi
if echo "$LOG" | grep -q "$PKG/.MainActivity.*Displayed\|Displayed $PKG"; then
  echo "ok: MainActivity displayed"
fi

[ "$FAIL" -eq 0 ] && echo "== AUTOMATED SMOKE: PASS (launch + native libs OK) ==" || { echo "== AUTOMATED SMOKE: FAIL =="; exit 1; }

cat <<'EOF'

== MANUAL verification checklist (the things the sandbox could not test) ==
  [ ] App is full-screen / edge-to-edge; bottom category bar clears the gesture area
  [ ] System BACK navigates Settings/About -> editor (does NOT exit); double-back exits
      with a one-time "press back again" hint
  [ ] Bottom category bar scrolls (spring); tapping a category opens its panel between
      preview and bar; sliding indicator follows
  [ ] Import a JPEG that is normally sideways -> it appears UPRIGHT (EXIF orientation)
  [ ] Rotate button rotates the preview 90 deg; EXPORT and confirm the saved file is rotated
  [ ] Export 16-bit PNG and 16-bit TIFF -> open on desktop, confirm 16-bit + correct image
  [ ] Export Ultra HDR on an Android 14+ device -> confirm gain-map JPEG shows HDR in Google Photos
  [ ] Exported JPEG retains source EXIF (camera/exposure) — and decide on the GPS-privacy toggle
  [ ] Import a Samsung Expert RAW DNG (DEFLATE) -> decodes via LibRaw; a lossy/JPEG-XL DNG
      -> falls back via system decoder with the "system decoder" snackbar
  [ ] Auto-exposure "Auto" button + metering-method popup change the render
  [ ] Recipe persists across reopen (edits + rotation restored)
Screenshot saved to /tmp/spectra_device.png
EOF
