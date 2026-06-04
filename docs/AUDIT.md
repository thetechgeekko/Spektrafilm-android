# Audit — incomplete / open items (updated 2026-06-03)

A sweep of the codebase and docs for things that are **not complete**: genuine feature gaps,
stale docs that misstate status, test-coverage holes, and release/build follow-ups. Grouped by
type, with severity (🔴 notable · 🟡 worth doing · ⚪ minor/by-design). This is a status snapshot,
not a commitment to do all of it.

## A. Feature gaps (engine / app)

- ✅ **AAssetManager path wired (2026-06-01).** The native engine now reads profiles, the spectral
  LUT, and neutral filters **directly from the APK** via `AAssetManager`, so the ~17 MB first-run
  extraction to `filesDir` is skipped. New C API `spk_engine_create_asset_manager(void*, ...)` +
  JNI `nativeCreateFromAssets(AssetManager)` + Kotlin `SpektraEngine.fromAssets(assets)`;
  `MainActivity` tries `fromAssets` first and falls back to the old extract path on failure. All
  AAsset code is `#ifdef __ANDROID__`-guarded so the host parity build is unchanged; the three
  loaders gained `parse_*`-from-bytes entry points with the path-based APIs kept as thin wrappers.
  **Verified:** on-device arm64 parity still `ALL PASS` (max_abs 5.96e-08, FS path byte-identical);
  fresh-data launch on SM-S948W creates the engine with **no `files/spektra` extraction** and no
  crash (i.e. `fromAssets` succeeded, no fallback). The long-standing M3 remainder is closed.
  (Full render/export visual re-confirm pending a screen-unlocked device pass.)
- 🟡 **Memory tiling for very large RAW** (old issue #7) — still open. Only app-side mitigation
  exists (OOM-retry ladder in `decodeRawToLinear`, opt-in half-size decode). No native tiling /
  streaming / GPU path. The full-res scan holds several float buffers of `npix*3` at once.
- ✅ **`use_enlarger_lut` wired (2026-06-01).** The enlarger-side 3D-LUT now accelerates the
  print-expose spectral integral in `printing.cpp::print_expose` (PCHIP LUT of
  `_film_cmy_to_print_log_raw` over `[-grain.density_min, nanmax(film.density_curves)]`),
  mirroring the scanner LUT and the oracle's `spectral_compute_enlarger`. Opt-in / default-off →
  the default path is **byte-identical** (verified: `test_simulate_e2e` `print_portra` unchanged
  at 5.11e-07). LUT path converges with resolution (res 17 ≈1.1e-4, res 64 ≈1.9e-6 vs direct) —
  the print-expose integral is less smooth than the scanner's, so its bands are looser. Gated by
  the new `test_enlarger_lut_e2e` in CI `engine-parity`. No reserved engine LUT flag remains.
- ⚪ **Enlarger lens blur** intentionally unwired (no oracle call site to validate against).
- ✅ **`spectral_gaussian_blur`** WIRED. `build_filming_tc_lut` blurs the Hanatos2025 spectra LUT
  along its spectral axis (scipy.ndimage.gaussian_filter sigma=(0,0,σ), mode='reflect',
  truncate=4.0) before the sensitivity contraction, matching `compute_hanatos2025_tc_lut`. Default
  0.0 is a strict no-op (all pre-existing goldens reproduce bit-exactly). The UI slider is
  un-gated. Gated by the `scan_spectral_blur` golden (oracle **c1d0e44**) + `test_spectral_blur_e2e`
  in CI `engine-parity`.
- ✅ **`apply_hanatos2025_adaptation_window`/`_surface`** WIRED. `build_filming_tc_lut` now threads
  both toggles: `apply_window` (default on) folds the white-balance-preserving erf4 band-pass into
  the per-channel sensitivity before the spectra contraction; `apply_surface` (default off)
  multiplies the resulting tc_lut per-cell, per-channel by `2**surface`, where `surface` is the
  poly4 log-exposure-correction surface (`eval_poly4_log_exposure_surface`) evaluated at the film
  reference-illuminant chromaticity, matching `compute_hanatos2025_tc_lut`'s apply_surface branch.
  The profile loader parses `hanatos2025_adaptation_surface_params`; the tc_lut + print-route memo
  cache keys fold both toggles. The default (window on, surface off) is a strict no-op (all
  pre-existing goldens reproduce bit-exactly). Both UI toggles are un-gated. Gated by the
  `scan_portra_surface` + `scan_portra_nowindow` goldens (oracle **c1d0e44**) +
  `test_hanatos_surface_e2e` in CI `engine-parity`.
- ✅ **Camera UV/IR cut band-pass (`camera.filter_uv` / `filter_ir`)** WIRED (2026-06-04).
  `build_filming_tc_lut` now applies the camera UV/IR cut filter to the profile sensitivity BEFORE
  the spectra contraction, mirroring `FilmingStage._rgb_to_film_raw` →
  `model/color_filters.py::compute_band_pass_filter`: `band = filter_uv*filter_ir` (each
  `1-amp + amp*sigmoid_erf`, amp clipped to [0,1]) multiplies the sensitivity with a per-channel
  white-balance normalisation against the film reference illuminant. Gated on
  `filter_uv[0] > 0 || filter_ir[0] > 0` exactly like the oracle, so the default amplitudes (0,0)
  are a strict no-op (all pre-existing goldens reproduce bit-exactly). The UI sliders ("UV filter"
  / "IR filter") were already present + JNI-marshalled; only the engine consumption was missing.
  The tc_lut + print-route memo cache keys fold the band-pass triples. Gated by the
  `scan_portra_uvir` golden (oracle **c1d0e44**) + `test_camera_uvir_e2e` in CI `engine-parity`.
  This was the last self-contained §1 LUT-build-path wiring; §4 (enlarger lens blur) stays unwired
  by design.
- ✅ **Enlarger preflash (`preflash_exposure` / `preflash_y_filter_shift` / `preflash_m_filter_shift`)**
  WIRED (2026-06-04). `printing.cpp::print_expose` now adds the preflash raw term to the print
  exposure, mirroring `printing.py::_compute_raw_preflash` +
  `runtime/services/filter_enlarger_source.py::preflash_filtered_illuminant`: a uniform
  pre-exposure flash of the print paper. The preflash illuminant is
  `color_enlarger(enlarger source, CC=[c_neutral, m_neutral+preflash_m, y_neutral+preflash_y])`
  (its OWN filter shifts, NOT the image-exposure shifts); the constant per-channel
  `raw_preflash = sum_l 10^-base_density[l] * preflash_illuminant[l] * sens[l,k]` is scaled by
  `preflash_exposure` and added to the print raw AFTER the midgray factor, BEFORE the log10.
  Gated on `preflash_exposure > 0` exactly like the oracle, so the default (0) is a strict no-op
  (all pre-existing goldens reproduce bit-exactly). Print route only — affects `print_density_cmy`
  + `final_rgb`, NOT the film taps, so it is correctly NOT folded into the film-density memo key
  (`print_expose` re-runs with live preflash params on every call; a film-cache HIT serves the
  unchanged negative). The UI sliders were already live + JNI-marshalled; only the engine
  consumption was missing. Gated by the `print_portra_preflash` golden (oracle **c1d0e44**) +
  `test_preflash_e2e` in CI `engine-parity`.
- ✅ **Scanner black/white corrections** (`scanner_white_correction` / `_black_correction` /
  `_white_level` / `_black_level`) **WIRED (this PR).** Port of
  `runtime/services/color_reference.py` (`ColorReferenceService`) +
  `runtime/pipeline.py:45-46`, new native module `runtime/color_reference.{h,cpp}`. A scan-time
  tone anchor that maps the measured CIE Y at the reference black/white densities to the
  sRGB-decoded target levels via the shared affine `y_corrected = clip(m*Y+q, 0, 1)`
  (`_correction_fucntion`), where `m=(white_level-black_level)/(y_white-y_black+1e-10)`,
  `q=black_level-m*y_black`. It is applied in two coupled places, each route-gated:
  (a) the **scanning XYZ correction** (`black_white_xyz_correction`, every route except
  `scan_film`+negative film): per pixel `xyz *= clip(m*Y+q,0,1)/(Y+1e-10)` in `scanning.cpp`;
  (b) an **exposure correction** re-anchoring midgray — the **printing** exposure correction
  (`black_white_printing_exposure_correction`, print route + negative paper) folded into the
  already-plumbed `PrintingParams::bw_exposure_correction`, and the **filming** exposure
  correction (`black_white_filming_exposure_correction`, `scan_film`+positive film) folded into a
  new `FilmingParams::bw_exposure_correction` (`raw *= factor` after halation, before log10). The
  level decode reproduces colour's `_remove_sRGB_cctf` bit-for-bit (cctf decode × the
  near-identity `RGB_to_RGB(sRGB,sRGB)` mean row-sum residue `1.0000282666666667`). Defaults
  (both corrections false) are a STRICT no-op — every pre-existing golden reproduces bit-exactly,
  and the `scan_film`+negative route (the default scan goldens) is a no-op by construction. NOT
  folded into the film-density memo key: on the print route the corrections only touch
  `print_expose`/`scan`, not the negative's film density (filming correction is 1.0 for negative
  film), so a film-cache HIT serves the unchanged negative; the `scan_film` route never consults
  that cache. The UI sliders were already live + JNI-marshalled; only the engine consumption was
  missing. Gated by two goldens (oracle **c1d0e44**): `print_portra_bwcorr` (print route: printing
  exposure + XYZ corrections) and `scan_provia_bwcorr` (`scan_film`+positive film: filming
  exposure + XYZ corrections, DIR couplers off to isolate the un-gated positive-film coupler
  branch) + `test_scanner_bwcorr_e2e` in CI `engine-parity`. **This closes audit-action-#2: all
  named inert engine params are now wired or explicitly resolved.**
- ⚪ **Glare-on-print** wired but default-OFF and not bit-exact (stochastic per-pixel lognormal) —
  by design, can't be parity-gated.
- ⚪ **Downscale (`upscale_factor < 1`) anti-aliasing prefilter** — documented follow-up; the cubic
  rescale has no low-pass prefilter for minification.
- ⚪ **GPU preview accelerator** — a default-OFF experimental GPU LUT preview path now exists
  (`LutGpuPreview.kt`, Settings → Experimental; renderer + cube parser unit-tested), but it is
  **unverified on a real GPU** (no GPU/emulator in CI) and the GPU surface lacks zoom/magnifier/
  compare. Needs on-device verification before promotion. **EXR / 32-bit-float TIFF export** —
  still deferred M6/M7.
- ⚪ **`RawCoilDecoder`** uses a "naive ACES→sRGB approximation" (`RawCoilDecoder.kt:75`, TODO) for
  the optional Coil gallery-decode path; not the main edit pipeline.

## B. Stale / inaccurate docs (status drift)

- ✅ **`HANDOFF.md` current** — reflects v0.7.0 / `versionCode 9`, the AAssetManager + enlarger-LUT
  completion, and the off-heap export-OOM fix. The earlier stale v0.3.0-wave description no longer
  applies.
- ✅ **`docs/PRESETS.md` preset count fixed** (2026-06-01) — was "20 curated presets" while
  `presets.json` ships **21** (the `neutral_adobe_like` "Neutral (Adobe-like)" preset, added in
  #55, was undocumented). The intro count, the group list, and `README.md` are now 21, and the
  Neutral preset has its own documented section.
- 🟡 **`docs/ROADMAP.md` is frozen pre-v0.5.0** — it still flags `use_enlarger_lut` as
  reserved/unwired (×4), AAssetManager as the sole remaining M3 item, and a "debug-signed release"
  blocker. All three are shipped (enlarger LUT wired + gated; AAssetManager wired; v0.7.0 signed via
  `release.yml`). ROADMAP needs status flips + a v0.5.0/v0.6.x/v0.7.0 progress note.

## C. Test-coverage gaps

- 🟡 **JVM unit tests exist but instrumented (`androidTest`) coverage is still absent.**
  `:app:testDebugUnitTest` now ships **6 suites / 30 tests, all green** (verified on-device-class
  toolchain 2026-06-01): `EditHistoryTest` (undo/redo store), `PresetsRoundTripTest`
  (recipe/sidecar serialize↔parse), `ToneCurveParamsTest`, `CubeLutTest` (GPU-preview LUT parser),
  `McrawContainerTest` (`.mcraw` footer parser), `AppUpdaterTest`. The earlier "no Kotlin tests
  anywhere" claim is **resolved**. Remaining holes: no automation for `ImagePipeline` export
  quantisation, `DecodedSourceCache`, `EngineHelpers`, or `RawDecoder`/`PngWriter` JNI marshaling
  (these need Robolectric or an instrumented device run). Only C++ host tests + the LibRaw
  C++ unit/fuzz tests cover the native layer.
- ✅ **(Resolved 2026-06-01) `test_output_spaces`, `test_lensblur`, `test_parallel` are now gated**
  in CI `engine-parity` (`.github/workflows/ci.yml`): all six output color spaces, camera/scanner
  lens-blur parity, and thread-count invariance run on every push/PR. The earlier "not gated" /
  stale-`HANDOFF` claims no longer apply.
- 🟡 **Parity tests that exist but are still NOT gated:**
  - `test_params_passthrough`, `test_bake_lut` — not gated.
  - (`test_printing`/`test_scanning` are effectively subsumed by `test_simulate_e2e`; `test_grain`/
    `test_grain_sublayer` are statistical and run locally; `test_spectral_upsampling` needs the
    source-tree `.lut`.)
- 🟡 **`android-emulator` CI job** is gated to manual `workflow_dispatch` + `continue-on-error` (no
  `/dev/kvm` on hosted runners) — there is no automated emulator/device smoke test on push.

## D. Release / build follow-ups

- ✅ **R8 enabled (2026-06-04, `a28d30d`).** `isMinifyEnabled = true` (`app/build.gradle.kts:53`),
  **Stage 1 — shrink only, `-dontobfuscate`** (`app/proguard-rules.pro`), with keep-rules for the
  four name-based JNI boundaries (`com.spectrafilm.engine.**`, `RawDecoder`, `TiffWriter`,
  `PngWriter`, `native <methods>`) and enum value/`valueOf` persistence. Remaining: on-device
  release-build smoke (JNI keep-rules surface only at runtime) and Stage-2 obfuscation. `shrinkResources`
  not yet enabled.
- ✅ **Device smoke test — DONE** (issue #5, 2026-05-31, Galaxy S25 Ultra / Android 16 / arm64;
  see `docs/DEVICE_TEST_REPORT.md`). Native libs load; 16-bit PNG/TIFF + Ultra HDR exports verified;
  **Samsung Expert RAW decodes via LibRaw**; source EXIF retained, GPS stripped. It also **found a
  real bug** — exports were capped at the 2048 px preview size (12 MP → ~3 MP), fixed in PR #21.
  Still open from that pass: lossy/JPEG-XL DNG **fallback** branch, the GPS Settings toggle, and the
  subjective visual checks (rotate→export orientation, presets/grain/AE, recipe persistence).
- ✅ **Re-validated on-device at v0.6.3 (2026-06-01, same SM-S948W / Android 16 / arm64).** Fresh
  `:app:assembleDebug` (JBR 21 + NDK r27 + build-tools 35) → install → launch (COLD 404 ms, no
  `UnsatisfiedLinkError`; `libspektra.so` loads on arm64) → **full-resolution export = 3060×4080
  with NO `OutOfMemoryError` / no `149817619`** (the PR #56 off-heap export fix holds). Default
  export is 8-bit PNG **by Settings choice** (the 16-bit path is `ExportFormat.PNG16`/TIFF). Lint
  (`:app:lintDebug`) and the 30 JVM unit tests are green on the same toolchain.
- 🟡 **On-device NEON SIMD speedup magnitude** (the `exp10` work) is still unmeasured — only x86 was
  profiled; a device timing of a large-RAW export would confirm it.
- ✅ **Committed `dist/*.apk` removed (2026-06-03).** The repo no longer commits release binaries;
  they were stale (v0.1–0.3 while the repo was v0.7.0), **16 KB-page-misaligned** (every project
  `.so` LOAD-aligned `0x1000` → `dlopen`-fail on Android 15 16 KB devices) and **debug-signed**.
  Releases ship via the signed `release.yml` workflow as GitHub Release assets; the `!dist/*.apk`
  un-ignore was removed so they don't return.
- ⚪ **Local tags `v0.1.0`/`v0.2.0` were never pushed** to origin — minor history gap.

## E. Dead code / cleanup

- ⚪ **`NotYetActiveNote` in `Widgets.kt`** has no *direct* call sites — but it is **not** dead:
  its wrapper `GatedBlock` (same file) calls it and is still used to gate the **enlarger lens blur**
  (§4 — "no engine call site", intentionally unwired). The spectral Gaussian blur and the
  hanatos2025 adaptation window/surface toggles were previously gated here but are now wired and
  un-gated. So the widget stays as long as §4 (or any future inert param) is gated. (Correction: an
  earlier draft of this audit wrongly listed it as dead code.)

---

### Highest-value next actions (suggested, not committed)
1. ✅ **Re-sync the frozen docs** to v0.7.0 — `docs/RELEASE_CHECKLIST.md` rewritten to the
   `release.yml` signed-tag flow (no `dist/`) with the explicit 16 KB-page gate, and the stale
   `docs/ROADMAP.md` status markers flipped. Both also reflect R8 Stage-1 shrink now being enabled.
2. **Wire or strip the remaining inert engine params.** Investigated end-to-end against the pinned
   oracle (c1d0e44); none were stale and none were "no oracle call site" (C). Status:
   - `apply_hanatos2025_*` (window/surface) — **already WIRED** (#69, verified). No action.
   - `spectral_gaussian_blur` — **already WIRED** (#68). No action.
   - **camera UV/IR cut (`camera.filter_uv`/`filter_ir`) — WIRED (2026-06-04, #72).** Lowest-risk
     wireable param: self-contained in the LUT-build path, default-off no-op, full 4-gate parity
     (`scan_portra_uvir` golden + `test_camera_uvir_e2e`). See §A.
   - **`preflash_exposure` / `preflash_y/m_filter_shift` — WIRED (2026-06-04, this PR).** Oracle
     call site: `runtime/stages/printing.py:92-101` (`_compute_raw_preflash`) +
     `runtime/services/filter_enlarger_source.py:29-32`. Print-route only; `printing.cpp::print_expose`
     adds the constant preflash raw 3-vector to the print expose (after the midgray factor, before
     log10), built from the preflash-filtered illuminant + film base density + print sensitivity.
     Default-off no-op, full 4-gate parity (`print_portra_preflash` golden pinned to c1d0e44 +
     `test_preflash_e2e`). See §A.
   - **`scanner_white_correction` / `_black_correction` / `_white_level` / `_black_level` —
     WIRED (this PR), the LAST #2 follow-up.** Oracle call site:
     `runtime/services/color_reference.py` (`ColorReferenceService`) + `runtime/pipeline.py:45-46`,
     ported to `runtime/color_reference.{h,cpp}`. Spans THREE stages (filming exposure correction
     for `scan_film`+positive film, printing exposure correction for the print route + negative
     paper, scanning XYZ correction for every route except `scan_film`+negative) via the shared
     affine `clip(m*Y+q,0,1)`. Default-off strict no-op; full 4-gate parity with TWO goldens pinned
     to c1d0e44 (`print_portra_bwcorr` + `scan_provia_bwcorr`) + `test_scanner_bwcorr_e2e`. **With
     this, every named inert engine param from action #2 is resolved — action #2 is CLOSED.** See §A.
3. **Instrumented (`androidTest`) coverage** for the JNI/marshalling + export-quantisation paths the
   JVM tests can't reach (needs a device/Robolectric).
4. Maintainer/device items: a release-build (R8-enabled) on-device smoke + screen-unlocked visual
   re-confirm pass; Stage-2 obfuscation. (R8 Stage-1 shrink itself is now enabled — see §D.)
