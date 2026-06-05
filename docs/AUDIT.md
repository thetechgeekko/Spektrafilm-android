# Audit — incomplete / open items (updated 2026-06-05)

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
- ✅ **Print EXPOSURE-COMPENSATION midgray balance (`exposure_compensation_ev`) +
  `normalize_print_exposure` / `print_exposure_compensation`** FIXED/WIRED (this PR). The
  user-reachable bug: on the PRINT route (`scan_film=false`, default "Print auto compensation"
  ON) the native midgray exposure factor (`runtime/print_digest.cpp::compute_midgray_exposure_factor`)
  hardcoded `exposure_compensation_ev == 0` and **always returned the UNcompensated factor**, so
  whenever the user set Camera EV ≠ 0 the print was balanced differently than the oracle. The two
  enlarger toggles were never consumed at all. Port of
  `PrintingStage._compute_exposure_factor_midgray` (`runtime/stages/printing.py` c1d0e44 L104-118):
  the factor is selected by a 4-case branch over (`print_exposure_compensation`,
  `normalize_print_exposure`) using the UNcompensated midgray (rgb=0.184) AND the COMPENSATED
  midgray (rgb=0.184·2^`exposure_compensation_ev`) from
  `FilmingStage._compute_density_spectral_midgray_to_balance_print`
  (`runtime/stages/filming.py` c1d0e44 L125-134) — the compensated gray uses the SAME camera EV
  that scales the filming raw (filming.py L57). The branch:
  `comp && !normalize → factor_midgray_comp/factor_midgray`;
  `normalize && comp → factor_midgray_comp`; `normalize && !comp → factor_midgray`; `else → 1.0`.
  The factor is recomputed fresh on every print call (not cached), so the new params need NO
  cache-key change — the film-density memo gates the negative only, which does not depend on these
  toggles. **CRITICAL default-no-op:** at `exposure_compensation_ev == 0` the compensated gray
  equals the uncompensated gray, so the factor is byte-identical to before for every existing EV=0
  print golden (verified: full engine-parity suite `fail=0`, all pre-existing print goldens
  bit-exact). Gated by two goldens (oracle **c1d0e44**, both EV=1.5): `print_portra_evcomp`
  (comp+normalize ON → `factor_midgray_comp`) and `print_portra_evcomp_nonorm` (comp ON, normalize
  OFF → `factor_midgray_comp/factor_midgray`) pinning both EV-active branches + `test_print_evcomp_e2e`
  in CI `engine-parity` (which also asserts EV on-vs-off and norm-vs-no-norm each produce a real
  delta). The UI sliders/toggles were already live + JNI-marshalled; only the engine consumption
  was missing.
- 🔎 **Full param-wiring audit (2026-06-05).** A 3-crew end-to-end sweep (filming / printing /
  scanning+geometry+metering) traced EVERY `spk_params` field UI→facade→JNI→engine-consumer and
  cross-checked the oracle at `c1d0e44`. Most params are correctly WIRED (exposure, both density
  gammas, AE + all 7 metering methods, full grain model, DIR active/amount/inhibition, UV/IR,
  spectral blur, hanatos window/surface, output color spaces + CCTF, geometry incl. the `<1` AA
  prefilter, scanner/enlarger LUTs, preflash trio, dichroic Y/M, print exposure/gamma, scanner b/w
  corrections route-gated correctly). **Open findings (NOT yet fixed; severity-ranked):**
  - 🔴 **`rgb_to_raw_method = MALLETT2019` — MIS-WIRED (filming).** UI dropdown offers it and the
    oracle has a real `rgb_to_raw_mallett2019` (`utils/spectral_upsampling.py:283`), but **no
    Mallett path exists in C++** — the value only perturbs the tc_lut cache key; the engine always
    runs Hanatos2025. Selecting Mallett silently changes nothing. **Decision pending: implement
    (new spectral basis + oracle golden) vs remove the dropdown option.**
  - 🟠 **Highlight-boost trio `halation.boost_ev`/`boost_range`/`protect_ev` — INERT (filming).**
    The oracle's `boost_highlights` stage (`filming.py:58-60`, called unconditionally, gated only
    on `boost_ev>0`, independent of halation) is entirely UNPORTED (`diffusion.cpp:55` literally
    "Not applied here"). Three live sliders do nothing. Default `boost_ev=0` is identity so goldens
    stay green. Fix: port `numba_boost_hightlights.boost_highlights` into `expose`, gate `>0`, new
    golden (boost_ev>0). **Pure parity fix, no decision needed.**
  - 🟡 **Spatial-effects conflated under `halation_active` — PARTIAL (filming+scanning).** Camera
    lens-blur, camera diffusion filter (Black-Pro-Mist), DIR spatial diffusion, scanner unsharp +
    scanner lens-blur are ALL gated on the single `halation_active` toggle (`spatial =
    (halation_active != 0)`), whereas the oracle gates each independently (`deactivate_spatial_effects`
    is a debug flag, default False). Turning halation OFF silently kills them. Schema-default
    `scanner_unsharp=(0.7,0.7)` therefore no-ops unless halation on. Lens-blur sliders disclose this
    in tooltips; the **camera diffusion filter does not**. **Decision pending: keep the
    "halation = master spatial switch" design (+ disclose) vs decouple to per-effect gating (more
    correct, new "effect-on/halation-off" golden).**
  - 🟡 **Print route hard-forces film grain + spatial OFF — PARTIAL (filming).** `run_print` forces
    the negative's `spatial_effects=false` and never digests grain (`spektra.cpp` ~1007-1021),
    pinned to the `print_portra` goldens (which set `deactivate_spatial_effects=True`). The oracle's
    normal print path does NOT deactivate them, so a user printing loses all film grain/halation.
    **Decision pending: keep (lower risk) vs honor toggles on the print route (new print-route
    golden with grain/spatial on).**
  - ⚪ **Dead-but-oracle-consistent UI controls (UX honesty, NOT parity).** DIR-coupler gamma
    sliders (`gamma_samelayer_rgb`, 3× `gamma_interlayer_*`) — film-baked, the oracle overwrites
    them per-film too; `enlarger_lens_blur` — the oracle never consumes `enlarger.lens_blur` either
    (vestigial upstream); film-side `glare_*` (`glare_active/percent/roughness/blur`) — dead on the
    `scan_film` route and dead upstream (`film_render.glare` declared but never read). All present
    as live UI controls that do nothing. **Decision pending: dim+disclose vs remove.**
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
- ✅ **POSITIVE-film DIR-coupler parity gap RESOLVED (2026-06-04).** Surfaced by #74: the
  `scan_film` route on a POSITIVE film (e.g. `fujifilm_provia_100f`) with DIR couplers ON
  diverged ~0.32 from the oracle, while couplers-OFF matched to ~2.4e-7 — so the gap was isolated
  to the positive-film coupler develop. Root cause: the coupler *math* in `model/couplers.cpp`
  (positive branch: `density_silver = nanmax(density_curves) - density`, interpolate the
  `-density` curve) was already correct — fed the oracle's exact inputs it reproduced the result
  to 2.38e-7. The divergence was the **inhibitor matrix**: `runtime/params.cpp`'s
  `digest_filming_params` ported the negative/positive coupler-gamma branch of
  `params_builder._apply_film_specifics` (lines 112-122) but **omitted the per-stock override**
  (lines 149-158) that overwrites the generic positive default `(0.12,0.08,0.06)` with the
  provia values `gamma_samelayer_rgb=(0.156,0.104,0.078)` (and matching interlayer terms;
  `fujifilm_velvia_100` likewise). The digest had no access to the stock string. Fix: thread the
  profile `info.stock` into `digest_filming_params` and apply the velvia/provia gamma overrides
  AFTER the positive branch (mirroring the oracle order). With the right matrix the full pipeline
  matches: `film_density_cmy` 0.3206 → 2.38e-7, `final_rgb` 0.158 → 2.27e-7. The override only
  matches the two Fuji positive stocks, so every pre-existing (negative-film) golden reproduces
  bit-exactly. Gated by the new `scan_provia_couplers` golden (oracle **c1d0e44**, couplers ON) +
  `test_provia_couplers_e2e` in CI `engine-parity` (also asserts couplers on-vs-off ACTIVE and
  byte-identical at `SPK_NUM_THREADS` 1 vs 8).
- ✅ **Highlight boost (`halation.boost_ev` / `boost_range` / `protect_ev`) WIRED (2026-06-05).**
  Found inert: the "Boost EV" / "Boost range" / "Protect EV" sliders (`MainActivity.kt:2447`) were
  live + JNI-marshalled (`spektra.h:186-188`, `getBoostEv/Range/ProtectEv`) and reached the native
  `HalationParams`, but the engine **dropped them** (`diffusion.cpp` Step 1 was an identity stub),
  so the sliders did nothing — the one remaining unported piece of engine math
  (`utils/numba_boost_hightlights.py`). Now ported: `model/diffusion.cpp::apply_highlight_boost`
  reproduces `boost_highlights` (midgray=0.184), called from `filming.cpp::expose` right after the
  exposure-comp scale and BEFORE the diffusion filter / lens blur / halation (filming.py:58-60). It
  lifts every raw value above `raw_x0 = clip(0.184·2^protect_ev, 0, max(raw))` by
  `k·max_raw·(exp(a·dx)−a·dx−1)`, `a = 28^(1−boost_range)`,
  `k = (2^boost_ev−1)/(exp(a(1−x0))−a(1−x0)−1)`. It is NOT a spatial effect (the oracle's
  `params_builder` zeroes only the scatter/halation sigmas under `deactivate_spatial_effects`,
  never `boost_ev`), so the three params are threaded into `fparams.halation` in BOTH routes
  regardless of the spatial flag, and folded into `compute_film_cache_key` (the print-route memo).
  `boost_ev == 0` (schema/UI default) is a strict no-op → every pre-existing golden reproduces
  bit-exactly. Gated by the new `scan_portra_boost` golden (oracle **c1d0e44**) +
  `test_highlight_boost_e2e` in CI `engine-parity` (film taps + final_rgb within tol, boost
  on-vs-off ACTIVE, and byte-identical at `SPK_NUM_THREADS` 1 vs 8). **This was the last inert
  engine param; no UI-exposed engine param is now a silent no-op.**
- ⚪ **Glare-on-print** wired but default-OFF and not bit-exact (stochastic per-pixel lognormal) —
  by design, can't be parity-gated.
- ✅ **Downscale (`upscale_factor < 1`) anti-aliasing prefilter (RESOLVED).** The UI exposes
  `upscale_factor` as a `0..4` slider (`MainActivity.kt:2129`), so sub-unity (minifying) values are
  reachable on the parity-gated `crop_and_rescale` preprocess (NOT the preview proxy — that is the
  separate `preview_max_size` path). At oracle `c1d0e44`, `ResizingService.crop_and_rescale`
  (`runtime/services/resize.py:25`) calls `skimage.transform.rescale(image, factor, order=3)` with
  `anti_aliasing` left at its default. skimage 0.26 resolves `anti_aliasing=None` to **True** whenever
  an output dimension shrinks with `order>0` (`transform/_warps.py:178-183`), running
  `scipy.ndimage.gaussian_filter` with `sigma = max(0, (input/output - 1)/2)` per axis, `mode='mirror'`
  (`_warps.py:195-214`) BEFORE the cubic zoom. The C++ `rescale_cubic_rgb` previously skipped this, so a
  full-pipeline minifying case diverged from the oracle by `final_rgb` max_abs **1.78e-1** /
  `film_density_cmy` **3.95e-1**. Fix: port scipy's gaussian_filter1d kernel
  (`radius = int(4*sigma + 0.5)`, normalised `exp(-0.5 k²/σ²)`, `mirror` boundary) as a separable
  prefilter in `crop_resize.cpp`, applied only when `factor < 1` (`sigma == 0` for `factor >= 1`, so the
  upscale path stays byte-identical and every pre-existing golden reproduces bit-exactly). After the fix
  the minifying case matches `c1d0e44` to `final_rgb` max_abs **5.96e-8** / `film_density_cmy`
  **2.38e-7**. Gated by the new `scan_portra_downscale` golden (oracle `c1d0e44`) +
  `test_downscale.cpp` in CI `engine-parity` (asserts the 32×32 geometry, both taps within tol, with a
  genuine on-vs-off delta).
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
  `PngWriter`, `native <methods>`) and enum value/`valueOf` persistence.
  **On-device release-build smoke — DONE (2026-06-04, SM-S948W / Android 16 / arm64).** A
  minified release APK ran full RAW import → preview render (`render mode=preview … 187–268ms`) →
  full-res **12 MP PNG and TIFF export** (`export format=PNG/TIFF 3000x4000 12000000px ok`), with
  `libsftiff.so` loading via `nativeloader … ok` immediately before the TIFF write — i.e. the
  name-based JNI keep-rules (engine + RAW/TIFF/PNG writers) all resolve at runtime under R8, no
  `UnsatisfiedLinkError`/crash/OOM. Captured via the in-app diagnostics `Spektra` breadcrumbs.
  Remaining: Stage-2 obfuscation; `shrinkResources` not yet enabled.
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
3. **Resolve the open param-wiring-audit findings (2026-06-05, see §A "Full param-wiring audit").**
   ✅ #1 print EV-compensation + `normalize_print_exposure` — FIXED (#80). Remaining, in order:
   (a) 🟠 port the highlight-boost stage (`boost_ev`/`boost_range`/`protect_ev`) — pure parity fix,
   no decision; (b) 🔴 MALLETT2019 — implement-vs-remove decision; (c) 🟡 spatial-effects per-effect
   gating (decouple from `halation_active`) — keep-vs-decouple decision; (d) 🟡 print-route
   grain/spatial — keep-vs-honor decision; (e) ⚪ dim/disclose-or-remove the dead-but-oracle-consistent
   sliders (DIR gammas, enlarger lens blur, film glare). Each engine fix needs a new oracle golden at
   `c1d0e44` + the default-no-op/thread-invariant discipline.
4. **Instrumented (`androidTest`) coverage** for the JNI/marshalling + export-quantisation paths the
   JVM tests can't reach (needs a device/Robolectric).
5. Maintainer/device items: the R8 release-build on-device smoke is **DONE** (2026-06-04, see §D —
   import→render→full-res PNG/TIFF export validated under minify). Remaining: a screen-unlocked
   subjective visual re-confirm pass; R8 Stage-2 obfuscation; `shrinkResources`.
