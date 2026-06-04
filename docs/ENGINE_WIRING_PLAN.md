# Engine wiring plan — the gated parameters

Status snapshot 2026-06-01, updated 2026-06-04. **§3 (`use_enlarger_lut`) is WIRED**
(shipped in v0.7.0, opt-in/default-off, gated by `test_enlarger_lut_e2e`). **§2
(`spectral_gaussian_blur`) is WIRED** (default-off no-op, gated by the `scan_spectral_blur`
golden pinned to oracle **c1d0e44** + `test_spectral_blur_e2e`). **§1 (hanatos
window/surface) is now WIRED** (default window-on/surface-off is a strict no-op, gated by the
`scan_portra_surface` + `scan_portra_nowindow` goldens pinned to oracle **c1d0e44** +
`test_hanatos_surface_e2e`); both UI toggles are un-gated. **§5 (camera UV/IR cut band-pass
`camera.filter_uv`/`filter_ir`) is now WIRED** (default amplitudes 0 are a strict no-op, gated
by the `scan_portra_uvir` golden pinned to oracle **c1d0e44** + `test_camera_uvir_e2e`); the UI
sliders were already live. **§6 (enlarger preflash `preflash_exposure` /
`preflash_y/m_filter_shift`) is now WIRED** (default `preflash_exposure` 0 is a strict no-op,
gated by the `print_portra_preflash` golden pinned to oracle **c1d0e44** + `test_preflash_e2e`);
the UI sliders were already live. §4 (enlarger lens blur) remains intentionally not wireable (no
oracle call site).

All **LUT-build-path** inert params are now wired, and the print-route preflash is wired. One
inert param remains as a **scoped follow-up** (real oracle call site, but a higher-complexity
multi-stage port, so it warrants its own PR — see §7): `scanner_white/black_correction` +
`_white/black_level` (spans filming/printing/scanning). Only §4 stays gated by design.

Bit-exact tolerance (per `HANDOFF.md`): `max_abs ≤ 1e-4`, `rms ≤ 1e-5`, and
byte-identical across thread counts.

---

## 1. `apply_hanatos2025_adaptation_window` / `_surface` — ✅ WIRED

**What it is.** The Hanatos2025 sensitivity-adaptation `tc_lut` (built in the filming
stage) optionally folds an erf4 spectral **window** into the per-band sensitivity AND/OR
multiplies the resulting tc_lut by a **log-exposure-correction surface** term, both read
from the profile.

**Oracle reference (verified at c1d0e44, not taken on trust).**
- `spektrafilm/src/spektrafilm/profiles/io.py:107-138` — profile carries
  `apply_window`, `apply_surface`, `spectral_gaussian_blur`,
  `hanatos2025_adaptation_window_params`, `hanatos2025_adaptation_surface_params`.
  Runtime defaults are window **on**, surface **off** (`runtime/params_schema.py:189-190`).
- `spektrafilm/.../utils/spectral_upsampling.py::compute_hanatos2025_tc_lut` — the
  reference math. **IMPORTANT:** the window and surface are NOT the same kind of term.
  - **window** (`apply_window`): `eval_erf4_spectral_bandpass(window_params)` → a common
    per-band `(S,)` erf4 band-pass, white-balance-normalised per channel
    (`window /= norm`), folded into `sensitivity` before the spectra contraction.
  - **surface** (`apply_surface`): `eval_log_exposure_correction_surface(surface_params,
    illuminant_xy, model='poly4')` → a per-LUT-cell, per-channel `(L,L,3)` degree-4 2D
    polynomial (`poly2d_deg4`, centred at `tri2quad(_illuminant_to_xy(reference_illuminant))`,
    passed through `hanika_sigmoid(raw, max=2.0)`); the tc_lut is then multiplied by
    `2**surface`. This is a separate post-contraction step, NOT an erf4 bandpass.
- `spektrafilm/.../runtime/services/spectral_lut_compute.py:50-67` — toggles feed the
  LUT build + cache key.

**Wired C++.** `build_filming_tc_lut` (`runtime/stages/filming.cpp`) now takes
`apply_window` / `apply_surface` args (defaults true/false). `apply_window` gates the erf4
window fold (unchanged math); `apply_surface` evaluates `eval_poly4_surface` (port of
`eval_poly4_log_exposure_surface`) over the `(L,L)` tc grid at the film reference-illuminant
chromaticity — computed in C++ from the same illuminant SPD integrated against the CIE 1931
2° CMFs (xy reproduces the oracle to ~3e-9) — and multiplies the tc_lut by `2**surface`.
The profile loader (`profiles/profile.cpp`) now parses
`hanatos2025_adaptation_surface_params` (a `(3, cols)` matrix). Both the tc_lut cache key
(`engine_tc_lut`) and the print-route `film_density_cmy` memo key
(`compute_film_cache_key`) fold the two toggles. The default (window on, surface off) is a
strict no-op.

**Parity.** `scan_portra_surface` golden (`apply_surface=true`, window left on) and
`scan_portra_nowindow` golden (`apply_window=false`) generated at oracle **c1d0e44**; gated
by `tests/test_hanatos_surface_e2e.cpp` in CI `engine-parity`. Verified within tol
(surface film_log_raw max_abs≈1.2e-7, final_rgb max_abs≈6e-8; window-off similar) and
byte-identical at `SPK_NUM_THREADS` 1 vs 8. Surface on-vs-off final-RGB delta ≈9.3e-2 and
window on-vs-off ≈1.8e-1 confirm both toggles are genuinely active, not no-ops.

---

## 2. `spectral_gaussian_blur` — ✅ WIRED

**What it is.** A 1-D Gaussian blur applied to the reconstructed-spectra LUT
(`HANATOS2025_SPECTRA_LUT`) along its **spectral axis** before sensitivity integration —
softens the spectral upsampling. Sigma is in spectral-axis **samples** (each working-shape
band is 5 nm); upstream's `profiles/io.py:110` comment "sigma in nm" refers to the band
spacing, but the actual `scipy.ndimage.gaussian_filter` sigma is in array-index units.

**Oracle reference.** `utils/spectral_upsampling.py::compute_hanatos2025_tc_lut` (line ~333):
when `spectral_gaussian_blur > 0`, `spectra_lut = scipy.ndimage.gaussian_filter(spectra_lut,
(0, 0, sigma))` — i.e. blur axis 2 only, with the SciPy defaults `order=0, mode='reflect',
truncate=4.0`. The LUT is loaded as `np.double` (float64), so the blur runs in float64.

**Wired C++.** `build_filming_tc_lut` (`runtime/stages/filming.cpp`) now takes a
`spectral_gaussian_blur` arg. When `> 0` it blurs `spectra_lut` along its spectral axis with a
float64 1-D Gaussian (radius `int(4.0*sigma+0.5)`, kernel `exp(-0.5*(x/sigma)^2)` normalised,
`reflect` boundary) BEFORE the sensitivity contraction. `engine_tc_lut` folds the sigma into
the tc_lut cache key, and the print-route `film_density_cmy` memo key folds it too. `sigma==0`
is an exact no-op (all pre-existing goldens reproduce bit-exactly).

**Parity.** `scan_spectral_blur` golden (`settings.spectral_gaussian_blur=5.0`) generated at
oracle **c1d0e44**; gated by `tests/test_spectral_blur_e2e.cpp` in CI `engine-parity`. Verified
within tol (film_log_raw max_abs≈1.2e-7, final_rgb max_abs≈6e-8) and byte-identical at
`SPK_NUM_THREADS` 1 vs 8.

---

## 3. `use_enlarger_lut` — ✅ WIRED (v0.7.0)

**What it is.** Opt-in 3-D LUT acceleration of the **enlarger** (print-expose) spectral
integral — the print-side analogue of the scanner LUT.

**Status.** Shipped. `printing.cpp::print_expose` PCHIP-interpolates the print-expose
integral through a per-channel LUT when `params.use_enlarger_lut` is set (opt-in,
**default-off** → the default/export path stays bit-exact). Gated by `test_enlarger_lut_e2e`
in CI `engine-parity`. This was the last reserved engine LUT flag; no reserved flag remains.

**Oracle reference.**
- `spektrafilm/.../runtime/stages/printing.py:53` — `use_lut=self._settings.use_enlarger_lut`.
- `spektrafilm/.../runtime/services/spectral_lut_compute.py:88-104` — enlarger LUT memory +
  build/cache.

---

## 4. Enlarger lens blur — NOT wireable

The oracle has **no enlarger-stage lens-blur call site** (only camera + scanner lens blur
exist, both wired + gated by `test_lensblur`). The `GatedBlock` note "no engine call site"
is accurate. Leave gated unless the oracle adds one upstream.

---

## 5. Camera UV/IR cut band-pass (`camera.filter_uv` / `filter_ir`) — ✅ WIRED

**What it is.** The camera UV/IR cut filter: a band-pass on the profile sensitivity that
attenuates the UV and IR tails before the RGB→raw spectral contraction.

**Status.** Wired (2026-06-04). `build_filming_tc_lut` applies the band-pass to the
sensitivity BEFORE the spectra contraction, gated on `filter_uv[0] > 0 || filter_ir[0] > 0`
(default amplitudes 0 → strict no-op, default/export path stays bit-exact). The tc_lut +
print-route memo cache keys fold the band-pass triples. The UI "UV filter" / "IR filter"
sliders + the JNI marshalling were already present; only the engine consumption was missing.
Gated by `test_camera_uvir_e2e` + the `scan_portra_uvir` golden (oracle **c1d0e44**).

**Oracle reference.**
- `spektrafilm/.../runtime/stages/filming.py:99-104` — `if filter_uv[0]>0 or filter_ir[0]>0:`
  builds `compute_band_pass_filter`, tiles it across channels, and
  `sensitivity *= band_pass_filter / normalization` (per-channel WB normalisation against the
  film reference illuminant).
- `spektrafilm/.../model/color_filters.py:88-107` — `sigmoid_erf` + `compute_band_pass_filter`
  (`band = filter_uv * filter_ir`, each `1-amp + amp*sigmoid_erf`, amp clipped to [0,1]).

---

## 6. Preflash (`preflash_exposure` / `preflash_y_filter_shift` / `_m_filter_shift`) — ✅ WIRED

**What it is.** A uniform pre-exposure of the print paper through (optionally shifted) enlarger
filters, lifting print shadows. Print route only.

**Status.** WIRED (2026-06-04). `print_expose` now adds the preflash raw term to the print
exposure when `preflash_exposure > 0`. Default `preflash_exposure` 0 is a strict no-op (mirrors
the oracle's `if preflash_exposure > 0` guard), so the default print path stays byte-identical.
The UI sliders were already live (no un-gate needed). Gated by `test_preflash_e2e` + the
`print_portra_preflash` golden (oracle **c1d0e44**).

**Oracle reference (read at c1d0e44).**
- `runtime/stages/printing.py:92-101` — `_compute_raw_preflash`: when `preflash_exposure > 0`,
  returns `raw_preflash * preflash_exposure`, where
  `raw_preflash[k] = sum_l (10^-base_density[l] * preflash_illuminant[l]) * sensitivity[l,k]`.
  It is added to `raw` in `_film_cmy_to_print_log_raw` **after** the midgray exposure factor and
  **before** the `log10(fmax(raw,0)+1e-10)`. The term is constant across pixels (depends only on
  film base density, the preflash illuminant, and print sensitivity).
- `runtime/services/filter_enlarger_source.py:29-32` — `preflash_filtered_illuminant`:
  `color_enlarger(light_source, CC=[c_filter_neutral, m_filter_neutral + preflash_m_filter_shift,
  y_filter_neutral + preflash_y_filter_shift])` — its OWN shifts off the neutral CC, **not** the
  image-exposure `m_filter_shift`/`y_filter_shift`.

**Native mapping.** `PrintingParams` gained `preflash_illuminant[81]` + `preflash_exposure`;
`spektra.cpp::run_print` builds the preflash illuminant via `color_enlarger` from the un-shifted
neutral CC + the preflash shifts; `printing.cpp::print_expose` precomputes the constant
`raw_preflash` 3-vector once and adds it after the midgray factor (both the direct per-pixel path
and the opt-in enlarger 3D-LUT context). Preflash affects the print expose only, NOT the film
density, so it is correctly excluded from `compute_film_cache_key` (the film-density memo): a
print-route film-cache HIT returns the same negative density and `print_expose` re-runs with the
live preflash params on every call.

---

## 7. Scanner white/black corrections (`scanner_white/black_correction`, `_white/black_level`) — wireable follow-up

**What it is.** A scan-time tone-anchor correction that maps measured Y at the reference
black/white densities to target levels (and back-corrects the filming/printing exposure).

**Status.** Inert (marshalled, consumed by no stage). Wireable, but the **highest-complexity**
remaining item — it spans three stages with film/print-type and `scan_film` branches. Scoped as
its own PR with multiple goldens (negative-scan, positive-scan, print routes).

**Oracle reference.**
- `spektrafilm/.../runtime/services/color_reference.py` — `ColorReferenceService`:
  `black_white_filming_exposure_correction` (filming), `black_white_printing_exposure_correction`
  (printing), `black_white_xyz_correction` (scanning), `_correction_fucntion` (the `m*y+q` map).
- `spektrafilm/.../runtime/pipeline.py:45-46` — wires the four params into the service.

---

## Sequence

1. Stand up the `spektrafilm` oracle; regenerate the new goldens above.
2. §3 (enlarger LUT) first — lowest risk, proven template.
3. §1 + §2 together (same tc_lut builder).
4. Gate every new golden in `engine-parity`; verify default goldens stay byte-identical.
5. Remove each `GatedBlock` only once its parity test is green in CI.
