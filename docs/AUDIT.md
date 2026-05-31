# Audit — incomplete / open items (2026-05-31)

A sweep of the codebase and docs for things that are **not complete**: genuine feature gaps,
stale docs that misstate status, test-coverage holes, and release/build follow-ups. Grouped by
type, with severity (🔴 notable · 🟡 worth doing · ⚪ minor/by-design). This is a status snapshot,
not a commitment to do all of it.

## A. Feature gaps (engine / app)

- 🟡 **AAssetManager path not wired** (`spektra.cpp:828`, `spektra_jni.cpp:413`). The engine can't
  read assets directly from the APK; `EngineHelpers.extractAssets()` copies the whole `spektra/`
  tree to `filesDir` on first launch. Works, but costs first-run time + duplicate on-device
  storage. Long-standing M3 remainder.
- 🟡 **Memory tiling for very large RAW** (old issue #7) — still open. Only app-side mitigation
  exists (OOM-retry ladder in `decodeRawToLinear`, opt-in half-size decode). No native tiling /
  streaming / GPU path. The full-res scan holds several float buffers of `npix*3` at once.
- ⚪ **`use_enlarger_lut` reserved/unwired** (`spektra.h:232`) — the scanner 3D-LUT is wired and
  opt-in; the enlarger-side LUT accel is declared but not implemented.
- ⚪ **Enlarger lens blur** intentionally unwired (no oracle call site to validate against).
- ⚪ **Glare-on-print** wired but default-OFF and not bit-exact (stochastic per-pixel lognormal) —
  by design, can't be parity-gated.
- ⚪ **Downscale (`upscale_factor < 1`) anti-aliasing prefilter** — documented follow-up; the cubic
  rescale has no low-pass prefilter for minification.
- ⚪ **GPU preview accelerator**, **EXR / 32-bit-float TIFF export** — explicitly deferred M6/M7.
- ⚪ **`RawCoilDecoder`** uses a "naive ACES→sRGB approximation" (`RawCoilDecoder.kt:75`, TODO) for
  the optional Coil gallery-decode path; not the main edit pipeline.

## B. Stale / inaccurate docs (status drift)

- 🔴 **`HANDOFF.md` is stale** — describes the v0.3.0 wave (HEAD `b12492e`, branch
  `claude/sharp-allen-I7wQK`, draft PR #8, "remaining = 5 external gates", dist debug-signed). It
  predates v0.4.0, the signed-release workflow, M6 threading (#13), the vector `exp10` SIMD (#16),
  and the APK-size cleanup (#15). Should be refreshed or retired.
- 🟡 **`spektra.cpp:17`** — comment still says `scan_film=false => print route, TODO M4`, but the
  print route is fully implemented and parity-gated. Stale comment.
- 🟡 **ROADMAP M3 note** (`docs/ROADMAP.md:63,83`) lists "remaining M3 small items: AAssetManager
  path + **non-sRGB wiring**", but non-sRGB output **is** wired (output color space in Settings +
  per-image dropdown → `ParamsState.outputColorSpace`). Only AAssetManager remains.

## C. Test-coverage gaps

- 🔴 **No Kotlin/JVM or instrumented (`androidTest`) tests anywhere.** The entire app + library
  Kotlin layer is untested by automation — export (`ImagePipeline`), recipe/sidecar persistence,
  undo/redo (`EditHistory`), `DecodedSourceCache`, `EngineHelpers`, `RawDecoder` JNI marshaling,
  `PngWriter` JVM overload. Only C++ host tests + the LibRaw C++ unit/fuzz tests exist.
- 🟡 **Parity tests that exist but are NOT run in CI `engine-parity`:**
  - `test_output_spaces` — only **sRGB** is gated (via `test_simulate_e2e`); Adobe RGB / ProPhoto /
    Rec.2020 / ACES2065-1 / linear sRGB are **not** continuously guarded.
  - `test_lensblur` — camera/scanner lens-blur parity is not gated (despite `HANDOFF.md` claiming it
    is — another stale claim).
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
- 🟡 **Device smoke test never run** (issue #5) — no real-device validation of rotate→export, EXIF
  orientation, 16-bit PNG/TIFF, Ultra HDR, Expert RAW import. Relatedly, the **on-device NEON SIMD
  speedup magnitude** (the `exp10` work) is unmeasured — only x86 was profiled here.
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
