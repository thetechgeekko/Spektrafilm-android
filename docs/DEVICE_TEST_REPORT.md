# Spektrafilm — On-Device Test Report (issue #5 verification)

> **⚠️ Historical.** This is the **v0.4.0 (versionCode 3)** device pass. It is superseded by later
> on-device re-validations recorded in `docs/AUDIT.md` (the v0.6.3 export-OOM confirmation) and
> `HANDOFF.md` (v0.7.0). Kept as the original issue-#5 evidence record; do not read it as current.

**Date:** 2026-05-31 · **APK:** `app-debug.apk` built from `main` @ **v0.4.0** (`versionCode 3`),
package `com.spectrafilm.app` · **Driven via `adb` on the host laptop** (the build sandbox has no
`/dev/kvm` / USB passthrough, so this is the on-device verification CI could not do).

This reconciles **issue #5** ("App not yet verified on a real device") — closed earlier without an
actual device run; now backed by real arm64 hardware results.

## Environment
| Field | Value |
|---|---|
| Device | Samsung Galaxy S25 Ultra (`SM-S948W`) |
| Android | 16 (API 36), ABI `arm64-v8a`, Adreno/Vulkan 0842.19.8 |
| Tooling | platform-tools 37.0.0, exiftool 13.57; UI driven via `uiautomator dump` (exact element bounds), logcat pinned to the app PID |

## Automated smoke — PASS
| Check | Result | Evidence |
|---|---|---|
| Install | PASS | `Performing Streamed Install / Success` |
| Launch MainActivity | PASS | `am start -W` → `Status: ok`, `COLD`, `TotalTime: 574` ms |
| Displayed | PASS | `ActivityTaskManager: Displayed com.spectrafilm.app/.MainActivity +574ms` |
| FATAL EXCEPTION | none | logcat clean |
| `UnsatisfiedLinkError` (`libspektra`/`libsfraw`/`libsftiff`/`libsfpng`) | none | native libs load + init on arm64 |

Benign noise (not app faults): Samsung launcher widget probe, edge-panel `ExtensionLoader`,
`libpenguin.so` (Samsung system profiling lib, not ours), Adreno QSPM AIDL.

## Export formats — PASS (3/3)
Written to `/sdcard/Pictures/Spektrafilm/` (MediaStore-indexed); verified with exiftool on pulled files.
| Format | Result | Evidence |
|---|---|---|
| 16-bit PNG | PASS | `Bit Depth: 16`, RGB, 1530×2040, ~17 MB |
| 16-bit TIFF | PASS | `Bits Per Sample: 16 16 16`, `Samples Per Pixel: 3`, RGB, uncompressed, ~18 MB |
| Ultra HDR (gain-map JPEG) | PASS | `XMP-hdrgm Version 1.0` + `APP2 urn:iso:std:iso:ts:21496:-1` (ISO 21496-1) + `MPF Number Of Images: 2` + Google gain-map image + sRGB ICC |

All exports carry `Software: Spektrafilm`.

## RAW / DNG decode — PASS (issue-#5 core path)
Import via Source panel → **Open RAW/DNG** (DocumentsUI; shows filenames).
| Source | Characteristics (exiftool) | Result |
|---|---|---|
| MotionCam DNG | uncompressed, 16-bit, DNG 1.4 | decoded + rendered in **557 ms** |
| **Samsung Expert RAW** | `Compression: JPEG`, `Photometric: Color Filter Array`, 16-bit, DNG 1.6, `SM-S948W` | decoded via **LibRaw** in **4560 ms**, **no system-decoder fallback**, no crash |

In-process native decode confirmed (logcat: large native RGB buffer alloc + blocking GC during
decode; app PID alive throughout). The JPEG-compressed-CFA Expert RAW — the real issue-#5 target —
decodes through the LibRaw primary path; the lossy/JPEG-XL fallback branch was therefore **not**
exercised (see Gaps).

## EXIF / metadata — PASS (privacy-preserving)
Tested with a real Samsung camera JPEG carrying full EXIF + GPS.
| Tag | Source | Exported |
|---|---|---|
| Make / Model / ExposureTime / FNumber / ISO / FocalLength / DateTimeOriginal | present | **retained** |
| `Software` / ICC | — | `Spektrafilm` + sRGB written |
| **GPSLatitude/Longitude** | present | **STRIPPED (absent)** |

Camera/exposure EXIF is retained; **GPS is stripped by default** (privacy). (Earlier "no EXIF"
exports were because those sources — synthetic gradient, MotionCam DNG — had none.)

## UI / behavior
Edge-to-edge preview; bottom category bar (Source/Presets/Simulation/Input/RAW WB/Grain) clears the
gesture area. Top bar (content-desc verified): Open photo, Undo, Redo, Export to gallery, Settings,
About. Editor: Toggle histogram, Before/after compare, Crop and transform, Rotate 90°. Source panel
shows `Edits auto-saved for this image (original never modified)`. Predictive BACK handled by the app
(logcat `OnBackPressedDispatcher$Api34Impl` + `onKeyUp(KEYCODE_BACK) hasCallback=true`). In-UI render
timing: 557 ms (uncompressed DNG), 4560 ms (Expert RAW), 564 ms (JPEG). Manifest has only
`MAIN/LAUNCHER` (no VIEW/SEND import intents — all import is in-app).

## Not verified (gaps)
- **Lossy / JPEG-XL DNG → system-decoder fallback + snackbar** — no genuinely lossy/JPEG-XL DNG on
  hand (the Expert RAW was JPEG-compressed CFA and went through LibRaw directly).
- **GPS "keep location" Settings toggle** — confirmed stripped by default; didn't open Settings to
  confirm the toggle.
- **Rotate→export pixel correctness**, **sideways-JPEG EXIF-orientation auto-upright on import**,
  **presets/simulation/grain/AE visual render changes**, **recipe persistence across reopen**,
  **double-back-to-exit hint** — subjective/visual, not automated.

## Bug found during this test — fixed
- **Exports were not full-resolution.** A 12 MP source exported at ~3 MP (1530×2040). Root cause:
  the export render decoded at the 2048 px **interactive-preview** cap (`MAX_EDGE_PX`) instead of
  the native resolution. Fixed by rendering export at full resolution (`EXPORT_MAX_EDGE_PX`),
  keeping the 2048 px cap only for the live preview/magnifier — see PR #21. Re-verify on-device
  that a 12 MP source now exports at ~12 MP.

## Bottom line
The native pipeline **works on real arm64 hardware**: launch + native-lib load OK; true 16-bit
PNG/TIFF; spec-correct ISO 21496-1 Ultra HDR; **Samsung Expert RAW decodes via LibRaw** with no crash
/ no fallback; source EXIF retained, GPS stripped. **Issue #5 is satisfied** for the headline paths;
the lossy/JPEG-XL fallback, the GPS toggle, and the visual checks remain for a follow-up pass.
