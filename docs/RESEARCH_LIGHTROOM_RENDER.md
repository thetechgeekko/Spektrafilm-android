# Research — Lightroom default render vs ours (on the test DNG)

Goal: run the test DNG through Lightroom's processing, RE how Lightroom renders by default, and
align ours to that result. Lightroom can't be run headless here (Adobe account + cloud), so this
RE's its **default render pipeline** from the decompiled engine and reproduces it with an
Adobe-default approximation, then compares against our engine on the same DNG.

## Lightroom's default render pipeline (RE'd from `libLrAndroid.so`)
Confirmed symbols (android-reverse-engineering skill, `strings`/`nm`):
- **Camera profile:** `Adobe Color` / `Adobe Standard`, `CameraProfile`, `BaselineExposure` — the
  default is the **Adobe Color** DCP profile, which bakes a medium-contrast tone + color rendering
  into the camera→working-space transform.
- **Process version:** `ProcessVersion` (current PV) — fixes the demosaic/HL/noise model.
- **Tone:** `ToneCurvePV2012` + parametric regions `ParametricDarks/Lights/Shadows/Highlights`,
  named curve `Medium Contrast` (also `Strong Contrast`, Linear). Default = the profile's baked
  tone + zeroed parametric/point curve.

So Lightroom's *default* = camera-matrix color → **Adobe Color** profile (medium-contrast S baked
in) → PV defaults (parametric all 0) → output space. No user edits.

## Method
- Input: the test DNG (`raw_test.bin`, 4080×3060 Bayer, decoded via LibRaw).
- **LR-default approximation:** LibRaw demosaic + camera WB (as-shot) + sRGB primaries + an
  Adobe-Color-like medium-contrast tone curve, EXIF-oriented. (`lr_default.png`)
- **Ours:** the spektra engine, Portra-400 → Endura print, auto-exposure + grain + halation + glare.
- Both at matched size; per-channel + luminance stats.

## Result (test DNG)
| | meanL | medL | R / G / B | shadow p10 | hi p90 |
|---|---|---|---|---|---|
| **Ours** (film) | 0.269 | 0.223 | 0.307 / 0.263 / 0.218 | 0.066 | 0.544 |
| **LR** (neutral) | 0.229 | 0.183 | 0.257 / 0.224 / 0.193 | 0.048 | 0.465 |

Same scene, same white-balance ballpark, exposure within ~0.04 L. The differences are the
**intended film aesthetic**: ours is **brighter, warmer (larger R−B), and glowier** (Portra +
halation + glare); LR is **neutral and slightly darker** with deeper shadows. Ours is *not*
mis-processing the DNG — it's a faithful film rendering of the same scene.

## Alignment — how to make ours track Lightroom's neutral baseline
Our engine already exposes every lever needed to neutralise toward the LR default; no engine
change is required to "match the result", only parameters:
1. **Warmth / cast** — the R−B excess (0.089 vs LR 0.064) is the film/coupler warm bias. Reduce
   `dirCouplers.amount` and/or use a neutral print balance to pull toward LR's R−B.
2. **Midtone exposure** — drop the auto-exposure target ~0.04 L (or `exposureCompensationEv ≈ −0.2`)
   to match LR's `medL 0.18`.
3. **Glow** — `glare.active = false` + lower `halation.*Amount` removes the bloom LR doesn't have.
4. **Contrast** — LR's "Medium Contrast" ≈ our default print contrast; a mild tone curve
   (now available, docs/RESEARCH_BIG_FILES sibling) can fine-tune.

Recommended product move (not forced): ship a built-in **"Neutral (Adobe-like)"** preset wiring
the above, so users get a Lightroom-style neutral starting point alongside the film looks — the
film default stays the app's identity.

*RE'd from Adobe Lightroom (com.adobe.lrmobile). Film modeling powered by spektrafilm (GPLv3).*
