# Spektrafilm Android — Session Handoff

## State (2026-06-01)
- **`main` is the trunk.** Current on `main`: **`versionCode 8` / `versionName 0.6.3`** (tags
  `v0.3.0`/`v0.4.0` on origin; 0.5.0–0.6.3 cut via PRs). Host engine parity suite is **bit-exact /
  green**; `:app:assembleDebug` builds + packages `libspektra.so` (+ `libsfraw`/`libsftiff`/
  `libsfpng`, and the opt-in `gpu`/`ml` stubs) for `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- Workflow: feature branch → **draft PR into `main`** → maintainer merges. `origin/HEAD` may still
  point at a stale `claude/sharp-allen-*` branch; `main` is the real trunk.
- Build/test/parity commands and the engine architecture are in **`CLAUDE.md`** (read it first).
  Prime directive stands: **bit-exact parity with the spektrafilm oracle** for the default/export
  path (`-fno-finite-math-only` required; `test_*` host gates; thread-invariant). Commit with
  `-c commit.gpgsign=false`. Keep the "Film modeling powered by spektrafilm" attribution. Never put
  the model identifier in committed artifacts.

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

## Known engine/app gaps (authoritative list: `docs/AUDIT.md`, updated 2026-06-01)
- **AAssetManager path not wired** (`spektra.cpp:828`, `spektra_jni.cpp:413`) — assets are
  extracted to `filesDir` on first run instead of read from the APK.
- **`use_enlarger_lut` reserved/unwired** (`spektra.h`) — scanner 3D-LUT is wired+opt-in; the
  enlarger-side spectral LUT (the real perf lever for the *expose* hotspot) is declared only.
- **Issue #7 — full-res RAW tiling/GPU still Open**: mitigations now = OOM ladder, fd + half-size
  proxy, `maxLongEdge` preview cap, and **off-heap native buffers for full-res export (#56)** —
  the export OOM is resolved for typical phone DNGs. Still no native **tiling/streaming**, so a
  *pathologically* large DNG (single buffer > native headroom) is unbounded; tiling is the
  remaining work. The fd-failure file fallback uses `allocateDirect` (still managed) → a possible
  off-heap follow-up.
- **Glare-on-print** wired but default-off (stochastic → not bit-exact); **enlarger lens blur**
  and **`upscale_factor<1` AA prefilter** intentionally unwired (no oracle call site).
- **No Kotlin/JVM instrumented UI tests** beyond the host C++ parity + a few JVM unit tests.
- Doc upkeep: `docs/PRESETS.md` still says "20 presets" — bump to **21** now that #55 (Neutral
  preset) is merged; `docs/DEVICE_TEST_REPORT.md` is the v0.4.0 device pass (historical).
  `docs/ENGINE_WIRING_PLAN.md`
  tracks the 4 once-gated params (now largely wired). `docs/ROADMAP.md` / `docs/RELEASE_CHECKLIST.md`
  (maintainer-only) hold the milestone + publish state.

## Doc map (what to read for what)
`CLAUDE.md` build/parity/arch · `docs/AUDIT.md` open items · `docs/IMPROVEMENT_BACKLOG.md` Lightroom
RE'd feature list · `docs/PERF_ROADMAP.md` perf plan+policy · `docs/RESEARCH_*` the RE studies
(stack, render, big-files, mcraw, film-character, lens-bokeh) · `docs/PRESETS.md`/`FILM_STOCKS.md`
content · `docs/maps/` source-project maps.

## Next steps
1. **Cut a `v0.6.3` release tag** (`release.yml` builds the signed APK + GitHub Release on a `v*`
   tag). Main is at `versionCode 8` / `0.6.3` with the device-confirmed export OOM fix.
2. **Bump `docs/PRESETS.md` to 21 presets** (Neutral preset merged via #55).
3. **Issue #7 — native RAW tiling/streaming** for *pathologically* large DNGs (export OOM is
   resolved for typical phone DNGs via off-heap buffers; a single buffer larger than native
   headroom is still unbounded). Optional smaller follow-up: make the fd-failure file fallback
   off-heap too (currently `allocateDirect` = managed).
4. Performance parity: the only path to Lightroom-class speed is the **GPU (Vulkan) compute** port
   (kernels exist, off by default) — it needs **on-device arm64 profiling/validation** to wire in
   and prove. Policy: **approximate proxy / exact export** (see `PERF_ROADMAP.md`).
