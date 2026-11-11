# Maintainer Release Checklist

> **Release state (2026-09-15):** v0.10.0 is published as an immutable GitHub release (run
> 34929790265, tag at `d7f9fcf`, 14 verified assets). The remaining production blockers (#138 and its open children) and ordered
> implementation plan live in [EXECUTION_INDEX.md](EXECUTION_INDEX.md),
> [PRODUCTION_READINESS_PLAN.md](PRODUCTION_READINESS_PLAN.md), and the
> [Wayfinder map: production-ready Spektrafilm + 1–2 s exact export](https://github.com/thetechgeekko/Spektrafilm-android/issues/164).
> The release pipeline now builds without secrets, transfers a hash-bound unsigned candidate, and
> signs only inside the protected Environment. Every open release-blocking child on the live map,
> including human legal/product decisions, must close before tagging.
> The owner-selected GitHub release remains free. After that immutable release and its documentation
> portal are verified, [#200](https://github.com/thetechgeekko/Spektrafilm-android/issues/200)
> publishes the same logical release as an upfront-paid Google Play supporter edition that unlocks
> nothing. Play publication requires target SDK 36 and the Android 16 behavior matrix from
> [#171](https://github.com/thetechgeekko/Spektrafilm-android/issues/171). APK/AAB signatures, ZIP
> layout, and device splits can differ and must never be described as byte-identical containers.

Releases are **one button**. Running **Actions → Release** on `main` with a new
stable `vMAJOR.MINOR.PATCH` version (no prerelease/build suffixes or
leading-zero components) checks that full CI passed on that commit, qualifies
the pinned LibRaw dependency, builds, signs and device-tests a production-signed
APK, and stages it with the verified LibRaw source/relink ZIP as a draft
Release; SPDX, dependency-lock, mapping, native-symbol, runtime-classpath,
provenance and checksum artifacts stay in the signed workflow artifact. You
publish the draft, which creates the tag. Signing is available only through the
protected `release-signing` Environment. **APKs
are not committed to the repository** — there is no `dist/` directory
and you should never copy a built APK into the repo.

Current in-tree version: **v1.0.0 / versionCode 15** (`minSdk 24`, `targetSdk` 36 /
`compileSdk` 37 on `:app`); latest tag is **v0.10.0** (published 2026-09-15).

[Make production signing and exact release-candidate verification fail closed](https://github.com/thetechgeekko/Spektrafilm-android/issues/168)
produced a local test candidate validated on 2026-08-30 on an API 36
SM-S948W: both release APKs installed, instrumentation passed, the pulled
`base.apk` was byte-identical, R8/JNI and 16 KiB checks passed, and cold launch
completed without app-fatal logs. That test used the repository's public debug
certificate solely to exercise the mechanics; it is not production-signing
evidence and cannot satisfy the protected Environment gate.

[Make MediaStore exports, URI imports, recipes, and masks transactional and versioned](https://github.com/thetechgeekko/Spektrafilm-android/issues/170)
adds a release-targeted transactional-storage suite. Before a
candidate is accepted, run its normal storage smoke plus the documented
`seed -> force-stop -> recover` phases on a real API 29+ device. The recovery
phase must remove the exact app-owned pending row and durable journal entry;
there must be no tagged pending export left behind. Also retain JVM coverage for
encoder/stream failure, digest mismatch, out-of-space injection, future schema
versions, corrupt/oversized JSON, duplicate names, canceled Activity waiters,
FIFO source/recipe races, and revoked URI grants. Rotate/recreate the Activity
during an export and confirm the retained terminal result is delivered without a
second publication. API 24-28 candidates must separately verify legacy storage-
permission grant and denial; an API 36 device cannot certify that branch. See
[the storage device gate](MAINTAINERS.md#storage-and-native-safety-device-gate) for the exact commands and
the limits/guarantees being certified.

[Harden JNI lifetime, buffer bounds, cancellation, and render-close races](https://github.com/thetechgeekko/Spektrafilm-android/issues/172)
adds a second mandatory native-safety layer. Before accepting a candidate, require the shared host
inventory enumerated by `tools/release/run_native_safety.sh`, the standalone engine JNI boundary runner with
`ENGINE_BOUNDARY_INSTRUMENTATION: PASS` and instrumentation code `-1`, and the release-targeted app
runner's native-result Activity-recreation marker. The release gate must also resolve the separately
packaged AndroidTest APK's exact target classes, field types, method prototypes, facade ancestry, and
interface dispatch in the physical minified DEX; member-name checks alone are insufficient. Retain
the deterministic foreground-service rapid-completion/watchdog regression and native-owned direct-
buffer byte-order tests. The host sanitizer runner covers native helpers, the real C render/cancel
path, fork/join exception containment, allocation-token races, and writers; it does not claim
sanitizer instrumentation of the Android JNI bridge. The exact ownership and evidence contract is in
[the native boundary contract](MAINTAINERS.md#native-boundary-and-lifetime). Local connected-device qualification must use the
fail-closed `tools/android/run_release_device_gate.ps1` helper documented in
[the storage device gate](MAINTAINERS.md#storage-and-native-safety-device-gate), not an unchecked sequence of native commands.

The final local candidate for
[Harden JNI lifetime, buffer bounds, cancellation, and render-close races](https://github.com/thetechgeekko/Spektrafilm-android/issues/172)
passed on 2026-08-31 on an arm64 API 36 SM-S948W. The app and
AndroidTest SHA-256 values were
`EE1A1BA636FA123C93186EB4A3E80E964281080462B5AB8E24B790DE361318E8` and
`6368EF2697FFD4A557E4418C22B7052F831D518AE4340E04473776CABB6E0F4A`; pulled installed bytes matched
both. Full instrumentation passed twice, process-death recovery preserved and recovered the exact
token, standalone engine/LibRaw suites passed, and cold launch had no app-scoped fatal log. As with
the signing-mechanics ticket above, the repository debug key was used only for local
mechanics; this is not production-signing
evidence.

---

## 1. How a release happens (the automated flow)

**The short version.** Bump the version, merge, run full CI on main, then press
one button:

1. Merge the version bump (`versionName`/`versionCode`, `CHANGELOG.md`) to `main`.
2. Actions -> **CI** -> Run workflow on `main`. Wait for green (about 70 min).
3. Actions -> **Release** -> Run workflow on `main`, version `vX.Y.Z`.
4. Approve the `release-signing` deployment when it asks (once).
5. Open the draft under Releases, paste the notes, press **Publish**. Publishing
   creates tag `vX.Y.Z` on the built commit.

Nothing is tagged until you press Publish, so a failed run never leaves a tag
to delete: fix, merge, and run again. There is no personal token: the draft is
created with the workflow's built-in `GITHUB_TOKEN`.

`.github/workflows/release.yml` runs only on manual `workflow_dispatch` from
`main`, with the new version as input.

The `resolve-release` -> `qualify-libraw` -> `build-release-candidate` ->
`sign-and-publish` chain then:

1. Requires `main`, a stable `vMAJOR.MINOR.PATCH` version whose tag does not
   exist yet, and a successful full `ci.yml` run on this exact commit
   (`tools/release/resolve_release.sh`). It fails in seconds otherwise.
2. Verifies the official LibRaw archive plus all hashed patches; runs serial and
   OpenMP ASan/UBSan, serial TSan, and bounded public-seam fuzz gates.
3. In a job with no Environment and no signing secrets, verifies the official
   Gradle wrapper JAR and distribution hashes, installs **NDK `28.2.13676358`,
   CMake `3.22.1`, build-tools `36.0.0`**, and checks that the tag exactly matches
   the literal app `versionName`.
4. Accepts both exact engine parity legs (`-O2` and the shipping
   `-O3 -ffast-math -fno-finite-math-only` flags) from that green `ci.yml` run on
   the same commit and records its run ID in provenance, then runs release JVM
   tests, LibRaw JVM tests, and `:app:lintRelease`.
5. Builds an **unsigned**, R8-minified app APK and a release-targeted custom
   instrumentation APK. It verifies package/version/runner identity, R8's
   name-based JNI members, the AndroidTest APK's exact target-DEX ABI (classes,
   fields, method prototypes and hierarchy dispatch), the R8 mapping, full native
   symbols, and 16 KiB ZIP plus every 64-bit ELF `PT_LOAD`; `apksigner` must reject
   the app as unsigned.
6. Verifies the app GPL/NOTICE plus the three pinned LibRaw legal files in that
   candidate, then creates a canonical stored source/relink ZIP and SPDX sidecar
   from the authenticated archive and exact checked-out source. It verifies with
   `--require-resolved`. An unelected or unsupported route, source or patch drift,
   missing APK notices, a malformed SBOM, or a checksum
   mismatch fails before publication. A green tool result is technical evidence,
   not legal approval.
7. Generates a deterministic app SPDX document from the exact seven Gradle
   lockfiles and release runtime classpath. It archives those seven locks exactly,
   retains the LibRaw SPDX/source bundle, R8 mapping and full native symbols, and
   emits a fixed gate attestation bound to source SHA, version, run ID/attempt,
   and both unsigned APK hashes.
8. Uploads one run/attempt-specific candidate artifact and exposes its numeric
   artifact database ID and service-computed digest. Only then enters the
   protected `release-signing` Environment, downloads by that exact ID, verifies
   its digest/inventory/manifest/attestation, recomputes both SPDX documents and
   all lock bytes, and checks that nobody created the version tag on another
   commit. No Gradle task runs in the protected job.
9. Aligns and production-signs the exact app and instrumentation APKs with pinned
   build-tools `36.0.0`. The key exists for that step only; an `if: always()` step
   proves it and any untracked keystore are gone before certificate verification.
   Both APKs must have exactly one signer matching `RELEASE_CERT_SHA256`.
10. Rechecks the signed app's R8/JNI, legal, ZIP, and every 64-bit ELF invariant.
    An API 35 emulator installs both signed APKs, pulls installed `base.apk` and
    byte-compares it with the local signed APK, runs the release instrumentation,
    cold-launches `MainActivity`, and rejects app-scoped fatal Java/JNI/native logs.
11. Stages the app APK, both SPDX documents, LibRaw source/relink ZIP, seven-lock
    archive, R8 mapping, full native symbols, runtime classpath, provenance, and
    checksums. Provenance binds source/tag/version, run ID/attempt, candidate
    artifact ID/digest, signer, unsigned/signed/installed/test APK hashes, and all
    release gates. That full set is kept in the signed workflow artifact (90
    days); only the APK and the LibRaw source/relink ZIP (LGPL-2.1 section 6) go
    on the public Release, so users see one obvious download.
12. `tools/release/stage_draft_release.sh` creates a **draft** Release for the
    version on the built commit with the built-in `GITHUB_TOKEN`, uploads the two
    public files, and reads the draft back by numeric ID: exactly those names,
    each with GitHub's SHA-256 digest equal to the local signed file. It refuses
    if that version is already published and deletes its own draft on failure.
    It never publishes. The maintainer's **Publish** creates the tag, and
    repository immutable releases lock tag and assets from then on.

`release.yml` builds, signs and device-tests the exact bytes it stages. Host-only
evidence that does not depend on those bytes (both parity legs) comes from the
required green `ci.yml` run on the same commit; LibRaw and native safety are
still requalified in the run.
The LibRaw qualifier emits the current run ID/attempt/source SHA and the candidate
job rejects a receipt from any earlier attempt. After any failed release job, use
**Re-run all jobs**; a partial "re-run failed jobs" intentionally fails closed.

---

## 2. Pre-release checklist

**Before pressing Release.** The Release run refuses to start without green
full CI on the commit, so run `ci.yml` on `main` first. Its `export-benchmark`
job builds the R8 release candidate, scans the shrunk dex, and runs the same
`tools/ci/release_device_smoke.sh` as the release (debug key), so an R8 keep-rule
gap fails there, not after signing (v1.0.0 run 42938059021).

- [ ] Confirm a repository ruleset covers `refs/tags/v*` and forbids tag updates
  and deletion (creation stays allowed, so Publish can create the tag). The
  workflow checks between jobs that nobody created the tag on another commit;
  the ruleset keeps the published tag immutable afterward.
- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts` (currently `15` / `"1.0.0"`).
- [ ] Update `CHANGELOG.md` for the new version.
- [ ] Note: the release build now runs **R8** (`isMinifyEnabled = true`, `app/build.gradle.kts:64`).
  This is **Stage 1 — shrink only, `-dontobfuscate`** (`app/proguard-rules.pro:2`), with explicit
  keep-rules for the four name-based JNI boundaries (`com.spectrafilm.engine.**`, `RawDecoder`,
  `TiffWriter`, `PngWriter`, `native <methods>`) and enum value/`valueOf` persistence. Because the
  JNI symbols resolve classes/methods by literal string from C++, a missing keep-rule surfaces only
  at runtime — sanity-check that a release build still loads native libs and exports/decodes before
  publishing. The release workflow now scans the shrunk DEX and runs the exact signed
  candidate on API 35; `android-emulator` remains an advisory debug-build job. R8 Stage-2 +
  `shrinkResources` remain open (`-dontobfuscate` is still set in `app/proguard-rules.pro`; no ticket).
- [ ] Confirm CI is green on `main`. The relevant gating jobs in `.github/workflows/ci.yml` are:
  - `engine-native` — host C++ build of libspektra.
  - `engine-parity` — the stage parity gate (deterministic goldens, thread-invariance).
  - `libraw-hostile` — shipping-serial and OpenMP-required sanitizer suites,
    serial TSan first-use regression, and both bounded fuzz targets.
  - `parity` — the `.spkvec` comparator self-test.
  - `python-lint` — byte-compile plus deterministic compliance and release-policy tests.
  - `android` — JVM unit tests + **`:app:lint`** (`ci.yml:379-388`; a hard gate —
    `abortOnError = true`, no baseline since #298) + full debug assemble,
    an NDK 27 x86_64 standalone-recipient relink smoke (SONAME, JNI/marker exports,
    and 16 KiB `PT_LOAD` alignment),
    **including the 16 KB-page-alignment
    check** (`zipalign -c -v -P 16 4 <apk>` and `readelf -lW` requiring `LOAD` offset `0x4000`
    on every 64-bit `arm64-v8a`/`x86_64` `.so`; 32-bit ABIs are exempt).
  - (`android-emulator` is manual-dispatch only and not a standing gate.)
- [ ] Confirm the five protected Environment secrets, signer-fingerprint
  variable, required reviewer, immutable-releases setting, and tag ruleset are
  configured exactly as described in §4.
- [ ] Re-run the privacy boundary suites: `AppUpdaterTest`, `UpdateDownloadPolicyTest`,
  `UpdateVerifierTest`, `UpdateControllerTest`, `DiagnosticsTest`, and
  `BackupSecurityConfigTest`. Inspect the merged release manifest and require the
  narrow backup/data-extraction rules, cleartext denial, the exact updater domains,
  `restoreAnyVersion=false`, `REQUEST_INSTALL_PACKAGES` only with the non-exported
  `UpdateInstallReceiver` (and removed from any Play build), no `READ_LOGS`/broad media-read
  permission, and a non-exported `dataSync` export service. The in-app updater depends on
  the release asset being named exactly `Spektrafilm-vX.Y.Z.apk` and signed with the same
  production key as the previous release; renaming the asset or rotating the key without a
  proof-of-rotation lineage silently turns in-app updates into "Open release page". See
  [the privacy summary](MAINTAINERS.md#privacy) and [the updater contract](MAINTAINERS.md#updater).
- [ ] Reconcile the store privacy policy and Data Safety form with the exact candidate.
  The present app has no automatic telemetry; standard Auto Backup/cross-platform transfer is
  disabled and the OEM-D2D defense-in-depth rules allow only settings/back-exit state, the updater
  contacts GitHub only after a user tap, and diagnostics leave only through an explicit Copy/Share
  target. Treat any new
  SDK, endpoint, permission, backup path, or update/download behavior as a release block
  until code, UI disclosure, tests, and both documents agree.
- [ ] Confirm `lib/libraw/compliance/license-route.txt` records the exact
  human-reviewed SPDX route, currently **LGPL-2.1-only**. Any other value, including
  the repository's pre-election sentinel, is a hard release failure. Including both
  upstream texts is provenance and is not itself an election.
- [ ] Recheck LibRaw OSS-Fuzz mirrors [#840](https://github.com/LibRaw/LibRaw/issues/840)
  and [#843](https://github.com/LibRaw/LibRaw/issues/843). If either discloses
  memory corruption reachable through an enabled codec, keep the release on hold
  until the pinned dependency is patched and its hostile regression is green.
- [ ] Commit version/changelog changes (try a signed commit first; fall back to
  `-c commit.gpgsign=false` only where signing actually fails).

---

## 3. Cutting the release

Do not push a tag. Run **Actions → Release → Run workflow** on `main` with the
version (e.g. `v0.7.0`) and approve `release-signing` when asked.

Expect the unsigned-candidate and protected signing jobs to assemble, gate,
sign, device-verify, and stage `Spektrafilm-v0.7.0.apk` and the deterministic
LibRaw source/relink ZIP on a draft Release (the run summary links it). Paste
the notes into the draft and press **Publish**; that creates tag `v0.7.0` on the
built commit. If anything fails, nothing was tagged: fix it on `main`, run CI,
and press Release again (delete a leftover draft first). The SPDX documents,
seven-lock archive, R8 mapping, full native symbols, release runtime classpath,
provenance, and `SHA256SUMS` stay in the run's signed workflow artifact.

**Verify the published APK** (download from the Release, then):

```bash
# Signature (production keystore, not debug)
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs Spektrafilm-v0.7.0.apk

# Checksum matches the sidecar
sha256sum -c Spektrafilm-v0.7.0.apk.sha256

# Verify every compliance artifact listed by the bundle generator
sha256sum -c SHA256SUMS

# Re-run the fail-closed bundle audit against the downloaded APK
python3 tools/compliance/libraw_bundle.py verify \
  --repo-root . \
  --bundle <libraw-source-relink.zip> \
  --require-resolved

# 16 KB-page alignment (same checks ci.yml's android job runs)
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -v -P 16 4 Spektrafilm-v0.7.0.apk
# and, for each extracted 64-bit lib/*.so:
readelf -lW lib/arm64-v8a/libspektra.so | awk '/LOAD/{print $NF; exit}'   # expect 0x4000
```

---

## 4. Required protected Environment configuration

Create a GitHub Environment named `release-signing`, require the designated
maintainer approval, prevent self-review where the plan supports it, and keep
all production signing values out of repository-level secrets. Configure these
Environment secrets:

- `SIGNING_KEYSTORE` — base64 of `spectrafilm-release.jks`.
- `SIGNING_KEY_ALIAS` — keystore key alias.
- `SIGNING_KEYSTORE_PASSWORD` — store password.
- `SIGNING_KEY_PASSWORD` — key password.

No publishing token is needed: the draft is staged with the workflow's
built-in `GITHUB_TOKEN` (`contents: write` on the protected job only). An old
`RELEASE_GITHUB_TOKEN` secret is unused and can be deleted.

Configure this Environment variable:

- `RELEASE_CERT_SHA256` — the 64-hex SHA-256 fingerprint of the only accepted
  APK signing certificate (colons and case are normalized by the workflow).

Also enable repository immutable releases and protect `refs/tags/v*` against
updates/deletion. If any signing input or
certificate fingerprint is empty/invalid, the job fails closed. The built-in
token cannot read the immutable-releases setting, so confirm it in the
repository settings once; the published release page shows the lock.

---

## 5. Post-release

- [ ] Open the immutable published Release; confirm exactly two assets are
  attached: the APK and the LibRaw source/relink ZIP. Download the run's
  `signed-release-*` artifact if you need the SPDX files, mapping, symbols,
  locks, provenance or `SHA256SUMS` (kept 90 days).
- [ ] Verify the APK is production-signed and 16 KB-aligned (§3).
- [ ] Close/update issues resolved by the release; keep `CHANGELOG.md` current.
- [ ] **Do not** commit the APK anywhere in the repo — the release artifacts live only on the
  GitHub Release. There is intentionally no `dist/` directory.
- [ ] The attribution **"Film modeling powered by spektrafilm"** must remain in the app and
  release notes (GPLv3 requirement).

---

### Sources

- `.github/workflows/release.yml` (exact candidate gates and attestation,
  run/attempt/artifact-ID binding, protected signing and cleanup, API 35
  instrumentation, provenance, tag-state checks, draft staging).
- `tools/release/` (deterministic app SPDX, fixed gate attestation, release
  preconditions, tag-state check, draft stager, and structural regression tests).
- `.github/workflows/ci.yml` (standing gates, including LibRaw and 16 KB
  `zipalign -P 16` plus `readelf -lW` checks).
- `app/build.gradle.kts:12-22` (optional local release keystore; absent means
  unsigned), `:39-40` (versionCode 12 / 0.10.0), and the release build type (R8,
  full native symbols, and optional
  real release signing; never a debug-key fallback).
- `app/proguard-rules.pro` (R8 Stage-1 keep-rules: JNI boundary classes + enum persistence,
  `-dontobfuscate`).
