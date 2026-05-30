# Spektrafilm Android — Session Handoff (v0.3.0 wave, security-hardened)

## State — HEAD `b12492e`, dev branch `claude/sharp-allen-I7wQK`, draft PR #8 (68 commits since v0.2.0 base `4b93acc`)
HEAD == origin, clean tree, 1 worktree. Host parity **14/14 bit-exact**; `:app:assembleDebug` + `:app:assembleRelease` green. CI green on gating jobs. Built by parallel skilled sub-agents across engine/app/lib/docs lanes, each re-verified (parity suite / unified build) by the integrator before merge.

## What landed this session (all on PR #8)
**Engine (bit-exact vs the spektrafilm Python oracle, parity-gated):** crop/resize, auto-exposure (7 metering patterns; JNI forwards `auto_exposure_method`), diffusion filters, lens blur (camera+scanner), **print path generalized to ALL film/paper pairs** (native `print_digest`, `print_ektar` golden); print-route glare wired (default OFF — stochastic, can't be bit-exact); **scanner LUT acceleration WIRED** (`use_scanner_lut`, opt-in/default-OFF → default path byte-identical; LUT-on ~4e-5@res17 / ~5e-7@res64; `use_enlarger_lut` reserved/unwired); robust JNI error propagation. New goldens: scan_portra_crop/autoexp/autoexp_matrix/lensblur, scan_diffusion, diffusion_bpm, print_ektar, lut_accel; new test scanner_lut_e2e.
**App:** Lightroom-style **UI redesign** (edge-to-edge, pinned preview + 90° rotate via single decode path, scrollable custom-icon `SpectraIcons` category bar, inline panel, back→prev-screen + double-back-to-exit + one-time DataStore hint, gear/“?” icons); Lightroom **Auto-exposure** control (default OFF, expandable metering popup); RAW WB UI; profile-curve browser; non-destructive **recipe/sidecar** layer (incl. persisted rotation); render **status pill**; **16-bit TIFF + 16-bit PNG + Ultra HDR** export; **EXIF-orientation WYSIWYG import**; **lossy/JPEG-XL DNG → platform ImageDecoder fallback**; perf (`derivedStateOf` render signal, safe magnifier recycle + debounce, OOM guard).
**lib:** Expert RAW **DEFLATE DNG decode** (`USE_ZLIB`); typed `DecodeStatus`/`RawDecodeException` + DNG compression sniffer/classifier (LJ92/uncompressed native, lossy/JPEG-XL typed for fallback). New modules `lib:tiffwriter` + `lib:pngwriter` (both wired into export; `libsftiff`/`libsfpng` ship for all 3 ABIs).
**Security (pre-release review + fixes — F1/F2/F3/F5/F6):** JNI `>2 GiB` guards before `allocateDirect((jint))` (RAW-decode + engine-output); TIFF/PNG `nativeWriteBuffer` capacity checks; PNG 32-bit-ABI overflow guard; **GPS-on-export opt-in** (`AppSettings.exportKeepGps`, default OFF/strip; Settings toggle). **F7/F10 fuzzed:** `lib/libraw/src/test/cpp/fuzz_dng_sniffer.cpp` (libFuzzer+ASan+UBSan), ~795k execs, 0 crashes.
**Release prep:** `dist/SpectraFilm-v0.3.0.apk` (+ .sha256) rebuilt WITH security fixes — **debug-signed** (fallback). versionCode 1→2, versionName 0.3.0. `tools/device_smoke_test.sh` for on-device verification. CHANGELOG/ROADMAP/README/RELEASE_CHECKLIST synced; PR #8 body rewritten with the maintainer checklist.

## Remaining "what's left" = 5 EXTERNAL-AUTHORITY GATES (cannot be done from this sandbox; verified by attempting)
All documented in `docs/RELEASE_CHECKLIST.md`:
1. **Generate a real release keystore** + `keystore.properties`, re-sign (current dist is debug-signed; intentionally NOT auto-generated — a key the maintainer can't own/rotate is harmful).
2. **Push the `v0.3.0` tag** — the build env's git proxy returns 403 on `refs/tags/*` (confirmed); a local `v0.3.0` tag exists, push it from a normal machine.
3. **Create the GitHub Release** + attach `dist/SpectraFilm-v0.3.0.apk`/.sha256 — no create-release tool in the GitHub MCP set (confirmed).
4. **Set the repo "About"** (description/topics/homepage) — no edit-repository MCP tool; ready-to-paste text in `RELEASE_CHECKLIST.md`.
5. **Run `tools/device_smoke_test.sh` on a real device** (no `/dev/kvm` here) — reconciles issue **#5** (closed on GitHub but never device-run) and validates the device-only features (rotate→export, EXIF-orientation, 16-bit PNG/TIFF, Ultra HDR, Expert RAW import), then review + merge PR #8 to `main`.

## Honest caveats for the device test
Nothing this session was run on a device. Glare-on-print is default-OFF (stochastic). LUT-accel is verified but unwired. Enlarger lens-blur intentionally unwired (no oracle call site). Expert RAW DEFLATE fix verified at build/linkage level, not against a real Expert RAW file.

## Build & verify
- App: `ANDROID_SDK_ROOT=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug` (build cache OFF — keep off).
- Engine parity (the gate): host tests with full source set `spektra.cpp kernels/*.cpp io/*.cpp model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp`, g++ -std=c++17 -O2; tests use repo-root golden paths. CI `engine-parity` gates crop/autoexp(+matrix)/diffusion(+e2e)/lensblur/print_ektar/lut_accel.
- Fuzz: `clang++ -std=c++17 -O1 -g -fsanitize=fuzzer,address,undefined -I lib/libraw/src/main/cpp -include lib/libraw/src/main/cpp/raw_decoder.cpp lib/libraw/src/test/cpp/fuzz_dng_sniffer.cpp -o /tmp/fz` (needs `libclang-rt-18-dev`).
- Commit with `-c commit.gpgsign=false` (signing server 400s here).

## Key docs
`docs/ROADMAP.md` (M0–M7; remaining engine = `use_enlarger_lut` reserved + glare-bit-exactness — both minor/by-design; all UI-exposed params are now functional), `docs/RELEASE_CHECKLIST.md` (the external-authority gates + paste-text), `CHANGELOG.md` (v0.3.0). `/tmp/spectrafilm-handoff.md` mirrors this.

## Suggested skills next session
**verify**/**run** (device smoke test for #5), **code-review** (PR #8 diff before merge), **autopilot** (LUT-accel wiring or a queued item), **docs** (keep in sync as release lands).

## Notes
Attribution "Film modeling powered by spektrafilm" must stay (GPLv3). No model identifier in any committed artifact.
