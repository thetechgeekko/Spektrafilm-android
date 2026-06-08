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

**Status (2026-06-08): API surface + architecture done; algorithm sections being filled by a 6-front
research swarm (⏳).**

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

### §A — Masking & local adjustments (+ AI subject/sky/people, PDR) ⏳
### §B — White balance & color (DCP profiles, Temp/Tint, HSL/color mixer, calibration) ⏳
### §C — Tone & color grading (PV2012 tone, ToneCurvePV2012, 3-way wheels, clarity/texture/dehaze, auto-tone) ⏳
### §D — Render/preview pipeline & performance (CR core, pyramid/render-levels, tiling, caches, GPU, fp16) ⏳
### §E — Heal/retouch, geometry/upright, lens corrections, noise/sharpen ⏳
### §F — Presets/profiles/amount, versions/copy-paste, export formats, C2PA, ML stack ⏳

---

## How this maps to our port

Cross-references `docs/USER_DRIVEN_SOLUTIONS.md` and the `spectrafilm-solutions` skill's tier model
(UI / pre-engine / post-engine / engine-gated / data). The RE confirms the *what* and *which APIs*;
the algorithm sections give the *how*; the solutions doc gives our *parity-safe implementation*.

## Changelog
- 2026-06-08 — Fresh RE of the current build (APKMirror 2026-05-14): native-stack architecture +
  1,038 ICB / 16,841 cr_ surface catalogued (`docs/lightroom-re/`); new features identified (AI Lens
  Blur, PDR/Generative Remove, adaptive/scene presets, HDR edit). Algorithm sections pending swarm.
