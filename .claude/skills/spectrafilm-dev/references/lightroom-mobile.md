# Lightroom-mobile editor patterns

Source: Lightroom-mobile research brief. This is the editor reference: read it before changing
anything in `app/src/main/java/com/spectrafilm/app/**`. The editor is a parametric,
non-destructive, Lightroom-style stack over the parity-critical CPU/NDK engine. Engine and
build rules live in `references/parity-and-build.md`.

The editor (~27 Kotlin files) — key ones:
- `MainActivity.kt` — edge-to-edge Compose, pinned preview, 90 degree rotate, scrollable
  category bar (12 categories), `AnimatedVisibility` panel, double-back-to-exit.
- `ParamsState.kt` — flat Compose-observable mirror of `SpektraParams`; `mutableStateOf` per
  control; `loadFrom`/`toParams` round-trip.
- `ImagePipeline.kt` — import/export; decode -> scene-linear ProPhoto; export float buffer ->
  8/16-bit TIFF/PNG; off-heap native alloc for full-res.
- `Viewer.kt` — `ZoomableImage` (pinch 1-8x, clamped pan, double-tap fit/2x); Lightroom-style
  ROI render (debounced visible-region native re-render overlay); `CompareSlider`;
  `HistogramCard` (off-thread Canvas); `MagnifierOverlay`.
- `CropOverlay.kt` — drag corners/edges, pin aspect; geometry stage `crop`/`crop_center`/`crop_size`.
- `Presets.kt` — save/load/import/export versioned JSON (`org.json`); every field round-tripped.
- `Recipes.kt` — non-destructive sidecar keyed to source RAW URI in `filesDir/recipes/`.
- `BuiltInPresets.kt` — 21 curated film -> paper looks.
- `ProfileCurvesScreen.kt`, `SettingsScreen.kt`, `EditHistory.kt`, `LutGpuPreview.kt`.

## 1. Editing model — parametric, never pixel-baked

- **Edits are instructions, not baked pixels.** The original RAW is never mutated; any parameter
  (crop included) stays revisitable. The implementation is a serializable parameter set
  (`SpektraParams` / `ParamsState`) plus a render that derives pixels on demand.
- **Presets/recipes are saved parameter snapshots, NOT LUTs.** This is the central design
  decision: VSCO-era apps shipped baked 3D LUTs (sample the RGB cube, replace each pixel) that
  users could not tweak; the modern approach stores presets as editable instructions — a preset
  is a starting point you keep editing. In this app: a recipe = a `SpektraParams` snapshot;
  "apply preset" pre-populates the parameter stack, it does not composite a fixed image.
  (`Presets.kt`, `Recipes.kt`, `BuiltInPresets.kt`)
- **Preset strength / amount.** A preset can carry an intensity slider; implement it as a global
  "preset strength" blend factor (drag to dial strength). See `PresetAmount.kt`.
- **Preset stacking.** When one preset is applied over another, define the stacking semantics
  explicitly (later-overrides vs additive) — do not leave it implicit.
- **Tool/panel taxonomy** (mirror Lightroom-mobile grouping): Light (tone), Color, Effects,
  Detail (sharpen/NR), Optics (lens correction), Geometry (transform/upright), Crop, Presets,
  Masking/local, Healing.
- **Interaction conventions to copy:** double-tap a slider handle resets that single slider to
  default; two-finger tap on the photo toggles the histogram; before/after compare + a
  non-destructive re-editable crop are baseline; crop overlays offer selectable composition grids
  (rule-of-thirds, golden ratio) + aspect lock + straighten + rotate/flip.

## 2. Real-time editing pipeline on Android

- **Two-resolution proxy is THE rule, not an option.** Edit interactively against the low-res
  proxy (~640 px preview); render full-res only on export. The engine already splits
  preview/scan; codify it as a rule, never render full-res during interaction.
- **Everything heavy off the main thread.** Decode + simulate run on a background dispatcher;
  only the finished bitmap touches the UI thread. Never render on the main thread.
- **Coalesce-to-latest (backpressure).** While a slider drags, intermediate param states are
  stale instantly. Keep only the newest pending render and drop superseded ones (the CameraX
  `STRATEGY_KEEP_ONLY_LATEST` mentality). Implement by cancel/replacing the in-flight preview job
  with the latest params (`conflate`/`collectLatest` on the params flow).
- **Debounce slider input.** Do not render on every pixel of slider travel; debounce/throttle
  (a few hundred ms of silence) or coalesce-to-latest before kicking a preview render.
  **FLAG:** the droidcon `debounce(300)` snippet was recovered from an excerpt (page 503'd) —
  verify before quoting it verbatim.
- **GPU is not a drop-in for the simulate path.** JNI/native is good for low-latency CPU image
  processing; GPU shaders win for truly real-time per-frame, but the spectral engine is NDK/CPU
  by design (parity-critical, deterministic, thread-invariant). Bit-exact parity + NaN-propagation
  semantics break under GPU float. `LutGpuPreview.kt` is preview-only and default OFF; never route
  the simulate/export path through it. (RenderScript is deprecated — do not recommend it.)
- **RAW/DNG via LibRaw -> linear RGB** (`:lib:libraw`, `RawDecoder.kt`): decode is part of the
  off-thread work feeding the proxy/full-res split. Half-size decode for preview; off-heap full
  size; Samsung Expert RAW DEFLATE DNG via zlib/NDK `libz`.

## 3. Jetpack Compose patterns

- **Hold the parameter set in a stable state holder, not loose composable state.** Collect state
  at the smallest scope needed (not one top-level `collectAsState`) — this dramatically reduces
  recompositions and lets Compose skip when params are stable/unchanged. `ParamsState.kt` is that
  holder.
- **Drive expensive renders via `snapshotFlow` + debounce/conflate, NOT recomposition.**
  `snapshotFlow` turns Compose `State` (`ParamsState`) into a cold `Flow` that emits only on
  actual change; chain `.debounce`/`.conflate`/`collectLatest` to throttle slider spam and start
  the off-thread render as a side effect — without recomposing every frame.
- **`derivedStateOf` for noisy -> quiet inputs** (slider drag position, scroll): memoizes,
  invalidates only when the computed result changes.
- **Defer fast-changing reads to the layout/draw phase** — the biggest gesture-perf lever. For
  pan/zoom/crop, read offset/scale inside lambda modifiers (`Modifier.offset { IntOffset(...) }`,
  `Modifier.graphicsLayer { ... }`) or `Canvas`/`drawWithCache`, so a gesture re-runs only
  layout+draw (or draw only), skipping recomposition.
- **Gestures:** `detectTransformGestures` for pinch-zoom/pan; keep scale/offset in `remember`'d
  state, apply via `graphicsLayer` (see `Viewer.kt`'s `ZoomableImage`).
- **Native-rendered bitmap (JNI interop) pitfalls:**
  - **Never pass `Painter`/`ImageBitmap` as a composable parameter** — `Painter` is not `@Stable`
    and bitmaps are expensive to equality-check, causing recomposition storms. Pass a stable
    key/handle and resolve the bitmap inside.
  - Cross JNI with a direct `ByteBuffer` (the engine already uses float32 RGB); convert native
    output -> `Bitmap` via `copyPixelsFromBuffer`.
  - Call `ImageBitmap.prepareToDraw()` before drawing to pre-upload the texture.
  - Use `Modifier.drawWithCache` so `Brush`/`Shader`/`Path` are not reallocated each frame.
  - **Reuse bitmap buffers** — do not allocate a new `Bitmap` per render; keep a reusable buffer
    sized to the proxy.

## 4. Color management & export

- **sRGB is the Android default; opt into wide gamut explicitly.** Any bitmap without a profile
  is treated as sRGB.
- **Enable Display-P3 (API 26+)** per `<activity>` via `android:colorMode="wideColorGamut"`, or
  `window.setColorMode(COLOR_MODE_WIDE_COLOR_GAMUT)` in `onCreate` before the window is created.
  Wide-gamut content needs an `RGBA_F16` (half-float) bitmap + a wide ICC profile (Display-P3 =
  DCI primaries + sRGB transfer); `ARGB_8888` is fine for sRGB.
- **Capability checks:** `Display.isWideColorGamut()`, `Configuration.isScreenWideColorGamut()`,
  `Bitmap.getColorSpace()`, `ColorSpace.isWideGamut()`. Decode into a target space via
  `BitmapFactory.Options.inPreferredColorSpace`.
- **Cost / pitfalls:** wide-color windows use more memory + GPU — reserve them for the
  full-screen preview, not thumbnail grids. Classic regression: pre-managed apps desaturated
  assets and looked muted on color-accurate displays. Author in the correct space and let the
  system manage.
- **16-bit export** (`:lib:tiffwriter` / `:lib:pngwriter`): recommended wide-gamut PNG = 16-bit
  per channel + embedded ICC + no extraneous metadata. **Carry the engine's output color space**
  (scanning emits XYZ -> output RGB in one of 6 spaces) into the embedded profile so the file is
  self-describing. Other export: JPEG 8-bit; Ultra HDR (Android 14+); EXR/32-bit deferred.
  `EXPORT_MAX_EDGE_PX = 16384`. **FLAG:** the exact "no extraneous metadata" wording came from a
  search excerpt only — verify against a colour-management reference before quoting verbatim.

## 5. Key pitfalls (bake these into any editor change)

- Do not bake edits to pixels — params stay parametric/serializable; presets = param snapshots,
  not LUTs.
- Never render on the main thread; never full-res during interaction.
- Coalesce-to-latest + debounce slider input; cancel superseded preview jobs.
- Never pass `Painter`/`ImageBitmap` as composable params (unstable -> recomposition storms).
- Defer pan/zoom/crop state reads to the layout/draw phase via lambda modifiers / `graphicsLayer`.
- Reuse bitmap buffers; `prepareToDraw()`; `drawWithCache`.
- For the CPU/NDK parity-critical engine, do NOT swap in GPU shaders for the simulate path —
  parity and NaN semantics break.
- Wide gamut is opt-in, memory-heavy, and needs `RGBA_F16` + ICC; reserve it for the full-screen
  preview.

## Sourcing & uncertainty (preserve these flags)

- **FLAG:** droidcon `debounce(300)` snippet recovered from an excerpt (page 503'd) — verify
  before quoting verbatim.
- **FLAG:** the 16-bit-PNG "no extraneous metadata" wording is from a search excerpt only —
  verify against a bjango-style colour-management reference before quoting verbatim.
- **FLAG:** two Adobe help pages 404'd/503'd during research; substituted with the Adobe
  edit-panel page, Lightroom Queen, and ON1.

### Sources (URLs)
- Presets (non-destructive): https://helpx.adobe.com/lightroom-cc/using/presets-lightroom-android.html ;
  https://on1.com/lightroom-mobile-presets/
- Darkroom recipes (presets-as-instructions): https://darkroom.co/blog/2017-03-recreating-vsco-filters
- VSCO Recipes: https://vsco.co/features/recipes
- Edit panel / global sliders: https://helpx.adobe.com/lightroom-cc/using/edit-panel-android.html ;
  https://lightroomqueen.com/lightroom-mobile-editing-global-sliders/
- Proxy editing: Foundry Nuke proxies; Neat Video
- Off-thread / backpressure: https://developer.android.com/media/camera/camerax/configuration
- Compose perf/side-effects/phases:
  https://developer.android.com/develop/ui/compose/graphics/images/optimization ;
  https://developer.android.com/develop/ui/compose/side-effects ;
  https://developer.android.com/develop/ui/compose/phases
- Compose gesture image ref: https://github.com/SmartToolFactory/Compose-Image
- Wide color gamut: https://developer.android.com/training/wide-color-gamut
