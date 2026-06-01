# Research — Lightroom Lens Blur / bokeh, and a film-characteristic plan for Spektrafilm

Reverse-engineered from Adobe Lightroom mobile **11.3.3** (`com.adobe.lrmobile`, APK
static analysis only — no execution). Purpose: inform a *future* Spektrafilm feature for
depth-aware lens blur + bokeh + light-scattering, tied to **film characteristics** (not a
generic portrait-blur clone). Nothing here is committed work; it is a design study.

## 1. How Lightroom's Lens Blur works (RE findings)

Evidence: dex symbols under `com/adobe/lrmobile/loupe/asset/develop/lensblur/LensBlurHandler`,
native `ICB*` bridge calls into `libLrAndroid.so`, ML libs `libLiteRt.so` +
`libLiteRtClGlAccelerator.so` + `libml.so` + `libkernel.so`, GPU shaders in
`assets/shaders/`, and UI in `res/layout/{lens_blur_focus_mode_flyout,bokeh_preset_item,
lens_blur_onboarding_*}.xml`.

**Pipeline (inferred):**
1. **Monocular depth estimation.** A LiteRt (TFLite-lineage) ML model produces a
   depth/disparity map from the single image, GPU-accelerated (`libLiteRtClGlAccelerator`).
   Symbols: `DEPTH_MAP_RECOMPUTE`, `ICBGetDepthRange`, `ICBSetDepthRange`, `depthMap=`.
2. **Focus selection.** User taps a point or subject → `ICBSetFocalRangeFromPoint`,
   `ICBUpdateFocalRangeHelperData`; a **focal range** (depth band kept sharp) is derived.
   Focus modes via `lens_blur_focus_mode_flyout` (point / subject / range).
3. **Blur synthesis (native, `libLrAndroid`).** Spatially-variant blur where the
   circle-of-confusion radius ≈ `f(|depth − focusDepth|) · BlurAmount`, scaled by an
   aperture model:
   - `BlurAmount` / `BLUR_AMOUNT_CHANGE` — master strength.
   - `ApertureValue` / `FStop` / `maxApertureValue` — physical aperture → CoC scale.
   - **`5-blade`** — aperture blade count → bokeh **polygon shape** (pentagon vs disc).
   - **Bokeh presets** — `ICBApplyBokehPreset`, `ICBGetBokehPresetPositionIndexList`,
     `bokehDataList` (named bokeh looks; thumbnails via `ICBCreateBokehPresetThumb`).
   - **Highlights / `enableHighlights` / `highlightsValue`** — specular OOF **highlight
     boost**: recover clipped highlights before blur so bright points bloom into bright,
     shaped discs (the visually defining bokeh trait).
4. **GPU render.** Gather/scatter blur + the building blocks present as shaders
   (`gaussian`, `gaussianbigsigma`, `locallaplacian`, `linearcombine`, `tonemap`) in a
   linear working space (`srgb2lin`/`lin2srgb`, `log2lin`).

**Separate but related:** `assets/stagingcontent/LensProfiles/Index.dat` = Adobe's optical
**lens-correction** database (geometric distortion, vignette, chromatic aberration per
lens/body) — used by the Optics panel, independent of Lens Blur.

## 2. What Spektrafilm already has (reuse points)

- **Spatial light scatter:** halation/scatter (`halScatter*`, `halHalation*`, per-channel
  core/tail µm), in-emulsion diffusion (`cameraDiffusionState`/`printDiffusionState`),
  glare (`glarePercent/Roughness/Blur`). Engine stages: `model/diffusion.cpp`,
  `model/glare.cpp`, `runtime/stages/*` with the `kernels/gaussian` + `exponential_filter`.
- **Optical blur:** camera + scanner Gaussian lens blur (`cameraLensBlurUm`, `scanLensBlur`).
- **Working space:** scene-linear float pipeline + spectral model (per-stock dye/grain).
- **No depth map, no aperture/blade bokeh, no spatially-variant blur** today — all blur is
  global/uniform.

## 3. Proposed Spektrafilm feature — "Optical Bokeh" (film-characteristic)

Goal: not a generic LR clone — a **film-lens** simulation where bokeh + scatter inherit the
selected stock's character.

### 3a. Depth (the enabling piece)
- On-device monocular depth via a small TFLite model (LiteRT / NNAPI / GPU delegate).
  Keep it **optional + default-OFF** (model is a few MB; download-on-demand).
- App-side (Kotlin) produces a normalized depth map; passed to the engine as an extra
  single-channel buffer alongside the linear image.

### 3b. Aperture-shaped, depth-weighted bokeh (new engine stage)
- New native stage `runtime/stages/optical_bokeh.cpp`: per-pixel CoC radius
  `r(x) = k · |depth(x) − focus| · (1/Nf)` (Nf = f-number), clamped; gather blur with a
  **kernel shaped by blade count** (regular polygon / disc / cat-eye toward frame edges).
- Inputs: `focusDepth`, `focusRange`, `fNumber`, `bladeCount`, `blurAmount`, `bokehRotation`.
- **Highlight bloom:** detect near-clip luminance pre-blur, boost into the kernel so
  speculars render as bright shaped discs (LR's `highlightsValue`).

### 3c. Film-characteristic coupling (the differentiator)
- Bokeh discs pick up **halation tint** at their edges from the stock's antihalation/scatter
  tail (reuse `halScatterTailUm`/`...WeightPct`) → warm-fringed OOF highlights, per stock.
- OOF regions get slightly more **diffusion/glare** (reuse existing kernels) — emulating how
  film + vintage glass bloom away from focus.
- Optional **lens optical profile** (à la LR `LensProfiles`): per-preset vignette + geometric
  distortion + CA applied in-pipeline, authored per "lens" the way film stocks are authored.
- Grain stays applied **after** blur on the sharp plane only (OOF grain is suppressed
  physically), giving a believable focus falloff.

### 3d. Parity / fidelity note
Bokeh + depth are **synthesis**, not a spektrafilm-oracle stage, so they are **not
parity-gated** (like grain/glare). Keep them default-OFF and behind the same "stochastic/
synthetic, not bit-exact" boundary documented in `AUDIT.md`. The bit-exact scan/print core
is untouched.

## 4. Rough sequencing (future)
1. Depth-model spike (TFLite + GPU delegate; benchmark on-device).
2. `optical_bokeh` engine stage with uniform-disc bokeh + depth weighting (no film coupling).
3. Blade-shape kernels + highlight bloom + focus-from-tap UI (reuse magnifier point-pick).
4. Film coupling (halation-tinted edges, OOF diffusion, per-lens optical profiles).
5. Tie into the GPU preview path (`LutGpuPreview` groundwork) for interactive focus drag.

## 5. Risks / open questions
- Depth quality on single images is the make-or-break; LR leans on a strong proprietary model.
- Spatially-variant gather blur is expensive on CPU → likely needs the GPU path (§5 above)
  before it's interactive.
- Licensing: do not lift Adobe assets/models; use an openly-licensed depth model.
- Keep all of this opt-in so the parity-bearing film engine and its goldens stay pristine.
