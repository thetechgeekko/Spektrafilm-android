# Changelog

## Unreleased

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

**APK:** [`dist/SpectraFilm-v0.1.0.apk`](dist/SpectraFilm-v0.1.0.apk) (min Android 7.0).
