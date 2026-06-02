# Audit — incomplete / open items (updated 2026-06-01)

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
- ℹ️ **`apply_hanatos2025_adaptation_window`/`_surface` + `spectral_gaussian_blur`** are
  UI-present (dimmed) + JNI-marshalled but applied by **no** engine stage. Wiring each needs an
  engine change + a new spektrafilm-oracle golden — see **`docs/ENGINE_WIRING_PLAN.md`** for the
  per-param plan (oracle refs, C++ hook points, parity strategy). `use_enlarger_lut` and enlarger
  lens blur are covered there too.
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

- ✅ **`HANDOFF.md` refreshed** (PR #58, "handoff-refresh-postoom") — now reflects v0.6.3 /
  `versionCode 8`, the off-heap export-OOM fix, and the current `main` trunk. The earlier stale
  v0.3.0-wave description no longer applies.
- ✅ **`docs/PRESETS.md` preset count fixed** (2026-06-01) — was "20 curated presets" while
  `presets.json` ships **21** (the `neutral_adobe_like` "Neutral (Adobe-like)" preset, added in
  #55, was undocumented). The intro count, the group list, and `README.md` are now 21, and the
  Neutral preset has its own documented section.
- 🟡 **`spektra.cpp:17`** — comment still says `scan_film=false => print route, TODO M4`, but the
  print route is fully implemented and parity-gated. Stale comment.
- 🟡 **ROADMAP M3 note** (`docs/ROADMAP.md:63,83`) lists "remaining M3 small items: AAssetManager
  path + **non-sRGB wiring**", but non-sRGB output **is** wired (output color space in Settings +
  per-image dropdown → `ParamsState.outputColorSpace`). Only AAssetManager remains.

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

- 🟡 **Release build has `isMinifyEnabled = false`** (`app/build.gradle.kts`). The shipped APK
  carries ~23 MB of un-minified `.dex`. Enabling R8 + `shrinkResources` is the biggest size win but
  needs JNI/engine keep-rules and **on-device validation** (a wrong rule → runtime crash).
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
- ⚪ **`dist/` stops at v0.3.0** (debug-signed APKs). v0.4.0 is tagged on origin and ships via the
  signed `release.yml` workflow as a GitHub Release asset — worth confirming that Release + its
  signed APK actually exist/published.
- ⚪ **Local tags `v0.1.0`/`v0.2.0` were never pushed** to origin (origin has only `v0.3.0`,
  `v0.4.0`) — minor history gap.

## E. Dead code / cleanup

- ⚪ **`NotYetActiveNote` in `Widgets.kt`** has no *direct* call sites — but it is **not** dead:
  its wrapper `GatedBlock` (same file) calls it and is used twice in `MainActivity.kt` to gate
  genuinely-inert params (the hanatos2025 adaptation toggles + spectral Gaussian blur, and the
  enlarger lens blur — "no engine call site"). So the widget stays until those params are wired or
  removed. (Correction: an earlier draft of this audit wrongly listed it as dead code.)

---

### Highest-value next actions (suggested, not committed)
1. Refresh or retire **`HANDOFF.md`** (🔴 — actively misleading).
2. Add **`test_output_spaces` + `test_lensblur`** to the `engine-parity` CI gate (🟡 — closes a
   real bit-exact coverage hole with existing tests).
3. First **Kotlin unit tests** for the pure-logic layer (`EditHistory`, sidecar JSON round-trip,
   `ExportFormat`) — no device needed (🔴 coverage, 🟡 effort).
4. Maintainer/device items: R8 enablement + the #5 device smoke test (need a real device).
