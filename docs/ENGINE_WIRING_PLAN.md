# Engine wiring plan — the gated parameters

Status snapshot 2026-06-01, updated 2026-06-04. **§3 (`use_enlarger_lut`) is WIRED**
(shipped in v0.7.0, opt-in/default-off, gated by `test_enlarger_lut_e2e`). **§2
(`spectral_gaussian_blur`) is now WIRED** (default-off no-op, gated by the `scan_spectral_blur`
golden pinned to oracle **c1d0e44** + `test_spectral_blur_e2e`). The remaining item §1 (hanatos
window/surface) is still present in the app UI (dimmed via `GatedBlock`) and marshalled across
JNI into `spk_params`, but the native engine does **not** yet apply it; §4 (enlarger lens blur)
is intentionally not wireable. Wiring §1 requires an engine change **plus** a new
spektrafilm-oracle golden to keep the bit-exact parity gate honest — so that work needs a session
with the `spektrafilm` Python oracle available (it lives at `../spektrafilm`) to regenerate
goldens.

Bit-exact tolerance (per `HANDOFF.md`): `max_abs ≤ 1e-4`, `rms ≤ 1e-5`, and
byte-identical across thread counts.

---

## 1. `apply_hanatos2025_adaptation_window` / `_surface`

**What it is.** The Hanatos2025 sensitivity-adaptation `tc_lut` (built in the filming
stage) optionally multiplies the per-band sensitivity by an erf4 spectral **window**
and/or a **surface** term, read from the profile.

**Oracle reference.**
- `spektrafilm/src/spektrafilm/profiles/io.py:110-121` — profile carries
  `apply_window`, `apply_surface`, `spectral_gaussian_blur`,
  `hanatos2025_adaptation_window_params`, `hanatos2025_adaptation_surface_params`.
- `spektrafilm/.../utils/spectral_upsampling.py::compute_hanatos2025_tc_lut` — the
  reference math: `sensitivity * window (* surface) / norm`.
- `spektrafilm/.../runtime/services/spectral_lut_compute.py:50-67` — toggles feed the
  LUT build + cache key.

**Current C++.** `runtime/stages/filming.cpp` (`build_filming_tc_lut`, see
`filming.h:47`) **hardcodes** `apply_window=true, apply_surface=false,
spectral_gaussian_blur=0`. The profile loader (`profiles/profile.cpp:132`) already parses
`hanatos2025_adaptation_window_params`; confirm it also parses `_surface_params`.

**Wire.**
1. Pass `p->apply_hanatos_window` / `p->apply_hanatos_surface` into the tc_lut builder.
2. When surface on, multiply by `eval_erf4_spectral_bandpass(surface_params)` (mirror the
   window path already present).
3. Parse `hanatos2025_adaptation_surface_params` in `profile.cpp` if not already.

**Parity.** Generate `scan_portra_surface` goldens from the oracle with
`apply_surface=true` (and a window-off variant). Add `test_filming` / `test_simulate_e2e`
cases; gate in `engine-parity`. Window-on/surface-off stays the existing default golden.

**Risk.** Medium. Touches the tc_lut hot path; default behavior must stay byte-identical
(only the non-default toggles change output).

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

## Sequence

1. Stand up the `spektrafilm` oracle; regenerate the new goldens above.
2. §3 (enlarger LUT) first — lowest risk, proven template.
3. §1 + §2 together (same tc_lut builder).
4. Gate every new golden in `engine-parity`; verify default goldens stay byte-identical.
5. Remove each `GatedBlock` only once its parity test is green in CI.
