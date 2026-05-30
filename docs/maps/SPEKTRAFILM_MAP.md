# Technical map — spektrafilm (the engine we port)

Source: `andreavolpato/spektrafilm`, Python, GPLv3, v0.3.2. Produced by surveying the actual
source under `src/spektrafilm/`. This is the reference for the C++ port (`engine/spektra-core`).

## What it is

A **spectral** simulator of analog photography. From a linear scene-referred RGB (typically a
camera RAW), it reconstructs spectra, exposes a virtual **negative**, develops it (density
curves + couplers + grain + halation), projects it through a virtual **enlarger** (illuminant +
dichroic Y/M filters) onto **print paper**, develops the print, and **scans** it to an output
color space — all grounded in manufacturer datasheet data.

## Pipeline (the three stages)

```
RGB ─► [Filming] ─► negative CMY density ─► [Printing] ─► print CMY density ─► [Scanning] ─► RGB
                                         └─(scan_film: skip Printing, scan negative directly)
```

1. **FilmingStage** (`runtime/stages/filming.py`): RGB→spectral upsampling (Hanatos2025 default,
   Mallett2019 fallback) → integrate against film spectral sensitivities × illuminant → camera
   "raw" → log-exposure → CMY density via characteristic curves → DIR couplers → grain →
   halation/scatter.
2. **PrintingStage** (`runtime/stages/printing.py`): film CMY → spectral density
   (`density_cmy @ channel_density + base`) → transmittance `10^(-D)` × enlarger illuminant →
   dichroic Y/M filter shifts → integrate against paper sensitivities → midgray normalization →
   optional preflash → print density curves.
3. **ScanningStage** (`runtime/stages/scanning.py`): density → spectral radiance
   `illuminant × 10^(-D)` → integrate against CIE 1931 2° CMFs → XYZ → glare → chromatic adapt
   → output RGB (sRGB/AdobeRGB/ProPhoto/Rec2020/ACES) → optional blur/unsharp → CCTF encode.

## Core model modules (`model/`, ~1,600 LOC)

| File | LOC | Role |
|------|-----|------|
| `diffusion.py` | 639 | **Largest.** Halation (additive Σ of N Gaussians with √k widths, multi-bounce decay), in-emulsion scatter (energy-conserving Gaussian core + exponential tail per channel), diffusion filters (Black Pro-Mist etc.), highlight boost. |
| `color_filters.py` | 272 | UV/IR bandpass, Schott KG3 heat filter, enlarger dichroic Y/M/C in CC units, lens transmission — all as transmittance spectra. |
| `grain.py` | 239 | Poisson-binomial AgX particle model: Poisson particle count, Binomial developed count, density contribution, cloud blur; multi-sublayer + micro-structure clumping. |
| `couplers.py` | 161 | DIR couplers: self- + interlayer inhibition matrix, spatially diffused (Gaussian + exp tail); boosts saturation/contrast, simulates orange mask. |
| `density_curves.py` | 101 | log-exposure→CMY-density interpolation, gamma contrast control, multi-layer decomposition `(n,3,3)`. |
| `emulsion.py` | 87 | spectral density tensor contraction `density_cmy ⊗ channel_density`. |
| `illuminants.py` | 56 | D-series (via `colour`), black-body BB3400, custom TH-KG3 (tungsten + KG3). |
| `glare.py` | 24 | lognormal scatter added at scan. |

## Runtime (`runtime/`, ~1,800 LOC)

- `pipeline.py` (220) — stage composition + `_pipeline_print` / `_pipeline_scan_film`.
- `params_schema.py` (218) — the full param dataclass tree (mirrored in `SpektraParams`).
- `params_builder.py` (211) — `init_params` / `digest_params`: load profiles, resolve neutral
  filters, precompute.
- `services/spectral_lut_compute.py` (163) — enlarger/scanner LUT caching + Hanatos TC LUT.
- `services/color_reference.py`, `services/filter_enlarger_source.py`, `services/resize.py`.

## Performance kernels (`utils/`, Numba-JIT → C++)

`fast_interp_lut.py` (827, 1D/2D/3D cubic LUT interp), `fast_gaussian_filter.py` (413, separable
Gaussian), `fast_stats.py` (353, Poisson/binomial), `spectral_upsampling.py` (394, Hanatos2025
LUT parse + deg-4 2D poly + cubic interp), `fast_interp.py` (181), `numba_boost_hightlights.py`
(164), `fft_gaussian_filter.py` (171, optional).

## I/O (`utils/`)

- `raw_file_processor.py` (445) — **rawpy/LibRaw**: `output_color=ACES, output_bps=16,
  no_auto_bright, gamma=(1,1)`, WB as-shot/daylight/tungsten/custom (temp+tint, Von-Kries adapt).
- `io.py` (402) — **OpenImageIO** read/write 16/32-bit PNG/TIFF/EXR, **exiv2** metadata, ICC.
- `profiles/io.py` (368) — JSON profile `{metadata, info, data}` parse.

## Params (the contract)

`RuntimePhotoParams { film: Profile, print: Profile, film_render, print_render, camera,
enlarger, scanner, io, debug, settings }`. Notable nested params (all mirrored in
`SpektraParams.kt`): `CameraParams(exposure_compensation_ev, auto_exposure, lens_blur_um,
filter_uv, filter_ir, diffusion_filter)`, `EnlargerParams(illuminant=TH-KG3, y/m_filter_shift,
y/m/c_filter_neutral=55/65/0, preflash_*)`, `ScannerParams(white/black_correction, unsharp_mask)`,
`GrainParams(agx_particle_area_um2=0.2, agx_particle_scale=(.8,1,2), density_min, uniformity,
blur=.65, n_sub_layers)`, `HalationParams(scatter_core_um, scatter_tail_um, halation_strength,
halation_first_sigma_um, halation_n_bounces=3, ...)`, `DirCouplersParams(gamma_samelayer_rgb,
gamma_interlayer_*, diffusion_size_um=20, diffusion_tail_um=200)`, `GlareParams(percent=.03)`,
`IOParams(input/output_color_space, cctf, crop, upscale_factor, scan_film)`,
`SettingsParams(rgb_to_raw_method=hanatos2025, use_*_lut, lut_resolution=17, preview_max_size=640,
preview_mode)`, `DebugParams(output_film_log_raw, output_film_density_cmy, output_print_density_cmy,
inject_film_density_cmy)` ← used for golden-vector parity.

## Public API

```python
from spektrafilm import create_params, simulate          # README form
init_params / digest_params / simulate / simulate_preview / load_profile / save_profile
```

## Portability verdict

- **Pure math (~1,000 LOC):** density curves, couplers, emulsion, glare, color filters,
  autoexposure, conversions — direct C++.
- **Hot kernels (~1,500 LOC):** Numba JITs — mechanical C++ translation.
- **Pipeline + spectral (~1,800 LOC):** stages, services, grain, diffusion, spectral upsampling.
- **I/O (~850 LOC):** Android-native (LibRaw + ExifInterface + host export).
- **Skip:** napari/Qt GUI (~5,000 LOC) → Compose; plotting; unused modules.

Total to a functional engine: **~6,000 LOC ported**, ~8–12 weeks.
