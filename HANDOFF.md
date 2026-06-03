# Spektrafilm Android — Session Handoff

## State (2026-06-03) — audit + cleanup + lifecycle fixes + render-speed/zoom (PR #60)
All this session's work is on branch `claude/intelligent-johnson-DEOqK` (draft **PR #60**, **CI
green** on every job — engine-parity, parity comparator, python-lint, engine-native, android
assemble; emulator skipped as designed). **6 commits, +845 / −367, working tree clean.** Trunk is
still v0.7.0 / versionCode 9; nothing here is merged (merging is policy-gated — needs user
go-ahead).

**Environment note:** this container now has the **full Android toolchain installed at
`/opt/android-sdk`** (NDK `27.0.12077973`, CMake 3.22.1, build-tools 35, platforms 34+35) — so a
web/CI session can build the app AND run `:app:testDebugUnitTest` end-to-end. `local.properties`
(git-ignored) points `sdk.dir` there. A freshly-built arm64 `libspektra.so` was confirmed
`0x4000`-aligned (the current build IS 16 KB-correct; only the old committed `dist/` APKs were not).

What landed (commit → what):
- `213a421` **chore(cleanup/license):** removed the committed `dist/*.apk` (+`.sha256`) — stale
  (v0.1–0.3), **16 KB-page-misaligned** (`dlopen`-fail on Android 15 16 KB devices), debug-signed;
  dropped the `!dist/*.apk` un-ignore. Closed the **ICC license gap** (`NOTICE.md` ICC section +
  `icc/ellelstone/LICENSE-CC-BY-SA-3.0` + `icc/saucecontrol/LICENSE-MIT`). Fixed a false
  `scanning.cpp` comment + CLAUDE.md version.
- `cf8f2d1` **fix(app): three 🔴 Android lifecycle bugs** (compile+CI verified): native engine
  leaked on every config change → process-scoped `EngineHolder` singleton (engine is immutable +
  thread-safe, so never closed mid-life → no use-after-free); edit session lost on process death →
  `rememberSaveable` for source URI/kind/name + rotation; `DecodedSourceCache.put/invalidate` now
  `close()` evicted entries; `SpektraEngine.close()` made idempotent.
- `3b33904` + `6a0f5dc` **docs: full re-sync to v0.7.0 reality** (two passes, agent-swarm + verified
  against code): engine README "M0 contract only" → "shipped"; `ENGINE_WIRING_PLAN.md` §3 enlarger
  LUT marked **wired** + fixed wrong fn name (`build_filming_tc_lut`); **`RELEASE_CHECKLIST.md`
  rewritten** (was telling maintainers to commit APKs to the removed `dist/`, no 16 KB check → now
  the real `release.yml` flow); AUDIT/ASSETS/CHANGELOG (added the missing v0.6.x RAW-OOM entry)/
  RAW_DNG; `tools/parity/cases.md` (+lensblur case); `RESEARCH_MCRAW` line-refs; GPU/fp16 framing;
  ⚠️ banners on the never-built ImageToolbox-host docs (ARCHITECTURE, IMAGETOOLBOX_MAP, bootstrap,
  SCREEN_REGISTRATION).
- `54d4d3d` **perf(engine): profile + filming tc_lut cache** (PARITY-SAFE). Every `simulate` was
  re-parsing the profile JSON and rebuilding the tc_lut; now memoized on `spk_engine` keyed by
  **immutable profile id** → byte-identical (a memo, not an approximation). **Verified byte-exact:**
  host parity suite ALL-PASS unchanged + a new `test_simulate_e2e` **warm-engine-vs-fresh-engine
  `memcmp`** assertion; thread-invariance intact. This is the down-payment on the bigger §3 stage
  cache.
- `af09449` **feat(viewer): Lightroom-style zoom** — zoom past fit now renders the **visible region
  at ~screen resolution** (ROI render via the existing magnifier primitive `cropLinearImageRect` +
  `simulatePreview`, debounced last-wins, overlaid sharp on the scaled proxy), instead of
  GPU-scaling the 640px proxy. **Zero engine/C++ change**, memory bounded to screen res (sidesteps
  full-res OOM). New `ZoomViewportTest` (7 cases) proves the transform math.

**Verified this session:** host engine-parity ALL-PASS + byte-identical (1 vs 8 threads);
`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL, 37/37 unit tests** (incl. new
`ZoomViewportTest`); PR #60 CI green.

**GPU rendering — investigated (no code).** Verdict: GPU can be a **preview-only accelerator,
never export** (parity is bit-exact-vs-oracle AND byte-identical-across-threads; GPU float varies by
vendor, the expose integrals are float64, and `-fno-finite-math-only` NaN handling is
implementation-defined on GPU). The existing `gpu/vulkan_compute.cpp` + SPIR-V is **dead scaffolding**
(default-OFF, zero callers) and covers the **scan** stage — NOT the measured hotspot (the filming/
print **expose** integrals, which have no GPU kernel). `LutGpuPreview.kt` is a default-off pointwise
`.cube` loupe, not a pipeline render. A real GPU render path is **XL and hardware-blocked** (no arm64
GPU here to validate cross-vendor).

**Next steps (ranked, best speed-per-effort first):**
1. **§3 per-stage cache** (cache `film_density_cmy` so print/scan-only edits skip the two most
   expensive stages) — biggest interactive win, **bit-exact**, host-verifiable. Builds directly on
   `54d4d3d`. Needs a double-render correctness test like the one added this session.
2. **Filming-expose LUT for preview** (the print side is wired as `use_enlarger_lut`; extend the LUT
   technique to the filming expose, where the cost actually is) — preview-only, exact path stays the
   gate.
3. **On-device verification pass** — the zoom UX (overlay registration, sharpness, gesture feel) and
   the lifecycle fixes are unit/compile/CI verified but need a real device/display (this container
   has none). Un-gate/fix the `android-emulator` CI job while there.
4. **fp16 preview buffers** (`kernels/half` exists + tested) — ~1.5–2×, preview-only.
5. **GPU compute** — last; only after 1–4 and with real devices. Target a fused filming→print→scan
   per-pixel kernel, NOT the existing scan-only shader; grain/spatial stay on CPU.
6. **Still deferred** (from the audit): inert marshalled engine params (UV/IR filter, preflash,
   spectral blur — wire-or-strip decision + a new oracle golden); latent int32 `npix*3` overflow
   (>715 MP, unreachable via the app's 16384px export cap); build posture (`targetSdk 34`→35, lint
   baseline masks 26 dep warnings, emulator CI gate is manual-only).

## State (2026-06-02)
- **`main` is the trunk.** Current on `main`: **`versionCode 9` / `versionName 0.7.0`** (PR #59
- **`main` is the trunk.** Current on `main`: **`versionCode 9` / `versionName 0.7.0`** (PR #59
  merged). **`v0.7.0` is tagged + RELEASED** — `release.yml` built and published the **signed**
  `Spektrafilm-v0.7.0.apk` (21.5 MB) + `.sha256` to the GitHub Release (run success, `apksigner
  verify` passed). Host + on-device parity suites **bit-exact / green**; `:app:assembleDebug`
  packages `libspektra.so` (+ `libsfraw`/`libsftiff`/`libsfpng`, opt-in `gpu`/`ml` stubs) for the
  3 ABIs.
- Workflow: feature branch → PR into `main` → merge. **Merging a PR and self-merging is
  policy-gated** in this env (the harness blocks it as "exceeds granted intent") — needs explicit
  user go-ahead; releases (tag push) are allowed once the user asks.
- Build/test/parity commands + engine architecture are in **`CLAUDE.md`** (read first). Prime
  directive: **bit-exact parity with the spektrafilm oracle** for the default/export path
  (`-fno-finite-math-only`; `test_*` gates; thread-invariant). Commit with
  `-c commit.gpgsign=false`. Keep "Film modeling powered by spektrafilm". Never put the model
  identifier in committed artifacts.

## 🟢 Shipped this session (v0.7.0) — engine completion, device-verified
Working copy lives at **`C:\Filmcam123\Spectrafilmandroid`** on the Windows laptop (NOT
`C:\Spectrafilm`, which is docs-only). A real **Galaxy S25 Ultra (SM-S948W, Android 16, arm64)**
is connected via `adb` (device `R5GL13Z3S6L`).
- **AAssetManager direct-load** (PR #59) — engine reads profiles/LUT/filters straight from the
  APK; the ~17 MB first-run extraction to `filesDir` is skipped. New
  `spk_engine_create_asset_manager` + JNI `nativeCreateFromAssets` + Kotlin
  `SpektraEngine.fromAssets`; `MainActivity` tries it first, falls back to `extractAssets`. All
  AAsset code is `#ifdef __ANDROID__`-guarded (host build unchanged). Verified on-device: fresh
  launch → **no `files/spektra` extraction**, demo renders ~640 ms.
- **`use_enlarger_lut` wired** (PR #59) — opt-in enlarger 3D-LUT in `printing.cpp::print_expose`
  (PCHIP LUT of `_film_cmy_to_print_log_raw` over `[-grain.density_min, nanmax(film density
  curves)]`), mirror of the scanner LUT. Default-off → direct `exp10_vec` path byte-identical.
  Gated by **`test_enlarger_lut_e2e`** in CI `engine-parity`. **Last reserved engine LUT flag is
  gone.** Accel bands are looser than the scanner LUT (print-expose integral is less smooth):
  res 17 ≈1.1e-4, res 64 ≈1.9e-6 vs direct.
- Doc truth: presets **21** (Neutral preset documented), AUDIT refreshed.

### ⭐ On-device parity runner (the key technique — reuse this)
This Windows box has **no host g++/MSVC stdlib** (only MSVC-target clang, no libc++) → the
documented host C++ parity gate **can't run locally**. Instead, build the parity tests for
**arm64 with the NDK clang and run them on the connected device** — same goldens, real target
arch. This is what made engine changes verifiable here:
```bash
NDK=C:/Users/thete/AppData/Local/Android/Sdk/ndk/27.0.12077973
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
cd engine/spektra-core/src/main/cpp
SRC="spektra.cpp kernels/*.cpp io/*.cpp model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp"
"$CC" --target=aarch64-linux-android24 -std=c++17 -O2 -I. -I../../../../../tools/parity \
  -DSPK_TEST_DIR='"/data/local/tmp/spk/tests"' tests/test_simulate_e2e.cpp $SRC -landroid \
  -o /c/Filmcam123/spk_arm64/test_e2e         # -landroid REQUIRED (AAsset/etc)
# push test + libc++_shared.so + assets/spektra + tools/parity/goldens + tests/*.f64 to
# /data/local/tmp/spk, then:
adb shell "cd /data/local/tmp/spk && LD_LIBRARY_PATH=. ./test_e2e assets/spektra \
  goldens/scan_portra tests/scan_portra_input_rgb.f64 goldens"   # expect: ALL PASS
```
Result this session: `test_simulate_e2e` + `test_enlarger_lut_e2e` **ALL PASS** on device
(max_abs 5.96e-08, identical to host figures). MSYS mangles `/sdcard`/`/data` paths → use
`export MSYS_NO_PATHCONV=1` (or the PowerShell tool) for `adb push/shell` with abs device paths.

### Build env (this laptop)
- `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` (JDK 21); SDK at
  `C:\Users\thete\AppData\Local\Android\Sdk`; NDK r27 / CMake 3.22.1 / build-tools 35 all present.
  `local.properties` (gitignored) has `sdk.dir`. `:app:assembleDebug`, `:app:lintDebug`,
  `:app:testDebugUnitTest` (30/30) all green here.
- **spektrafilm oracle** runs under **Python 3.13** (`C:\Users\thete\AppData\Local\Programs\
  Python\Python313`) via venv `C:\Filmcam123\spkenv` + PYTHONPATH to `C:\Filmcam123\spektrafilm-main\src`,
  with `C:\Filmcam123\spkstubs\sitecustomize.py` mocking the heavy IO deps (exiv2/rawpy/
  OpenImageIO/lensfunpy/pyfftw). Needed only to regenerate goldens; enlarger LUT did NOT need a
  new golden (it reuses `print_portra` within the accel band).

## 🟢 Just resolved — RAW export OOM (merged, device-confirmed)
- **RAW import/export OOM on a real Galaxy S25 (Android 16) — FIXED in PR #56 (merged), confirmed
  on-device.** Symptom (every build through v0.6.2): `OutOfMemoryError "Failed to allocate a
  149817619 byte allocation … growth limit 268435456"` on loading/exporting a DNG.
- **Root cause (two parts):** `149,817,619 ≈ 4080×3060×3×4` = the **full-res decoded linear float
  buffer** (~140 MB). On Android `ByteBuffer.allocateDirect` is a **non-movable `byte[]` on the
  ART managed heap** (256 MB growth limit) — NOT native memory — so the full-res RAW input plus
  the engine's equally large output buffer can't coexist there. The earlier `maxLongEdge` cap fixed
  only the **preview** path; export is uncapped by design and still OOMed.
- **Fix (PR #56):** learned from Lightroom (RE'd: zero `NewDirectByteBuffer`/`allocateDirect` in its
  native libs — all full-res pixels live in oneTBB `scalable_malloc`/`mmap` native memory behind a
  handle, only a compressed JPEG crosses to Java). We now allocate the RAW result
  (`raw_decoder_jni.cpp`) and engine output (`spektra_jni.cpp`) with **`malloc` + `NewDirectByteBuffer`**
  (true off-heap), freed explicitly; `LinearImage`/`SimResult` are `AutoCloseable`; export-scale
  buffers are closed by the caller while proxy/preview buffers stay managed/GC'd. Also folded #54
  (rethrow `RawDecodeException` → platform decoder; direct-buffer file fallback). Engine C++ math
  untouched → parity unaffected. Detail in `docs/RESEARCH_BIG_FILES.md`.
- **Device confirm (v0.6.3 (8), SM-S948W / Android 16):** export logged
  `sfraw: decoded 3060x4080 (halfSize=0) -> 3060x4080 (step=1)` twice with **no `149817619`, no
  OOM, no crash** — app stayed responsive.
- **Test DNG:** the user's `raw_test.bin` (~24.9 MB, 4080×3060 Bayer, from Google Drive). A decoded
  linear-ACES copy was at `/tmp/dng_lin_aces.f32` — `/tmp` does NOT persist across sessions;
  re-download from the user if needed.
- **PR housekeeping done:** #56 merged; #55 (Neutral preset) merged; #57 (this handoff's prior
  refresh) merged; #53/#54 closed (absorbed into #56). No RAW-OOM PRs remain open.

## What landed since v0.4.0 (merged to `main`)
- **Lightroom-RE feature wave** (from `docs/IMPROVEMENT_BACKLOG.md`, RE'd off `com.adobe.lrmobile`):
  preset **amount** slider (#35), **copy/paste settings** (#36), granular **resets** crop+grain
  (#39/#40), and the **tone-curve** stage — engine (#41, parity-gated, default identity) + JNI/
  Kotlin/presets plumbing (#42). Before/after `CompareSlider` already existed.
- **MotionCam `.mcraw`** RE: `docs/RESEARCH_MCRAW.md`, pure-Kotlin `McrawContainer` parser (#37),
  picker detection (#38).
- **Performance (toward Lightroom)**: `docs/PERF_ROADMAP.md` (#46, measured; bottleneck = the
  filming+print **expose** 81-band integrals, not the scan); preview-LUT fast-path (#47); and
  **scaffolding, all opt-in / default-off so parity holds**: Vulkan compute fast-path + a real
  SPIR-V port of the spectral scan integral (#48/#52), fp16 NEON conversion (#49), oneTBB backend +
  LiteRT ML stub (#50). **GPU speedup is UNPROVEN** — needs a physical arm64 GPU device (sandbox has
  no KVM/GPU; the x86 emulator is the wrong ISA and mis-traps our `-O2` vectorized copy).
- **Big-file RAW** (the OOM saga): fd decode (#43), half-size proxy (#44) covered the LibRaw
  *success/preview* path; the native `maxLongEdge` cap (#56) fixed preview; **off-heap `malloc` +
  `NewDirectByteBuffer` for the full-res RAW input + engine output (#56)** is the real export fix
  (device-confirmed) — the Lightroom-style "full-res pixels never on the Java heap" model.
- **`Neutral (Adobe-like)` preset** + `docs/RESEARCH_LIGHTROOM_RENDER.md` (#55).
- `CLAUDE.md` (#34); v0.5.0–0.6.3 release bumps.

## RE assets / where the analysis lives
- `docs/RESEARCH_LIGHTROOM_STACK.md` — LR stack (C++ "CR" engine + oneTBB + Vulkan/OpenCL/Metal +
  LiteRT + fp16, under a Kotlin/Java UI) vs ours, ranked "what to copy".
- `docs/RESEARCH_LIGHTROOM_RENDER.md` — LR default render (Adobe Color DCP + ProcessVersion +
  ToneCurvePV2012) vs ours on the test DNG; deltas + the `Neutral (Adobe-like)` preset rationale.
- `docs/RESEARCH_BIG_FILES.md`, `docs/IMPROVEMENT_BACKLOG.md`, `docs/AUDIT.md`.
- The Lightroom APK was decompiled in-env to `/tmp/lrx` (ephemeral) via the android-reverse-
  engineering skill; `aapt2` + `libLrAndroid.so` `strings`/`nm` were the evidence source.

## Known engine/app gaps (authoritative list: `docs/AUDIT.md`, updated 2026-06-02)
- ✅ **AAssetManager path wired** (PR #59, v0.7.0) — see "Shipped this session" above. No longer a gap.
- ✅ **`use_enlarger_lut` wired** (PR #59, v0.7.0) — opt-in, default-off, parity-gated. No reserved
  engine LUT flag remains.
- **Issue #7 — full-res RAW tiling/GPU still Open**: mitigations now = OOM ladder, fd + half-size
  proxy, `maxLongEdge` preview cap, and **off-heap native buffers for full-res export (#56)** —
  the export OOM is resolved for typical phone DNGs. Still no native **tiling/streaming**, so a
  *pathologically* large DNG (single buffer > native headroom) is unbounded; tiling is the
  remaining work. The fd-failure file fallback uses `allocateDirect` (still managed) → a possible
  off-heap follow-up.
- **Glare-on-print** wired but default-off (stochastic → not bit-exact); **enlarger lens blur**
  and **`upscale_factor<1` AA prefilter** intentionally unwired (no oracle call site).
- **No Kotlin/JVM instrumented (`androidTest`) UI tests** beyond the host C++ parity + **30 JVM
  unit tests** (6 suites, green). Robolectric/instrumented coverage of `ImagePipeline`/
  `EngineHelpers`/`RawDecoder` marshaling is still absent.
- Doc upkeep: `docs/PRESETS.md` fixed to **21**; `docs/DEVICE_TEST_REPORT.md` is the v0.4.0 device
  pass (historical — the v0.7.0 re-validation is recorded in `docs/AUDIT.md`). `docs/ROADMAP.md` /
  `docs/RELEASE_CHECKLIST.md` (maintainer-only) hold milestone + publish state.

## Doc map (what to read for what)
`CLAUDE.md` build/parity/arch · `docs/AUDIT.md` open items · `docs/IMPROVEMENT_BACKLOG.md` Lightroom
RE'd feature list · `docs/PERF_ROADMAP.md` perf plan+policy · `docs/RESEARCH_*` the RE studies
(stack, render, big-files, mcraw, film-character, lens-bokeh) · `docs/PRESETS.md`/`FILM_STOCKS.md`
content · `docs/maps/` source-project maps.

## Next steps
1. **Performance (the headline ask).** Bottleneck = the per-pixel 81-band **expose** integrals
   (filming + print), not the scan. Ranked levers (`docs/PERF_ROADMAP.md`):
   - **GPU (Vulkan) compute** port of expose/print/scan — 10–50×, the only path to Lightroom-class
     speed. Scaffolding exists (opt-in, default-off, **UNPROVEN**). Needs on-device arm64
     profiling/validation on the S25's Adreno; hold preview to a *visual* tolerance, keep export on
     CPU. **Policy: approximate proxy / exact export.**
   - Cheap preview-only wins: turn **`use_enlarger_lut` + `use_scanner_lut` ON for preview** (~3–8×
     on the expose hotspot, now both wired); **fp16** intermediate buffers (~1.5–2×, scaffolding
     exists).
   - "Feels faster", bit-exact: **pause/refresh render on gesture**, **per-stage caches**
     (re-run only the changed stage), **progressive pyramid**, **embedded-JPEG instant preview**
     (LibRaw `unpack_thumb`).
2. **Issue #7 — native RAW tiling/streaming** for *pathologically* large DNGs (export OOM resolved
   for typical phone DNGs via off-heap buffers; a single buffer > native headroom is still
   unbounded). Smaller follow-up: make the fd-failure file fallback off-heap (currently
   `allocateDirect` = managed).
3. **Robolectric/instrumented tests** for the app layer (`ImagePipeline` export quantisation,
   sidecar persistence, `RawDecoder`/`PngWriter` JNI marshaling) — only JVM unit tests exist today.
4. Lower priority: minor by-design items (glare-on-print stochastic; enlarger lens blur / `upscale
   <1` AA prefilter — no oracle call site).
