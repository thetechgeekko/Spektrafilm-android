# Changelog

## v0.8.0 — tungsten WB, masking, and high-bit-depth export 🎞️

A maintenance and polish release that brings film-stock WB, local adjustments, and
high-precision export support while keeping the core engine parity-safe.

### Film / WB
- **Balance to film stock** adds a virtual 85-filter workflow for tungsten film looks.
- **Default auto-exposure now matches spektrafilm** and fixes the blue-cast behavior.
- **Gray-point WB eyedropper improvements** make the neutral selector discoverable, work
  on non-RAW images, and wire the `CreativeWhiteBalance.solveNeutral` solver end to end.

### Local adjustment / masking
- **Class-S local adjustments** are now live, with Clarity, Texture, Sharpness, Highlights,
  and Shadows controls available in the masking UI.

### Export / formats
- **Lightroom-style export sheet** is added to surface the new high-bit-depth export options.
- **Scene-linear TIFF input export** is supported for the honest “linear DNG” workflow.
- **True 32-bit float TIFF export** is now supported in the native TIFF writer and UI.

### Docs
- Updated the release notes and HANDOFF docs around the new masking and high-bit-depth export
  workflows.

## v0.7.0 — engine completion: APK-direct assets + enlarger LUT 🎞️

Closes the two long-standing engine remainders, both verified on-device (Galaxy S25 Ultra,
Android 16, arm64) and parity-gated. No change to the bit-exact default/export path.

### Engine
- **Assets read directly from the APK (AAssetManager).** Profiles, the spectral upsampling LUT,
  and neutral print filters are now read straight from the packaged assets via `AAssetManager`,
  so the app no longer extracts the ~17 MB `spektra/` tree to internal storage on first launch
  (faster first run, no duplicate on-device copy). The on-disk path is preserved as a fallback;
  all Android-only code is `#ifdef __ANDROID__`-guarded so the host parity build is unchanged.
- **Enlarger 3D-LUT acceleration wired (`use_enlarger_lut`).** The print-expose spectral integral
  can now be PCHIP-interpolated through a per-channel 3D LUT (the print-side analogue of the
  scanner LUT), mirroring the spektrafilm oracle. Opt-in and **default-off**, so the default and
  export renders stay bit-exact; the new `test_enlarger_lut_e2e` gates it in CI. This was the last
  reserved engine LUT flag.

### Docs / quality
- Preset count corrected to **21** (the "Neutral (Adobe-like)" preset is now documented), AUDIT
  refreshed to current truth, and an on-device v0.7.0 re-validation recorded (full-res export with
  no OOM, presets re-render, rotate→export dimension swap).

## v0.6.x — RAW export out-of-memory fix 🧠

Device-confirmed fix for the OutOfMemoryError on loading/exporting large RAW/DNG files
(reproduced on a real Galaxy S25, Android 16). No change to the bit-exact render/export result.

### Fixed
- **Full-resolution RAW + engine buffers moved off the ART managed heap.** A full-res decoded
  linear float buffer is ~140 MB; on Android `ByteBuffer.allocateDirect` is a non-movable `byte[]`
  on the ~256 MB managed heap, so the full-res RAW input plus the engine's equally-large output
  could not coexist there and OOMed on export. Following Lightroom, those large buffers are now
  allocated natively (`malloc` + `NewDirectByteBuffer`) and reclaimed explicitly via
  `AutoCloseable` (`LinearImage`/`SimResult`), keeping the full-res pixels off the managed heap.
- Supporting work: file-descriptor RAW decode, an opt-in half-size decode + OOM-retry ladder for
  borderline-memory devices, and direct-buffer file fallbacks.

## v0.5.0 — Lightroom-feel editor wave ✨

A usability/feel pass informed by a deep reverse-engineering study of Lightroom mobile, plus
the Android 15 compatibility + full-resolution export fixes.

### Editor
- **Progressive preview** — slider edits now paint a fast coarse pass first, then refine to full
  resolution, so tuning feels immediate instead of waiting one full render per change. The coarse
  source is decoded separately so it never evicts the cached full-res proxy that look-edits reuse.
- **Slider haptics** — a light tactile tick when a slider drag settles.
- **Double-tap to reset a slider** — double-tap any value pill to snap that control back to its
  neutral default (with a haptic), Lightroom-style. Wired across all 50 single-value sliders.
- **Editor coach marks** — a one-time tip overlay (tap-a-category / before-after / 100% inspect /
  pinch-zoom) the first time the editor opens.
- **Sticky adjustment category** — the open adjustment section now survives a trip to Settings/
  About and back, so you return to where you were editing.
- **GPU preview (beta, opt-in)** — Settings → Experimental adds a GPU LUT preview path (renders the
  live preview by GPU-sampling a 3D LUT of the current look). Default OFF; export and the bit-exact
  film core are unaffected; grain/halation and zoom/compare are not on this path yet.

### Research / docs
- `docs/RESEARCH_LENS_BOKEH.md`, `docs/RESEARCH_FILM_CHARACTER.md`, `docs/IMPROVEMENT_BACKLOG.md`,
  `docs/ENGINE_WIRING_PLAN.md` — a reverse-engineering + film-imaging research wave guiding the
  next feature cycle (masking/local adjust, tone curve, color grading, depth-aware bokeh, lens/
  scatter character). Design studies only; no behavior change.

### Fixes
- **16 KB page-size compatibility (Android 15)** — the app now loads on devices with a 16 KB
  memory page size. Two changes were required: (1) the native libraries we build
  (`libspektra`, `libsfraw`, `libsftiff`, `libsfpng`) are now linked with 16 KB-aligned
  `PT_LOAD` segments — the build moved to **NDK r27** (16 KB by default) and each `CMakeLists`
  also pins `-Wl,-z,max-page-size=16384`; and (2) the APK now uses **build-tools 35**, whose
  `zipalign -P 16` page-aligns the (uncompressed) bundled `.so` to 16 KB offsets in the zip —
  without this the already-16 KB-aligned prebuilt libs (`libc++_shared`,
  `libdatastore_shared_counter`, `libandroidx.graphics.path`) still failed to map. A CI guard
  (`Verify 16 KB page compatibility`) asserts both conditions on every build. 32-bit
  `armeabi-v7a` is exempt (16 KB pages are a 64-bit-only feature).
- **Full-resolution export** — exports were silently capped at the 2048 px interactive-preview
  edge, so e.g. a 12 MP photo exported at ~3 MP. The final export render now uses the full
  source resolution (`EXPORT_MAX_EDGE_PX`); the 2048 px cap stays only for the live preview /
  magnifier. (On-device verification: issue #5 report.)

### Performance (M6)
- **Multithreaded full-res render** — the engine's per-pixel hot loops (`expose`
  spectral upsampling, `scan` density→RGB, `print_expose`) now run across all CPU
  cores via a deterministic fork-join helper (`kernels/parallel`). The image range
  is split into contiguous, disjoint pixel chunks whose boundaries depend only on
  (pixel-count, worker-count), so the output is **byte-identical regardless of
  thread count** — the bit-exact parity gate is preserved. Measured ~3.2× faster on
  a 12 MP scan on a 4-core host; larger gains on 6–8 core phones. Stochastic grain
  and the spatial blurs stay serial (grain walks a seeded RNG in pixel order). Worker
  count follows the core count, overridable via the `SPK_NUM_THREADS` env var; small
  previews fall back to serial below an 8192-pixel-per-worker floor.
- New `tests/test_parallel` gate asserts 1-thread vs 8-thread output is byte-identical
  for the scan route, the print route, and the grain+halation branch; the full
  `engine-parity` suite also runs multithreaded in CI.
- **Vector `exp10` SIMD** (`kernels/exp10.h`) replaces the `pow(10,−spectral)` bottleneck in
  the scan/print spectral integrals; lowers to NEON `fmla v.2d` on arm64, byte-identical at the
  float32 output (goldens unchanged).

### Testing & CI
- **First JVM unit tests** (`:app:testDebugUnitTest`) — `EditHistoryTest` covers the undo/redo
  store (push/undo/redo, redo-branch invalidation, cap eviction, clear, rotation), and
  `PresetsRoundTripTest` covers the non-destructive recipe layer (serialize → parse → decode
  preserves the editing state; missing keys keep defaults; re-serialization is idempotent). Both
  run on the plain JVM (no device) and are gated in the `android` CI job.
- **More parity gates** — `test_output_spaces` (all six output color spaces, not just sRGB) and
  `test_lensblur` (camera/scanner lens-blur spatial parity) are now in the `engine-parity` CI job.

## v0.4.0 — usability, performance & undo/redo ✨

Builds directly on the v0.3.0 engine/export foundation with an editor-usability overhaul, a
performance pass that keeps slider edits instant, in-session undo/redo, and the **Spektrafilm**
rebrand (display name only — package `com.spectrafilm.app` and the engine are unchanged).

### Rebrand
- **App display name is now "Spektrafilm"** across the UI, docs, Gradle, CI, and source headers.
  The application ID (`com.spectrafilm.app`), repository, and bit-exact engine are unchanged, so
  the rebrand carries no signing or compatibility impact.

### Editor usability
- **Open-photo button fixed** — the picker reliably opens from the editor.
- **Interactive crop overlay** — drag-to-adjust crop handles drawn over the live preview
  (replaces the previous numeric-only crop).
- **Histogram over preview** — the histogram now overlays the preview canvas for at-a-glance
  exposure/tonal reading while editing.
- **Reordered category bar** + **tooltips** on the category icons for discoverability.
- **Camera & scanner lens-blur controls un-gated** — both are now adjustable from the UI.
- **In-app "How to use" guide** (`HowToUseScreen`) surfaced from both About and the Welcome
  screen.

### Performance
- **Decoded-source proxy cache** (`DecodedSourceCache`) — the decoded RAW/photo proxy is cached
  so look-parameter edits (sliders, presets) re-render without re-decoding the source, keeping
  interaction responsive. Cache invalidates correctly on source change, white-balance, and
  rotation.
- **Opt-in half-size RAW decode** (`lib:libraw`) — a `halfSize` proxy-decode option that caps
  peak memory on large RAW/DNG. Default remains full-resolution; export is unaffected.

### Editing
- **In-session undo/redo with edit history** (`EditHistory.kt`) — top-bar Undo/Redo step through
  your edits, reusing the existing `Presets` JSON snapshots (rotation-aware). Edits are debounced
  so one drag is one undo step; the history clears on source change.

## v0.3.0 — Lightroom-style redesign, new engine stages, export upgrade 🎛️

Lightroom-style UI redesign, new engine stages, and a major export/import upgrade.

### Engine & pipeline
- **Auto-exposure stage (bit-exact, #6)** — all 7 metering patterns (center-weighted, spot,
  matrix, and 4 more) ported and parity-gated (`scan_portra_autoexp` golden). JNI now forwards
  `auto_exposure_method`, making every pattern selectable from the app.
- **Diffusion-filter stage (bit-exact, #6)** — spatial diffusion filters (halation/scatter
  coupling, DIR diffusion) ported and gated (`diffusion_bpm` golden).
- **Print path proven on all film/paper pairs** — native `print_digest` resolves neutral dichroic
  CC values + midgray exposure from `neutral_print_filters.json` (no longer baked for specific
  pairs). Proven end-to-end on a second pair via new `print_ektar` golden; both `print_portra`
  and `print_ektar` parity tests pass. Any profile combination is now valid.
- **Expert RAW DNG fix (`lib:libraw`)** — `USE_ZLIB` + NDK libz linked so DEFLATE-compressed
  DNGs (Samsung Expert RAW and similar) decode correctly. Adds a typed `DecodeStatus` enum and
  `RawDecodeException` for structured error propagation; DNG compression sniffer integrated.

### New native modules
- **`lib:tiffwriter`** (`libsftiff`) — hand-rolled 16-bit baseline TIFF writer with ICC + EXIF;
  wired live into the export pipeline.
- **`lib:pngwriter`** (`libsfpng`) — 16-bit PNG writer with zlib/deflate + iCCP; built and
  host-tested. Not yet wired into the export UI (in progress).

### App features
- **16-bit TIFF export** — live option in the export sheet, backed by `lib:tiffwriter`.
- **Lightroom-style Auto-exposure control** — "Auto" button is opt-in (default OFF); expands
  to a metering-method popup with adaptive above/below anchoring and tap-outside dismiss.
- **Profile-curve browser** — dedicated screen to browse film/paper density curves.
- **Non-destructive recipe/sidecar layer** — edits are stored as a `SpektraParams` sidecar
  keyed to the source; original RAW is untouched; re-renders on open/export.
- **Engine/render status pill** — persistent readout showing decoding / rendering / exporting /
  error / last-render-ms on the preview canvas.
- **Source EXIF copy on export** — camera/lens/exposure/date EXIF from the source image is
  copied into exported JPEGs. **GPS/location is opt-in** (Settings → "Preserve location",
  default OFF/stripped) so shared images don't leak location by default.
- **Google Ultra HDR export** — exports a gain-map JPEG Ultra HDR when the device supports it.

### Major UI redesign (Lightroom-style)
- **Edge-to-edge full screen** — no Scaffold / ModalBottomSheet; root `Column` layout.
- **Pinned preview** (`weight(1f)`, near-black, fit) with a **90° rotate button** applied via
  the single `loadSource()` decode path (preview + export + magnifier all rotate together).
- **Horizontal scrollable bottom category bar** — custom hand-drawn `SpectraIcons` (12
  categories + gear / "?" / rotate), spring overscroll, sliding indicator,
  `navigationBarsPadding` for gesture-safe operation.
- **Inline `AnimatedVisibility` adjustment panel** between the preview and the category bar
  (replaces modal bottom sheets).
- **Back → previous screen**; **double-back-to-exit** with a one-time DataStore-persisted
  hint toast.
- Settings → gear icon, About → "?" icon.

### Quality & CI
- Crop, auto-exposure, diffusion (incl. full-pipeline + matrix-metering), lens-blur,
  `print_ektar`, and LUT-accel parity tests gated in the `engine-parity` CI job.
- `android-emulator` KVM smoke job added but gated to manual `workflow_dispatch` (hosted
  runners have no `/dev/kvm`; needs runner-level fix before it can run automatically).
- `tools/device_smoke_test.sh` — one-command on-device verification for the maintainer.

### Security hardening (from a pre-release review)
- Reject >2 GiB before `ByteBuffer.allocateDirect((jint))` in the RAW-decode and engine-output
  JNI paths (prevents `jint` truncation → heap overflow). Added direct-buffer capacity checks to
  the TIFF/PNG writer JNI and a 32-bit-ABI overflow guard to the PNG writer.
- GPS-on-export is now opt-in (see above).
- **Release note:** the `dist/` APK is **debug-signed** (fallback) — the maintainer must rebuild
  with a real release keystore before publishing. LibRaw decode paths should be fuzzed pre-release.

*Film modeling powered by [spektrafilm](https://github.com/andreavolpato/spektrafilm).*

---

## v0.2.0 — in development 🎛️

Turning the engine into a real, playable tool.

- **Full parameter surface wired through** — every `SpektraParams` field (camera, enlarger,
  scanner, grain, halation, DIR couplers, glare, IO, settings) now reaches the engine; defaults
  stay bit-exact (parity preserved), and edits measurably change the render.
- **RAW/DNG import** via LibRaw (`libsfraw.so`, all ABIs) → linear ACES, plus an sRGB photo
  picker (→ProPhoto) and the synthetic demo image.
- **Full GUI organized exactly like the spektrafilm desktop GUI** — Input · Import Raw ·
  Simulation · Grain · Preflash · Halation · Couplers · Glare · Experimental · Display —
  ImageToolbox-styled collapsible cards + sliders, with a debounced live preview.
- **Presets:** save / apply / delete and **import / export** as JSON, plus **20 built-in
  researched presets** (portrait, landscape, slide/chrome, cinema, low-light, nostalgic).
- **Film/print-stock catalog** — friendly names grouped by category (negative / slide /
  motion-picture / print film / paper) with ISO · balance · era · character.
- **Custom adaptive app icon** (35 mm film frame + spectral strip; Material You monochrome).
- **Export mask** — a full-screen overlay during the full-resolution render → gallery save.
- **Crop / resize geometry stage ported** (bit-exact) — the previously-inert `IOParams` crop
  (`crop`, `crop_center`, `crop_size`) and cubic `upscale_factor` now run up front in both the
  scan and print routes, matching the spektrafilm `_preprocess` step. Defaults are a strict
  no-op (parity preserved); a new `scan_portra_crop` golden gates the non-default path.
  (Downscale `upscale_factor < 1` AA is a documented follow-up.)
- **RAW white-balance UI** — Temperature/Tint sliders + reset-to-as-shot, shown only for
  RAW/DNG sources and wired to the existing LibRaw decoder so changing WB re-decodes the
  preview. Default (as-shot) decode unchanged.

## v0.1.0 — first release 🎞️

The complete **spektrafilm** spectral film-simulation engine, ported to native C++ and running
on Android. Dedicated to the [pixls.us](https://pixls.us) community.

### Engine (native C++ / NDK, bit-exact vs the original)
- Spectral upsampling (Hanatos2025), filming (expose → develop), **DIR couplers**
  (pointwise + spatial diffusion), printing (enlarger + dichroic Y/M/C filters, **all 28
  film/paper profiles** via a native neutral-filter + midgray digest), scanning
  (spectral → XYZ → RGB + unsharp + CCTF).
- **Halation**, in-emulsion scatter, and **film grain** (Poisson-binomial particle model with
  sublayers + micro-structure, statistically matched).
- **6 output color spaces:** sRGB, Adobe RGB, ProPhoto, Rec.2020, ACES2065-1, linear sRGB.
- `spk_simulate` (both routes) exposed through a C API + **JNI bridge**; `libspektra.so` for
  arm64-v8a / armeabi-v7a / x86_64.

### App
- Jetpack Compose UI: film/print profile pickers, scan-vs-print toggle, exposure, live render.
- 28 profiles + spectral LUTs bundled (~17 MB); assets extracted on first run.

### Quality
- Parity-first port: gated stage-by-stage against the live Python engine (golden vectors).
- CI builds `libspektra.so`, runs the engine parity tests, and assembles the APK on every push.

### Known next steps
- On-device RAW/DNG import (LibRaw module scaffolded).
- Non-destructive recipe/preset editing; richer editing UI.

**APK:** see the [GitHub Releases](../../releases) page (min Android 7.0). *(Historical `dist/`
APKs were removed from the repo — they were stale, 16 KB-page-misaligned and debug-signed.)*
