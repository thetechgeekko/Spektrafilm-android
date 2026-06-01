# Engine wiring plan — the 4 gated parameters

Status snapshot 2026-06-01. These parameters are present in the app UI (dimmed via
`GatedBlock`) and marshalled across JNI into `spk_params`, but the native engine does
**not** yet apply them. Wiring each requires an engine change **plus** a new
spektrafilm-oracle golden to keep the bit-exact parity gate honest — so this work needs
a session with the `spektrafilm` Python oracle available (it lives at `../spektrafilm`)
to regenerate goldens. None of it is doable from the Android repo alone.

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

**Current C++.** `runtime/stages/filming.cpp` (`build_hanatos2025_tc_lut`, see
`filming.h:39`) **hardcodes** `apply_window=true, apply_surface=false,
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

## 2. `spectral_gaussian_blur`

**What it is.** A Gaussian blur (sigma in **nm**) applied to the reconstructed spectra
before sensitivity integration — softens the spectral upsampling.

**Oracle reference.** Same files as §1; `profiles/io.py:110` documents "sigma in nm for
gaussian blur of the spectra". Applied inside `compute_hanatos2025_tc_lut`.

**Current C++.** Struct field plumbed (`spektra.h:223`, JNI `spektra_jni.cpp:389`), init 0
(`spektra.cpp:806`), **never read** in any stage.

**Wire.** In the tc_lut builder, before integrating, convolve `spectra_lut` along the
81-band axis with a Gaussian kernel whose sigma (nm) → bands using the working-shape band
spacing. Reuse `kernels/gaussian` if a 1-D separable variant fits; else a small dedicated
1-D conv. `sigma==0` must be an exact no-op (preserve current goldens).

**Parity.** Oracle golden with `spectral_gaussian_blur > 0`; new `test_filming` case.

**Risk.** Medium. Band-axis convolution + nm→band mapping must match the oracle's kernel
construction exactly.

---

## 3. `use_enlarger_lut`

**What it is.** Opt-in 3-D LUT acceleration of the **enlarger** (print-expose) spectral
integral — the print-side analogue of the already-wired scanner LUT.

**Oracle reference.**
- `spektrafilm/.../runtime/stages/printing.py:53` — `use_lut=self._settings.use_enlarger_lut`.
- `spektrafilm/.../runtime/services/spectral_lut_compute.py:88-104` — enlarger LUT memory +
  build/cache.

**Current C++.** `spektra.h:232` marks it RESERVED. The scanner LUT path
(`kernels/lut3d`, `use_scanner_lut`, gated by `test_scanner_lut_e2e`) is the working
template. Enlarger side declared but unimplemented.

**Wire.** Mirror the scanner-LUT integration in `runtime/stages/printing.cpp`: build/cache
an enlarger 3-D LUT at `lut_resolution`, sample with the existing PCHIP path, guard behind
`p->use_enlarger_lut`. Default OFF → exact non-LUT path unchanged.

**Parity.** Oracle `print_portra` golden with `use_enlarger_lut=true`; new
`test_enlarger_lut_e2e` mirroring `test_scanner_lut_e2e`. (LUT accel is an approximation —
match the oracle's LUT path, not the exact path.)

**Risk.** Medium-low — close clone of a proven, gated path.

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
