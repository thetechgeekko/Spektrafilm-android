# Film emulation physics (spektrafilm / agx-emulsion)

What the engine models and where each piece lives in C++. This is the *domain* reference: read
it before changing anything under `model/`, `kernels/spectral_upsampling.*`, or
`runtime/stages/{filming,printing,scanning}.cpp`. Parity rules live in
`references/parity-and-build.md`; this file is the physics behind those numbers.

> Naming: the upstream repos `andreavolpato/spektrafilm` and `andreavolpato/agx-emulsion`
> resolve to the **same project**. The cited/original name is **agx-emulsion**; **spektrafilm**
> is the OFX product name and the name of this Android port. Mention both when citing.
> (Name canonicality is itself UNVERIFIED — see "Sourcing & uncertainty".)

## The one idea that matters: spectral, not LUT

A conventional film sim bakes a fixed 3D RGB→RGB lookup cube and replaces each pixel's color by
sampling that cube. **This engine does not do that.** It reconstructs a *spectrum* per pixel and
models the material interactions at the wavelength level (380–780 nm, 5 nm step, 81 bands), so
effects that depend on the interplay of spectra — exposure change, enlarger filter swaps, the
reference illuminant, inter-layer couplers — emerge correctly *without* regenerating any table.

The rule that follows: **LUTs in this codebase are an acceleration cache of the spectral math,
not the model.** The Hanatos upsampling table, and the opt-in enlarger/scanner 3D LUTs, exist to
go faster. They must reproduce the spectral path within parity tolerance, or they are bugs. The
spectral path is the source of truth; never "improve" output by editing a LUT to diverge from it.

## Spectral upsampling (RGB → spectrum)

Film responds spectrally (each layer has its own per-wavelength sensitivity curve). You cannot
expose a virtual emulsion with a bare RGB triple — you must first reconstruct a plausible
spectrum. Implementation: `kernels/spectral_upsampling.cpp` (LUT parse + degree-4 2D polynomial +
cubic interpolation).

- **Hanatos2025 (engine default).** Treats the input as a colorimetric sample:
  1. RGB → XYZ under the selected film reference illuminant.
  2. Split XYZ into chromaticity `xy` + brightness `b`.
  3. Look up a smooth spectrum keyed by `xy` from a precomputed sigmoidal upsampling table
     (`irradiance_xy_tc.npy`, ~10 MB), then rescale by `b`.
  4. Integrate against the film sensitivities.
  Round-trips the CIE CMFs exactly for the standard observer (≈ zero error). v0.3.2 added a
  window+surface prototype correction.
- **Mallett & Yuksel 2019 (fallback lineage).** sRGB reflectance as a convex combination of 3
  fixed basis spectra (one per BT.709 primary), reproducing sRGB under D65.
- **Jakob & Hanika 2019 (the sigmoid model underpinning the LUT).** Reflectance as a
  sigmoid-of-a-quadratic; 3 coefficients fetched from a precomputed 3D table indexed by RGB. Zero
  error on the full sRGB gamut, ~6 float ops per wavelength.

## The pipeline, in order, mapped to files

Five stages, `negative → enlarger → print → scan`, on 81-band spectral data. Routing:
`scan_film=true` (default) skips Printing and scans the negative directly; `scan_film=false`
runs the full print route.

### Stage 1 — Filming: RGB → negative dye density (`runtime/stages/filming.cpp`)
RGB → spectral upsampling → integrate against film sensitivities × reference illuminant → camera
raw → log-exposure → CMY dye density via the profile **characteristic curves** → **DIR couplers**
→ **grain** → **halation / in-emulsion scatter**. Models: `model/density_curves.*`,
`model/emulsion.*`, `model/couplers.*`, `model/grain.*`, `model/diffusion.*`.

### Stage 2 — Printing: film CMY → print paper density (`runtime/stages/printing.cpp`)
Film CMY → spectral density → transmittance `10^(−D)` × enlarger illuminant (default tungsten
TH + Schott KG3 heat filter) → **dichroic Y/M/C** shifts in CC units (relative to a neutral CC
that renders 18% gray neutral) → integrate against paper sensitivities → **midgray
normalization** → optional preflash → paper density curves. **Paper has no couplers.** Models:
`model/color_filters.*`, plus the shared `model/density_curves.*` / `model/emulsion.*`.

### Stage 3 — Scanning: print/negative density → output RGB (`runtime/stages/scanning.cpp`)
Density → spectral radiance (illuminant × `10^(−D)`) → integrate against **CIE 1931 2° CMFs** →
XYZ → veiling glare → chromatic adaptation to the output white → output RGB (6 spaces: sRGB,
Adobe RGB, ProPhoto, Rec.2020, ACES2065-1, linear sRGB) → optional scanner blur / unsharp → CCTF.
Models: `model/spectral.*`, `model/color_output.*`, `model/glare.*`.

> **NaN law lives here.** Profile nulls are encoded as NaN; `density_to_light` computes
> `10^(−density)` and a NaN density must propagate to 0. This is why
> `-fno-finite-math-only` is mandatory — see `references/parity-and-build.md`.

### Stage 4 — Crop / resize (`runtime/stages/crop_resize.cpp`)
Cubic resampling 0.5×–2.0×; default identity; bit-exact vs the preprocess path.

### Stage 5 — Auto-exposure (`runtime/stages/autoexposure.cpp`)
7 metering methods (`center_weighted` default, `average`, `median`, `partial`, `matrix`,
`multi_zone`, `highlight_weighted`) → EV compensation.

## Glossary (the terms that show up in code, profiles, and the UI)

- **Characteristic curve.** Per-layer map from log-exposure → dye density (toe / linear /
  shoulder). One curve per CMY layer. `model/density_curves.*`.
- **CMY layers / dye density.** A color negative is three silver-halide units: red-sensitive →
  cyan dye, green → magenta, blue → yellow. CMY diffuse densities are usually not published, so
  they are derived/fit from datasheet data.
- **Coupler.** A molecule released during development.
  - **Masking coupler** → the **orange mask** of color negative: a colored coupler consumed
    locally that reduces interimage effects; modeled as negative absorption.
  - **DIR coupler** (Development-Inhibitor-Releasing) → where density forms, releases a diffusible
    inhibitor that suppresses density in the same and neighboring layers. Modeled as a self- +
    inter-layer **inhibition matrix** (diagonal same-layer + off-diagonal interlayer), spatially
    diffused via a Gaussian core + exp tail (`diffusion_size_um`, `diffusion_tail_um`). Produces
    higher saturation / purer hues (interimage), finer effective grain, and **edge/adjacency
    (Eberhard) effects = real acutance** — honest physical sharpening, not an unsharp mask.
    `model/couplers.*`.
- **Halation vs irradiation.** Two *distinct* phenomena, both in `model/diffusion.cpp`:
  - **In-emulsion irradiation / scatter** = sideways spread of light *within* the emulsion;
    energy-conserving Gaussian core + exp tail, per channel (`scatter_core_um`, `scatter_tail_um`,
    `scatter_tail_weight`).
  - **Halation** = light passes through, reflects off the film base / pressure plate, and
    re-exposes the layers; strongest on the red-sensitive layer (farthest from the lens) → the
    red/orange halo around highlights. Modeled as an additive sum of N Gaussians with `√k`-scaled
    widths and multi-bounce decay (`halation_strength`, `halation_first_sigma_um`,
    `halation_n_bounces`, `halation_bounce_decay`). Anti-halation backing suppresses it
    (`info.antihalation` tag); CineStill (rem-jet removed) shows very strong halation. Both
    branches run only when `halation_active && spatial_effects`.
- **Grain (signal-dependent).** Grain is **not** additive Gaussian/Poisson noise on the image — it
  is signal-dependent and modeled per layer with an **AgX Poisson-Binomial particle model**:
  particle count per pixel ~ Poisson; developed count ~ Binomial; each developed particle
  contributes density; multiple sublayers (faster/higher-ISO layers = larger, noisier particles) +
  lognormal micro-structure clumping + per-particle dye-cloud blur. Params: `agx_particle_area_um2`,
  `agx_particle_scale`, uniformity, `density_min`, `n_sub_layers`. **Deterministic seeds →
  reproducible → parity-gateable.** `model/grain.cpp`, `kernels/stats.cpp`. Same family as
  Aurélien Pierre's physically-based grain and Newson et al. 2017.
- **Dichroic CC / neutral CC.** A virtual color enlarger: tungsten light through dichroic Y/M/C
  filters measured in CC (color-correction) units. The **neutral CC** makes 18% gray render
  neutral under the correct reference illuminant; the user's `y/m_filter_shift` is steps away from
  that neutral. Subtractive color printing. `model/color_filters.*`.
- **Densitometer status.** The spectral weighting used to read density (e.g. Status M for color
  negative). Relevant to how density is integrated in scanning.
- **CMFs (color matching functions).** CIE 1931 2° observer curves; the spectral radiance is
  integrated against them to get XYZ in the scanning stage.
- **Diffusion filters** (e.g. Black Pro-Mist look). Energy-conserving blend applied on float64
  pre-log irradiance — distinct from the DIR/halation diffusion above.

## Comparison points (context only — these are NOT this engine)

- **AgX / AgX DRT** (Sobotka et al.; Blender, darktable 5.4): a *display rendering transform* —
  per-channel primaries rotation + soft tone curve / filmic highlight rolloff. Tone-mapping only,
  no emulsion physics. Shares the "agx" token but is unrelated to agx-emulsion.
- **darktable filmic RGB / sigmoid:** scene→display tone curves in the RGB domain, no spectral
  reconstruction.
- **vkdt filmsim (hanatos):** a Vulkan implementation of *this same* spectral model — the closest
  sibling.
- **ART (agriggio):** a LUT-based bridge that bakes agx-emulsion output into LUTs — illustrates
  exactly the LUT-vs-spectral tradeoff this engine avoids.
- **Negative inversion ≠ film emulation.** Film-scan inversion (invert transmittance, remove
  orange mask, per-channel gamma/black-white points) runs in the *opposite* direction from this
  engine, which *synthesizes* a negative. Do not conflate the two.

## Sourcing & uncertainty (preserve these flags — do NOT launder into fact)

Solidly verified: pipeline stage order; the spectral-vs-LUT rationale; the Hanatos
`xy + brightness` shape; the Mallett/Yuksel and Jakob/Hanika identities; DIR/masking coupler
chemistry; the halation red-layer mechanism; the Poisson/Binomial grain family; the subtractive
dichroic enlarger; CIE-CMF scan → XYZ.

Flagged / re-verify before stating as authoritative:

- **OFX whitepaper** at `spektrafilm.114c.de/technical/` returned **HTTP 403, unread** — the
  pipeline description above was reconstructed from snippets, not the primary source.
- **"Hanatos 2025" has NO formal paper located.** It is documented only in project / forum / vkdt
  text. Cite it as "the method named in the project," not as peer-reviewed work.
- **Upsampling PDFs (Jakob/Hanika, Mallett/Yuksel) were read at abstract level only** — full PDFs
  not loaded.
- **Exact engine constants** (e.g. neutral CC defaults Y≈55 / M≈65 / C=0, grain params like
  `agx_particle_area_um2≈0.2`, halation bounce counts) came from local porting docs
  (`docs/maps/`, `SPEKTRAFILM_MAP.md`-style notes), **not from live profile JSON**. Re-check
  against the actual `engine/spektra-core/src/main/assets/spektra/profiles/*.json` before quoting
  any number as authoritative.
- **spektrafilm ↔ agx-emulsion name** canonicality: unverified.
- **"filmbox" / negative-inversion references:** no single authoritative OSS reference found —
  do not cite.

Source URLs (for the verified-but-secondary claims above):
- https://github.com/andreavolpato/spektrafilm
- https://discuss.pixls.us/t/spectral-film-simulations-from-scratch/48209
- https://jo.dreggn.org/vkdt/src/pipe/modules/filmsim/readme.html
- https://artraweditor.github.io/SpectralFilmSimHowto.html
- https://cemyuksel.com/research/papers/spectral_primary_decomposition.pdf (Mallett & Yuksel 2019)
- https://rgl.epfl.ch/publications/Jakob2019Spectral ; tables: Zenodo 4050598 (Jakob & Hanika 2019)
- DIR coupler patents: USPTO 5,021,331 ; patents.justia.com 5,451,492
- https://en.wikipedia.org/wiki/Anti-halation_backing ; https://blog.dehancer.com/articles/halation/
- https://eng.aurelienpierre.com (stochastic grain) ; Newson et al. 2017 (IPOL, film grain)
