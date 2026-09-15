# Improvement backlog — reverse-engineered from Lightroom mobile 11.3.3

> **Idea catalog, not a ranked release queue.** An item becomes executable only after it is accepted
> as a native Wayfinder child with dependencies and acceptance criteria. See
> [EXECUTION_INDEX.md](EXECUTION_INDEX.md).

Static RE of Adobe Lightroom mobile (`com.adobe.lrmobile` 11.3.3, APK only) vs Spektrafilm
today. Evidence keys: `ICB*` = native engine bridge methods in `libLrAndroid.so`;
`layout/*.xml` = decompiled UI; `native:` = `libLrAndroid.so` symbols. **Spektrafilm baseline:
global params only (`SpektraParams.kt`), `EditHistory`, `Presets`/`Recipes`, `CropOverlay`,
TIFF/PNG + Ultra-HDR export.** Masking v1 (radial/linear + luminance/color range, 13 local ops)
and a tone-curve UI (master + per-channel RGB) have since shipped; the remaining headline gaps
are HSL/color mix, 3-way grading wheels, and brush + AI masks.

Effort: S = small, M = medium, L = large. This is a backlog, not a commitment.

## A. Masking & local adjustments — the biggest gap (Spektrafilm is 100% global)
- ✅ **Local-adjustment container** — shipped (masking v1). Wraps a Spektra edit set per mask; the architectural keystone for everything below.
- ✅ **Linear gradient mask** — shipped (masking v1).
- ✅ **Radial gradient mask** — shipped (masking v1).
- **Brush mask** + feather/flow — `ICBCreateBrush`, `ICBBrushMaskToByteArray`. (L)
- **AI Select Subject** — `ICBSetUPSelectSubjectPipelineConfig` (would add LiteRT as a NEW dependency — no LiteRT/TFLite is in the app today; no ticket; it would be a new dependency). One-tap isolation. (L)
- **AI Select Sky** — `ICBSetUPSelectSkyPipelineConfig`, `ICBGenerateDynamicSkyPreset`. Pairs with sky-tint film presets. (M)
- ✅ **Luminance range mask** — shipped (masking v1).
- ✅ **Color range mask** (with eyedropper) — shipped (masking v1).
- **Depth range mask** — `ICBGetDepthRange`/`SetDepthRange`; reuse the Lens-Blur depth map to mask by distance. (M)
- ✅ **Mask overlay viz** — shipped (draw-on-preview overlay).
- (Defer: people part-masks `ICBComputePeopleMasks`, background/object select.)

## B. Tone / color (film looks live here)
- ✅ **Parametric + point tone curve UI** — shipped (master + per-channel RGB point curve).
- **3-way color grading wheels** — `layout_color_wheel_group.xml`, native `ColorGrade/SHADOW/MIDTONE/HIGHLIGHT/Balance/Blending`. Most-requested film-mood control. (M)
- **HSL / targeted color mix** — `ICBFillColorMixValues`, `colormixer_layout.xml`. Per-band hue/sat/lum → emulate dye responses. (M)
- **Targeted on-image color drag (TAT)** — `ICBSampleHueColorForAdjustment`. (M)
- **Split toning (simple)** — `splittone_layout.xml`. Lighter alternative to wheels. (S)
- **Auto-tone** — `ICBCalculateAutoToneParams`. Starting point before a recipe. (M)

## C. Healing / retouch
- **Spot heal + clone** — `ICBGetRetouchBrushData`, `CloneMode`, `heal_mode_selector.xml`. Dust/blemish (essential for scans). (L)
- Heal feather/opacity refine — `ICBSetRetouchFeather`/`Opacity`. (S)
- (Defer: AI blemish `ICBSetUPSelectSkinBlemishPipelineConfig`, distractor removal PDR.)

## D. Geometry / crop / upright
- **Guided + auto Upright** — `ICBCalculateGuidedUpright`, `geometry_upright_button_group.xml`. (M)
- **Perspective/distortion sliders** — `ICBFillUprightTransforms`, `geometry_layout_ev.xml`. (M)
- Constrain/expand crop — `ICBHandleConstraintCrop`. (S)

## E. Presets / profiles / amount
- ✅ **Preset/profile amount slider** — shipped (`PresetAmount.kt`: continuous params lerp, categorical snap at 0.5).
- ✅ **User presets from current edit** — shipped (save/import/export own presets).
- Preset groups / favorites / selective-settings — `ICBGetPresetGroupNames`, `create_preset_settings_group_item.xml`. (M)
- (Defer: recommended/adaptive `ICBComputeAndCacheRecommendedStyle`.)

## F. History / versions / copy-paste / batch
- ✅ **Copy/paste settings** — shipped (v0.4.0; `settingsClipboard` in MainActivity.kt). Per-section selection still open — `ICBCreateClipBoardForAllParams`, `ICBPasteFromClipboardParams`, `dialog_loupe_copyoptions.xml`. (M)
- **Named versions/snapshots** — `loupe_versions.xml` (extend our linear `EditHistory`). (M)
- **Batch apply recipe** — `cloudy_sync_status_item_batch_edit.xml`. (L)
- ✅ Granular reset scopes — shipped (v0.4.0).

## G. Compare / before-after / discovery
- ✅ **Before/after** — shipped as a draggable split/wipe (`CompareSlider` in `Viewer.kt`). (S)
- Inline interactive tutorials ("Discover") — `discover_*_step_view_holder.xml`, `tutorials/content/tut_*.json`. (M)
- Per-feature onboarding gates — `fragment_masking_onboarding.xml` (extend our `CoachMarks`). (S)

## H. Render / preview pipeline & performance
- **Multi-level progressive render** — no ticket; history in `docs/PERF_ROADMAP.md` #6 (a CPU coarse→fine two-pass shipped in v0.5.0).
- **Tiled GPU pyramid** — native `cr_image_tile`, `cr_gpu_pyramid`, `cr_gaussian_pyramid`. Large images / low memory. (L)
- **Layer-scoped re-render** — `ICBRenderLayerAsync`. Re-render only changed mask/layer. (M)
- **Pause/refresh render on gesture** — no ticket; history in `docs/PERF_ROADMAP.md` #5.
- **Grain mask caching** — native `cr_grain_mask_cache`. Cache AgX grain buffers across renders. (M)
- **GPU delegate for ML masks** — `libLiteRtClGlAccelerator.so`. (M)
- **Live histogram w/ clipping** + HDR-range viz — `HistogramView`, `ICBVisualizeHDRRange`. (M)

## I. Output / export / format / color management
- **AVIF export** — `ICBGenerateExportAvif`, `DEFAULT_AVIF_COLOR_SPACE`. Modern HDR, small. (M)
- **JPEG XL export** — `RAW_FORMAT_JPEGXL`. Archival. (M)
- **HEIC/HEIF 10-bit** — `RAW_FORMAT_HEIC`. Efficient HDR scans. (M)
- **Content Credentials (C2PA)** — `export_cai_config_section.xml`, `c2paIngredients` (lib `libadobe_c2pa.so`). Provenance; differentiator. (L)
- **DNG export** — `ICBGenerateExportDNG`. (M)
- **Watermark / film-frame borders** — `ICBAddBorderToJpegFile`, `watermark_editor.xml`. Popular aesthetic. (M)
- ✅ Structured export bottom-sheet — shipped (`ExportSheet.kt`).

## J. Misc engine surface
- AI denoise / texture / sharpen — `ICBCopyValidNoiseReductionParams`, `ICBCopyValidSharpeningParams` (pairs with our unsharp). (M)
- Lens-profile distortion/vignette UI — `ICBGetLensProfileDistortionScaleValue`, `ICBSetLensProfileLensVignettingValue` (we have LensProfiles data). (S)

