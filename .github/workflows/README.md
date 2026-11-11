# CI workflows

CI is kept deliberately small to save Actions minutes. Pull requests are
reviewed by Codex rather than CI by default.

## `build.yml` (everyday CI)

One job, modelled on adityawarmanfw/rawr's `debug.yml`: `:app:testDebugUnitTest`
plus `:app:assembleDebug`, with the debug APK uploaded as a 14-day artifact.
When the change touches `engine/spektra-core/src/main/cpp/`, the engine assets
or `tools/parity/`, the same job also runs `tools/parity/run_engine_parity.sh`
(the 44-case `-O2` leg).

| Trigger | Runs? |
|---------|-------|
| Push to `main` (every merge) | Yes |
| Pull request | Only with the **`run-ci`** label (the fallback when Codex review is unavailable); later pushes to a labelled PR re-run it |
| Manual dispatch | Yes, parity always included |

## `gpu-gates.yml` (automatic, native render changes only)

Every GPU gate under lavapipe (software Vulkan): `test_gpu_host`, the resident
pointwise chain, halation, grain, FFT diffusion, filming stages and the
contrast mask, at `-O2` and the shipping flags. It runs on a PR or a push to
`main` only when `engine/spektra-core/src/main/cpp/**`, the engine assets,
`tools/gpu_gates/**` or the workflow itself changes, plus manual dispatch. The
Fast GPU route is the default for every render and export, and the parity
suite compiles without Vulkan, so this is the only automatic check of the
route users get. Both this workflow and `ci.yml` call
`tools/gpu_gates/run_gpu_gates.sh`, which compiles the engine once per leg,
requires every gate to engage a device, and fails if a `gpu/tests/test_*.cpp`
is not wired in.

## `ci.yml` (full gate set, manual only)

Runs only on manual dispatch (Actions → CI → Run workflow, or
`gh workflow run ci.yml --ref <branch>`). Use it for risky native, LibRaw or
release-path changes. A green run on the exact `main` commit is required before `release.yml` will start. Nine jobs:

| Job | What it gates |
|-----|---------------|
| **engine-native** | The engine C++ + JNI bridge compile and link into `libspektra.so` on a host g++ toolchain (`-Wall -Wextra`; JDK provides `jni.h`); checks the exported `spk_*` symbols exist. |
| **native-safety-writers** | One committed runner performs the exact fourteen-suite host inventory: eight ASan+UBSan rows for JNI safety helpers, fork/join exception containment, the bounded `tc_lut` cache, bounded JSON/NPY parsing, an asset-backed engine C render/cancel race, and PNG/TIFF hostile-input/JNI-helper tests; six TSan rows cover the synchronized engine race, fork/join containment, the allocation registry, `tc_lut` cache concurrency/lifetime, and writer cancellation races. This does **not** claim sanitizer runtime coverage of the actual engine JNI bridge. |
| **engine-parity** | The stage-parity gate: **44 `build_run` cases**, run twice (`-O2` and the shipping `-O3 -ffast-math -fno-finite-math-only` flags), against bundled assets and committed goldens (e2e goldens pinned to oracle `c1d0e44`), incl. thread-invariance (`SPK_NUM_THREADS` 1 vs 8). The workflow table is authoritative; `tools/parity/run_engine_parity.sh` must fail loudly if its table drifts from that count. |
| **libraw-hostile** | Fetches the official SHA-pinned LibRaw 0.22.2 archive through the Android resolver, verifies/applies all 25 hashed patches, runs independent shipping-serial and OpenMP-required public-seam ASan/UBSan gates (including dormant X3F model-boundary regressions), a serial TSan first-use gate, and 1,000 iterations on each bounded libFuzzer target. It also builds the LibRaw source/relink ZIP twice, verifies deterministic bytes, and audits the route-neutral bundle; the elected route is LGPL-2.1-only, and the release workflow enforces it with `--require-resolved`. Shipping decode stays serial because the repeated compressed-Fuji corpus rejected OpenMP exactness for the current patched release. |
| **parity** | The standalone `.spkvec` comparator (`tools/parity`) builds via CMake and its `spkvec_selftest` ctest passes. |
| **python-lint** | The parity harness, LibRaw compliance tools, release-policy tools, documentation consistency checker, and offline Wayfinder frontier tests byte-compile; deterministic bundle/relink, app-SPDX, exact gate-attestation, immutable-publisher, workflow-structure, repository-fact, common Markdown link/image, preset-inventory, and ticket-routing tests pass. |
| **android** | JDK 21. Runs debug JVM unit tests for `:app`, `:lib:libraw`, `:engine:spektra-core`, `:lib:pngwriter`, and `:lib:tiffwriter`, then `:app:lint` (a hard gate — `abortOnError = true`, no baseline since #298), assembles the debug APK with the NDK-built `.so` for all 3 ABIs, and verifies the bundled app GPL/NOTICE plus the three pinned LibRaw legal assets. It extracts the generated source/relink bundle and performs a real NDK 27 x86_64 recipient build, checking SONAME, JNI/recipient marker exports, and every `PT_LOAD` alignment. Finally it runs the APK **16 KB-page gate** (`zipalign -c -P 16 4` plus `readelf -lW`). It uploads the app APK and standalone engine boundary Android-test APK as separate exact artifacts. |
| **android-emulator** | Required on every `ci.yml` run. A single pinned emulator action installs the standalone engine Android-test APK on the same API 35 x86_64 `google_apis` route used by release, runs the actual `EngineBoundaryInstrumentation` JNI bridge suite, and requires both `ENGINE_BOUNDARY_INSTRUMENTATION: PASS` and `INSTRUMENTATION_CODE: -1`. It then installs/launches the app and rejects fatal Java/JNI/native failures. |
| **export-benchmark** | Release preflight. Builds the R8-minified release candidate and its instrumentation APKs, runs `tools/r8_check/check_release_dex.sh` on the shrunk dex, debug-signs them and runs the same `tools/ci/release_device_smoke.sh` the release runs after production signing (same API 35 emulator options), so a missing keep rule fails here. It then boots a second emulator for the #177 export benchmark, uploaded as a trend artifact that is never gated. |

`bash tools/release/run_native_safety.sh --list` prints the locked eight
ASan+UBSan plus six TSan inventory. Running it without arguments compiles and
executes every row. CI and release both delegate to this one file, so their
native-safety command bodies cannot drift independently.

## `release.yml`

One button: manual dispatch on `main` with a new stable `vMAJOR.MINOR.PATCH`
version (no leading-zero components). `tools/release/resolve_release.sh` refuses
in seconds unless the ref is `main`, the version's tag does not exist yet, and
`ci.yml` succeeded on that exact commit. No tag is pushed before or during the
run. It qualifies pinned LibRaw in serial/OpenMP
sanitizer, TSan, and fuzz gates. A separate job with no signing secrets verifies
the Gradle wrapper/distribution, matches the tag to source `versionName`, builds
the R8-minified APK, its release-targeted app instrumentation APK, and the
standalone engine boundary instrumentation APK; it proves the app is unsigned
and checks its compiled package/version. A separate native-safety
qualification calls CI's same committed fourteen-suite ASan+UBSan/TSan runner for
JNI safety helpers, `tc_lut` cache bounds/lifetime/concurrency, fork/join exception
containment, bounded JSON/NPY parsing, the synchronized asset-backed engine C
cancellation race, and PNG/TIFF writer gates on
the exact commit before candidate build/signing. It makes no actual-JNI ASan
claim.
That job takes both exact engine parity legs from the required green `ci.yml` run on the same commit (its run ID goes into provenance), then runs release JVM tests for the app and all four native-backed modules, and release lint,
R8/JNI checks, and 16 KiB ZIP/ELF checks. A fixed gate attestation binds those
results, the exact commit/version, run ID/attempt, and all three APK hashes. The
service artifact digest and checked manifest bind that attestation together with
all seven Gradle lockfiles, deterministic app and LibRaw SPDX documents, runtime
classpath, R8 mapping, and full native symbols; final provenance records the
artifact database ID/digest and the signed/installed identities.
The separate LibRaw qualifier emits its run ID/attempt/source receipt; a partial
rerun cannot relabel a previous attempt as current and fails with an instruction
to re-run all jobs.
Only that qualified artifact enters the protected `release-signing` Environment;
no Gradle task runs with the production key. Pinned `zipalign`/`apksigner` tools
align and sign both exact APKs, remove the temporary keystore before the next
step, and pin their single signer fingerprint. An API 35 emulator installs the
signed bytes, proves the installed `base.apk` is byte-identical, runs both the
exact transferred engine JNI boundary APK and the custom release instrumentation,
cold-launches `MainActivity`, and rejects app-scoped fatal Java/JNI/native logs.
The engine runner's APK hash, PASS/code transcript hash, and gate result are
recorded in published provenance, and its APK/transcript are retained as release
assets. That standalone APK is the engine module's debug Android-test variant:
it executes the resolved source's real JNI bridge, but does not claim dynamic
coverage of the R8/shipping `libspektra.so`. The exact release app is separately
R8/dex-gated, installed-byte checked, instrumented, and cold-launched. The
compliance verifier uses `--require-resolved`;
`UNRESOLVED`, an unsupported route, source/patch drift, or a missing notice/bundle
component stops publication.

`tools/release/stage_draft_release.sh` then stages the APK and the LibRaw
source/relink ZIP on a **draft** Release with the built-in `GITHUB_TOKEN`
(`contents: write` on the protected job; no personal token exists to expire),
reads it back by numeric ID, and requires exactly those names with GitHub
digests equal to the local signed files. It refuses an already published
version, deletes its own draft on failure, and never publishes. The maintainer
publishes the draft, which creates the tag on the built commit. Between jobs
`tools/release/check_tag_state.sh` fails the run if the tag appears on any other
commit. The full evidence set (SPDX documents, seven-lock archive, R8 mapping,
native symbols, runtime classpath, engine boundary APK/transcript, provenance,
checksums) stays in the signed workflow artifact for 90 days.
Release uses JDK 21; NDK r28c (28.2.13676358), CMake 3.22.1, and build-tools 36.0.0 match CI.
Repository administrators must additionally protect `refs/tags/v*` with a
ruleset that forbids updates and deletion (creation stays allowed so Publish
can create the tag), enable immutable releases, and protect the
`release-signing` Environment with maintainer approval.

## `r8-smoke.yml` (added 2026-08-26)

Manual `workflow_dispatch` only. Builds an **unsigned R8-minified** release APK,
then explicitly aligns and signs it with the hash-pinned committed public debug
keystore (no production secrets), runs the same 16 KB-page checks as `ci.yml`, and uploads the APK
as `Spektrafilm-r8-smoke-apk`. Purpose: the CI `android` job builds debug (minify off), so a wrong
R8 keep-rule surfaces only at runtime — download this artifact and smoke-test it on a device
**before** tagging a release. See `docs/RELEASE_CHECKLIST.md`.
