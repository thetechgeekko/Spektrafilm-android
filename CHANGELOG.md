# Changelog

## v0.3.0 — in development 🎛️

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
- **Full source EXIF copy on export** — all EXIF tags from the source image are copied into
  exported JPEGs and TIFFs.
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
- Crop, auto-exposure, and diffusion parity tests gated in the `engine-parity` CI job.
- `android-emulator` KVM smoke job added but gated to manual `workflow_dispatch` (hosted
  runners have no `/dev/kvm`; needs runner-level fix before it can run automatically).

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
