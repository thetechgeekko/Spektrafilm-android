# Parity case matrix

The initial set of golden-vector parity cases that gate **M3** (pipeline port) and
**M4** (params/profiles) in `docs/PORTING_PLAN.md`. Each case fixes a film/print
profile pair plus deterministic toggles; for each case we capture a set of *taps*
(intermediate buffers) as `.spkvec` goldens. A stage of the C++ port is "done" when
its tap matches the Python golden within tolerance (see `README.md`).

Goldens are generated from the synthetic 64×64 ramp+Macbeth test image
(`gen_goldens.py make_test_image`, seed `20260529`, ProPhoto-RGB linear input).

## Taps (pipeline order)

| Tap                 | DebugParams field            | C API `tap_name`     | Shape     | Gates stage |
|---------------------|------------------------------|----------------------|-----------|-------------|
| `film_log_raw`      | `output_film_log_raw`        | `film_log_raw`       | (H,W,3)   | filming/expose (RGB→raw, Hanatos/Mallett) |
| `film_density_cmy`  | `output_film_density_cmy`    | `film_density_cmy`   | (H,W,3)   | filming/develop (density curves, couplers) |
| `print_density_cmy` | `output_print_density_cmy`   | `print_density_cmy`  | (H,W,3)   | printing (enlarger expose+develop) |
| `final_rgb`         | — (full pipeline, debug off) | (use `spk_simulate`) | (H,W,3)   | scanning (density→XYZ→output) |

`print_density_cmy` does not exist on the `scan_film` route (no print stage).

## Cases

| case_id        | film              | print               | route       | grain | spatial/stochastic | taps |
|----------------|-------------------|---------------------|-------------|-------|--------------------|------|
| `print_portra` | kodak_portra_400  | kodak_portra_endura | print       | off   | off                | all 4 |
| `scan_portra`  | kodak_portra_400  | kodak_portra_endura | scan_film   | off   | off                | film_log_raw, film_density_cmy, final_rgb |
| `print_ektar`  | kodak_ektar_100   | kodak_supra_endura  | print       | off   | off                | all 4 |
| `scan_portra_spatial` | kodak_portra_400 | kodak_portra_endura | scan_film | off | spatial ON         | film_density_cmy, final_rgb (+) |
| `scan_portra_crop`    | kodak_portra_400 | kodak_portra_endura | scan_film | off | off (crop+upscale) | film_density_cmy, final_rgb |
| `scan_portra_autoexp` | kodak_portra_400 | kodak_portra_endura | scan_film | off | off (auto-exposure ON) | film_log_raw, film_density_cmy, final_rgb |

Rationale:
- `print_portra` is the canonical reference pair (matches `init_params` defaults)
  and exercises every stage of the full print pipeline.
- `scan_portra` flips `io.scan_film` to gate the negative-scan route, which skips
  the print stage entirely (`PORTING_PLAN.md` Stage 3 `scanning.py`).
- `print_ektar` uses a different film/paper pair to catch regressions that are
  coupled to profile data (density curves, dye spectra) rather than the math.
- `scan_portra_spatial` / `scan_portra_crop` exercise the spatial branch and the
  crop+cubic-upscale geometry stage respectively (still deterministic: grain off).
- `scan_portra_autoexp` is the NON-default auto-exposure case: it flips
  `camera.auto_exposure = True` (method `center_weighted`) to exercise
  `FilmingStage.auto_exposure` in `pipeline._preprocess` — the image is metered
  (small_preview luminance → `-log2(Y_meter/0.184)` EV) and globally scaled by
  `2**ev` before crop/rescale. Tested by `tests/test_autoexposure.cpp`.

All cases EXCEPT `scan_portra_autoexp` run with `auto_exposure = False`; every
case uses `exposure_compensation_ev = 0` and stochastic effects off so the
goldens are bit-stable across hosts. (`scan_portra_autoexp` is still fully
deterministic — the synthetic image is fixed, so the metered EV is reproducible.)

## Tolerances

Deterministic cases (all of the above) gate tightly: `max_abs ≤ 1e-4`,
`rms ≤ 1e-5` (well above float32 epsilon; see per-case `manifest.json`).

## Future cases (not yet gating)

- **Grain-on** (`grain_active = True`): grain is stochastic. Even with a fixed
  seed the Python and C++ RNGs differ, so these are compared by *statistics*
  (per-channel mean/variance, local autocorrelation) within a looser band, not
  element-wise. Documented in `README.md` → "Grain & stochastic taps". Add once
  `model/grain.py` is ported (Stage 3).
- **Spatial-effects-on** (halation, glare, DIR diffusion, lens blur): deterministic
  but sensitive to the Gaussian/exponential filter port. Add per-effect as
  `utils/fast_gaussian_filter.py` / `model/diffusion.py` land.
- **Positive stock** (e.g. `fujifilm_velvia_100`): exercises the `is_positive`
  coupler-preset branch in `params_builder._apply_film_specifics`.
- **Mallett2019 RGB→raw** (`settings.rgb_to_raw_method = "mallett2019"`): the
  alternate upsampling path (`SPK_RGB2RAW_MALLETT2019`).
