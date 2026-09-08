# Parity, build, and the native contract

Source: repo doctrine (CLAUDE.md, HANDOFF.md, docs/AUDIT.md, `.github/workflows/ci.yml`).
This is the operational reference for keeping the engine parity-correct and buildable.

## 1. The parity gate (the real definition of "done")

The prime directive: **bit-exact parity with the upstream spektrafilm Python oracle.**

- Tolerance: `max_abs <= 1e-4`, `rms <= 1e-5` (float32 rounding). (CLAUDE.md 14-17)
- Byte-identical across thread counts. **Not** required byte-identical across CPU
  architectures — `-ffast-math` FMA contraction differs by arch.
- A test passes when its stdout contains **no `FAIL` line**. Do not interpret absence of a
  PASS banner as failure or vice-versa — grep for `FAIL`.

### Golden vectors
- Committed in `tools/parity/goldens/` and `tests/*.spkvec`, generated once from the Python
  oracle (`tools/parity/gen_goldens.py`).
- **Golden generation discipline.** Goldens are pinned **per family** to a specific oracle SHA:
  param-wiring/e2e at `c1d0e44` (recorded in `tools/parity/setup_env.sh`), gamut primitives at
  `27bd085`. **NEVER regenerate at oracle tip/HEAD** — check out the pinned SHA in
  `/home/user/spektrafilm` first. Pin the SHA inside each gen script. Oracle env:
  `PYTHONPATH=/home/user/spektrafilm/src:/tmp/spkstubs`. New golden families record their SHA
  in `setup_env.sh`.
- Four intermediate taps are captured for stage-level comparison:
  `film_log_raw`, `film_density_cmy`, `print_density_cmy`, `final_rgb`
  (tap API: `spk_simulate_tap` in `spektra.h`; see the comment block near `spektra.h:307`).
- `tools/parity/` is a standalone `.spkvec` golden-vector comparator with its own CMake +
  ctest self-test (CI `parity` job).

### Measured parity (from README.md 113-126 — cite, do not invent)
- Hanatos upsampling: `max_abs ~ 1.1e-7`
- Filming: `1.2e-7 / 2.4e-7`
- Printing: `2.4e-7 / 5.6e-7`
- Scanning: `~6e-8`
- Halation + scatter + coupler diffusion: `1.5e-7`
- Grain: mean-preserving (stochastic; parity-gated via fixed seeds + statistical refs)

## 2. Host-parity compile + run recipe

Stage tests live in `engine/spektra-core/src/main/cpp/tests/` and run on the **host g++**
toolchain (not NDK); they are not part of the Android library.

```bash
cd engine/spektra-core/src/main/cpp
CPP=$(pwd)
ASSET=../assets/spektra
SRC="spektra.cpp kernels/*.cpp io/*.cpp model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp"
g++ -std=c++17 -O2 -pthread -I. -I../../../../../tools/parity \
  -DSPK_TEST_DIR="\"$CPP/tests\"" \
  tests/<test>.cpp $SRC -o /tmp/<test>
```

`-pthread` is required (`kernels/parallel`). Then run with the **exact argv** the CI
`engine-parity` job uses (copy verbatim from `.github/workflows/ci.yml`; the job defines
`ASSET`, `G` (goldens), `LUT`, `CPP`). The argv per test (as gated):

```
test_simulate_e2e   "$ASSET" "$G/scan_portra" tests/scan_portra_input_rgb.f64 "$G"
test_filming        "$ASSET/profiles/kodak_portra_400.json" "$G/scan_portra" "$LUT" tests/scan_portra_input_rgb.f64
test_spatial        "$ASSET/profiles/kodak_portra_400.json" "$G/scan_portra_spatial" "$LUT" tests/scan_portra_input_rgb.f64
test_crop_resize    "$ASSET" "$G/scan_portra_crop" tests/scan_portra_input_rgb.f64
test_autoexposure   "$ASSET" "$G/scan_portra_autoexp" tests/scan_portra_input_rgb.f64 "$G"
test_diffusion      "$G"
test_diffusion_e2e  "$ASSET" "$G/scan_diffusion" tests/scan_portra_input_rgb.f64 "$G"
test_lut_accel      "$G"
test_scanner_lut_e2e  "$ASSET" "$G/scan_portra" tests/scan_portra_input_rgb.f64
test_enlarger_lut_e2e "$ASSET" "$G/print_portra" tests/scan_portra_input_rgb.f64
test_output_spaces  "$ASSET/profiles/kodak_portra_400.json" "$G/scan_portra" "$CPP/tests"
test_lensblur       "$ASSET/profiles/kodak_portra_400.json" "$G/scan_portra_lensblur" "$LUT" tests/scan_portra_input_rgb.f64
test_parallel       "$ASSET" tests/scan_portra_input_rgb.f64
test_tonecurve      "$ASSET/profiles/kodak_portra_400.json" "$G/scan_portra"
test_half
```

(Excerpt only — the suite has since grown to **36 gates** (downscale, small_preview_aa,
spectral_blur/hanatos_surface/camera_uvir/preflash/print_evcomp/scanner_bwcorr/
provia_couplers/highlight_boost e2e wiring gates, print_curves_morph, np_interp,
gamut_out_aces, gamut_out_oklch, gamut_out_oklrab, gamut_in_xy, bake_lut, params_passthrough,
spatial_decouple_e2e, print_spatial_e2e, lut_cache_e2e). `.github/workflows/ci.yml` is the
authoritative list + argv, and `tools/parity/run_engine_parity.sh` replays the whole suite
locally with the same argv.)

### Fast full-suite replay

Do NOT recompile the full engine source set per test — 36 gates × full rebuild is prohibitively
slow. Compile the engine sources **once** into a static archive, then link each test against it
(same flags: `-std=c++17 -O2 -pthread -I. -I tools/parity -DSPK_TEST_DIR=...`):

```bash
for f in $SRC; do g++ -std=c++17 -O2 -pthread -I. -I../../../../../tools/parity \
  -DSPK_TEST_DIR="\"$CPP/tests\"" -c "$f" -o /tmp/obj/$(basename "$f" .cpp).o; done
ar rcs /tmp/libspk.a /tmp/obj/*.o
# per test: link only tests/<test>.cpp against the archive
g++ -std=c++17 -O2 -pthread -I. -I../../../../../tools/parity \
  -DSPK_TEST_DIR="\"$CPP/tests\"" tests/<test>.cpp /tmp/libspk.a -o /tmp/<test>
```

1 build + 34 links instead of 34 full builds. Loop over the argv table copied from
`.github/workflows/ci.yml` and grep each run's output for `FAIL`.

`SPK_NUM_THREADS` overrides `std::hardware_concurrency()`. The parity tests pin `1` vs `8` to
prove byte-identical output.

### Benchmarking doctrine

- Harness: `tests/bench_stages.cpp` — **local-only, not a CI gate.**
- Decompose stage costs via the `spk_simulate_tap` intermediate taps
  (`film_log_raw` / `film_density_cmy` / `print_density_cmy` / `final_rgb`). The
  `film_density_cmy` / `print_density_cmy` taps **bypass the memos**; a fresh engine per rep
  = fully cold.
- Report **medians at a fixed size** (512x512) with `SPK_NUM_THREADS` pinned.
- **NEVER compare absolute ms across containers/CPU generations** — 2-core vs 4-core boxes
  differ 2x on parallel-dominated paths. Re-baseline cold AND warm on the current box
  before/after every perf change. Current 4-core baseline (8 threads, 512x512): warm print
  edits 153-162 ms vs 402 cold; warm scan 144-159 vs 243; S4 cold scan 243 -> 211 ms.
- Scenario-5-style repeat medians are **STEADY-STATE**: only rep 1 pays a memo MISS.

## 3. CI jobs (`.github/workflows/ci.yml`)

- **`engine-native`** — host C++ build of `libspektra`.
- **`engine-parity`** — the 34-test stage gate (run on push/PR; see ci.yml for the list).
- **`parity`** — `.spkvec` comparator self-test (`tools/parity/` CMake + ctest).
- **`python-lint`**.
- **`android`** — `:app:testDebugUnitTest` + full assemble for all ABIs.
- **`android-emulator`** — manual dispatch only.
- `release.yml` — builds a signed APK from keystore secrets on a `v*` tag push and creates the
  GitHub Release.

## 4. Build toolchain pins (do not deviate)

- **NDK r27 only: `27.0.12077973`.** First NDK to guarantee 16 KB page-aligned `LOAD`
  segments; required by Android 15+. Wrong NDK -> `dlopen` failure on 16 KB devices.
- **CMake 3.22.1**, **build-tools 36.0.0** (AGP 9.3 minimum; has `zipalign -P 16`), **JDK 21** (temurin).
  `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1" "build-tools;36.0.0"`.
- **Engine Release C++ flags:** `-O3 -ffast-math -fno-finite-math-only`
  (`engine/spektra-core/src/main/cpp/CMakeLists.txt:12`). Host-parity tests use `-O2` (above).
- **`-fno-finite-math-only` is NON-NEGOTIABLE.** Scanning's `density_to_light` (`10^-density`)
  relies on NaN propagation to match the oracle's profile-null handling (profile null = NaN,
  must collapse to 0). Stripping it makes NaN handling undefined and **breaks parity silently**.
- 16 KB page alignment is CI-gated: every `arm64-v8a`/`x86_64` `.so` must have `0x4000` `LOAD`
  alignment (`readelf -lW`), and `build-tools/36.0.0/zipalign -c -P 16 4 <apk>` must pass.

### Build commands

```bash
ANDROID_SDK_ROOT=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :app:assembleDebug          # builds libspektra/libsfraw/libsftiff/libsfpng for 3 ABIs
./gradlew :app:testDebugUnitTest        # only automated Kotlin test layer
./gradlew :app:lint                     # abortOnError=true; baseline app/lint-baseline.xml
```

ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. `minSdk 24`, `targetSdk`/`compileSdk 34`.

## 5. Native modules (`settings.gradle.kts`)

- **`:engine:spektra-core`** -> `libspektra.so`; package `com.spectrafilm.engine`. Bundles
  film/paper profiles, spectral LUTs, ICC under `src/main/assets/spektra/`.
- **`:lib:libraw`** -> `libsfraw.so` (LibRaw -> linear ACES RGB for RAW/DNG).
- **`:lib:tiffwriter`** -> `libsftiff.so`, **`:lib:pngwriter`** -> `libsfpng.so` (16-bit export).
- **`:app`** -> `com.spectrafilm.app`, the application; all UI lives here.

Ignore `feature/film-emulation/` and the aspirational `core/`/feature layout in
`docs/ARCHITECTURE.md` — that doc is the *target* design; `feature:film-emulation` is not in
`settings.gradle.kts` and is not compiled. The real app is the standalone `:app` module.

## 6. JNI marshalling contract

- The single native boundary is `spektra_jni.cpp`.
- Image buffers cross as **interleaved float32 RGB, row-major, direct `ByteBuffer`** (no
  per-pixel JNI). (`spektra_jni.cpp:6-10`, `spektra.h:60-66`)
- Buffers are owned by the engine and freed with `spk_image_free`.
- **Full-res export uses off-heap native memory:** `malloc` + `NewDirectByteBuffer` (true
  native memory), **not** `ByteBuffer.allocateDirect` (256 MB ART limit). Wrapped
  `AutoCloseable`. (`ImagePipeline.kt:126-148`, HANDOFF.md 153-162)
- Per the JNI design note, field/method IDs are cached on first use (`spektra_jni.cpp:11`).

## 7. Engine lifecycle & thread safety

- The engine is **immutable and thread-safe**: `SpektraEngine` holds a shared `spk_engine`
  handle that never mutates (HANDOFF.md 26).
- **Process-scoped singleton `EngineHolder`** (Kotlin `object`): one instance across config
  changes, avoids leaks. `close()` is idempotent.
- **Config-change crash fix:** native engine leaked on rotation -> use-after-free; the fix was
  a process-scoped `EngineHolder` + `rememberSaveable` (HANDOFF.md 23-25). Do not regress this
  by tying engine lifetime to an Activity/Composable.
- Preview vs scan share one code path: preview downscales to `preview_max_size` (default
  640 px); scan is full-res. Decode + simulate run off the main thread.

### Thread-invariance
- Per-pixel stages run on the **deterministic fork-join** in `kernels/parallel.cpp`; output is
  byte-identical for any worker count. `test_parallel` asserts this (`SPK_NUM_THREADS={1,8}`).
- Never introduce a reduction order or accumulator that depends on worker count.

### Deterministic parallelization rules (S4)

- Per-pixel loops with **disjoint writes and no cross-iteration state** MAY use `parallel_for`:
  chunk bounds are a pure function of `(count, threads)`, so output is byte-identical for any
  thread count. Prove every newly parallelized loop with `SPK_NUM_THREADS` 1 vs 8
  (`test_parallel`, fresh engine per thread count).
- **MUST stay serial:** grain (seeded RNG walk in pixel order) and the recursive
  gaussian/exponential filters (row-to-row data dependency).
- Already parallel: the `scan()` and `expose` main loops, and `print_expose`.

### LUT caching
- The spectral LUT (~10 MB) is loaded once; the filming `tc_lut` is memoized per profile id.
  Memoization is byte-identical, verified by `test_simulate_e2e` warm-vs-fresh `memcmp`.
- Enlarger/scanner 3D-LUT accelerators are **opt-in, NOT bit-exact, default off**.

### Two-memo architecture

The engine's caching semantics — the single most important thing NOT to break when adding a
param:

- **S1 film-density memo.** Per-route single slot (the scan and print routes each keep one).
  Key folds rgb-content ⊕ dims ⊕ color space ⊕ **every filming-affecting param**, incl. the
  full Option-A deterministic spatial set. Bypassed only for debug taps + grain (stochastic).
  Key-completeness is **TEST-ENFORCED per param**: `test_simulate_e2e` does warm → tweak →
  assert MISS + byte-identical-to-cold for each key member.
- **S2 print-density memo.** Keys `print_expose` + `print_develop` on the `film_density_cmy`
  buffer **CONTENT hash** ⊕ every printing input ⊕ the tc_lut-shaping film params (spectral
  blur, hanatos window/surface, camera UV/IR, input_gamut_compress — the midgray factor reads
  the tc_lut directly, not through the film bytes). Output-only edits rerun `scan()` alone.
  The content hash makes it correct even when the film memo bypasses (grain: seeded →
  identical bytes → HIT), so it works with grain ON.
- **RULE:** any new engine param MUST be folded into every memo key whose stage consumes it,
  and gets a key-completeness test case — `test_simulate_e2e`'s per-param check FAILS if you
  forget. Run it after adding any param.

## 8. Open AUDIT items / gotchas (docs/AUDIT.md)

- **Formerly-inert spektral params** (hanatos window/surface, `spectral_gaussian_blur`, camera
  UV/IR, preflash, print EV comp, scanner B/W corrections) are now **WIRED end-to-end**, each
  gated by its own `*_e2e` parity test with goldens pinned to oracle `c1d0e44`. Remaining
  disclosure-only items: MALLETT2019 (GatedBlock, implement-vs-remove decision open) and
  DIR-gamma sliders (film-baked, disclosed).
- **Memory tiling for large RAW is NOT implemented.** An OOM-retry ladder + half-size decode +
  off-heap alloc mitigate ~12-50 MP; pathological DNGs are still unbounded. (AUDIT.md 21-23)
- **GPU preview is not bit-reproducible.** GPU float varies by vendor; the parity engine is CPU
  C++ only. `LutGpuPreview.kt` is default OFF with no on-device validation. Never route
  `simulate`/export through GPU. (HANDOFF.md 51-58, AUDIT.md 42-46)
- Release `isMinifyEnabled = true` (R8 shrink, `-dontobfuscate` + JNI/enum keep-rules; the R8
  path is NOT CI-exercised — smoke-test a release build on a device before tagging).

## 9. Version & release

- `versionCode 10` / `versionName 0.8.0`.
- Commit with `-c commit.gpgsign=false` (signing server rejects signing here).
- Release signing: `keystore.properties` (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`)
  in repo root; absent -> falls back to debug signing. Never commit a real keystore or an APK.
- **GPLv3 attribution "Film modeling powered by spektrafilm" must remain in the built app**
  (CLAUDE.md 126, NOTICE.md, README.md). The whole app is a GPLv3 derivative.
