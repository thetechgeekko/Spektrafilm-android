# Spektrafilm Android — Session Handoff

## State (2026-05-31)
- **Branch `main` is the trunk**; `versionCode 3` / `versionName 0.4.0`. Tags `v0.3.0` and
  `v0.4.0` are on origin. Host parity suite **bit-exact / green**; `:app:assembleDebug` builds and
  packages `libspektra.so` (+ `libsfraw`/`libsftiff`/`libsfpng`) for `arm64-v8a`, `armeabi-v7a`,
  `x86_64`.
- Work flows on feature branches → **draft PR into `main`** → maintainer merges. (The repo's
  `origin/HEAD` still points at the old `claude/sharp-allen-I7wQK` branch, which is stale — `main`
  is the real trunk.)

## What landed recently (merged to `main`)
- **v0.4.0 release prep** (#12) — version bump + CHANGELOG for the usability/perf/undo-redo wave
  (editor overhaul, decoded-source proxy cache, opt-in half-size RAW decode, in-session undo/redo,
  Spektrafilm display-name rebrand; package `com.spectrafilm.app` and the engine unchanged).
- **M6 threading** (#13) — per-pixel stages (`expose`/`scan`/`print_expose`) parallelized via a
  deterministic fork-join (`kernels/parallel`); **byte-identical for any worker count**
  (`test_parallel` gate). ~3.2× on a 12 MP scan / 4 cores.
- **M6 vector `exp10` SIMD** (`kernels/exp10.h`) — replaces the `pow(10,−spectral)` bottleneck in
  the scan/print spectral integrals with a portable vector `exp10` that lowers to **NEON `fmla v.2d`
  on arm64** (SSE2/AVX on x86). Matches `std::pow` to ≤ a few ULP → **byte-identical at the float32
  output** (goldens `max_abs` unchanged 5.97e-08; `test_parallel` byte-identical). x86 ~1.85×; the
  on-device NEON magnitude is **unmeasured** (no ARM build/run here). See `docs/DECISION.md`.
- **APK-size** (#15) — dropped the unused 4.1 MB `hanatos_..._coeffs.lut` from the bundle (runtime
  only loads `irradiance_xy_tc.npy`).
- **Signed-release pipeline** — `.github/workflows/release.yml` builds a signed APK from keystore
  secrets on a `v*` tag push and creates/attaches the GitHub Release.
- **Docs** — `docs/AUDIT.md` (open-items audit, #17), SIMD decision record reconciled (#16).

## What's NOT complete (see `docs/AUDIT.md` for the full list + severity)
- **AAssetManager path unwired** — assets are extracted to `filesDir` on first run instead of read
  from the APK (`spektra_jni.cpp:413`).
- **Large-RAW memory tiling** (issue #7) — only app-side OOM-retry + opt-in half-size decode; no
  native tiling/streaming/GPU.
- **No Kotlin/JVM or instrumented tests** — the whole app+lib Kotlin layer is untested by
  automation; only C++ host tests + the LibRaw C++ unit/fuzz tests exist.
- Minor/by-design: `use_enlarger_lut` reserved; glare-on-print stochastic (default OFF); enlarger
  lens blur unwired (no oracle site); downscale (`upscale_factor<1`) AA prefilter; GPU preview /
  EXR export deferred.
- Release/build: release `isMinifyEnabled = false` (~23 MB un-minified dex — needs R8 keep-rules +
  device validation).
- **Issue #5 device smoke test — DONE (2026-05-31)** on a real Galaxy S25 Ultra (arm64; see
  `docs/DEVICE_TEST_REPORT.md`): native libs load, 16-bit PNG/TIFF + Ultra HDR + Samsung Expert RAW
  all verified. It also surfaced a full-res export bug (fixed in PR #21). Follow-up device pass:
  lossy/JPEG-XL DNG fallback, GPS Settings toggle, visual checks, and the on-device NEON `exp10`
  speedup magnitude (still unmeasured).

## Build & verify
- **App:** `ANDROID_SDK_ROOT=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug`.
  Requires **NDK r27 (`27.0.12077973`)** + **build-tools 35.0.0** (16 KB page-size fix):
  `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1" "build-tools;35.0.0"`.
- **16 KB page check (Android 15):** `build-tools/35.0.0/zipalign -c -P 16 4 <apk>` must pass and
  every `arm64-v8a`/`x86_64` `.so` must have `0x4000` `LOAD` alignment (`readelf -lW`). CI gates
  this in the `Verify 16 KB page compatibility` step.
- **Engine parity (the gate):** host tests, from the cpp root, full source set
  `spektra.cpp kernels/*.cpp io/*.cpp model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp`,
  `g++ -std=c++17 -O3 -ffast-math -fno-finite-math-only -pthread` (the **`-pthread`** is required now
  for `kernels/parallel`). CI `engine-parity` gates: simulate_e2e (scan+print_portra+print_ektar),
  filming, spatial, crop_resize, autoexposure, diffusion(+e2e), lut_accel, scanner_lut_e2e,
  **output_spaces**, **lensblur**, and **test_parallel** (thread-invariance).
- **Thread-count knob:** `SPK_NUM_THREADS` overrides `hardware_concurrency()` (tests pin 1 vs 8).
- Commit with `-c commit.gpgsign=false` (signing server rejects here).

## Notes
- Attribution "Film modeling powered by spektrafilm" must stay (GPLv3).
- Do not put the model identifier in any committed artifact.
- "Bit-exact" here = matches the spektrafilm oracle within the parity tolerance (max_abs ≤ 1e-4,
  rms ≤ 1e-5) **and** byte-identical across thread counts — not necessarily byte-identical across
  CPU architectures (`-ffast-math` FMA contraction differs).
