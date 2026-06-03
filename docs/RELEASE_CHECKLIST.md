# Maintainer Release Checklist

> **⚠️ LARGELY OBSOLETE — releases are automated; do NOT commit APKs.** Releasing is now handled by
> `.github/workflows/release.yml`: pushing a `v*` tag builds a **signed** APK (from keystore
> secrets) and publishes it + its `.sha256` as a **GitHub Release** asset via `gh release create`.
> v0.7.0 already shipped this way. Consequently:
> - **Do not** copy/commit APKs into `dist/` — that directory was **removed** (the old committed
>   APKs were stale, 16 KB-page-misaligned and debug-signed). Ignore every `dist/`-commit step below.
> - Signing is **not** a debug-fallback in CI — it is real, from secrets.
> - The "cannot push tags / cannot create Releases" premise no longer holds for the release pipeline.
> - **Missing here and worth adding:** the **16 KB-page-alignment gate** (`zipalign -c -P 16` + every
>   arm64/x86_64 `.so` must show `LOAD 0x4000` via `readelf -lW`) — this is a hard Android-15 release
>   requirement that CI enforces but this checklist never mentions.
>
> The version-specific (v0.3.0) tag/notes template below is kept only as a historical example. This
> file should be rewritten to "how to cut a tag + what `release.yml` does."

This document is for the **project maintainer only**. It covers publishing steps that cannot be
automated from the development environment (git tag proxy restrictions, no GitHub Release API in
the tooling, no `edit-repository` MCP method).

---

## Environment constraints — why this file exists

The development environment used to build and commit Spektrafilm:

- **Cannot push git tags** — the git proxy returns 403 on `refs/tags/*` pushes. Local tags
  `v0.1.0` and `v0.2.0` exist in the repo but are not yet on GitHub.
- **Cannot create GitHub Releases** — the GitHub MCP tools available do not expose a
  create-release or upload-release-asset endpoint.
- **Cannot set repository "About" metadata** — description, topics, and homepage must be set
  via the GitHub web UI at
  https://github.com/thetechgeekko/Spectrafilmandroid/settings (or the gear icon on the
  repo's main page).

Everything else (engine, APK, changelog) is committed and ready.

---

## 1. Pre-release security review

Before tagging and publishing any release:

- [ ] Run `git diff main...HEAD` and review all changes since the last published release.
- [ ] Check for accidental inclusion of secrets: signing keys, `keystore.properties`,
  `.env` files, or any file matching `*.jks` / `*.p12` / `*.keystore`.
- [ ] Verify `keystore.properties` is listed in `.gitignore` and is **not** committed.
- [ ] Confirm CI is green on `main` (all jobs: `engine-native`, `engine-parity`, `parity`,
  `python-lint`, `android`).
- [ ] Optionally run the `/security-review` skill on the branch before merging.

---

## 2. Signed-release key — current state and where to add it

**Current state:** `assembleRelease` falls back to the **debug signing config** when
`keystore.properties` is absent (which is the case in CI and in this repo). The APKs in
`dist/` are therefore **debug-signed** and will install on any device with "unknown sources"
enabled, but are **not suitable for Play Store submission**.

**To enable proper release signing:**

1. Generate a release keystore once:
   ```
   keytool -genkeypair -v -keystore spectrafilm-release.jks \
       -keyalg RSA -keysize 4096 -validity 10000 \
       -alias spectrafilm
   ```
   Store the `.jks` file securely **outside** the repository.

2. Create `keystore.properties` at the project root (already `.gitignore`-listed — do not
   commit this file):
   ```
   storeFile=/absolute/path/to/spectrafilm-release.jks
   storePassword=<your_store_password>
   keyAlias=spectrafilm
   keyPassword=<your_key_password>
   ```

3. Run `./gradlew :app:assembleRelease` — Gradle (`app/build.gradle.kts` lines 33–53) reads
   `keystore.properties` automatically and uses the release signing config.

4. The resulting APK at `app/build/outputs/apk/release/app-release.apk` is release-signed.
   Copy it to `dist/Spektrafilm-vX.Y.Z.apk` and regenerate the SHA-256:
   ```
   sha256sum dist/Spektrafilm-vX.Y.Z.apk > dist/Spektrafilm-vX.Y.Z.apk.sha256
   ```

> **Note:** for Play Store distribution, also configure `bundleRelease` to produce an `.aab`.
> Side-loaded installs from `dist/` use the `.apk` directly.

---

## 3. Create and push the git tag

The local repo already has tags `v0.1.0` and `v0.2.0`; push them (and future tags) from a
machine where the git remote accepts tag refs:

```bash
git push origin v0.1.0
git push origin v0.2.0
```

For a new release, tag first, then push:

```bash
git tag -a v0.3.0 -m "Spektrafilm v0.3.0"
git push origin v0.3.0
```

**Shortcut for v0.3.0:** the release commit is already pushed as the branch
**`release/v0.3.0`** (branch pushes succeed even though the build env's tag pushes 403).
From any machine with normal GitHub access you can tag straight off it — no re-fetch of
the wave needed:

```bash
git fetch origin
git tag -a v0.3.0 origin/release/v0.3.0 -m "Spektrafilm v0.3.0"
git push origin v0.3.0
```

Or skip the CLI entirely: GitHub's "Create release" page can create the `v0.3.0` tag
on the fly with **Target: `release/v0.3.0`** (see §4).

---

## 4. Create the GitHub Release

Navigate to https://github.com/thetechgeekko/Spectrafilmandroid/releases/new and:

- [ ] **Tag:** `v0.3.0`. **Target:** `release/v0.3.0` (or `main` once PR #8 is merged) — GitHub
  will create the tag on publish if it doesn't exist yet.
- [ ] **Release title:** `Spektrafilm v0.3.0`
- [ ] **Description:** paste the template below (mirrors the `## v0.3.0` block in `CHANGELOG.md`).
- [ ] **Attach files:** `dist/SpectraFilm-v0.3.0.apk` and `dist/SpectraFilm-v0.3.0.apk.sha256`
  — **BUT** see §2: the committed APK is **debug-signed**. Rebuild with the real keystore and
  re-attach before publishing a non-pre-release.
- [ ] **Mark as pre-release** until on-device testing (§ device smoke test) closes issue #5.
- [ ] Click **Publish release**.

### Release notes template (paste into the GitHub Release description)

```
## Spektrafilm v0.3.0

Lightroom-style UI redesign, new bit-exact engine stages, and a major export/import upgrade.

**UI redesign** — full edge-to-edge layout; pinned preview with a 90° rotate button; a
horizontal scrollable bottom bar of custom category icons; inline adjustment panel; system
Back navigates within the app (double-back to exit). Settings (gear) and About ("?") icons.

**Engine (bit-exact vs the spektrafilm reference):** auto-exposure with 7 metering patterns,
diffusion filters, and camera/scanner lens blur. The print path now works with every
film/paper combination (previously limited).

**Editing & export:** opt-in "Auto" exposure with a metering-method popup; RAW white-balance;
a profile-curve browser; non-destructive recipe editing (your edits are saved per-image, the
original is never touched). Export to 16-bit TIFF, 16-bit PNG, or Google Ultra HDR, with the
source photo's EXIF preserved (GPS/location is opt-in, off by default for privacy).

**RAW/DNG:** Samsung Expert RAW (DEFLATE-compressed) DNGs now decode; lossy/JPEG-XL DNGs fall
back to the system decoder. Imported photos are auto-rotated to their correct orientation.

**Under the hood:** memory-safety hardening (reviewed + fuzzed), parity test coverage across
all new stages.

**Install:** download the APK, enable "Install from unknown sources," open. Min Android 7.0 (API 24).
Verify: `sha256sum SpectraFilm-v0.3.0.apk` and compare to the `.sha256` file.

Film modeling powered by [spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato.
Dedicated to the [pixls.us](https://pixls.us) community. GPLv3.
```

---

## 5. Set the repository "About" metadata

On the repo's main page, click the gear icon next to "About" (top-right of the description
panel) and paste the following:

### Description (≤ 350 characters)

```
Physically-based spectral film simulation for Android. Ports the spektrafilm engine (C++/NDK, bit-exact parity). Full negative→print→scan pipeline, RAW/DNG import, 28 film stocks, non-destructive presets. GPLv3. Dedicated to the pixls.us community.
```

### Topics (enter each as a separate tag)

```
android
film-simulation
spectral-rendering
jetpack-compose
ndk
kotlin
photography
raw-processing
film-photography
libraw
spektrafilm
open-source
gpl
pixls-us
```

### Website / Homepage

```
https://github.com/thetechgeekko/Spectrafilmandroid
```

(Leave as the repo URL unless you later set up a dedicated landing page or pixls.us thread link.)

---

## 6. Post-release

- [ ] Announce on [discuss.pixls.us](https://discuss.pixls.us) — the community this project is
  dedicated to. Link to the Release page and the spektrafilm original thread.
- [ ] Update the in-app `versionName` in `app/build.gradle.kts` (line 29) and `versionCode`
  before the next release cycle.
- [ ] Close or update GitHub issues that are resolved by the release (currently: check #5
  once on-device testing is complete).
- [ ] Keep `CHANGELOG.md` and `docs/ROADMAP.md` up to date as features land.

---

## Issue #5 / #6 / #7 — current status (as of 2026-05-30)

| Issue | Description | Status |
|-------|-------------|--------|
| #5 | On-device smoke-test. | **DONE (2026-05-31).** Verified on a real Samsung Galaxy S25 Ultra (Android 16, arm64-v8a) — see `docs/DEVICE_TEST_REPORT.md`. Native libs load; 16-bit PNG/TIFF + spec-correct Ultra HDR exports; **Samsung Expert RAW decodes via LibRaw** (no crash/fallback); source EXIF retained, GPS stripped. The pass also found and fixed a **full-resolution export bug** (12 MP exported at ~3 MP; PR #21). Remaining for a follow-up device pass: the lossy/JPEG-XL DNG fallback branch, the GPS Settings toggle, and the subjective visual checks (rotate/orientation/presets/persistence). |
| #6 | Some exposed params are inert (gated in UI). | **Essentially resolved.** Crop/resize geometry, auto-exposure (all 7 metering patterns), diffusion filters, lens blur (camera + scanner), and **scanner LUT acceleration** (`use_scanner_lut`, opt-in/default-off, default path byte-identical; LUT-on within ~4e-5 @ res17 / ~5e-7 @ res64) are all wired, parity-gated. **Remaining (minor):** `use_enlarger_lut` is reserved/unwired (the enlarger spectral chain is more involved); and **glare-on-print** is wired but default-off — it's stochastic, so it can't be made bit-exact vs the reference. No user-facing control is now falsely gated. |
| #7 | Full-res RAW memory/perf risk (no tiling or GPU path yet). | **Open** — partial app-side mitigation added (OOM-retry ladder in `decodeRawToLinear`); native tiling/NEON/GPU still deferred to M6/M7. |

> **Maintainer note — version & dist APK (DONE this wave):** `versionCode` is bumped to **2** and
> `versionName` to **0.3.0** in `app/build.gradle.kts`, and `dist/SpectraFilm-v0.3.0.apk` (+ `.sha256`)
> is committed — rebuilt **with the security fixes**. ⚠️ It is **debug-signed** (the `keystore.properties`
> fallback). Before publishing a non-pre-release, do §2 (generate the real keystore), run
> `./gradlew :app:assembleRelease`, and **re-copy** the signed APK to `dist/SpectraFilm-v0.3.0.apk`
> + regenerate the `.sha256`. The release commit is also pushed as branch **`release/v0.3.0`** for
> easy tagging/Release creation (see §3/§4).
>
> **Security review (DONE this wave):** a pre-release review found 2 blockers + hardening items.
> Fixed in-code: JNI >2 GiB allocation guards, writer buffer-capacity checks, PNG overflow guard,
> and **GPS-on-export is now opt-in (default off)**. The remaining blocker is the **debug-signing**
> above. The DNG sniffer (untrusted-input parser) was **fuzzed** (~795k execs, ASan+UBSan, 0 crashes)
> via `lib/libraw/src/test/cpp/fuzz_dng_sniffer.cpp`.
