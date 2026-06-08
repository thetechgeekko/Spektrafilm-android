# Lightroom implementation reference (RE'd) — to inform the Spectrafilmandroid port

**What this is.** A learn-the-implementation reference for Adobe Lightroom mobile
(`com.adobe.lrmobile`), fusing a **fresh static RE of the current build** (APKMirror bundle dated
2026-05-14) with **authoritative algorithm knowledge** for each feature. Goal: understand how LR
actually works so we can port the *approaches* (not code) into our spektrafilm film-sim, parity-safe.

**The RE ceiling (important).** LR's image processing is native C++ (the Adobe Camera-Raw "CR" core).
Static APK RE yields the **API surface** (exported `ICB*` JNI bridge names, `cr_*` engine symbols,
strings) and architecture — **not** the algorithms (compiled machine code disassembles to assembly,
not source). So: the *surface* below is real, current RE; the *algorithms* are learned from
authoritative public sources (Adobe Help/Camera-Raw, DNG spec, Adobe patents, color science) and
cited. We do **not** copy proprietary code — we learn the model and re-implement our own.

**Status (2026-06-08): complete.** API surface + architecture from a fresh decompile of the current build,
and all six algorithm sections (§A–§F) synthesized from a 6-front deeply-cited research swarm. Each section
ends with a parity-safe port mapping cross-referenced to `docs/USER_DRIVEN_SOLUTIONS.md`.

---

## Architecture (from this build's native libs)

Confirms + extends `docs/RESEARCH_LIGHTROOM_STACK.md`. The arm64 split ships a modular Adobe native
stack:

| Lib | Size | Role |
|---|---|---|
| **`libLrAndroid.so`** | **65 MB** | the Camera-Raw "CR" render engine (`cr_*`, `Adobe::PM`) + the `ICB*` JNI bridge |
| `libimaging.so`, `libkernel.so`, `libcore.so`, `libcapture.so`, `libweb.so` | 0.7–4.4 MB | modular Adobe imaging/kernel/core/capture/web |
| `libml.so`, **`libLiteRt.so`** (5 MB), **`libLiteRtClGlAccelerator.so`** (6 MB), **`libLrmModels.so`** | — | on-device **ML** (LiteRT/TFLite) + GPU(CL/GL) ML accel + model bundle (subject/sky/people/denoise/PDR/adaptive) |
| `libjxl*.so` (JPEG-XL), `libadobe_c2pa.so` (12 MB, Content Credentials), `libopencv_java4.so` (24 MB), `libwebp*`, `libsharpyuv`, `libbrotli*` | — | codecs, provenance, CV |
| `libabsl_*` (~90 libs), `libc++_shared` | — | Abseil + libc++ |
| `libpairipcore.so` | — | **Google Play "pairip" anti-tamper** — likely wraps/encrypts `base.apk` dex (expect thin jadx output; native strings are the reliable surface) |

Same fundamental split as ours — **a C++ render engine under a Kotlin/Java UI** — confirming our
architecture matches; the gaps remain scale/perf/feature infra (TBB, GPU, fp16, pyramids/tiling, ML).

## API surface (fresh decompile of `libLrAndroid.so`)

- **1,038 `ICB*`** JNI bridge methods (the full current UI→engine API) — full list:
  `docs/lightroom-re/icb-methods-full.txt`; grouped by feature: `docs/lightroom-re/icb-by-feature.md`.
- **16,841 `cr_*`** engine symbols; feature-relevant subset: `docs/lightroom-re/cr-symbols-curated.txt`.
- 60,107 exported dynsyms total (not stripped).

**Current features beyond our prior (11.3.3) catalog — newly confirmed:**
- **AI Lens Blur / Bokeh** — `ICBApplyBokehPreset`, `ICBCreateBokehPresetThumb`,
  `ICBGetLensBlurRoutingSetting`, bokeh preset position list (depth-based lens blur).
- **PDR — People/Distractor Removal ("Generative Remove")** — `ICBHasDetectedPdrMasks`,
  `ICBGetEstimatedTimeRemainingForPdrRemoval`, `ICBGetPdrModelVersion`, per-distractor *variations*
  (`ICBGetVariationCountForDistractorIndex`) — generative inpainting.
- **Adaptive profiles + Scene presets** — `ICBClearAdaptivePreset`, `ICBComputeMLMasksForPresetItem`,
  `ICBCreateScenePresetMasksAndApplyEdits`, `ICBActivateSceneWorkspace`, `ICBSceneBalanceModel*`
  (presets that carry AI masks + a scene-balance model).
- **HDR editing** — `ICBGetHDREditDefaultSetting`, `ICBGetAiSettingsToUpdateWithHDRToggle`,
  `ICBVisualizeHDRRange` (gain-map/HDR output).
- **People part-masks + smart masks** — `ICBComputePartMaskForPerson`, `ICBGetPersonPartMask`,
  `ICBCreateSmartMaskBasedOnType`, `ICBComputePeopleMasks`.
- Plus the established set: linear/radial/brush masks, luminance/color/depth range masks,
  select-subject/sky, tone curve (master+RGB), color grading (shadow/mid/highlight + blending/balance),
  color mixer, clarity/texture/dehaze, auto-tone, upright/geometry, lens profile, NR/sharpen,
  preset/profile amount, copy/paste, AVIF/JXL/HEIC/DNG export, C2PA.

---

## Algorithm sections (⏳ filled by the research swarm)

Each: LR's RE'd API anchor → how the algorithm actually works (authoritative, cited) → parity-safe
port mapping. Cross-referenced with `docs/USER_DRIVEN_SOLUTIONS.md` (our solution designs).

### §A — Masking & local adjustments (+ AI subject/sky/people, PDR) ✅

**Hard ground truth (not inference):** the `ICB*…ToParams` calls serialize to the *public*
`crs:MaskGroupBasedCorrections` XMP schema (ExifTool/Exiv2), so the **data model is proven**. A "mask"
(UI) = a **`Correction`** = `{ CorrectionAmount (opacity), one Local* adjustment set, CorrectionMasks[] }`.
Each component = `{ MaskBlendMode (int: add/sub/intersect), MaskInverted, geometry, optional nested
RangeMask }`. **Range masks are nested inside a component** (an *intersect* refinement), not top-level.

- **Components & math** (curves = `smoothstep`, Adobe's exact falloff is unpublished → standard
  reconstructions, flagged): **Linear** = scalar projection `t=clamp(((P−Z)·(F−Z))/|F−Z|²,0,1)`, `α=smoothstep(t)`
  (feather = the Z→F distance). **Radial** = elliptical `r`, feather band over `[1−f,1]`. **Brush** =
  `Dabs`+`Radius`+`Flow`+`Feather`+`CenterWeight`+`Density`: stamp-accumulate-clamp (`α←min(Density, α+Flow·k·(1−α))`),
  Flow builds up, Density caps; Auto-Mask gates by color similarity.
- **Combine algebra:** Add `1−(1−A)(1−m)` (soft-OR), Subtract `A·(1−m)`, Intersect `A·m` (Adobe ships
  intersect as subtract+invert); per-component invert `1−m`. Folded in component order.
- **Range masks:** Luminance (`LumMin/Max/Feather` trapezoid on luma), Color (≤5 eyedropper samples,
  `ColorAmount` tolerance, `AreaModels` = per-sample Lab mean+spread, soft min-distance), Depth
  (`DepthMin/Max/Feather`; source = HEIC device depth **or** AI monocular depth via Sensei).
- **AI (Sensei, on-device):** TFLite/LiteRT segmenters → coarse logits → **guided-filter/matting edge
  refinement** to soft alpha (Adobe matting patents US10068361/US9786078). People part-masks = per-person
  instance + multi-class parts (facial/body skin, hair, eyes, lips, teeth, clothes). PDR = generative
  remove (Firefly diffusion, cloud).
- **Per-correction adjustment set:** `LocalExposure2012/Contrast/Highlights/Shadows/Whites/Blacks/Clarity/
  Dehaze/Texture/Temperature/Tint/Saturation/Hue/Toning*/Sharpness/LuminanceNoise/Moire/Defringe`.

**Port (Tier 2, parity-safe → our `USER_DRIVEN_SOLUTIONS.md §4`):** mirror the `crs` tree 1:1 (gives free
XMP interop with LR's own masks); composite on the engine **output** at the single `simResultToBitmap`
seam; local adjustments = pointwise ops within α; AI via on-device LiteRT off-thread + guided-filter refine
(output luma as guide). Engine untouched. *Refs:* Adobe ACR/LrC masking help, ExifTool XMP-crs, US8175409,
US10068361.
### §B — White balance & color (DCP profiles, Temp/Tint, HSL/color mixer, calibration) ✅

**Pipeline:** raw → linearize → **WB = diagonal multiply on camera-native RGB, *before* the colorimetric
matrix** → demosaic → camera profile (DCP) → working space → tone → HSL/grade → output. **WB lives before
color; the DCP profile defines color.**

- **DNG dual-illuminant color** (spec, normative): `ColorMatrix1/2` (XYZ→camera), `ForwardMatrix1/2`
  (WB'd camera→XYZ-D50), `CameraCalibration`, `AnalogBalance`, `CalibrationIlluminant1/2`. Interpolated by
  **inverse CCT (mired):** `g=(1/T−1/T₂)/(1/T₁−1/T₂)`, *same g* for CM/FM/CC + the look tables. FM path:
  `CameraToXYZ_D50 = FM·D·inv(AB·CC)`, `D`=diag normalize of `CameraNeutral`.
- **Temp/Tint:** Temp = Kelvin (raw 2000–50000), Tint = ±150 = **Δuv in CIE-1960** ("Adobe Tint = Duv×3000").
  `(Temp,Tint)→white xy→CameraNeutral=XYZtoCamera·XYZ`; channel mults = reciprocal/green. Eyedropper inverts
  iteratively. **Non-raw = relative ±100 sliders** (empirical warp, no DCP).
- **DCP look tables** = HSV deformation lattices over ProPhoto-linear→HSV; node = `(hueShift°, satScale,
  valScale)`, trilinear, wrap-around hue. **HueSatMap** = colorimetric base; **LookTable** = creative look
  (after exposure, before tone curve). Newer profiles: RGBTables/PGTM (Adobe Adaptive).
- **HSL / Color Mixer:** 8 bands (R/O/Y/G/Aqua/B/Purple/Magenta) × hue/sat/lum, **smooth overlapping bands
  (partition-of-unity)**; `ICBFillColorMixValues`; TAT picks hue (`ICBSampleHueColorForAdjustment`).

**★ Alignment win:** LR's working space = **linear ProPhoto, D50 ("Lightroom RGB")** — *exactly our engine's
fixed input space* (display = "Melissa RGB" = ProPhoto + sRGB TRC). So WB/HSL/look ops transfer directly.
**Port (→ `USER_DRIVEN_SOLUTIONS.md §1/§2`):** creative WB = **pre-engine Bradford CAT D50→target(Temp/Tint)**
on the linear input (Tier 1, parity-free); HSL = post-engine 8-band op (Tier 2); looks = HueSatMap HSV
lattice. **Gap:** no per-camera DCP → can't match Adobe's exact/camera-specific numbers. *Refs:* DNG Spec
1.6 ch.6, Colour-HDRI dng.py, strollswithmydog (Tint×3000), Martin Evening "Lightroom RGB".
### §C — Tone & color grading (PV2012 tone, ToneCurvePV2012, 3-way wheels, clarity/texture/dehaze) ✅

PV2012 is **scene-referred** (linear, pre-output-transform). Order: WB → Exposure → Contrast →
Highlights/Shadows → Whites/Blacks → tone curve → color grading. Defaults are 0 but the baseline render
already applies an **auto highlight rolloff** (top ~3 stops compressed).

- **Exposure:** `2^EV` midtone gain + a **highlight shoulder** `g(x)=a·x/(x+b)+c` (asymptotes to white);
  per-channel with cross-channel highlight reconstruction.
- **★ Highlights/Shadows are LOCAL, edge-aware operators — NOT a curve.** Adobe used "edge-detecting
  algorithms" (~1 min/MP originally) = local-Laplacian/guided-filter **regional gain via a luminance mask**.
  This is the key port consequence: needs an edge-aware low-pass, not a 1-D LUT.
- **Whites/Blacks** = global endpoint/clip points (Whites does **not** engage the shoulder, unlike Exposure).
  **Contrast** = midtone-pivot S about mid-gray.
- **ToneCurvePV2012** = point curve (≤16 pts, 0–255, monotone cubic) + per-RGB + **parametric** (Shadows/
  Darks/Lights/Highlights region bumps, split points 25/50/75). Named curves Linear/Medium/Strong = presets.
- **Color Grading is NOT lift/gamma/gain** — it's **region-weighted HSL tint + per-region luminance**: 3 zones
  (S/M/H) + Global, each hue/sat/lum; `Blending` = region-overlap width, `Balance` = split shift. Legacy
  Split Toning = the S+H wheels at Blending=100.
- **Clarity** = edge-aware **midtone local contrast** (large-σ USM + midtone mask). **Texture** = mid-**frequency**
  band (DoG/bilateral, noise-sparing). **Dehaze** = **Dark-Channel-Prior atmospheric model** (patent
  US20160196637A1, full eqns: `y=t·x+(1−t)·a` → recover `x=(y−(1−t)a)/t`) — the *one* tool with a published
  algorithm. **Auto-tone:** legacy = histogram heuristic (`ICBCalculateAutoToneParams`); current = Sensei CNN.

**Port (→ `USER_DRIVEN_SOLUTIONS.md §3`):** Whites/Blacks/Contrast/curves/parametric → the **already-wired
PCHIP tone-curve** (Tier 0, trivial). Exposure/Clarity/Texture/Dehaze/Highlights-Shadows → a **new gated
linear `displayGrade` stage** on `lin_rgb` before CCTF (`scanning.cpp` ~347; default-no-op = parity-safe).
Color Grading = post-engine op (no physical analog → outside the oracle path). Highlights/Shadows is the
hardest (local); Dehaze the most faithfully portable. *Refs:* Adobe Color-Grading blog (Chan/Wendt),
Luminous-Landscape PV2012, Dehaze patent US20160196637A1, ACR Texture blog.
### §D — Render/preview pipeline & performance (CR core, pyramid, tiling, caches, GPU, fp16) ✅

**LR's architecture literally *is* our parity policy:** a non-destructive edit-list replayed through a
pyramid — deterministic prefix cached, param-dependent tail recomputed; **preview at a viewport-chosen
pyramid level (GPU, reduced precision); the only full-res *exact* pass is export, done tiled.** Approximate
preview / bit-exact CPU export is Adobe's own design, not a compromise.

- **Foundations (patents):** digital negative + order-independent edit-list (US7012621); **Gaussian pyramid**
  (US5790708: octave, ¼-area, 256² tiles; level chosen by viewport pixel demand); **progressive GPU-proxy →
  CPU-exact refine** (US8885208: each edit a state object carrying params to reconstruct at a finer level;
  tiles may overlap). Pipeline order: demosaic(+sharpen/NR)→profile→WB→exposure/contrast→HL/shadow→curve→
  effects→output, **disabled stages skipped**. ProcessVersion = versioned tone; PV5 gates "Full" GPU.
- **The ACR cache seam (most portable finding):** cache the **deterministic prefix** (decode/linearize/
  demosaic, file-keyed); recompute develop settings fresh. `cr_*_cache` family = per-stage memos keyed on
  *only the params each stage consumes* (`cr_tonemap/mask/stats/grain_mask_cache`).
- **Tiling:** const/dirty tile buffers; **halo = op radius**; raised-cosine overlap blend; global scalars
  (autoexposure) computed in a whole-image pre-pass.
- **GPU/precision:** Off/Limited/Full auto (Full needs PV5 + VRAM); **fp16 = approximation, not bit-exact**;
  ML via `libLiteRtClGlAccelerator`. **Embedded-JPEG instant preview** (`ICBGetAndReleasePreviewJpegBytes`).
  **Smart Preview** = 2560px lossy-DNG raw-like proxy; auto-switch to the original on 1:1/export.

**Port (→ `USER_DRIVEN_SOLUTIONS.md §5`) — top 2 by ROI:** (1) **input Gaussian pyramid + viewport
level-select** for preview (reuse `kernels/gaussian`+`downscale`; scale spatial-kernel radii with the level);
(2) **cache the filming spectral-upsampling prefix** (the 81-band hotspot) keyed on input+camera/Hanatos
params → tuning the print/scan tail never re-pays the spectral cost (= a `cr_*_cache` per stage). Then: tiled
export with halo + a **tile-invariance parity test** (extend `test_parallel` to tile size, not just thread
count); GPU spectral integral **preview-only**; embedded-JPEG / coarse-128px first paint; debounce drags.
*Refs:* US7012621/US5790708/US8885208, lightroom-blog ACR-cache (S. McCormack), Adobe GPU FAQ.
### §E — Heal/retouch, geometry/upright, lens corrections, noise/sharpen ✅

- **Heal/Clone:** Clone = copy + feather; **Heal = multi-resolution PatchMatch NNF** source-finding
  (US20160027159, the Barnes-et-al. nearest-neighbor field) + color-match blend; **Content-Aware Remove** =
  PatchMatch + a learned sampling-region selector; AI blemish = Sensei; **Generative Remove (PDR)** = Firefly
  diffusion (cloud).
- **Geometry/Upright:** Canny → **Hough lines (decremental)** → **vanishing-point clustering** (2D angular/
  radial bins, external VPs) → **homography** (US10354364); Auto = energy-min MAP under the Manhattan-world
  assumption (US9519954); Guided = user lines as constraints. Modes Level/Vertical/Auto/Full = which VPs +
  damping.
- **Lens (LCP profiles):** distortion = Brown-Conrady radial `1+k₁r²+k₂r⁴+k₃r⁶` (+tangential `p₁,p₂`);
  vignette = gain `1+α₁r²+α₂r⁴+α₃r⁶`; TCA = separate R-G & B-G radial polys; all interpolated per EXIF
  focal/aperture. Adobe deliberately **under-corrects vignette (~50–70%)**.
- **Sharpen (Detail panel, luminance-channel):** Amount/Radius/Detail/Masking; Detail=0 → halo-suppressed
  USM, Detail=100 → **deconvolution-like** (Schewe-confirmed; RL specifically unconfirmed); Masking =
  edge-gradient gate. **NR:** Luminance = multi-scale/wavelet band attenuation; Color = chroma bilateral/
  wavelet. **AI Denoise** = CNN on the raw mosaic (simultaneous demosaic+denoise → linear DNG; desktop NPU/
  TensorCore, **not on-device mobile**). **Enhance Details** = CNN demosaic.

**Port (→ `USER_DRIVEN_SOLUTIONS.md §6`):** geometry/Upright/LCP = **pre-engine** on the linear input
(homography `warpPerspective`/`undistort`; OpenCV); crop already in the engine. Heal/Clone/Sharpen =
**post-engine** on output. NR: input NR (pre-engine, clean sensor noise) is fine; **output NR is wrong for a
film-sim** (it removes the grain we just modeled). AI denoise/blemish/generative-remove = out of scope
(proprietary models). *Refs:* Upright patents US10354364/US9519954, PatchMatch (Barnes 2009), LCP/lensfun
ACM, Adobe "Denoise Demystified".
### §F — Presets/profiles/amount, versions/copy-paste, export formats, C2PA, ML stack ✅

- **Profiles vs presets:** **profiles** (DCP binary or XMP-Look) act at the raw-render layer *before* sliders;
  **presets** (`crs:` slider sets) layer on top. **Profile Amount** (Creative profiles only, 0–200%) blends
  toward the profile's embedded **base profile** (Adobe Standard) — *not* toward "no profile" (`crs:LookAmount`).
  **Preset Amount** (0–200, default 100, `crs:SupportsAmount`) = **linear lerp of every param** toward the
  pre-apply state (tone-curve point lerp can misbehave → param-space lerp).
- **Adaptive presets** encode *which* AI mask to run (Subject/Sky/People-parts/Landscape/Background) and
  **recompute the mask on apply** (geometry not baked); heavy AI masks now live in a binary **`.acr` sidecar**
  (LR Classic 15), lightweight edits in the `.xmp`.
- **Versions/copy-paste:** snapshots *can* write to XMP; virtual copies/history = catalog-only. Copy-paste is
  **section-based** (subsets of `crs:`); masks re-derived on paste. The `crs:` namespace (PV2012 tone,
  presence, 8-band HSL, Color Grading, detail, grain, post-crop vignette, perspective/upright, lens,
  local-adjust arrays) is the **canonical edit schema** — our recipe JSON should mirror it for interop.
- **Export formats:** **AVIF** (AV1-in-HEIF; 8/10/12-bit; CICP color; `tmap` gain map; LR: HDR sRGB/P3/Rec2020,
  10-bit HDR), **JPEG-XL** (ISO 18181; up to 32-bit float; `jhgm` gain map; LR exposes 8/16-bit; also wraps
  lossy DNG), **HEIC** (not a native LR-Android export), **DNG** (linear/raw; lossy=JXL). We ship TIFF/PNG/
  Ultra-HDR → Ultra-HDR is the nearest; **AVIF is the biggest gap** (needs an AV1 encoder, e.g. `avif-coder`).
- **C2PA (Content Credentials):** manifest = assertions (`c2pa.actions`/`ingredient`/`hash.data`/CreativeWork)
  + CBOR claim + **COSE_Sign1** signature; embedded as **JUMBF in JPEG APP11**; ingredient chain camera→LR→
  export. LR = **JPEG-only, opt-in, CC sign-in required**. We ship `libadobe_c2pa.so`; the open `rust-c2pa`
  SDK is the reference. Export-only.
- **ML stack:** **LiteRT** (ex-TFLite; `.tflite` FlatBuffers assets) via `libLiteRt.so` + `libLrmModels.so`
  (weights); GPU(OpenCL "ML Drift")/NPU(QNN/NeuroPilot)/NNAPI/XNNPACK delegates, wrapped by Adobe's "Sensei
  On-Device Framework". Segmentation (subject/sky/people-parts) = encoder-decoder CNN (MobileNet-class),
  **on-device** (64-bit, ≤6 GB RAM tiers). **AI Denoise = desktop-only** (NPU/TensorCore), not on-device mobile.

**Port (→ `USER_DRIVEN_SOLUTIONS.md §6`):** we already have preset amount + copy/paste + recipe JSON + `.cube`
LUT; mirror more of `crs:` for interop; add Profile-Amount's base-blend concept; **AVIF/JXL/HEIC/C2PA are
gaps** (native encoders); ML = an **additive on-device LiteRT UI layer**, never inside the parity engine.
*Refs:* Adobe XMP-crs, jkost profile-amount, AVIF v1.2 spec, ISO 18181 (JXL), C2PA 2.2 spec, LiteRT docs,
Adobe "Denoise Demystified" / Sensei-On-Device blog.

---

## How this maps to our port

Cross-references `docs/USER_DRIVEN_SOLUTIONS.md` and the `spectrafilm-solutions` skill's tier model
(UI / pre-engine / post-engine / engine-gated / data). The RE confirms the *what* and *which APIs*;
the algorithm sections give the *how*; the solutions doc gives our *parity-safe implementation*.

## Changelog
- 2026-06-08 — Fresh RE of the current build (APKMirror 2026-05-14): native-stack architecture +
  1,038 ICB / 16,841 cr_ surface catalogued (`docs/lightroom-re/`); new features identified (AI Lens
  Blur, PDR/Generative Remove, adaptive/scene presets, HDR edit).
- 2026-06-08 — All six algorithm sections (§A masking, §B WB/color, §C tone/grading, §D render/perf,
  §E heal/geometry/lens/NR, §F presets/export/ML) synthesized from authoritative sources + cross-mapped
  to parity-safe ports. Reference complete.
