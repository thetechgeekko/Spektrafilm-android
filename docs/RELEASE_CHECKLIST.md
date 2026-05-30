# Maintainer Release Checklist

This document is for the **project maintainer only**. It covers publishing steps that cannot be
automated from the development environment (git tag proxy restrictions, no GitHub Release API in
the tooling, no `edit-repository` MCP method).

---

## Environment constraints — why this file exists

The development environment used to build and commit SpectraFilm:

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
   Copy it to `dist/SpectraFilm-vX.Y.Z.apk` and regenerate the SHA-256:
   ```
   sha256sum dist/SpectraFilm-vX.Y.Z.apk > dist/SpectraFilm-vX.Y.Z.apk.sha256
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
git tag -a v0.3.0 -m "SpectraFilm v0.3.0"
git push origin v0.3.0
```

---

## 4. Create the GitHub Release

Navigate to https://github.com/thetechgeekko/Spectrafilmandroid/releases/new and:

- [ ] **Tag:** select the tag you just pushed (e.g. `v0.2.0`). Target: `main`.
- [ ] **Release title:** `SpectraFilm v0.2.0`
- [ ] **Description:** paste the relevant section from `CHANGELOG.md` (the `## v0.2.0` block).
  The changelog uses Markdown — GitHub renders it correctly as-is.
- [ ] **Attach files:**
  - `dist/SpectraFilm-v0.2.0.apk`
  - `dist/SpectraFilm-v0.2.0.apk.sha256`
- [ ] **Mark as pre-release** if this is a development/beta build (currently recommended;
  flip to "Latest release" once on-device testing closes issue #5).
- [ ] Click **Publish release**.

### Release notes template (paste into the GitHub Release description)

```
## SpectraFilm v0.2.0

Turning the engine into a real, playable tool.

- **Full parameter surface wired** — every SpektraParams field reaches the engine; defaults stay bit-exact.
- **RAW/DNG import** via LibRaw (libsfraw.so, arm64/armeabi-v7a/x86_64) → linear ACES; plus photo picker and synthetic demo image.
- **Full GUI organized like the spektrafilm desktop** — 10 collapsible sections, debounced live preview.
- **Presets:** 20 built-in researched looks + save / import / export your own as JSON.
- **28 film/paper stock catalog** with friendly names, ISO, era, character.
- **Custom adaptive icon** (35 mm frame + spectral strip; Material You monochrome).
- **Export to gallery** with full-resolution render mask.
- **Crop/resize geometry stage** (bit-exact) — IOParams crop fields and cubic upscale_factor are now live, matching the spektrafilm _preprocess step. Defaults are a strict no-op.
- **RAW white-balance UI** — Temperature/Tint sliders + WB-mode dropdown (as-shot / daylight / tungsten / custom) + reset, shown only for RAW/DNG sources; changing WB re-decodes the preview.
- **Welcome / onboarding, Settings, and in-app About** — credits, attribution, and a "Report an issue" shortcut.

**Install:** download the APK, enable "Install from unknown sources," open. Min Android 7.0 (API 24).
Verify the download: `sha256sum SpectraFilm-v0.2.0.apk` and compare to the `.sha256` file.

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
| #5 | On-device smoke-test. | **Needs reconciliation.** Issue #5 was closed on GitHub, but the app has never been run on a real physical device in this environment (the sandbox emulator has no `/dev/kvm` — software emulation is too slow and only ever reaches the welcome screen). The CI `android-emulator` job is gated to manual `workflow_dispatch` and also fails in setup on hosted runners. **Recommended action:** reopen #5 (or add a tracking note) until the redesigned UI, rotate→export flow, back-navigation, and Expert RAW import have been confirmed on an actual device. Maintainer: sideload `app/build/outputs/apk/debug/app-debug.apk` and smoke-test. |
| #6 | Some exposed params are inert (gated in UI). | **Substantially addressed.** Crop/resize geometry, auto-exposure (all 7 metering patterns), and diffusion filters are now ported, bit-exact, and parity-gated. **Remaining gated stages: lens blur and LUT acceleration.** Glare-on-print is a known gap being addressed in a separate work stream. |
| #7 | Full-res RAW memory/perf risk (no tiling or GPU path yet). | **Open** — deferred to M6/M7 (tiling, NEON SIMD, optional GPU accelerator). |

> **Maintainer note — version bump and dist APK:** the v0.3.0 feature wave (auto-exposure,
> diffusion, TIFF export, EXIF copy, Ultra HDR, UI redesign, Expert RAW fix, recipe layer,
> status pill, profile-curve browser) is committed on the dev branch but `versionCode` /
> `versionName` in `app/build.gradle.kts` have not yet been bumped to v0.3.0, and no updated
> APK has been placed in `dist/`. Another agent is handling the version bump in
> `app/build.gradle.kts`; once that lands, rebuild with `./gradlew :app:assembleDebug` (or
> `assembleRelease` with a signing key) and copy the result to `dist/SpectraFilm-v0.3.0.apk`
> along with a regenerated `.sha256`. This is a **maintainer step** — do not merge to `main`
> or publish a GitHub Release without a current dist APK.
