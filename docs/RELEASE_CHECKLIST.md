# Maintainer Release Checklist

Releases are **automated**. Pushing a `v*` tag triggers `.github/workflows/release.yml`, which
builds a production-signed APK from keystore secrets and publishes it (plus a `.sha256`) as a
GitHub Release asset. **APKs are not committed to the repository** — there is no `dist/` directory
and you should never copy a built APK into the repo.

Current shipping version: **v0.7.0 / versionCode 9** (`minSdk 24`, `targetSdk`/`compileSdk 34`).

---

## 1. How a release happens (the automated flow)

`.github/workflows/release.yml` fires on:

- a pushed tag matching `v*` (e.g. `v0.7.0`), or
- a manual `workflow_dispatch` with an existing tag name as input.

The single `build-signed-apk` job (Ubuntu runner) then:

1. Checks out the tagged ref.
2. Sets up JDK 17, the Android SDK, and installs **NDK `27.0.12077973`, CMake `3.22.1`,
   build-tools `35.0.0`** (the toolchain that produces 16 KB-page-aligned native segments).
3. Decodes the keystore from `secrets.SIGNING_KEYSTORE` (base64) to `spectrafilm-release.jks`
   and writes a `keystore.properties` from the other signing secrets.
4. Runs `./gradlew :app:assembleRelease`. Because `keystore.properties` is present,
   `app/build.gradle.kts` uses the **real release signing config** (not the debug fallback).
5. Verifies the APK signature with `apksigner verify --print-certs`.
6. Stages `Spektrafilm-<version>.apk` plus a `sha256sum` `.sha256` sidecar.
7. Uploads them as a workflow artifact, then attaches them to the GitHub Release: it calls
   `gh release upload --clobber` if a Release for the tag already exists, otherwise
   `gh release create --generate-notes` to create one.
8. Always removes `spectrafilm-release.jks` and `keystore.properties` from the runner
   (`if: always()` cleanup step).

> Note: `release.yml` does **not** itself run the 16 KB-page alignment check, and it does **not**
> run engine parity or unit tests. Those gates live in `ci.yml` and must be green on `main`
> *before* you tag (see §2). The release job builds with the correct toolchain, so an APK built
> from a green commit will be aligned, but the release workflow does not re-verify it.

---

## 2. Pre-tag checklist

- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts` (currently `9` / `"0.7.0"`).
- [ ] Update `CHANGELOG.md` for the new version.
- [ ] Note: the release build now runs **R8** (`isMinifyEnabled = true`, `app/build.gradle.kts:53`).
  This is **Stage 1 — shrink only, `-dontobfuscate`** (`app/proguard-rules.pro:2`), with explicit
  keep-rules for the four name-based JNI boundaries (`com.spectrafilm.engine.**`, `RawDecoder`,
  `TiffWriter`, `PngWriter`, `native <methods>`) and enum value/`valueOf` persistence. Because the
  JNI symbols resolve classes/methods by literal string from C++, a missing keep-rule surfaces only
  at runtime — sanity-check that a release build still loads native libs and exports/decodes before
  publishing (the `android-emulator` job is manual-only and not a standing gate). Obfuscation
  (Stage 2) is still deferred.
- [ ] Confirm CI is green on `main`. The relevant gating jobs in `.github/workflows/ci.yml` are:
  - `engine-native` — host C++ build of libspektra.
  - `engine-parity` — the stage parity gate (deterministic goldens, thread-invariance).
  - `parity` — the `.spkvec` comparator self-test.
  - `python-lint` — byte-compile of the parity harness scripts.
  - `android` — JVM unit tests + full debug assemble, **including the 16 KB-page-alignment
    check** (`zipalign -c -v -P 16 4 <apk>` and `readelf -lW` requiring `LOAD` offset `0x4000`
    on every 64-bit `arm64-v8a`/`x86_64` `.so`; 32-bit ABIs are exempt).
  - (`android-emulator` is manual-dispatch only and not a standing gate.)
- [ ] Confirm the four signing secrets are configured on the repo (see §4).
- [ ] Commit version/changelog changes with `-c commit.gpgsign=false`.

---

## 3. Cutting the release

Tag and push from a machine with normal GitHub access:

```bash
git tag -a v0.7.0 -m "Spektrafilm v0.7.0"
git push origin v0.7.0
```

(Or trigger `release.yml` manually via **Actions → Release → Run workflow** with the tag name.)

Expect the `build-signed-apk` job to assemble, sign-verify, and publish
`Spektrafilm-v0.7.0.apk` + `.sha256` to the Release for that tag.

**Verify the published APK** (download from the Release, then):

```bash
# Signature (production keystore, not debug)
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs Spektrafilm-v0.7.0.apk

# Checksum matches the sidecar
sha256sum -c Spektrafilm-v0.7.0.apk.sha256

# 16 KB-page alignment (same checks ci.yml's android job runs)
$ANDROID_HOME/build-tools/35.0.0/zipalign -c -v -P 16 4 Spektrafilm-v0.7.0.apk
# and, for each extracted 64-bit lib/*.so:
readelf -lW lib/arm64-v8a/libspektra.so | awk '/LOAD/{print $NF; exit}'   # expect 0x4000
```

---

## 4. Required GitHub secrets

`release.yml` references these (set via `gh secret set`):

- `SIGNING_KEYSTORE` — base64 of `spectrafilm-release.jks`.
- `SIGNING_KEY_ALIAS` — keystore key alias.
- `SIGNING_KEYSTORE_PASSWORD` — store password.
- `SIGNING_KEY_PASSWORD` — key password.

If `SIGNING_KEYSTORE` is empty the job fails fast. (`GITHUB_TOKEN` is provided automatically and
used for the `gh release` calls.)

---

## 5. Post-release

- [ ] Open the published Release; confirm the `.apk` and `.sha256` assets are attached.
- [ ] Verify the APK is production-signed and 16 KB-aligned (§3).
- [ ] Close/update issues resolved by the release; keep `CHANGELOG.md` current.
- [ ] **Do not** commit the APK anywhere in the repo — the release artifacts live only on the
  GitHub Release. There is intentionally no `dist/` directory.
- [ ] The attribution **"Film modeling powered by spektrafilm"** must remain in the app and
  release notes (GPLv3 requirement).

---

### Sources

- `.github/workflows/release.yml:14-21` (triggers), `:60-82` (keystore secrets → `keystore.properties`),
  `:84-92` (assemble + `apksigner verify`), `:94-106` (`.sha256` staging), `:114-131`
  (`gh release create`/`upload`), `:133-136` (keystore cleanup).
- `.github/workflows/ci.yml:32-176` (gating jobs), `:208-228` (16 KB `zipalign -P 16` +
  `readelf -lW` `LOAD 0x4000` check).
- `app/build.gradle.kts:10-17` (keystore.properties read), `:35-36` (versionCode 9 / 0.7.0),
  `:53` (`isMinifyEnabled = true` — R8 shrink), `:60-66` (real-keystore-else-debug signing).
- `app/proguard-rules.pro` (R8 Stage-1 keep-rules: JNI boundary classes + enum persistence,
  `-dontobfuscate`).
</content>
</invoke>
