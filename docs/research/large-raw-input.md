# Bounded file-backed RAW input

## Problem and scope

The app previously copied an entire RAW fd into a native vector with a 64 MiB
ceiling. Encoded length and decoded frame size are different constraints: a
larger container can still have admissible geometry. Simply increasing the
vector ceiling would increase heap pressure before LibRaw allocates its raw
store and output buffers.

The app now opts into a private file snapshot. This change is independent of
GPU processing and decoded-pixel admission; it does not add high-megapixel
rendering support or change color processing.

## Ownership and limits

`RawDecoder.decodeToLinear(fd, settings, cancellation, scratchDirectory)` creates
a temporary file in the app's private cache directory, opens it read/write, and
unlinks it before copying. Native code duplicates both descriptors, copies in
64 KiB chunks, and maps the completed snapshot read-only with `MAP_PRIVATE`.
LibRaw is destroyed before the mapping. Kotlin closes the original scratch fd
on success and failure. No named RAW cache file persists during decoding or
after process death. A process killed between creation and unlink can leave an
empty cache file, but no copied RAW bytes.

The native scratch contract requires an empty, unlinked regular file opened
read/write, distinct from the source inode. Native callers must keep it open
and unchanged until decode returns. The source descriptor remains caller-owned;
its offset advances because `dup` shares the open file description, as in the
legacy fd path. A snapshot avoids exposing LibRaw to truncation of the original
provider file while parsing. A source changing during copying can still produce
an invalid snapshot and a decode failure.

- Encoded snapshot: at most 512 MiB on 64-bit, 64 MiB on 32-bit. The 512 MiB
  value is a bounded admission policy, not a measured guarantee for all devices.
- Legacy fd, ByteArray, ByteBuffer, and InputStream: existing 64 MiB ceiling.
- Geometry: unchanged 12 MiPixels on 64-bit / 8 MiPixels on 32-bit, including
  half-size requests. LibRaw raw store: unchanged 128 MiB.
- Existing TIFF/DNG preflight, codec classification, metadata budgets, and
  post-open geometry checks still run against the complete input.

Regular-file size is an early rejection only; actual bytes read enforce the
ceiling for pipes and growing sources. Cancellation is checked between reads
and writes and before mapping. A blocked provider read must return before
cooperative cancellation can finish; this does not add asynchronous fd abort.

The snapshot trades a full-input heap allocation for temporary storage and
extra file I/O, including for small files on the app path. Mapped pages still
consume address space and can contribute to resident/page-cache pressure.
Concurrent calls each need their own bounded snapshot. Disk exhaustion and
mapping failures return errors; there is no fallback to an unbounded allocation.
No speed-up or peak-memory reduction has been benchmarked in this change.

## Validation (2026-10-16)

- Linux x86_64, Ubuntu 24.04, Clang 18, Debug ASan/UBSan: all 7 host CTests
  passed using the verified/patched LibRaw 0.22.2 source and OpenMP disabled.
  After strengthening the positive fixture to relocate its actual sensor strip
  beyond 64 MiB, the affected resource-limit CTest was rebuilt and passed again.
- Android arm64 Debug, Galaxy S24+ (SM-S926U1), Android 16/API 36:
  `RawDecoderBoundaryInstrumentation` returned `OK (11 tests)` and code `-1`.
  The large-input fixture puts the sensor strip at `64 MiB + 4096`; decoded RGB
  is byte-identical to the compact buffer fixture. Failure/cancellation cases
  check cache cleanup and retained caller-fd ownership.
- `:app:testDebugUnitTest`: 643 tests, zero failures/errors. `:app:lint` passed
  with 77 warnings (existing baseline filtering remains in effect).
- An arm64 native probe linked to the same Debug `libsfraw.so` read the 108,226,912-byte
  Fujifilm GFX 100 II `DSCF0668.RAF` through the new snapshot path and returned:
  `status=7 ... RAW geometry exceeds 12 MiPixel in-memory safety limit`.
  This verifies removal of the earlier encoded-input rejection for that sample;
  it is not a successful 102 MP decode/export or an app UI end-to-end test.
- Physical 32-bit devices, low-storage behavior, and other content providers
  have not been qualified. Engine code is unchanged; engine parity was not rerun.

The camera sample is externally hosted, not committed:
[DPReview GFX 100 II sample gallery](https://www.dpreview.com/samples/3931459255/second-time-around-fujifilm-gfx-100-ii-pre-production-sample-gallery/).
SHA-256: `d889c87adb5b132635c55e35108f8401d71aff68c10ca2ecb08e180f7a76fdc0`.

## Reproduce the automated gates

Use the host instructions in `lib/libraw/src/test/host/README.md`, with Clang
and sanitizers enabled. The generated fixtures require no camera downloads.

```sh
cmake -S lib/libraw/src/test/host -B build/libraw-host -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DCMAKE_CXX_COMPILER=clang++ \
  -DSFRAW_HOST_ENABLE_SANITIZERS=ON -DSFRAW_HOST_ENABLE_OPENMP=OFF
cmake --build build/libraw-host --parallel
ctest --test-dir build/libraw-host --output-on-failure
./gradlew :app:testDebugUnitTest :app:lint :lib:libraw:connectedDebugAndroidTest
```

The custom instrumentation runner's acceptance markers are `OK (11 tests)`
and `INSTRUMENTATION_CODE: -1`, not AGP's zero-test UTP summary. For the recorded
Windows run, the test APK was assembled and installed with adb, then invoked
with `am instrument -w -r com.spectrafilm.libraw.test/com.spectrafilm.libraw.RawDecoderBoundaryInstrumentation`.
Only arm64 was built locally; a local Gradle init script supplied a verified
LibRaw source directory to avoid Windows FetchContent path-length limits.
Neither local override is committed or changes the repository's ABI settings.
