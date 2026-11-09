# Shipped architecture — Spektrafilm for Android

Status: current-tree architecture, reconciled 2026-08-31. Build files and tests remain executable
truth. Live work starts at [EXECUTION_INDEX.md](EXECUTION_INDEX.md).

Spektrafilm is a standalone Jetpack Compose application backed by a native C++ spectral engine and
native file codecs. It is not an ImageToolbox fork, does not use ImageToolbox's Hilt/Decompose
module graph, and does not compile the dormant `feature/film-emulation` tree.

## Compiled modules

```text
:app
  Compose editor, source/session state, recipes/presets/masks,
  preview/export orchestration, grading, storage and Android services
       |
       +--> :engine:spektra-core  Kotlin facade + libspektra.so
       +--> :lib:libraw          RawDecoder + libsfraw.so
       +--> :lib:pngwriter       PNG16 writer + libsfpng.so
       +--> :lib:tiffwriter      TIFF16/TIFF32F writer + libsftiff.so
```

The configured native ABIs are `arm64-v8a`, `armeabi-v7a`, and `x86_64`. Current Gradle truth is
min SDK 24, target SDK 36 and compile SDK 37 on `:app` (36 elsewhere); the Android 16 / API 36
qualification closed in #171.

`feature/film-emulation/` was a never-compiled pseudo-module and was deleted by #184; its history
survives in git and in [DECISION.md](DECISION.md). The old proposed ImageToolbox integration is
preserved as historical decision input in [DECISION.md](DECISION.md).

## End-to-end data flow

```text
content Uri / demo source
        |
        +-- supported RAW/DNG --> patched LibRaw 0.22.2 --> linear RGB
        |
        +-- supported platform image --------------------> linear RGB
        |
        v
LinearImage: interleaved native-order float32 RGB boundary
        |
        +-- scene-linear TIFF32F: verbatim input primaries, untagged --> publication
        |
        +-- preview: bounded linear proxy
        +-- export: approved full-resolution source
        |
        v
SpektraEngine JNI facade --> libspektra.so
        |
        +-- look bake: synthetic lattice, spatial/stochastic effects forced off --> .cube / CLF
        |
        +-- Strict Exact CPU: adopted mixed-precision graph; f64 where parity requires it
        |
        +-- eligible Fast GPU: resident Vulkan pointwise print graph
        |      (capability/self-test gated; non-cancellation route failure falls back to CPU)
        |
        v
native-owned direct float32 RGB result + explicit lease
        |
        v
post-engine app grade / masks / output conversion
        |
        +-- JPEG / PNG8
        +-- PNG16
        +-- TIFF16 / rendered TIFF32F
        +-- separately gated Ultra HDR container
        |
        v
digest-verified transactional publication
```

Unsupported compressed inputs may take Android's display-referred platform fallback. That path is
not labelled as a native RAW/oracle-identical decode. Source EXIF carry-through currently belongs to
JPEG output; no architecture diagram may imply all-format metadata preservation.

## Native engine graph

The CPU graph follows the pinned upstream model:

1. crop/rescale, exposure and auto-metering;
2. spectral upsampling and film exposure;
3. density curves, DIR couplers, grain, halation/scatter and diffusion;
4. optional enlarger/print-paper exposure and development; and
5. scan/viewing illuminant, XYZ/output RGB, glare, tone curve and output transfer.

Profiles, scientific LUTs and ICC assets live under
`engine/spektra-core/src/main/assets/spektra/`. Scientific response tables are not a baked
whole-look cube. Direct simulation is the normal model; `.cube`/CLF generation is an explicit export
operation, and the legacy GLES LUT loupe is only a preview approximation.

### Profile-driven scan illuminants

Profile loading resolves `info.viewing_illuminant` through one fail-closed registry. D50 and K75P
currently have immutable spectral distribution, XYZ normalization, and adaptation records. Unknown
identifiers fail profile loading; they do not silently become D50.

The resolved record feeds direct CPU scan, scanner-LUT construction/cache keys, Vulkan tables,
viewing glare, and black/white references. Direct and LUT K75P routes have independent oracle
fixtures. Strict Exact CPU keeps the coarse scanner LUT disabled because its interpolation is an
explicit approximation.

## Numeric and exactness boundaries

Sensor/container bit depth, processing representation, and exported file depth are independent:

- decoded/editor/JNI interchange is interleaved linear float32;
- the parity-bearing CPU implementation retains its adopted mixed-precision arithmetic and
  operation order, including float64 where parity requires it;
- the resident Vulkan route uses float32 under a separate tolerance/same-device contract;
- float16 is not a global processing mode and requires a separately measured quality gate before
  any narrow use; and
- output may be 8/16-bit integer or 32-bit float, while Ultra HDR additionally needs an honest
  gain-map/transfer contract.

“Strict Exact” currently means oracle tolerance (`max_abs <= 1e-4`, `rms <= 1e-5`) plus byte
identity across worker counts for the same build. It does not imply cross-build/cross-ABI bytes,
CPU/GPU equality, or complete container SHA identity. The authoritative levels are in
[BIT_IDENTICAL_EXPORT_ROADMAP.md](BIT_IDENTICAL_EXPORT_ROADMAP.md).

### Band rendering for very large exports

A whole-frame render holds several full-resolution buffers at once (float64 irradiance for
halation, film and print densities, scan output, spatial scratch). On a 50 MP Samsung DNG
the host measured 4.3 GB peak RSS with app defaults, and the same render on an 11 GB phone
ended in an lmkd kill. So `spk_simulate` renders a one-shot frame above 16 MiP as horizontal
bands (`plan_band_render` / `run_banded` in `spektra.cpp`):

- each band is ~12 MiP: its own rows plus a margin above and below that covers every enabled
  spatial effect's reach (per blur component, sized from the measured IIR edge-error decay: up to ~11 sigma for a full-weight blur on linear light);
- the band renders as its own image with the frame's pixel size, whole-frame auto-exposure gain
  and date-back position pinned, and only its own rows are copied into the output;
- grain and glare are re-seeded per band (statistically the same field, different bytes);
- the band layout depends only on frame size and params, never on free memory;
- highlight boost bands: its curve is scaled by the frame's brightest pre-boost value, and
  everything before it is per pixel, so a pre-pass runs the bands' own expose over the band
  cores, takes that maximum, and hands it to every band (`BandFrame::boost_frame_max`);
- engine crop bands: the bands cover the crop box (`crop_box`), with pixel size and
  auto-exposure taken from the uncropped input exactly as the whole-frame crop takes them;
  rows are used in place for a full-width crop and copied one band at a time otherwise;
- the contrast mask bands (print route): its grid averages the print exposure over the whole
  frame, so a pass renders every band up to the print exposure and adds its core rows to the
  frame's cell sums (each cell's sums grow in raster order either way), then the bands render
  with the finished grid. Costs a second filming + print-exposure pass per band;
- the camera diffusion filter renders whole (exactly) up to `spk_set_whole_frame_pixel_limit`,
  and bands only above it, where the whole filter cannot run. Its kernel reaches ~0.36 of the
  short side, so the band form splits it by radius (`model/diffusion.h`): a near part (the core
  plus halo/bloom under a Gaussian window of rt = 10f, truncated at 3.5 rt) convolved per band at full
  resolution, and the smooth rest (including the tail of a widened core, so the split keeps the kernel's mass) convolved once on a copy of the frame tent-splatted down by
  f = 4..8 (about 4 M coarse cells), with the coarse kernel's mass pinned and a second-order
  (f²/6)·Laplacian correction, read back bilinearly. A pass over the band cores (after the
  highlight boost) builds the coarse frame. NOT bit-exact: on a real-size 50 MP frame with
  highlights 8 stops over a dark floor the irradiance is within 4.2e-4 in log10 (0.1 %) worst,
  RMS 1.4e-5 (Black Pro-Mist; Cinebloom 2.2e-4). Band margin grows by the near radius (140 px at
  f = 4, 280 at f = 8);
- frame-wide effects that still fall back to the whole-frame render: the enlarger diffusion
  filter and engine rescale;
- a whole-frame render above `spk_set_whole_frame_pixel_limit` fails with `SPK_ERR_OOM`
  (an `OutOfMemoryError` in Kotlin) instead of starting. The app sets 32 MiP, the largest RAW
  decode tier before banding, so a 50 MP frame with the enlarger diffusion filter is refused,
  not rendered into an lmkd kill (#589).

Measured on the 50 MP frame (host, 4 cores): peak RSS 4.3 GB → 2.0 GB including the input and
output frames, render time 84 s → 178 s from the margin rows. `test_band_render` holds banded
output to the parity tolerance against the whole-frame render (measured max_abs ~2e-5, rms ~1e-6)
on both routes, plus thread identity and the fallbacks; the banded camera diffusion filter is
held to |log10| ≤ 1e-3 worst / 1e-4 RMS against the whole filter on the irradiance and to a
measured output bound end to end. The JNI layer adopts the engine's output
frame instead of copying it, which removed a second full frame at the end of every render.

## CPU and Vulkan routes

### Strict Exact CPU

The C++ route is the parity-bearing implementation and universal fallback. Its host matrix (46 cases;
`.github/workflows/ci.yml` is authoritative) runs at O2 and the shipping `-O3 -ffast-math -fno-finite-math-only` flags. Deterministic fixed-chunk
parallelism proves worker-count invariance in covered scenarios. Spatial scratch, memory budgets,
writer streaming, and cold latency remain explicit work rather than reasons to change arithmetic
silently.

### Fast GPU

Since v0.10.0 this is the route the app renders and exports with by default (no user toggle);
Strict Exact CPU is the reference and automatic fallback. The eligible print route keeps pointwise
filming, printing, and scan in one persistent Vulkan chain with one upload, three dispatches, and one readback. Full-byte table keys, explicit
NaN/bounds handling, cancellation, route counters, and a CPU comparison self-test gate exposure.
GPU output remains private until the whole route succeeds. Unsupported devices and
non-cancellation availability, validation, allocation, or dispatch failures take the Strict Exact
CPU route; cancellation terminates the render and must not restart work on CPU.

This is not yet a full resident graph: cross-pass frame residency is declared in
`vulkan_compute.h` (`frame_open`/`frame_close`) but unwired outside the tests (#212). Halation/scatter,
the diffusion FFT convolution, the pointwise filming halves and DIR-coupler diffusion ride the same
Fast GPU latch as the scan (`spektra.cpp`); the grain and glare samplers ride the export-only
`fast_sampler` latch because they are a different RNG realisation of the same distribution (#180).
No functional device pass proves a 1–2 second SLO (#186).

## Ownership, threading and cancellation

- Kotlin coroutines keep decode, render and export off the main thread; UI state receives only
  bounded progress and terminal results.
- The native engine owns its worker scheduler and render-local stage timings. LibRaw shipping builds
  remain serial because the repeated compressed-Fuji OpenMP corpus changed output bytes;
  the current patched release has no exact OpenMP qualification, avoiding silent
  oversubscription and decode drift.
- Native allocations have explicit owner tokens and leases. A direct `ByteBuffer` is a view, never
  release authority. Geometry and logical buffer windows are checked at each JNI boundary.
- Render/close, cancellation, foreground-service generations, and exactly-once result publication
  follow [JNI_LIFETIME_SAFETY.md](JNI_LIFETIME_SAFETY.md).

## Persistence and publication

The storage contract is defined in [TRANSACTIONAL_STORAGE.md](TRANSACTIONAL_STORAGE.md):

- Android 10+ exports use app-tagged pending MediaStore rows and publish only a verified staged
  payload;
- Android 7–9 uses a same-directory fsynced temporary file and atomic no-overwrite rename after the
  legacy permission gate;
- process-owned work survives Activity recreation and journals recoverable state;
- source documents retain persistable SAF access when supported and otherwise enter explicit
  reauthorization; and
- recipe/preset/sidecar/mask documents use bounded, versioned parsing and atomic replacement.

These are process-recoverable guarantees, not a cross-provider database transaction. An
indeterminate provider result blocks another export until reconciliation resolves ownership.

### Two export caches (issue #179)

Both are keyed by content, live in `cacheDir`, and treat any doubt as a miss:

- **`ExportCache`** stores finished **container bytes** under a key covering the source digest,
  every engine parameter, the grade, the whole `OutputDescriptor`, geometry, quality and the build
  contract. A hit publishes the exact bytes a previous export produced — no decode, engine, grade
  or encode. It pays off on a repeated export.
- **`RenderPayloadCache`** stores the **engine's float output**, written by an idle pre-render
  after the editor sits still for 5 s, keyed on the engine inputs only (so one payload serves
  every container). A hit skips decode and the engine — ~86% of a first export — while the export
  still runs the real encoder, which is what makes the published bytes identical to an uncached
  export's.

Neither may ever return a stale or partial entry: metadata is committed after the payload, the
build contract folds in the install time, and a length or key mismatch discards the entry.

## Why native C++ remains the core

| Driver | Reason |
|---|---|
| Model fit | The pipeline performs 81-band spectral contractions plus large spatial filters and maps naturally to the upstream numeric graph. |
| Parity control | C++ exposes arithmetic order, compiler flags, NaN behavior, and deterministic scheduling to executable gates. |
| Boundary cost | LibRaw and output writers are native; explicit direct buffers avoid per-pixel JNI calls and accidental managed-heap copies. |
| GPU evolution | The owned C++ graph can add bounded Vulkan stages while preserving one CPU fallback and one parameter/profile implementation. |

Wholesale replacement with vkdt, Halide, RawSpeed, Adobe DNG SDK, or another framework is not an
architecture shortcut. Each may contribute a proven technique or narrow dependency only after its
numeric, format, licensing, memory, APK-size, security, and device-performance gates pass.
