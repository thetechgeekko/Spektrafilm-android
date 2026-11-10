# Maintainer guide

For people building the app or changing the engine. User guides start at [README.md](README.md).
[EXECUTION_INDEX.md](EXECUTION_INDEX.md) says which document owns what, and pins the toolchain.
[CONTRIBUTING.md](../CONTRIBUTING.md) is the step-by-step for a pull request. Build files,
workflows and tests outrank prose: if this page and the code disagree, the code is right and
this page needs fixing.

Before 1.0 each contract below had its own long document. They were folded into this page; the
full text, with its verification records, is in git history, for example
`git show 37d475e:docs/TRANSACTIONAL_STORAGE.md`.

## Architecture

```text
content Uri / demo source
        |
        +-- supported RAW/DNG --> patched LibRaw 0.22.2 --> linear RGB
        +-- supported platform image --------------------> linear RGB
        v
LinearImage: interleaved native-order float32 RGB
        |
        +-- scene-linear TIFF32F: verbatim input primaries, untagged --> publication
        +-- preview: bounded linear proxy    +-- export: full-resolution source
        v
SpektraEngine JNI facade --> libspektra.so
        |
        +-- look bake: synthetic lattice, spatial/stochastic effects off --> .cube / CLF
        +-- Strict Exact CPU: f64 where parity requires it (the reference)
        +-- Fast GPU: Vulkan compute, self-tested per device, falls back to CPU
        v
native-owned float32 RGB result + explicit lease
        v
app grade / masks / frame / output conversion
        v
JPEG / PNG8 / PNG16 / TIFF16 / TIFF32F / Ultra HDR --> digest-verified publication
```

| Module | What it is |
|---|---|
| `:app` | `com.spectrafilm.app`. Compose UI, editor state (`EditorViewModel` and friends), Library, recipes, presets, masks, preview and export orchestration, updater. Manual DI in `AppContainer`. |
| `:engine:spektra-core` | `libspektra.so` and the Kotlin facade `SpektraEngine` / `SpektraParams`. Profiles, spectral LUTs and ICC profiles under `src/main/assets/spektra/`. |
| `:lib:libraw` | `libsfraw.so`, patched LibRaw: RAW/DNG to linear ProPhoto RGB. See [lib/libraw/README.md](../lib/libraw/README.md). |
| `:lib:tiffwriter`, `:lib:pngwriter` | 16-bit and float TIFF/PNG writers. |
| `baselineprofile` | Baseline and startup profiles. |

Engine layout (`engine/spektra-core/src/main/cpp/`): `spektra_jni.cpp` is the single native
boundary; `runtime/stages/` holds `filming`, `printing`, `scanning`, `crop_resize` and
`autoexposure`; `model/` the photographic math; `kernels/` the hot numeric primitives; `gpu/`
the Vulkan route.

## Build

JDK 21, NDK r28c (`28.2.13676358`), CMake 3.22.1, build-tools 36.0.0. Exact pins are in
[EXECUTION_INDEX.md](EXECUTION_INDEX.md).

```bash
./gradlew :app:assembleDebug        # debug APK, all three ABIs
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:lint                 # abortOnError, no baseline
python tools/docs/check_docs_consistency.py   # before any docs change lands
```

Dependency locking is strict; a bump needs new lockfiles, written by CI (`run-ci` +
`write-locks` labels on the PR).

## Engine parity

The prime rule: every engine stage matches the upstream Python spektrafilm oracle within
**oracle tolerance** (`max_abs <= 1e-4`, `rms <= 1e-5`) and is byte-identical across worker
counts. Any change under `engine/spektra-core/src/main/cpp/**` must keep the host parity suite
green:

```bash
tools/parity/run_engine_parity.sh                         # the -O2 leg
SPK_PARITY_EXTRA_FLAGS="-O3 -ffast-math -fno-finite-math-only" tools/parity/run_engine_parity.sh
```

CI runs both legs. The parity build has no Vulkan, so it says nothing about `gpu/`; run
`tools/gpu_gates/run_gpu_gates.sh` (lavapipe) for GPU changes, and check that it reports
`engaged=1`. `-fno-finite-math-only` is required: scanning relies on NaN propagation. A new
engine `.cpp` must be added to `CMakeLists.txt` by hand; `tools/arm64_check/check_android_link.sh`
catches a missing one. The oracle and golden format are in [tools/parity/](../tools/parity/).

## Export identity contract

"Bit-identical" is not one promise. The accepted levels (issue #126, approved 2026-09-01):

| Level | Status | Promise |
|---|---|---|
| C0 same-build deterministic | Accepted, gated | Same build and device: byte-identical engine samples on repeat runs and at 1/2/4/8 workers |
| C3 decoded-output identity | Accepted, gated | Identical decoded samples and normalized metadata, even if the compressed stream differs |
| C4 container identity | Scoped | Whole-file SHA-256 only where the container can be made clock-deterministic (PNG16, TIFF16 with a fixed clock); JPEG is not C4 |
| C1 historical, C2 portable | Rejected | No promise of identical bytes across releases, compilers, ABIs, CPUs or devices |
| E3 Fast GPU | Tolerance-bounded | Within approved error of the CPU route and deterministic on one device and driver; never parity evidence |

A performance or identity claim must name its level, its build type (never size a lever from a
debug APK) and its workload. Exports above 16 MiP render in overlapping bands, within the parity
tolerance of a whole-frame render; above the whole-frame limit the diffusion filters use a
near/far split gated at `|log10| <= 1e-3`.

## Native boundary and lifetime

- Every native allocation has one owner (`LinearImage`, `SimResult`, LibRaw `LinearResult`,
  `NativeBufferOwner`). A direct `ByteBuffer` is only a view; a `DataLease` keeps the allocation
  alive until the lease closes. Owners close idempotently.
- `SpektraEngine.close()` takes a write lease, so it cannot destroy the handle while a render or
  its cancellation callback is still running.
- All caller geometry is widened before multiplication and checked before allocation; direct
  buffers are checked for position, limit, capacity, alignment and byte order.
- Every JNI entry point catches `std::bad_alloc`, `std::exception` and unknown exceptions; no C++
  exception unwinds into ART.
- Cancellation is polled in LibRaw, every engine stage, the writers and large result copies.
  Render and export generations use monotonic IDs, so a cancelled or superseded result is never
  published.
- R8 cannot see members resolved from JNI by name. `tools/r8_check/check_release_dex.sh` fails a
  release dex that lost one; teach `tools/r8_check/jni_inventory.py` about any new by-name lookup.

## Storage and publication

| Path | Guarantee |
|---|---|
| MediaStore export, API 29+ | The row stays pending while bytes are copied and SHA-256-verified; it is published only after the check. A journal recovers or deletes interrupted exports on the next start. |
| Public Pictures, API 24-28 | Written to a `.part` file, synced, verified, then renamed. |
| SAF document | Flushed, closed, reopened and verified; an app-created document is deleted on failure. |
| Presets, recipes, masks | Versioned JSON through `AtomicFile`; bounded strict UTF-8 reads; a corrupt or newer-version file is quarantined, never overwritten. |
| Source URIs | Only restored while the persisted grant is still valid and readable; otherwise the user is asked to choose the file again and the recipe is kept. |

Edits are a recipe keyed to the source, so the original is never written. The mask format is in
[MASK_JSON_SCHEMA.md](MASK_JSON_SCHEMA.md) and [schema/](schema/).

## RAW decoding

spektrafilm reads RAW through `rawpy`, a binding over LibRaw, so "RAW like spektrafilm" means
LibRaw with the same `postprocess` settings: linear 16-bit, camera white balance, ACES
intermediate, then linear ProPhoto RGB for the engine. LibRaw is pinned (0.22.2) and patched; the
patches and their licence route are in [lib/libraw/README.md](../lib/libraw/README.md) and
[dependencies/LIBRAW.md](dependencies/LIBRAW.md). Large files decode off-heap, with a half-size
and embedded-preview ladder when memory is short. If LibRaw cannot read a DNG, Android's decoder
is used and the app says the result is display-referred.

## Storage and native-safety device gate

[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) requires this gate for every candidate. The JVM suites
(`ExportTransactionTest`, `AtomicJsonStoreTest`, `SourceAccessTest`,
`ExportForegroundServiceGenerationGateTest`, `RecipeOperationGateTest`, `RecipeDocumentCodecTest`,
`MasksRoundTripTest`, `PresetsRoundTripTest`) cover the failure and state machine; the device
gate adds real MediaStore rows, injected out-of-space and interrupted-close faults, a revoked
persistable grant, and a real `seed -> force-stop -> recover` process-death sequence.

### Offline JVM command

Run from the repository root with Android Studio's bundled JDK (or another project-compatible JDK).
All dependencies must already be present in the Gradle cache:

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest `
  --tests "com.spectrafilm.app.ExportTransactionTest" `
  --tests "com.spectrafilm.app.AtomicJsonStoreTest" `
  --tests "com.spectrafilm.app.ExportForegroundServiceGenerationGateTest" `
  --tests "com.spectrafilm.app.SourceAccessTest" `
  --tests "com.spectrafilm.app.RecipeOperationGateTest" `
  --tests "com.spectrafilm.app.RecipeDocumentCodecTest" `
  --tests "com.spectrafilm.app.masks.MasksRoundTripTest"
```

### API 29+ device gate

The complete storage gate requires an API 29+ device with writable external MediaStore. The custom
instrumentation targets the minified `release` app, so a repository without `keystore.properties`
produces an unsigned target APK. For local testing only, sign a copy with the repository's public debug
key so it matches the Android-test APK. Never distribute this locally signed candidate.

```powershell
# Build offline, align and locally sign both APKs, then run the complete gate.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\android\run_release_device_gate.ps1 `
  -Build `
  -Serial '<adb-serial>'

# Or verify a specific already-signed candidate pair without rebuilding it.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\android\run_release_device_gate.ps1 `
  -AppApk '<path-to-signed-app.apk>' `
  -TestApk '<path-to-signed-androidTest.apk>' `
  -Serial '<adb-serial>'
```

`run_release_device_gate.ps1` is PowerShell 5.1-compatible and fail-closed. One centralized native
invoker checks every Gradle/build-tool/ADB exit code. Before either replace-only install, it pulls any
currently installed app and test base APK and requires their single signer to match the candidate pair;
an absent package is allowed, but a mismatched installed signer aborts without changing device data.
It then requires the registered runner, runs the full release instrumentation twice, rejects every
`FAIL`, and asserts all ticket #170/#172 `PASS` markers plus `INSTRUMENTATION_CODE: -1`. Its real
`seed -> force-stop -> recover` sequence requires the same token in both phases. It then runs the
ticket #181 `ticket181_phase=a11y` accessibility scan (`TICKET181_ACCESSIBILITY: PASS`) and pulls its
per-screen node dumps and screenshots into `<evidence>/accessibility/`. Finally it pulls both
installed packages and requires candidate-equal SHA-256 bytes and signer digests before emitting
`RELEASE_DEVICE_GATE: PASS`.

`-ExecutionPolicy Bypass` above applies only to that child PowerShell process; it does not change the
machine or user execution policy. It keeps the documented command usable when direct `.ps1` launch is
disabled. Omit it only on a machine whose existing policy already permits the checked-in script.

If install reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed target uses another signing
key. Back up any app-private work before explicitly uninstalling it; do not make uninstall an automatic
test step. The instrumentation deletes its two successfully published duplicate-name images in a
`finally` block and deletes its process-recovery row. If the runner itself is killed, launch the target
once to recover tagged pending rows and prior-process cache stages, then remove any published
`Spektrafilm_ticket170_duplicate.png` test images from the gallery. You can remove only the test package
afterward with:

```powershell
& $adb uninstall com.spectrafilm.app.test
```

## Updater

The only metadata request is `GET https://api.github.com/repos/thetechgeekko/Spektrafilm-android/releases/latest`,
at most once a day (switchable) or on demand, fail-closed on scheme, host and path. The integrity
gate is signing-certificate continuity, not the asset digest:

1. Before the installer, `UpdateVerifier` requires package `com.spectrafilm.app`, a strictly
   higher `versionCode`, a `versionName` equal to the tag, and signers that include every
   certificate of the installed app (or a proven v3 rotation lineage).
2. Android's installer enforces continuity again, and the user confirms. Nothing installs
   silently.

A debug or self-built copy is never updated in place. A Play install is never auto-checked.

## Privacy

No analytics, advertising SDK, account, crash uploader or image service. Network use is limited
to the update check, an update the user accepted, and the one-time skin-mask model download
(a pinned, versioned URL whose size and SHA-256 are compiled in). Diagnostics stay on the device,
are redacted (URIs, paths, file names, GPS and camera metadata) and leave only through Copy or
Share. `allowBackup` is false; the backup rules allowlist only two settings records for OEM
transfers that honour them.

## Release

Releases are cut only through `release.yml` and the protected signing environment, following
[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) and
[PRODUCTION_READINESS_PLAN.md](PRODUCTION_READINESS_PLAN.md). Tags are `vMAJOR.MINOR.PATCH`.
Licence obligations, including the GPLv3 §7(b) attribution and the LibRaw route, are in
[LICENSING.md](LICENSING.md) and [../NOTICE.md](../NOTICE.md). Any edit to `NOTICE.md` must be
copied to `app/src/main/assets/legal/spektrafilm/NOTICE.md` and re-pinned (SHA-256) in `ci.yml`
and `release.yml`.

## Rebuilding the README art

```bash
python3 tools/readme/make_readme_art.py --fonts /path/to/fonts
```

It reads phone captures from `docs/images/raw/` and writes the banner, framed screens and
pipeline diagram to `docs/images/` in light and dark. The fonts it needs are listed in the script.
