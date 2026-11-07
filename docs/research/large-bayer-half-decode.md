# Bounded large Bayer RAW input with reduced output

This proposal builds on [#265](https://github.com/thetechgeekko/Spektrafilm-android/pull/265),
which handles encoded files above 64 MiB. It addresses a separate limit: a large
sensor frame can be rejected even when its half-size processed image fits the
mobile decoder budget. It is related to #173 and #204, but does not close their
broader streaming or whole-pipeline memory work.

## Contract

One Android `/proc/meminfo` snapshot selects the output ceiling for a decode:

| Pointer width / memory | Output ceiling |
| --- | --- |
| 32-bit | 8 MiPixels |
| 64-bit baseline, missing/inconsistent telemetry | 12 MiPixels |
| 64-bit, total >= 6 GiB and available >= 2 GiB | 24 MiPixels |
| 64-bit, total >= 8 GiB and available >= 3 GiB | 32 MiPixels |

Only the highest tier admits a larger source for a half-size request. The
source must be three-color Bayer, without rotated Fuji geometry; after unpack
it must have a single integer RAW plane. Raw, visible and margin-adjusted areas
must each fit 128 MiPixels (134,217,728 pixels). Padded half-size allocation and
aspect-adjusted dimensions must fit the 32 MiPixel processed ceiling. The
post-unpack check uses `rawdata.sizes`, `rawdata.iparams` and
`rawdata.ioparams`, because LibRaw restores those before `raw2image_ex` allocates.
The final bitmap check still bounds returned processing dimensions.

These qualified large requests receive a 320 MiB LibRaw raw-store ceiling;
ordinary requests retain 128 MiB. This bounds the unpacked store, not total
process memory. X-Trans, linear/float/multi-plane, unknown and rotated layouts do
not gain this exception. The 512/64 MiB encoded-input limits from #265 stay
independent. No full-resolution 102 MP export is enabled.

The app catches typed native `NO_MEMORY` and retries a full-size request once
with `halfSize=true`, preserving its output edge cap. Already reduced native
failure, corruption, unsupported input and cancellation propagate without that
retry. Existing JVM allocation-failure handling remains. Export wording is
"Maximum available resolution" to avoid promising source dimensions.

No engine/shader, color conversion, film profile, grain parameter, application
ID, version, or release signing changes are included in this increment.

## Candidate gates (2026-10-16)

- Clang 18.1.3 / Ubuntu 24.04, Debug, ASan + UBSan + float-cast-overflow:
  **7/7 host CTests passed**, OpenMP off. Both LibRaw and the wrapper were
  instrumented using the audited patched 0.22.2 source resolver.
- Resource tests cover RAM threshold boundaries, missing/inconsistent telemetry,
  qualified 102 MP geometry, lower tiers/full-size rejection, excluded layouts,
  source area, aspect expansion, margin overflow, and all #265 snapshot tests.
- Five new JVM tests cover native success-on-retry, two-attempt bound, preview
  failure, non-memory failure propagation, and the existing managed OOM path.
- `:app:testDebugUnitTest`: **648 tests**, zero failures/errors/skips.
- `:app:lint`: passed, 77 warnings and 4 baseline-filtered warnings.
- Fresh candidate Android arm64 Debug native library (NDK r28c, CMake 3.22.1):
  full decode rejected with `NO_MEMORY`; half preview 1944 x 1458 succeeded;
  half export 5831 x 4373 succeeded, all RGB samples finite. Half-export process
  peak RSS was 996,348 KiB. Both reduced output FNV-1a byte digests matched
  the previously successful integration decoder. This is native decode
  qualification, not a fresh full-app export or a speed claim.
- [Native device transcript](large-bayer-half-decode-device.txt) and
  [source/library hashes](large-bayer-half-decode-evidence.json) identify this run.
- Engine parity is not rerun for this increment: no engine code or color math
  changed. RAW white-balance exact-bit and precision contracts passed in CTest.

Reproduction:

```sh
./gradlew :app:testDebugUnitTest :app:lint
cmake -S lib/libraw/src/test/host -B build/libraw-host -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DCMAKE_CXX_COMPILER=clang++ \
  -DSFRAW_HOST_ENABLE_OPENMP=OFF
cmake --build build/libraw-host --parallel
ctest --test-dir build/libraw-host --output-on-failure
```

The local run used `SFRAW_LIBRAW_SOURCE_DIR` pointing to an already verified,
patched source tree; the resolver still checks its audited aggregate. The
normal command above fetches and authenticates the pinned source.

## Earlier combined Release app evidence

The installed integration build `04d6091e2860606f40276147c7a75adf1582b128`
(`0.10.0-local.4`) also contains separate large-frame GPU changes. Its timings
and GPU engagement **are not performance claims for this isolated PR**.

Device: Galaxy S24+ SM-S926U1, Snapdragon 8 Gen 3 / Adreno 750, Android 16,
arm64, nominal 12 GB RAM. Ordinary indoor conditions, no external cooling,
USB connected; exact ambient/SoC temperature was not recorded.

Input: GFX 100 II `DSCF0668.RAF`, 108,226,912 bytes, from the
[DPReview sample gallery](https://www.dpreview.com/samples/3931459255/second-time-around-fujifilm-gfx-100-ii-pre-production-sample-gallery/).
SHA-256: `d889c87adb5b132635c55e35108f8401d71aff68c10ca2ecb08e180f7a76fdc0`.
The copyrighted source and generated JPEGs are not bundled with this PR.

- Full-source decode remained rejected; real RAW half-size decode succeeded.
- Native half-size output: 5831 x 4373 (25,498,963 pixels), 4.946 s,
  peak process RSS 993,656 KiB, no nonfinite RGB samples.
- Complete app Portra 400 export: 20.008 s; RAW decode 5.369 s, simulation
  13.871 s, JPEG encoding 0.640 s. JPEG quality 95, sRGB.
- Sampled application peak PSS: 3,620,412 KiB, approximately 2-second interval;
  instantaneous peaks may have been missed. This differs from native-probe RSS.
- Five additional Gold 200 / Fuji Crystal Archive Type II exports with grain,
  halation and DIR enabled completed at 5831 x 4373. As-shot WB, center-weighted
  auto exposure/EV0, print compensation on, preflash 0, diffusion off, scan
  unsharp 0.7/0.7, black/white correction off, sRGB JPEG quality 95.

| Print exposure | Yellow / magenta offsets | App export time |
| --- | --- | --- |
| 0.60 | 0 / 0 | 19.024 s |
| 0.50 | +3.3 / -0.27 | 19.180 s |
| 0.45 | +3.3 / -0.27 | 19.172 s |
| 0.55 | +3.3 / -0.27 | 19.062 s |
| 0.60 | +3.3 / -0.27 | 19.224 s |

Each preset was imported, re-exported and compared field-by-field with a
1e-6 float tolerance. Export logs reported grain/halation/DIR GPU engagement.
JPEGs were fully decoded for geometry and matched phone-to-computer SHA-256.
These are five recipes on **one RAW**, not five different camera samples.

## Limits and risks

Half-size processing loses source spatial detail and is not equivalent to a
full-size demosaic followed by resampling. It changes the previously failing
large-input path into a usable reduced output. The UI reports available output
size; callers must not infer original dimensions from successful decode.

Memory telemetry is a heuristic, not a reservation: the OS or later film/export
allocations can still run out of memory. Full source unpack remains necessary,
and a failed full attempt may require copying/reading the source again. This is
not a streaming decoder. Physical low-RAM/32-bit devices, other 100 MP cameras,
other compression modes, and all presets are not qualified by this one sample.
The highest-tier geometry bound is a policy limit, not a compatibility promise.
