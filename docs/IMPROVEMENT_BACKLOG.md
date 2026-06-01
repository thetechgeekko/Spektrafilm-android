# Improvement backlog — reverse-engineered from Lightroom mobile 11.3.3

Static RE of Adobe Lightroom mobile (`com.adobe.lrmobile` 11.3.3, APK only) vs Spektrafilm
today. Evidence keys: `ICB*` = native engine bridge methods in `libLrAndroid.so`;
`layout/*.xml` = decompiled UI; `native:` = `libLrAndroid.so` symbols. **Spektrafilm baseline:
global params only (`SpektraParams.kt`), `EditHistory`, `Presets`/`Recipes`, `CropOverlay`,
TIFF/PNG + Ultra-HDR export.** No masking / local adjustments / tone-curve UI / HSL / color
grading exist yet — that is the headline gap.

Effort: S = small, M = medium, L = large. This is a backlog, not a commitment.

## A. Masking & local adjustments — the biggest gap (Spektrafilm is 100% global)
- **Local-adjustment container** — `ICBCreateNewCorrectionMask`, `ICBAddSelectedMasksToParams`, group add/sub/invert. Wrap a Spektra edit set per mask; **architectural keystone** for everything below. (L)
- **Linear gradient mask** — `ICBSetLinearGradientCorrectionsToParams`, `ICBSetLinearGradientZeroPoint`. Graduated-ND / sky darkening. (M)
- **Radial gradient mask** — `ICBSetRadialGradientCorrectionsToParams`, `ICBGetRadialGradientFeather`. Local vignette / halation boost. (M)
- **Brush mask** + feather/flow — `ICBCreateBrush`, `ICBBrushMaskToByteArray`. (L)
- **AI Select Subject** — `ICBSetUPSelectSubjectPipelineConfig` (LiteRt already in our stack). One-tap isolation. (L)
- **AI Select Sky** — `ICBSetUPSelectSkyPipelineConfig`, `ICBGenerateDynamicSkyPreset`. Pairs with sky-tint film presets. (M)
- **Luminance range mask** — `ICBGetLumRange`/`SetLumRange`. Restrict grain/halation to shadows/highlights; no ML. (M)
- **Color range mask** — `ICBSetRangeMaskCorrectionsToParams`, eyedropper; reuses our spectral color math. (M)
- **Depth range mask** — `ICBGetDepthRange`/`SetDepthRange`; reuse the Lens-Blur depth map to mask by distance. (M)
- **Mask overlay viz** — `ICBSetMaskVisualizationMode`/`OverlayColor`/`Opacity`. Required UX for any mask. (S)
- (Defer: people part-masks `ICBComputePeopleMasks`, background/object select.)

## B. Tone / color (film looks live here)
- **Parametric + point tone curve UI** — `ICBGetMainToneCurvePoints`, per-RGB `ICBGetColorToneCurvePoints`, `tone_curve_sheet.xml`. Classic film S-curve over the spectral base. (M)
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
- **Preset/profile amount slider** — `ICBSetPresetAmount`, `preset_amount_slider_view.xml`, `ICBProfileSupportsAmountSlider`. Dial a film look 0–100%; **highest delight / low effort** on our recipe system. (M/S)
- **User presets from current edit** — `ICBCreateNewUserPreset` (we have `Recipes`). (M)
- Preset groups / favorites / selective-settings — `ICBGetPresetGroupNames`, `create_preset_settings_group_item.xml`. (M)
- (Defer: recommended/adaptive `ICBComputeAndCacheRecommendedStyle`.)

## F. History / versions / copy-paste / batch
- **Copy/paste settings** w/ per-section selection — `ICBCreateClipBoardForAllParams`, `ICBPasteFromClipboardParams`, `dialog_loupe_copyoptions.xml`. (M)
- **Named versions/snapshots** — `loupe_versions.xml` (extend our linear `EditHistory`). (M)
- **Batch apply recipe** — `cloudy_sync_status_item_batch_edit.xml`. (L)
- Granular reset scopes — `ICBResetCropAndGeometryToDefaultState`. (S)

## G. Compare / before-after / discovery
- **Before/after toggle & split** — `before_after_popup_view.xml`. Trivial, and we lack it. (S)
- Inline interactive tutorials ("Discover") — `discover_*_step_view_holder.xml`, `tutorials/content/tut_*.json`. (M)
- Per-feature onboarding gates — `fragment_masking_onboarding.xml` (extend our `CoachMarks`). (S)

## H. Render / preview pipeline & performance
- **Multi-level progressive render** — `ICBSetRenderLevel`, `ICBRenderAsync` (`level_t`/`status_t`). Coarse→fine for the spectral pipeline (we have a CPU two-pass; this is the deeper native model). (L)
- **Tiled GPU pyramid** — native `cr_image_tile`, `cr_gpu_pyramid`, `cr_gaussian_pyramid`. Large images / low memory. (L)
- **Layer-scoped re-render** — `ICBRenderLayerAsync`. Re-render only changed mask/layer. (M)
- **Pause/refresh render on gesture** — `ICBPauseRendering`/`RefreshRendering`. (S)
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
- Structured export bottom-sheet — `export_bottom_sheet_layout.xml`. (S)

## J. Misc engine surface
- AI denoise / texture / sharpen — `ICBCopyValidNoiseReductionParams`, `ICBCopyValidSharpeningParams` (pairs with our unsharp). (M)
- Lens-profile distortion/vignette UI — `ICBGetLensProfileDistortionScaleValue`, `ICBSetLensProfileLensVignettingValue` (we have LensProfiles data). (S)

---

## Top 10 for the next release (film-emulation focus)
1. **Preset/profile amount slider** — dial any film look 0–100%. Highest delight, low effort. (M)
2. **Parametric + point tone curve UI** — film contrast control users expect. (M)
3. **3-way color grading wheels** — defines color-film mood. (M)
4. **Local-adjustment container + Linear/Radial gradients** — unlocks all local editing. (L)
5. **AI Select Subject + Sky** — reuses shipped LiteRt; high "wow", little new infra. (L)
6. **Multi-level progressive + pause/refresh render** — makes the spectral pipeline feel instant. (L)
7. **Before/after toggle** — trivial, essential, missing. (S)
8. **HSL / targeted color mix** — emulate film dye responses per band. (M)
9. **AVIF + HEIC 10-bit export + C2PA option** — modern HDR formats + provenance (we're on TIFF/PNG). (M/L)
10. **Copy/paste settings + named versions** — turns `EditHistory` into a workflow. (M)

**Sequencing:** Item 4 (mask container) is the keystone — 5, 8 and most of §A depend on it; build the "a correction wraps a Spektra edit" abstraction first, then gradients → AI selections → range masks. Items 1, 2, 3, 7 are independent quick wins shippable in parallel.
