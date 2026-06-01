# Research — Film-Characteristic Customization (Lenses, Bokeh, Scatter, Emulation Depth)

Deep technical research note to guide a future Spektrafilm feature wave on **film-characteristic
customization**. This is a *design study*, not committed work. It builds on the existing
RE study in [`RESEARCH_LENS_BOKEH.md`](RESEARCH_LENS_BOKEH.md) and is sized to fit the current
spectral engine surface in [`ARCHITECTURE.md`](ARCHITECTURE.md) and the stock catalog in
[`FILM_STOCKS.md`](FILM_STOCKS.md).

## 0. Engine surface this proposal must fit

Confirmed from the C++ source (`engine/spektra-core/src/main/cpp`):

- **Pipeline stages** (`runtime/stages/`): `filming` → `printing` → `scanning`, plus
  `autoexposure`, `crop_resize`. The hot spatial passes live in `model/`.
- **Filming spatial chain** (`runtime/params.h::FilmingParams`, applied in `expose()`):
  highlight-boost → `apply_diffusion_filter_um` (Pro-Mist) → `apply_gaussian_blur_um`
  (`lens_blur_um`) → `apply_halation_um`, then DIR couplers + grain. Gated on
  `spatial_effects`; **default params are strict no-ops → bit-exact**.
- **Halation** (`model/diffusion.h::HalationParams`): per-RGB-channel `scatter_core_um[3]`,
  `scatter_tail_um[3]`, `scatter_tail_weight[3]` (in-emulsion irradiation: core Gaussian +
  exponential tail) **and** back-reflection `halation_strength[3]`,
  `halation_first_sigma_um[3]`, `halation_n_bounces`, `halation_bounce_decay`. Driven by the
  profile's `info.use` / `info.antihalation` preset tag (e.g. Portra 400 = `"strong"`).
- **Diffusion filter** (`DiffusionFilterParams`): `family` (BlackProMist), `strength`,
  `spatial_scale`, `halo_warmth`, and per-group `core/halo/bloom × intensity/size`
  multipliers. Energy-conserving convex blend `E_out = (1-p)·E_in + p·(K·E_in)`.
- **Glare** (`model/glare.h`): lognormal-per-pixel veiling glare in **XYZ**, added in the
  scanning stage as `percent · glare_amount · illuminant_xyz`, params `percent/roughness/blur`,
  deterministic `seed`.
- **Grain** (`model/grain.h::GrainParams`): per-layer AgX Poisson-binomial particle model,
  `agx_particle_area_um2`, `agx_particle_scale[3]`, `agx_particle_scale_layers[3]`,
  `density_min[3]`, `uniformity[3]`, `blur`, `blur_dye_clouds_um`, `micro_structure[2]`
  (clump blur µm, σ nm), `n_sub_layers`, deterministic seeds.
- **Optical blur**: single scalar Gaussian `lens_blur_um` (camera) + `scanLensBlur`, broadcast
  across channels. **No depth map, no aperture/blade bokeh, no spatially-variant blur today.**
- **Profiles**: JSON `{metadata, info, data}`. `info` carries `antihalation`, `use`,
  `reference_illuminant`, `viewing_illuminant`, `target_print`, `densitometer`, etc.

**Two parity classes** carry through every proposal below:

| Class | Meaning | Examples |
|-------|---------|----------|
| **Parity-gated** | Deterministic, can match a spektrafilm oracle / `.npz` golden bit-exact. Default no-op preserves goldens. | density curves, halation, diffusion filter, deterministic grain seeds, scanner XYZ→RGB |
| **Opt-in synthesis** | Stochastic or out-of-oracle. Default-OFF, behind the "synthetic, not bit-exact" boundary in `AUDIT.md`. | depth-aware bokeh, ML depth, geometric distortion, lens-flare sprites, gate weave |

---

## 1. Lens optical character

Physically a lens imposes a **per-field-position, per-wavelength PSF + a geometric coordinate
warp + a transmission falloff**. We can decompose into an authorable **lens profile** (à la
Adobe LCP) plus a small set of *character* effects.

### 1.1 Geometric distortion

**Model (Adobe LCP / Brown-Conrady radial).** Adobe LCP stores `RadialDistortParam1/2/3`,
which map to the radial polynomial coefficients `k1,k2,k3` over normalized radius
`r = √(x²+y²)/r_max` ([lensfun LCP conversion](https://lensfun.github.io/manual/v0.3.95/lensfun-convert-lcp.html),
[RawPedia Lens/Geometry](https://rawpedia.rawtherapee.com/Lens/Geometry)):

```
r_d = r · (1 + k1·r² + k2·r⁴ + k3·r⁶)
(x,y)_distorted = center + (r_d/r)·(x,y)
```

Barrel = k1<0, pincushion = k1>0, moustache = sign change between k1 and k2. Adobe's LCP DB
is a **per-(body,lens,focal,focus,aperture) profile table** the Optics panel interpolates
([Adobe Lens Profile Creator UG](https://www.adobe.com/special/photoshop/camera_raw/lensprofile_creator/lensprofile_creator_userguide.pdf),
[Computer Darkroom LCP overview](https://www.computer-darkroom.com/blog/lens-correction-profiles/)).

**Engine mapping.** New pre-stage `runtime/stages/lens_geometry.cpp` (a resampling warp on the
linear input, before `filming`), or fold into `crop_resize`. Cheap single bilinear/bicubic
gather. **Synthesis** (resampling is not in the oracle) → default-OFF, identity at k=0.

### 1.2 Vignetting (natural cos⁴ + optical)

Two superimposed terms:
- **Natural illumination falloff** `I(θ) = I0·cos⁴θ` where `tanθ = r·sensor_half/f` — pure
  geometry, always present, stronger on wide/fast lenses.
- **Optical (mechanical) vignetting**: off-axis rays clipped by the barrel; **aperture-dependent**
  (clears on stop-down) and is also what shapes **cat-eye bokeh** (§2.3). Adobe models the
  combined falloff with `VignetteModelParam1/2/3` — a radial polynomial gain in `r²,r⁴,r⁶`.

**Engine mapping.** Per-channel radial gain `G(r) = 1 + v1·r² + v2·r⁴ + v3·r⁶` applied as a
multiply. Natural term is computable from `focal_mm`, `sensor_format_mm` (we already carry
`film_format_mm` for `pixel_size_um`). Apply on **linear raw irradiance in `filming::expose`,
before diffusion** so the stock's tone curve sees the darkened corners (physically correct:
vignette is a pre-emulsion exposure loss). Parity-gateable (deterministic multiply; identity
at v=0). Cheap.

### 1.3 Chromatic aberration

- **Lateral (transverse) CA**: per-wavelength magnification difference → color fringing that
  grows with `r`, zero at center. Model as a **per-channel radial scale**
  `s_c = 1 + a_c·r²` (R/B scaled opposite the G reference). This is exactly LCP's
  `ChromaticGreenRedScale` / `ChromaticGreenBlueScale` polynomial.
- **Longitudinal (axial) CA / "bokeh fringing"**: focus shift by wavelength → magenta/green
  fringes on OOF edges, *uniform across the frame*, strongest wide-open. Best expressed as a
  **per-channel CoC offset** in the bokeh stage (§2), not a warp.

**Engine mapping.** Lateral CA = three per-channel resamples in `lens_geometry` (reuse the warp;
near-free incremental). Because our engine is **spectral** (81 bands, 380–780 nm), the *honest*
implementation is a small **per-band radial magnification** before the dye-sensitivity
contraction in `filming` — a uniquely spectral feature competitors can't do in RGB. Longitudinal
CA lives in the bokeh stage. Synthesis; default-OFF.

### 1.4 Field curvature, coma, spherical aberration, focus breathing

These are **field-dependent PSF** effects — the hard part. Pragmatic parameterization:

- **Field curvature**: focus plane is a paraboloid → the in-focus depth band varies with `r`.
  Implement as a radial bias on the bokeh focus term: `focus_eff(r) = focus + fc·r²`. Gives the
  classic "sharp center, soft corners" without a full PSF model.
- **Coma**: off-axis points smear into comet/teardrop PSFs pointing radially. Parameterize as a
  **radially-oriented asymmetric stretch of the bokeh kernel** with magnitude `coma·r`.
- **Spherical aberration (SA)**: governs **bokeh smoothness** (§2.4) and on-axis softness/veiling
  wide-open. A signed scalar `sa` reused by the bokeh kernel weighting.
- **Focus breathing**: focal length shifts with focus distance → a global magnification per
  focus. A scalar `breathing` driving a uniform scale; only matters for cine/animation, cosmetic
  for stills.

**Engine mapping.** All ride on the bokeh stage (§2) as field-varying modifiers; no new heavy
pass. All synthesis, default-OFF.

### 1.5 Named character lenses (presets, not physics-from-scratch)

Author as **"lens profiles"** the same way stocks are authored — a JSON sibling to film profiles
(`assets/spektra/lenses/*.json`), pickable independently of the stock:

| Lens character | Physical signature | Param recipe (from §1–§2) |
|---|---|---|
| **Petzval / swirly** | Strong field curvature + astigmatism → swirling OOF | high `fc`, tangentially-biased bokeh stretch rising with `r`, busy SA |
| **Anamorphic** | Cylindrical optics → 2× horizontal squeeze → **oval bokeh** + horizontal blue flares | bokeh kernel x/y aspect ≈ 1.33–2.0, horizontal streak flare sprite (§3.4), oval cat-eye |
| **Soft-focus (Imagon)** | Deliberate undercorrected SA → sharp core + glowing veil | large negative `sa`, strong veiling-glare lift, raised black floor |
| **Uncoated vintage** | Low coating → high **veiling glare**, low micro-contrast, warm cast | high `glare.percent`, lifted shadows, mild lateral CA, warm tint |

**Parity.** Lens presets are synthesis; default = "no lens" (identity). They never touch the
parity core, so the bit-exact scan/print goldens stay pristine.

---

## 2. Bokeh / defocus

### 2.1 Circle of confusion (CoC)

Thin-lens result, magnification-aware ([CoC — Wikipedia](https://en.wikipedia.org/wiki/Circle_of_confusion),
[Stanford ISETCam CoC](https://stanford.edu/~wandell/data/isetcam/optics/s_opticsCoC.html)):

```
c = A · |S2 − S1| / S2 · (f / (S1 − f))      [object-side form]
A = f / N   (aperture diameter, N = f-number)
```

In our single-image world we don't have metric depth, only a normalized disparity map `d∈[0,1]`.
Practical engine form:

```
CoC_px(x) = k · |d(x) − d_focus| / N · blurAmount      (clamped to maxCoC)
```

`k` folds focal length, sensor size and the depth-normalization into one calibration scalar.
This matches the LR RE: `r ≈ f(|depth−focus|)·BlurAmount` scaled by `ApertureValue`
(see `RESEARCH_LENS_BOKEH.md §1`). Add **near/far asymmetry** (foreground blur can exceed
background) and a `focusRange` plateau (depth band kept sharp).

### 2.2 Aperture blade count → kernel shape

`bladeCount` N_b: bright OOF discs are **regular N_b-gons** (rotated by `bladeRotation`), going
circular as N_b→∞ or wide-open (blades retract). Curved blades → rounded polygon (`bladeCurvature`
∈[0,1] interpolating polygon↔disc). Build the gather kernel as a rasterized polygon mask; precompute
once per CoC bucket.

### 2.3 Cat-eye / optical vignetting toward edges

Off-axis, the entrance pupil is **clipped to a lens (vesica) shape** → discs become cat-eyes,
the long axis oriented **tangentially** (perpendicular to the radius). Parameterize by
`catEye∈[0,1]`: at radius `r`, intersect the aperture polygon with a circle offset by
`catEye·r` along the radial direction. Free byproduct of §1.2 optical vignetting — drive both
from the same `opticalVignette` scalar for physical consistency.

### 2.4 Busy vs smooth bokeh (sign of spherical aberration)

SA redistributes energy inside the disc:
- **Undercorrected SA (`sa<0`)**: bright **center**, soft edge → smooth/creamy bokeh (and
  foreground "soap-bubble" inverts to background).
- **Overcorrected SA (`sa>0`)**: bright **ring/edge** ("soap-bubble", outlined discs) → busy,
  nervous bokeh.

Implement as a **radial weighting of the disc kernel**: `w(ρ) = 1 + sa·(2ρ²−1)`, ρ∈[0,1] across
the disc. One scalar, big perceptual payoff, and it's the differentiator vs a plain Gaussian blur.

### 2.5 Highlight clipping / bloom for shaped speculars

The defining bokeh trait: clipped speculars must bloom into **bright shaped discs**, not gray
smears. Mirror LR's `enableHighlights`/`highlightsValue`: before blur, detect near-clip
luminance and **boost it nonlinearly** (`highlightBoost`, `highlightThreshold`) so it survives
energy-spreading. Our linear float pipeline already preserves headroom; do the boost on linear
raw. This naturally couples to halation tint (§3) at disc edges.

### 2.6 Depth from a single image (monocular ML)

**Licensing is the gating constraint** ([Depth-Anything-V2 GitHub/license](https://github.com/DepthAnything/Depth-Anything-V2/blob/main/LICENSE)):

| Model | License | Usable in a shipped app? |
|---|---|---|
| **Depth-Anything-V2-Small** (~25 M params) | **Apache-2.0** | **Yes** — ship/redistribute freely |
| Depth-Anything-V2 Base/Large/Giant | **CC-BY-NC-4.0** | No (non-commercial) — research only |
| **MiDaS** (v2.1/3.0/3.1) | **MIT** | **Yes** — older but permissive |

→ Target **Depth-Anything-V2-Small** (Apache-2.0) or **MiDaS small** (MIT), converted to TFLite,
GPU/NNAPI delegate. Output is *relative inverse depth* — fine for defocus weighting, no metric
calibration needed. App-side (Kotlin) produces a normalized depth plane passed to the engine as
a 4th channel (matches `RESEARCH_LENS_BOKEH.md §3a`). **Download-on-demand, default-OFF.**

### 2.7 Gather vs scatter; occlusion

- **Gather** (per output pixel, sample inputs over its CoC): GPU-friendly, simple, but leaks
  background over sharp foreground edges (occlusion error).
- **Scatter** (splat each input over its CoC): physically correct ordering, handles partial
  occlusion, but is a scatter-add → awkward on GPU, expensive on CPU.
- **Pragmatic**: layered gather — split into a few depth slices, blur each, composite
  **back-to-front** with the foreground's own alpha; premultiply to stop background bleed. This
  is the standard "spatially-variant + occlusion-aware" compromise. Foreground halo from
  near-blur is intentional (matches real lenses).

### 2.8 Cost & parity

Spatially-variant shaped gather at large CoC is **expensive on CPU** (O(npix·k²)); realistically
needs the **GPU preview path** (`LutGpuPreview` groundwork, see `RESEARCH_LENS_BOKEH.md §5`) to be
interactive. **All of §2 is opt-in synthesis** (ML + stochastic-ish + out-of-oracle),
default-OFF, never parity-gated.

---

## 3. Light scattering

Four physically distinct phenomena we currently conflate or only partly model. They differ by
**where** the scattering happens and **what** it scatters.

| Phenomenon | Physical site | Spectral signature | Spatial scale |
|---|---|---|---|
| **Irradiation** | sideways scatter *within* the emulsion | per-layer (broad) | small, µm core + tail |
| **Halation** | reflection off film base/pressure plate back into the **far (red) layer** | warm red/orange (anti-halation residue) | medium, multi-bounce |
| **Veiling glare / flare** | inter-element + barrel reflections in the **lens** | broadband, scene-illuminant tinted | global lift + sprites |
| **Diffusion filter (Pro-Mist)** | scatter on a **physical filter** in front of the lens | neutral (tunable warmth) | broad halo + bloom |

### 3.1 Irradiation & halation (per-stock, parity-gated)

Already modeled: `HalationParams` separates **in-emulsion scatter** (`scatter_core_um`,
`scatter_tail_um`, `scatter_tail_weight` — the irradiation term, core Gaussian + exponential
tail) from **back-reflection halation** (`halation_strength`, `halation_first_sigma_um`,
`halation_n_bounces`, `halation_bounce_decay`). The red-channel bias is physically correct: the
red-sensitive layer sits farthest from the lens, so back-reflected light re-exposes it most,
giving the signature reddish halo even with anti-halation backing
([Lomography anti-halation layer](https://www.lomography.com/magazine/352787-what-is-the-anti-halation-layer),
[Anti-halation backing — Wikipedia](https://en.wikipedia.org/wiki/Anti-halation_backing),
[Prodigium: Halation](https://www.prodigium-pictures.com/blog/insight09-halation-on-film-digitally-imitating-it)).

**Proposal**: expose these per-stock in the profile `info`/`data` and as user multipliers
(`scatter_amount`, `halation_amount`, `halation_spatial_scale` already exist as global scalars).
Author distinct presets: CineStill-style strong halation (no rem-jet) vs Portra "strong" vs
slide "weak". **Parity-gated** (already in the oracle); default tags keep goldens intact.

### 3.2 Diffusion filter / Pro-Mist (per-shot, parity-gated)

Already modeled (`DiffusionFilterParams`). Physically a Pro-Mist scatters a *fraction* of light
on an etched/particle-laden glass into a **highlight halo + overall bloom**; "black" variants
retain black level, plain variants lift blacks ([Tiffen Black Pro-Mist behavior](https://www.shopmoment.com/articles/moment-cinebloom-vs-tiffen-black-pro-mist-diffusion-filter),
[KentFaith: what a Pro-Mist does](https://www.kentfaith.com/blog/article_what-does-a-pro-mist-filter-do_24605)).
Our energy-conserving `(1-p)·E + p·(K·E)` blend with `halo_warmth` and core/halo/bloom group
multipliers already captures this. **Add a `black_level_lift` term** (0 = Black Pro-Mist,
>0 = plain Pro-Mist / Glimmerglass) and more `family` presets (Glimmerglass, CineBloom, fog).
Parity-gated; default `active=false` is a no-op.

### 3.3 Bloom / glow

The "spread bright into neighbors" look is currently emergent from diffusion+halation+glare.
For a dedicated **threshold bloom** (cheap, GPU-classic): extract luma above `bloomThreshold`,
multi-scale Gaussian, add back at `bloomIntensity`. Useful for the bokeh highlight bloom (§2.5)
and soft-focus lenses (§1.5). Synthesis; default-OFF.

### 3.4 Lens flare & veiling glare (per-lens)

- **Veiling glare**: broadband, uniform-ish lift from inter-element scatter — **we already have
  it** as `glare` in XYZ (`percent·glare_amount·illuminant_xyz`). Drive `glarePercent` up for
  uncoated/vintage lenses (§1.5). Parity-gated.
- **Ghosts/flares**: deterministic sprites reflected through the optical center from bright
  sources (rings, polygonal ghosts at the aperture shape, anamorphic horizontal streaks). Pure
  **synthesis** — author as a `lens_flare` overlay keyed off detected speculars; default-OFF.

### 3.5 Per-stock vs per-lens authoring

- **Per film stock**: irradiation + halation (it's an emulsion property) → in profile `info`.
- **Per lens**: veiling glare, flares, Pro-Mist (it's the optical front-end) → in the new lens
  profile (§1.5) / per-shot controls.

This split keeps the spectral film engine "honest" while letting users mix any stock with any
lens character.

---

## 4. Other film-emulation depth

### 4.1 Grain (size/shape/clumping/dye-cloud, per layer)

Already the strongest part of the engine: per-sublayer AgX **Poisson-binomial particle model**
with `agx_particle_area_um2`, per-layer `agx_particle_scale_layers[3]`, `uniformity[3]`,
per-particle dye-cloud blur (`blur_dye_clouds_um`), and lognormal **micro-structure clumping**
(`micro_structure[2]`). This already models size, shape (via dye-cloud blur), clumping, and
per-layer behavior — far beyond a tiled noise overlay.

**Proposals**: (a) expose `n_sub_layers`>1 per stock for richer clump structure; (b) surface a
single user "grain size/amount" that maps to `agx_particle_area_um2` + `n_particles`;
(c) verify push/pull stocks (Portra 800 Push +1/+2) scale grain area with EI. Deterministic
seeds → **parity-gated** (same realization), but the pass is stochastic so it lives behind the
"synthetic" boundary per `AUDIT.md`.

### 4.2 Spectral sensitivity nuances

Unique strength: the engine carries true **per-stock spectral sensitivities** (log-sensitivity
curves, `densitometer: status_M`, `reference_illuminant`). This is where Fuji's 4th
("cyan-correcting") layer, Kodachrome's warm signature, and tungsten balance physically *emerge*
rather than being faked. **Proposal**: document and expose `reference_illuminant`/balance as a
first-class "stock color temperature" control; allow a **white-balance-as-shot** offset feeding
the rgb→raw CAT in `filming`. Parity-gated (matrix is baked deterministically).

### 4.3 Push / pull, reciprocity failure

- **Push/pull**: already handled as EI + development variants (Portra 800 Push +1/+2 in the
  catalog) → shifted density curves + raised contrast + more grain. Generalize to a
  `push_pull_stops` param that scales the development gamma and grain area. Parity-gated (curve
  math).
- **Reciprocity failure**: at long exposures, effective speed drops nonlinearly and color
  balance shifts per layer (Schwarzschild: `t_eff = t^p`, p≈0.8–0.9, **per channel**). Model as a
  per-channel exposure compression keyed by a `reciprocity` param + `exposure_time_s`. Per-layer
  shift gives the authentic long-exposure crossover. Parity-gateable (deterministic curve).

### 4.4 Cross-process, print/paper character, color temp

- **Cross-process (C-41-in-E6 / E6-in-C-41)**: extreme contrast + color crossover. Cleanest as
  an alternate **density-curve set + coupler matrix** per stock (a "process" variant), reusing
  the existing curve/coupler machinery. Parity-gated.
- **Print/paper character**: already first-class — the negative→paper pairing
  (`target_print`, the Endura/Crystal Archive papers) defines contrast and palette via real
  paper density curves + dichroic Y/M filtering. **Proposal**: expose the **enlarger
  dichroic CC offsets** (we resolve `neutral_cc`) as a user "print color balance" + a
  print-contrast (paper grade) control. Parity-gated.
- **Stock color temp**: see §4.2.

### 4.5 Cine artifacts: gate weave, dust, scratches

Pure **synthesis**, cine-only, default-OFF: `gate_weave` (low-freq sub-pixel x/y jitter per
frame, e.g. Perlin), `dust_density` / `scratch_density` (stochastic sprites), `flicker`
(per-frame exposure jitter). Stateless per-frame from a frame index + seed. Never parity-gated.
Only meaningful in a future multi-frame/cine mode.

### 4.6 Edge effects (adjacency / MTF)

- **Adjacency (Eberhard / DIR-coupler) effects**: edge enhancement from inhibitor diffusion —
  **already physically present** via the spatially-diffused DIR couplers
  (`dir_couplers`, `diffusion_size_um`). This is the honest source of film "acutance," not an
  unsharp mask. Expose coupler `diffusion_size_um`/amount per stock. Parity-gated.
- **MTF / scanner sharpness**: the camera/scanner Gaussian blur + scanner unsharp already shape
  system MTF. **Proposal**: add an authorable per-stock/scanner MTF (a small Gaussian +
  optional unsharp) so resolution character differs by stock. Parity-gateable.

### 4.7 Tone / curve shoulder-toe

The characteristic curve's **toe** (shadow roll-off) and **shoulder** (highlight compression)
are the core of "filmic" tone and are already the literal density curves per stock/paper.
**Proposal**: expose per-stock-overridable `toe_strength` / `shoulder_strength` /
`density_curve_gamma` (gamma already in params) as a "contrast/latitude" user control that
*reshapes* the real curve rather than applying a generic S-curve. Parity-gated.

---

## 5. Param surface proposals (summary table)

Ranges are starting points. **PG** = parity-gateable (deterministic, identity-at-default keeps
goldens); **SY** = opt-in synthesis (default-OFF). Stage refers to the C++ insertion point.

| Area | Param (proposed) | Range / default | Stage / file | CPU | GPU | Class |
|---|---|---|---|---|---|---|
| **Distortion** | `lens.k1,k2,k3` | ±0.3 / 0 | new `lens_geometry.cpp` (pre-filming) | low | low | SY |
| **Vignette** | `lens.vignetteV1..V3` | ±0.5 / 0 | `filming::expose` (pre-diffusion multiply) | low | low | **PG** |
| | `lens.naturalVignette` | 0–1 / 0 (cos⁴) | same | low | low | **PG** |
| **Lateral CA** | `lens.caRedScale,caBlueScale` | ±0.01 / 0 | `lens_geometry` / per-band in filming | low | low | SY |
| **Long. CA** | `lens.bokehFringe` | ±2 px / 0 | bokeh stage (per-ch CoC offset) | med | low | SY |
| **Field curve** | `lens.fieldCurvature` | 0–1 / 0 | bokeh focus bias | low | low | SY |
| **Coma** | `lens.coma` | 0–1 / 0 | bokeh kernel stretch | med | low | SY |
| **Spherical ab.** | `lens.sa` (signed) | ±1 / 0 | bokeh kernel weight `1+sa(2ρ²−1)` | low | low | SY |
| **Breathing** | `lens.breathing` | 0–1 / 0 | global scale per focus | low | low | SY |
| **Bokeh** | `bokeh.blurAmount` | 0–1 / 0 | new `optical_bokeh.cpp` | high | med | SY |
| | `bokeh.fNumber` | 0.95–22 / 8 | same | — | — | SY |
| | `bokeh.bladeCount` | 4–14 / 9 | kernel raster | — | — | SY |
| | `bokeh.bladeCurvature` | 0–1 / 0.5 | kernel raster | — | — | SY |
| | `bokeh.bladeRotation` | 0–360° / 0 | kernel raster | — | — | SY |
| | `bokeh.catEye` (=opticalVignette) | 0–1 / 0 | edge pupil clip | med | med | SY |
| | `bokeh.aspect` (anamorphic) | 1.0–2.0 / 1 | kernel x/y | — | — | SY |
| | `bokeh.focusDepth` | 0–1 | depth weighting | — | — | SY |
| | `bokeh.focusRange` | 0–1 / 0.1 | depth plateau | — | — | SY |
| | `bokeh.highlightBoost` | 0–4 / 1 | pre-blur specular lift | low | low | SY |
| | `bokeh.highlightThreshold` | 0–1 / 0.9 | same | — | — | SY |
| **Depth (ML)** | `depth.enabled` | bool / false | Kotlin (Depth-Anything-V2-Small, Apache-2.0) | — | high | SY |
| **Halation** | `hal.scatterAmount` *(exists)* | 0–4 / 1 | `apply_halation_um` | med | — | **PG** |
| | `hal.halationAmount` *(exists)* | 0–4 / 1 | same | med | — | **PG** |
| | `hal.halationSpatialScale` *(exists)* | 0.5–4 / 1 | same | — | — | **PG** |
| **Diffusion** | `diff.strength` *(exists)* | 0–1 / 0.5 | `apply_diffusion_filter_um` | med | — | **PG** |
| | `diff.haloWarmth` *(exists)* | ±1 / 0 | same | — | — | **PG** |
| | `diff.blackLevelLift` *(new)* | 0–1 / 0 | same | low | — | **PG** |
| | `diff.family` *(extend)* | enum (+Glimmerglass, fog) | same | — | — | **PG** |
| **Bloom** | `bloom.threshold/intensity` | 0–1 / off | new bloom pass (scanning) | med | low | SY |
| **Glare** | `glare.percent` *(exists)* | 0–20 / 0 | scanning `add_glare` | low | — | **PG** |
| **Flare** | `flare.enabled` + sprites | bool / false | new overlay (scanning) | low | low | SY |
| **Grain** | `grain.size` → `agx_particle_area_um2` | 0.05–1 µm² / stock | `grain.cpp` | high | — | PG(seed) |
| | `grain.nSubLayers` *(exists)* | 1–3 / 1 | same | high | — | PG(seed) |
| **Spectral** | `stock.colorTemp` / WB offset | ±mireds / 0 | `filming` rgb→raw CAT | low | — | **PG** |
| **Push/pull** | `dev.pushPullStops` | ±2 / 0 | density curves | low | — | **PG** |
| **Reciprocity** | `dev.reciprocity` + `exposureTimeS` | p 0.8–1 / 1 | per-ch exposure curve | low | — | **PG** |
| **Cross-proc** | `dev.process` (variant) | enum / native | curve+coupler set | low | — | **PG** |
| **Paper** | `print.ccShift` (Y/M/C), `print.grade` | ±CC, 0–5 | `printing` dichroic + curves | low | — | **PG** |
| **Adjacency** | `dir.diffusionSizeUm` *(exists)* | 0–40 µm / stock | DIR couplers | med | — | **PG** |
| **MTF** | `scan.mtfSigma`, `scan.unsharp` | 0–2 px, 0–2 / 0 | scanning | low | low | **PG** |
| **Tone** | `curve.toe/shoulder`, `gamma` *(exists)* | 0–2 / 1 | density curves | low | — | **PG** |
| **Cine** | `cine.gateWeave/dust/flicker` | 0–1 / 0 | future cine pass | low | low | SY |

---

## 6. Sequenced roadmap

Ordered for **value-per-effort**, front-loading parity-safe authoring wins (no GPU dependency)
before the heavy ML/bokeh wave.

**Wave A — Parity-safe stock & lens authoring (no new ML, mostly profile + existing kernels).**
1. **Lens profiles as data** (`assets/spektra/lenses/*.json`): vignette (natural cos⁴ + optical
   poly), lateral CA, veiling-glare level. Reuses existing multiply/glare; all PG or cheap SY.
2. **Halation/Pro-Mist preset expansion**: per-stock irradiation vs back-reflection presets
   (CineStill/Portra/slide), `black_level_lift`, more diffusion families. PG.
3. **Tone & dev controls**: `toe/shoulder/gamma`, `pushPullStops`, reciprocity, paper
   `ccShift`/`grade`, adjacency `diffusionSizeUm`. All ride existing curve/coupler/print math. PG.
4. **Spectral color-temp / WB-as-shot** surfaced from `reference_illuminant`. PG.

**Wave B — Geometric optics (synthesis, CPU-fine).**
5. `lens_geometry.cpp`: distortion warp + lateral-CA resample (+ optional per-band spectral CA).
   Default-OFF identity. SY.
6. Bloom + flare/ghost overlays keyed off speculars. SY.

**Wave C — Depth-aware bokeh (the headline, GPU-dependent).**
7. **Depth spike**: Depth-Anything-V2-Small (Apache-2.0) or MiDaS-small (MIT) → TFLite, GPU/NNAPI;
   benchmark on-device. Default-OFF, download-on-demand.
8. `optical_bokeh.cpp`: depth-weighted CoC, blade-polygon kernels, highlight bloom (CPU first,
   then GPU preview). SY.
9. **Lens character on bokeh**: SA smoothness, cat-eye, field curvature/coma, anamorphic aspect,
   longitudinal CA fringe → wire the §1.4 modifiers into the kernel. SY.
10. **Film coupling**: halation-tinted disc edges, suppressed OOF grain, OOF diffusion lift —
    the differentiator vs a generic portrait blur. SY.

**Wave D — Cine (future, multi-frame).**
11. Gate weave / dust / flicker; per-stock MTF; cine print-film coupling.

---

## 7. Guardrails

- The **bit-exact filming→printing→scanning core and its `.npz` goldens stay untouched.** Every
  parity-gated addition must be **identity at its default** so existing goldens pass unchanged;
  add new goldens for non-default values.
- All ML/stochastic/geometric synthesis is **default-OFF** behind the "synthetic, not bit-exact"
  boundary in `AUDIT.md` (consistent with `RESEARCH_LENS_BOKEH.md §3d`).
- **Licensing**: ship only **Apache-2.0 (Depth-Anything-V2-Small)** or **MIT (MiDaS)** depth
  models; do **not** use CC-BY-NC Base/Large or lift Adobe LCP assets/models.
- Heavy spatially-variant bokeh realistically needs the **GPU preview path** before it's
  interactive — gate Wave C on that groundwork.

## Sources

- [Adobe Lens Profile Creator User Guide (PDF)](https://www.adobe.com/special/photoshop/camera_raw/lensprofile_creator/lensprofile_creator_userguide.pdf)
- [Lensfun — Converting Adobe LCP files (RadialDistortParam / VignetteModelParam)](https://lensfun.github.io/manual/v0.3.95/lensfun-convert-lcp.html)
- [RawPedia — Lens/Geometry](https://rawpedia.rawtherapee.com/Lens/Geometry)
- [Computer Darkroom — Adobe DNG Lens Profiles](https://www.computer-darkroom.com/blog/lens-correction-profiles/)
- [Geometrical analysis of polynomial lens distortion models (arXiv 1804.03584)](https://arxiv.org/pdf/1804.03584)
- [Circle of Confusion — Wikipedia](https://en.wikipedia.org/wiki/Circle_of_confusion)
- [Stanford ISETCam — The circle of confusion](https://stanford.edu/~wandell/data/isetcam/optics/s_opticsCoC.html)
- [Depth-Anything-V2 — License (Apache-2.0 Small; CC-BY-NC Base/Large)](https://github.com/DepthAnything/Depth-Anything-V2/blob/main/LICENSE)
- [Depth Anything V2 (arXiv 2406.09414)](https://arxiv.org/pdf/2406.09414)
- [Anti-halation backing — Wikipedia](https://en.wikipedia.org/wiki/Anti-halation_backing)
- [Lomography — What is the Anti-Halation Layer in Film?](https://www.lomography.com/magazine/352787-what-is-the-anti-halation-layer)
- [Prodigium Pictures — Halation on Film & Digitally Imitating It](https://www.prodigium-pictures.com/blog/insight09-halation-on-film-digitally-imitating-it)
- [Dehancer — Halation and its simulation](https://blog.dehancer.com/articles/halation/)
- [Moment — CineBloom vs Tiffen Black Pro-Mist](https://www.shopmoment.com/articles/moment-cinebloom-vs-tiffen-black-pro-mist-diffusion-filter)
- [KentFaith — What Does A Pro Mist Filter Do?](https://www.kentfaith.com/blog/article_what-does-a-pro-mist-filter-do_24605)
