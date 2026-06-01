# Spektrafilm Android — Session Handoff

## State (2026-06-01)
- **`main` is the trunk.** Current on `main`: **`versionCode 5` / `versionName 0.6.0`** (tags
  `v0.3.0`/`v0.4.0` on origin; 0.5.0/0.6.0 cut via PRs). Host engine parity suite is **bit-exact /
  green**; `:app:assembleDebug` builds + packages `libspektra.so` (+ `libsfraw`/`libsftiff`/
  `libsfpng`, and the opt-in `gpu`/`ml` stubs) for `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- Workflow: feature branch → **draft PR into `main`** → maintainer merges. `origin/HEAD` may still
  point at a stale `claude/sharp-allen-*` branch; `main` is the real trunk.
- Build/test/parity commands and the engine architecture are in **`CLAUDE.md`** (read it first).
  Prime directive stands: **bit-exact parity with the spektrafilm oracle** for the default/export
  path (`-fno-finite-math-only` required; `test_*` host gates; thread-invariant). Commit with
  `-c commit.gpgsign=false`. Keep the "Film modeling powered by spektrafilm" attribution. Never put
  the model identifier in committed artifacts.

## 🔴 Open / in-flight (most important first)
1. **RAW import OOM on a real Galaxy S25 (Android 16) — fix in PR #56, awaiting on-device confirm.**
   - Symptom (every build through v0.6.2): `OutOfMemoryError "Failed to allocate a 149817619 byte
     allocation"` on loading a DNG, then a toast; app survives but no image.
   - **Root cause (confirmed):** `149,817,619 = 4080×3060×3×4` = the **full-res decoded linear
     buffer**, NOT the file. This DNG **ignores LibRaw `half_size`** and decodes full-resolution;
     the result's direct `ByteBuffer` is a **managed (non-movable) byte[] on Android**, so ~150 MB
     blows the ART growth limit (~256 MB). Earlier theories (full-file `readBytes`, compressed-DNG
     fallback) were wrong/partial — #43/#44/#53/#54.
   - **Fix = PR #56** (`claude/sharp-allen-platform-oom`): native `DecodeOptions.maxLongEdge` —
     `raw_decoder` subsamples `img->data` straight into a final-sized buffer (no full-res float
     copy), plumbed JNI → `RawDecoder.Settings` → `EngineHelpers` (`maxLongEdge = maxEdge`). Also
     downsamples the platform-decoder fallback bitmap, and logs `sfraw: decoded WxH …` so the next
     logcat shows the real dims. Built as **v0.6.3 (8)** APK, delivered to the user; **waiting on
     their logcat** to confirm no OOM + a render. Codex P2s on #56 already addressed (host-build
     `#ifdef __ANDROID__` log guard; single-alloc subsample).
   - **Test DNG:** the user's `raw_test.bin` (~24.9 MB, 4080×3060 Bayer, from Google Drive). A
     decoded linear-ACES copy was at `/tmp/dng_lin_aces.f32` — `/tmp` does NOT persist across
     sessions; re-download from the user if needed.
2. **Open PRs:** **#56** (the RAW OOM fix — merge this), **#55** (`Neutral (Adobe-like)` preset +
   `docs/RESEARCH_LIGHTROOM_RENDER.md`; Codex P2 fixed → `scanFilm=false` print path). **Superseded,
   close without merge:** #53, #54 (their intent is folded into #56).

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
- **Big-file RAW** (the OOM saga): fd decode (#43), half-size proxy (#44) — both only covered the
  LibRaw *success* path; #56 is the real fix.
- `CLAUDE.md` (#34); v0.5.0/v0.6.0 release bumps.

## RE assets / where the analysis lives
- `docs/RESEARCH_LIGHTROOM_STACK.md` — LR stack (C++ "CR" engine + oneTBB + Vulkan/OpenCL/Metal +
  LiteRT + fp16, under a Kotlin/Java UI) vs ours, ranked "what to copy".
- `docs/RESEARCH_LIGHTROOM_RENDER.md` — LR default render (Adobe Color DCP + ProcessVersion +
  ToneCurvePV2012) vs ours on the test DNG; deltas + the `Neutral (Adobe-like)` preset rationale.
- `docs/RESEARCH_BIG_FILES.md`, `docs/IMPROVEMENT_BACKLOG.md`, `docs/AUDIT.md`.
- The Lightroom APK was decompiled in-env to `/tmp/lrx` (ephemeral) via the android-reverse-
  engineering skill; `aapt2` + `libLrAndroid.so` `strings`/`nm` were the evidence source.

## Known engine/app gaps (authoritative list: `docs/AUDIT.md`, updated 2026-06-01)
- **AAssetManager path not wired** (`spektra.cpp:828`, `spektra_jni.cpp:413`) — assets are
  extracted to `filesDir` on first run instead of read from the APK.
- **`use_enlarger_lut` reserved/unwired** (`spektra.h`) — scanner 3D-LUT is wired+opt-in; the
  enlarger-side spectral LUT (the real perf lever for the *expose* hotspot) is declared only.
- **Issue #7 — full-res RAW tiling/GPU still Open**: only app-side mitigations (OOM ladder, fd +
  half-size proxy, and now the #56 native `maxLongEdge` cap); no native tiling/streaming.
- **Glare-on-print** wired but default-off (stochastic → not bit-exact); **enlarger lens blur**
  and **`upscale_factor<1` AA prefilter** intentionally unwired (no oracle call site).
- **No Kotlin/JVM instrumented UI tests** beyond the host C++ parity + a few JVM unit tests.
- Doc upkeep: `docs/PRESETS.md` says "20 presets" — bump to 21 when #55 (Neutral preset) merges;
  `docs/DEVICE_TEST_REPORT.md` is the v0.4.0 device pass (historical). `docs/ENGINE_WIRING_PLAN.md`
  tracks the 4 once-gated params (now largely wired). `docs/ROADMAP.md` / `docs/RELEASE_CHECKLIST.md`
  (maintainer-only) hold the milestone + publish state.

## Doc map (what to read for what)
`CLAUDE.md` build/parity/arch · `docs/AUDIT.md` open items · `docs/IMPROVEMENT_BACKLOG.md` Lightroom
RE'd feature list · `docs/PERF_ROADMAP.md` perf plan+policy · `docs/RESEARCH_*` the RE studies
(stack, render, big-files, mcraw, film-character, lens-bokeh) · `docs/PRESETS.md`/`FILM_STOCKS.md`
content · `docs/maps/` source-project maps.

## Next steps
1. Get the user's **v0.6.3 logcat** for the DNG → confirm the `sfraw: decoded …` line shows capped
   dims and no `149817619` OOM, then merge **#56** and cut **v0.6.3**.
2. Merge **#55** (neutral preset). Close #53/#54.
3. Performance parity: the only path to Lightroom-class speed is the **GPU (Vulkan) compute** port
   (kernels exist, off by default) — it needs **on-device arm64 profiling/validation** to wire in
   and prove. Policy: **approximate proxy / exact export** (see `PERF_ROADMAP.md`).
