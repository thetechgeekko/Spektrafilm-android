# spektrafilm OFX to Android port record

Status: the low-level resident pointwise Vulkan slice is implemented and independently
code-reviewed under [#148](https://github.com/thetechgeekko/Spektrafilm-android/issues/148).
[#149](https://github.com/thetechgeekko/Spektrafilm-android/issues/149) remains the future
tolerance-bounded GPU-export/product-routing track. Capability persistence and spatial stages
remain open; the current-binary Adreno Phase A gate passes.

## Source pin and license

- Repository: https://github.com/chaert-s/spektrafilm-ofx
- Pinned commit: `86476afc5b077de77e2278e3658d1ba9309892a1`
- Commit date and author: 2026-07-06, Aedan Oskar Otto Diez
- Source license: GPL-3.0-only ([pinned license](https://github.com/chaert-s/spektrafilm-ofx/blob/86476afc5b077de77e2278e3658d1ba9309892a1/LICENSE.txt))
- Pin verified against public `main`: 2026-08-31

The Android application is already GPL-3.0-only. GPL therefore permits source adaptation when
copyright/license notices are preserved, modified files are marked, and corresponding source is
provided. The author's separate chat permission should be archived as project correspondence,
but the public GPL grant is the source-code permission used here.

## Adaptation map

| Upstream source | Android destination | Mode | Android change |
|---|---|---|---|
| `src/SpektraVulkanRenderer.cpp` | `engine/spektra-core/src/main/cpp/gpu/vulkan_compute.cpp` | orchestration/resource-lifetime adaptation | Retain the small Android Vulkan interface while adapting persistent device-resident ping-pong buffers, static-table caching, chained dispatch/barrier discipline, bounded allocation and fail-closed CPU fallback. |
| `src/SpektraVulkanRenderer.h` | `engine/spektra-core/src/main/cpp/gpu/vulkan_compute.h` | interface concepts only | Expose only the Android pointwise-chain seam and diagnostics needed by runtime gating; no OpenFX types cross the seam. |
| `tools/SpektraVulkanCopyHarness.cpp` | Android native/device GPU tests | test-structure reference | Reuse the independent upload/dispatch/readback validation pattern, not desktop host integration. |
| `shaders/vulkan/SpektraHalation.comp` | `engine/spektra-core/src/main/cpp/gpu/halation_scatter.comp` (+ `halation_scatter_spv.{h,inc}`, host runner `halation_scatter` in `gpu/vulkan_compute.cpp`, gate `gpu/tests/test_halation_gpu.cpp`) | shader math adaptation (modified copy, #206) | Keep upstream's op-multiplexed pass, buffer roles and scatter/bounce resolves; read every film-dependent constant from FrameFloats (this engine's digested `HalationParams`); replace the per-pixel truncated FIR by the engine's own per-channel Gaussian (Young-van Vliet IIR at sigma >= 3, FIR below, CPU coefficients and edge initialisation): FIR-class channels stay per-pixel, IIR-class channels run one thread per column, and an X pass goes through a tiled transpose so the recursion always walks columns (a per-row thread layout measured 1.62 s on the Adreno 840 at 12.5 MP from uncoalesced loads; the transposed design 0.87 s); raw-domain bounce resolve with `halation_renormalize`; highlight boost stays on the CPU; f32 storage only (owner decision #205). The pass runs on the whole frame (four packed f32 planes, 48 B/px, refused above 1 GiB) so every IIR sweep sees the complete column as the CPU does; the earlier row slicing with a 10-sigma halo left a 6e-6 join residual and could not fit the DIR tail at export scale. **The shader derives no parameter of its own**: sigma, the FIR/IIR class, the accumulation weight and the Young-van Vliet coefficients come from a per-(slot, channel) table the host computes in f64, because deriving them in f32 on the device was wrong three ways — rounding the coefficients moves the poles (they sit ~1/q from the unit circle, so a unit step drifts 2.5e-3 at sigma 63 px and 0.20 at 256 px even with the DC gain pinned), an f32 sigma can land on the other side of the sigma = 3 class boundary from the host's and leave a channel unwritten, and GLSL's `pow(0, 0)` is undefined where the CPU's is 1 (a zero bounce decay is UI-reachable and would have produced NaN, hence a black frame, on any driver lowering `pow` through `exp2`/`log2`). The coefficients therefore arrive as double-float (hi + lo) pairs and the recursion state is double-float too (Dekker products under `precise`, no FMA assumed). The FIR tap radius `int(3*sigma + 0.5)` is a step function of sigma and travels in the same table for the same reason: derived from the f32 sigma it gains a tap pair wherever rounding crosses a step, which reachable slider detents do produce (6.6e-2 on log10, 660x the band). A scatter tail weight outside [0, 1] is refused rather than clamped: the CPU blends with the raw weight, that blend becomes a difference of larger numbers, and the f32 sum buffer cannot hold it in band (5.3e-4), so the pass declines and the CPU renders it. The scatter core and the three tail surrogates fold into one weighted sum buffer. Measured under lavapipe vs the f64 CPU pass: the halation scene max_abs 2.1e-7 / rms 4.5e-8 on log10(raw); flat field 8e-13 to 1.1e-9 for sigma 12 to 256 px; every UI-reachable wide sigma (halation first sigma x spatial scale up to 200 px) ~2.1e-7; a blur whose channels straddle the FIR/IIR boundary 1.7e-7; a sigma one ulp below each FIR radius step (0.5, 1.5, 2.5 px) 1.3e-7 to 2.4e-7; the DIR shape at the 12.5 MP pitch, including the 500 um tail and tail weight 1 the sliders allow, 1.0e-7 to 2.5e-7 in density; three warm runs byte-identical; one-row staging bands byte-identical to the default band. The same pass diffuses the DIR-coupler correction (`model/couplers.cpp`, scatter step with amount 1) under the same latch. |

The exact introducing commits are recoverable from this file and the destination files with
`git log --follow`; every later adapted source path must be added to this table in the same change.

## Numeric authority and first slice

The first slice promotes the Android repository's already measured shaders:

- `tools/gpu_probe/filming.comp`;
- `tools/gpu_probe/printing.comp`; and
- `engine/spektra-core/src/main/cpp/gpu/scan_spectral.comp`.

Those shaders were built from this engine's current profile/parameter folds and passed the
connected-device M2 oracle-tolerance and repeat-determinism probes. No OFX shader math is copied
in the first slice. OFX contributes the resident-DAG orchestration pattern: one input upload, a
shared device-resident pointwise chain with explicit compute barriers, cached static f32 tables,
and one final readback. The existing Strict Exact CPU route remains unchanged and authoritative;
Fast GPU is opt-in, device-self-tested, tolerance-bounded, and fails closed to CPU.

## Phase A implementation checkpoint (2026-08-31)

The first production-shaped seam now exists in `gpu/vulkan_compute.{h,cpp}` with locally
generated `filming.comp`, `printing.comp` and `scan_spectral_chain.comp` shaders. One call:

1. validates span pointers/counts, exact table shapes, finite tables/scalars, strict axes and
   host-size arithmetic before pointwise buffer allocation; device-limit and heap checks follow
   Vulkan context initialization;
2. uploads one RGB frame to a persistently mapped staging buffer;
3. dispatches filming, printing and scanning exactly once each over two device-local ping-pong
   buffers, with compute barriers and no inter-stage host copy;
4. reads one final RGB frame back, validates every result is finite, then and only then copies it
   into caller-owned output; and
5. returns an explicit CPU-fallback reason on any failure, including allocation exceptions at the
   `noexcept` public seam.

Pipelines, grow-only frame buffers and complete f32 static tables persist across warm calls. The
caller supplies an opaque nonzero table-generation token; live engine ownership is not yet wired.
The Vulkan layer also compares the full table layout before treating it as resident.
Frame-operation diagnostics are completion counters:
success is `1 upload / 3 dispatches / 1 readback / 0 interstage host bytes`. Static-upload bytes
are separately documented as host-staged/attempted work and may be nonzero if a later GPU step
fails.

The dispatch planner is two-dimensional and guards the padded group before multiplying the flat
index. Planner assertions cover 12, 50 and 200 MP; the standalone WSL/lavapipe runtime gate
executes the first two-row boundary at 4,194,241 pixels. They do **not** claim that 12/50/200 MP
allocations fit a particular phone.
Approximate three-buffer payload residency is 432 MB, 1.8 GB and 7.2 GB respectively, before
alignment and driver overhead; 200 MP also exceeds the application's current single-Java-buffer
handoff and remains planning-only.

Current frozen-slice evidence:

- independent code review: APPROVE, no Critical/High finding;
- pinned NDK r27 regeneration is reproducible and all three modules pass Vulkan 1.1 `spirv-val`;
- arm64 O2 and shipping-flag warning builds pass; a fresh post-change Android
  `externalNativeBuildRelease` also passes for arm64-v8a, armeabi-v7a and x86_64;
- the full native engine parity suite passes 39/39 at O2 and 39/39 with the shipping
  `-O3 -ffast-math -fno-finite-math-only` flags;
- current shaders pass both O2 and shipping-flag WSL/lavapipe runtime gates, including an
  asymmetric combined f64 oracle (`max_abs` about `1.8e-7`), changed-key/table re-upload,
  100 byte-identical warm repeats and a real 4,194,241-pixel two-row dispatch; and
- the final revised O2 and shipping binaries also pass on the connected Adreno device: combined
  f64 `max_abs=1.99819717e-7` (`rms=1.00289698e-7`), changed-table
  `max_abs=2.06780449e-7` (`rms=1.01780053e-7`), 100 warm byte-identical runs with zero resource
  churn, and an executed 4,194,241-pixel boundary with `3 dispatch / 1 upload / 1 readback`.

This checkpoint proves the low-level direct renderer, not app/export engagement and not a 1-2 s
SLO. The next slice must build exact folded tables from live profiles, apply conservative route
eligibility before the CPU memos, publish render-local diagnostics, run the capability self-test,
and fail closed to the unchanged CPU route. Full tap-to-gallery performance remains ticket #177.

## LUT audit: 65 is an export choice, not the engine's universal grid

There are four different tables in play and their sizes must not be conflated:

| Purpose | Android behavior | Pinned OFX behavior | Port decision |
|---|---|---|---|
| User `.cube`/CLF export | UI offers `17^3`, `33^3`, and `65^3`; the default selection is `33^3`. The exact pointwise film/print pipeline evaluates every lattice point. | UI offers `33^3` and `65^3`; the default selection is `65^3`. It renders a `N^2 x N` identity lattice through the normal renderer after disabling non-pointwise effects. | Keep Android's three explicit choices. Do not silently force every export to 65; label 65 as highest-quality/largest/slowest. |
| Interactive GLES LUT preview | Always bakes a shaped `33^3` lattice and samples it trilinearly. | Not the OFX renderer's primary interactive path. | Temporary preview fallback only; the resident Vulkan DAG should replace it stage by stage. |
| Scanner/enlarger spectral acceleration | Opt-in, default `17^3`, PCHIP-interpolated, cached, and intentionally approximate; Strict Exact bypasses it. | The full Vulkan renderer evaluates its native shader pipeline rather than using this Android PCHIP accelerator. | Never use this approximate LUT on Strict Exact. Keep it only behind the Fast/tolerance gate until the resident shaders supersede it. |
| Color transfer functions | Analytic/native paths plus the spectral-upsample table `192 x 192 x 81` stored as finite f16 and expanded for computation. | Precomputed 1D decode/encode tables contain 4096 samples per color space. | These are not 3D creative LUTs. Evaluate a future 4096-entry transfer-table port separately against the local oracle before adoption. |

Both projects correctly omit auto exposure and spatial/stochastic operations from exported 3D
LUTs: a pointwise RGB cube cannot encode image-wide metering, grain, halation, diffusion, glare,
geometry, or scanner sharpening. The Android bake keeps spectral upsampling, density curves,
pointwise DIR couplers, printing, scanning and output conversion, emits blue-fastest `.cube`
ordering, and uses the regular engine route. The OFX exporter follows the same high-level method,
but its GPL implementation is only a design reference; no exported OFX LUT data is copied.

Later spatial stages may adapt individual GPL shader techniques only after their Android stage
semantics, halo/tiling law, NaN behavior, deterministic seed law, memory budget and CPU-oracle
error are independently gated. A wholesale renderer transplant is not permitted by this plan.

## Explicit exclusions

Do not copy or derive from:

- official OFX binary archives or any resources absent from the pinned public source tree;
- `.cube` or other LUTs exported by spektrafilm OFX, including re-gridded derivatives;
- SMPTE ST 2065-2 licensed CSV data absent from the public repository;
- OFX icons, branding, names used to imply endorsement, packaged manuals or screenshots;
- Metal/MPS code, OpenFX host/UI glue, desktop packaging scripts, or duplicate profiles/LUT assets.

The official-binary notice and exported-LUT license are separate from the GPL source grant:

- [official binary notice](https://github.com/chaert-s/spektrafilm-ofx/blob/86476afc5b077de77e2278e3658d1ba9309892a1/Legal/SPEKTRAFILM_OFX_LICENSE.txt)
- [exported LUT license](https://github.com/chaert-s/spektrafilm-ofx/blob/86476afc5b077de77e2278e3658d1ba9309892a1/Legal/SPEKTRAFILM_OFX_LUT_LICENSE.txt)

## Required gates per slice

1. RED test at the public native render seam, followed by the minimum implementation to pass.
2. CPU path and committed oracle corpus unchanged; 1/2/4/8-worker CPU digests remain stable.
3. Same-device GPU output within `max_abs <= 1e-4` and `rms <= 1e-5`, with at least 100 repeat digests before export enablement.
4. Capability verdict keyed by device, driver, OS and shader hashes; any mismatch re-runs the self-test.
5. One upload/one readback evidence and counters proving no hidden inter-stage host round-trip.
6. Bounded allocations, cancellation/watchdog, kill switch, and forced CPU-fallback tests.
7. Release arm64 benchmark with cold/warm p50/p95, thermals and memory; no speed claim from host-only timings.
8. Independent code, license/provenance and device-evidence review before the ticket closes.
