# Spectrafilm Codebase Review — 2026-06-24

> **Status update (resolved in the same PR as this report).** The five top-priority findings were
> fixed and re-verified; all 27 engine-parity gates remain green (the new `test_small_preview_aa`
> matches the skimage oracle to 5.95e-08). Resolved:
> - ✅ **Medium** — autoexposure `small_preview` AA prefilter (`autoexposure.cpp`, the only
>   default-path divergence) — now applies skimage's gaussian prefilter; new gate
>   `test_small_preview_aa`.
> - ✅ **Medium** — engine-parity CI now honors the test exit code (`ci.yml`).
> - ✅ **Low** — `crop_image` oversize NumPy slice semantics (`crop_resize.cpp`).
> - ✅ **Low** — `spk_simulate` null guard (`spektra.cpp`).
> - ✅ **Low** — `grain.cpp` dead store removed.
>
> The remaining findings below (np_interp non-monotonic axis, `float_to_half` rounding, JNI input
> color-space, and the Kotlin/UI items) are **not** addressed in this PR and are left as documented
> follow-ups. See the CHANGELOG for the fix summary.

## 1. Executive summary

The codebase is currently **green on the two gates that matter most**: the host C++ engine compiles cleanly under host g++ (zero warnings) and the representative end-to-end parity gate (`test_simulate_e2e`) passes byte-clean, with every metric 2–5 orders of magnitude inside the oracle tolerances (worst observed `max_abs = 9.42e-07` against a `1e-4` ceiling). The full Android toolchain required by `CLAUDE.md` (NDK r27, CMake 3.22.1, build-tools 35.0.0, JDK 21) is installed and the project is buildable. **There are no Critical or High findings** — the prime directive (bit-exact parity with the spektrafilm oracle) holds on the default render path and across thread counts. The headline risk is a cluster of **latent parity divergences that escape CI because no golden exercises them**: a missing anti-aliasing prefilter in the autoexposure preview path that perturbs the global EV gain on every real RAW import (the only finding that breaks default-path parity above tolerance today), plus several wrong-but-bounded divergences reachable only via non-default coupler controls, oversize crops, or hand-edited JSON. The remaining items are robustness, resource-hygiene, and UI-correctness nits with bounded, mostly-dormant blast radius.

## 2. Build & parity status

- **Host C++ engine build:** Compiles under host g++ 13.3.0 (Ubuntu 24.04), `-std=c++17 -O2 -pthread`, zero warnings/errors. Binary produced.
- **Parity gate run:** `test_simulate_e2e` (the headline end-to-end CI gate) executed with the exact CI argv → exit 0, **no `FAIL` line**, `ALL PASS`. Representative metrics: `[scan_portra final_rgb] max_abs=5.96e-08`; `[print_ektar final_rgb] max_abs=9.42e-07` (worst, tol `1e-4`); all film/print cache warm==cold checks byte-identical. Thread-invariance and tolerance both satisfied.
- **Android toolchain:** Full SDK at `/opt/android-sdk` (referenced by `local.properties: sdk.dir=/opt/android-sdk`). Contains the exact `CLAUDE.md`-required `ndk/27.0.12077973`, `cmake/3.22.1`, `build-tools/35.0.0`, `platforms/android-34`, plus cmdline-tools and platform-tools. JDK present (OpenJDK 21.0.10, `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`).
- **Caveat:** `ANDROID_SDK_ROOT` / `ANDROID_HOME` / `ANDROID_NDK_ROOT` are unset and `sdkmanager` is not on `PATH`. A Gradle assemble must export `ANDROID_SDK_ROOT=/opt/android-sdk` explicitly (as the `CLAUDE.md` build command shows) or rely on Gradle reading `local.properties`. No assemble was run (toolchain availability only). Host `cmake` is 3.28.3 but is not needed by the g++-built parity test.

**Verdict: build health is green. No CRITICAL signals.**

## 3. Findings by severity

**0 Critical · 0 High · 5 Medium · 13 Low.**

### Critical

None.

### High

None.

---

### Medium — small_preview omits the Gaussian anti-aliasing prefilter that skimage applies at order=0 on downscale, diverging the metered EV

**Location:** `engine/spektra-core/src/main/cpp/runtime/stages/autoexposure.cpp:284-312`

**Problem.** `small_preview` ports `ResizingService.small_preview`, which calls `skimage.transform.rescale(image, scale, channel_axis=2, order=0)` with `anti_aliasing` left at its default (`None`). In skimage 0.26 that default resolves to `anti_aliasing=True` on a downscale **even for order=0**, so skimage runs `scipy.ndimage.gaussian_filter` (`sigma=max(0,(in/out-1)/2)`, `mode='mirror'`) **before** the nearest-neighbour resize. The C++ `small_preview` goes straight to nearest-neighbour sampling with no AA prefilter — it matches the `anti_aliasing=False` path exactly (`max_abs 0.0`), but the oracle's `True` path differs by `max_abs ≈ 0.30` on a 500×300 image. The sibling `crop_resize` stage carefully replicates this exact prefilter for order=3 (`build_gaussian_kernel`/`gaussian_prefilter_line`); it is simply absent here for order=0.

**Why it matters (parity).** This is the one finding that breaks **default-path** parity **above tolerance today**. `small_preview` feeds `measure_autoexposure_ev`, so the omission perturbs the metered luminance and hence the global `2**ev` gain applied to **every pixel** of the full-resolution image. On a 3000×2000 structured scene with center-weighted metering: `dEV ≈ -2.3e-3`, relative gain error `≈ 1.6e-3` — above the `1e-4` parity tolerance. It triggers whenever `auto_exposure` is ON (the schema default, `params_schema.py:51`; `spektra.cpp:1243`) AND the source long edge exceeds the hardcoded `max_size=256` (any real RAW import, and even the 640px preview path). It is **invisible to CI**: the autoexposure goldens use a 64px image (≤256, so `small_preview` is a no-op) and the downscale golden exercises the order=3 path, not order=0.

**Fix.** Before the nearest-neighbour resampling, apply the same separable scipy gaussian_filter AA prefilter `crop_resize` already implements (`sigma = max(0,(in/out - 1)/2)` per axis, `mode='mirror'`, `truncate=4.0`), reusing `build_gaussian_kernel`/`gaussian_prefilter_line`. The verifier confirmed this reproduces the oracle bit-exactly (`max_abs 0.0`). Then add a parity golden with `auto_exposure` ON and a source whose long edge exceeds 256px.

---

### Medium — np_interp binary search diverges from numpy.interp when the DIR-coupler axis le0 is non-monotonic

**Location:** `engine/spektra-core/src/main/cpp/model/couplers.cpp:27-44` (used at 145 and 253)

**Problem.** `compute_density_curves_before_dir_couplers` interpolates with `np_interp(le[j], le0, ycol, n)`, where `le0[j] = le[j] - sum_k silver_curve[j,k]*M[k,c]`. The local `np_interp` does a **binary search** assuming `le0` is sorted ascending, but `le0` is not guaranteed monotonic: for a steep stock / large inhibition matrix the subtracted coupler term can grow faster than `le`, inverting `le0` at high-density indices. Upstream `numpy.interp` does a **left-to-right linear scan** and returns the first bracketing interval for non-monotonic `xp` — a different, canonical result (reproduced: `np.interp(0.7)=17.0` vs this `np_interp(0.7)=31.333`). The file comment at `couplers.cpp:24-26` ("Mirrors np.interp's right-biased search") overstates fidelity — it only matches for monotonic `xp`. `fast_interp_channel` (line 49) has the same binary search but is safe because its `le/gamma` axis is monotonic.

**Why it matters (parity).** A genuine latent parity break, **reachable through the shipping UI** (the verifier corrected the original "not user-exposed" claim): `getAmount`/`getInhibitionSamelayer`/`getInhibitionInterlayer` are read at `spektra_jni.cpp:369-371`, exposed as `0f..4f` sliders (`MainActivity.kt:2667-2674`), wired in `ParamsState.kt:393-397`, and `compute_dir_couplers_matrix` multiplies `M` by `amount` with no clamp (`couplers.cpp:84`). At default `amount=1` all 20 filming-stage stocks keep `le0` monotonic (goldens pass), but for real Portra 400 `le0` first inverts at `amount≈1.49–2.14`; at `amount=2.0` (inside the slider range) `dc0` diverges from the oracle by `max_abs=0.437` (~4000× tolerance); at `amount=2.5`, `max_abs=2.09`. Wrong-but-bounded density, no crash. Does not affect default rendering, any bundled preset, or the goldens.

**Fix.** Replace `np_interp`'s binary search with a left-to-right linear scan matching `compiled_interp` for non-monotonic `xp` (scan from the previous index, as numpy does), keeping the endpoint clamps. Add a host-parity golden with an aggressive coupler matrix that actually inverts `le0`.

---

### Medium — float_to_half rounds half-up, not round-to-nearest-even — diverges from NEON vcvt and from its own documented contract

**Location:** `engine/spektra-core/src/main/cpp/kernels/half.cpp:32,36-39`

**Problem.** The scalar `float_to_half` implements round-half-**up**, not IEEE-754 round-to-nearest-**even**. The normal path (line 36) does `if (mant & 0x1000u) h = h+1`, rounding up on the exact tie `0x1000` even when the kept mantissa LSB is even; the subnormal path (line 32, `if (m & 0x1000u) m += 0x2000u`) has the identical defect. This contradicts (a) `half.h:11-13` ("The scalar fallback is exact IEEE-754 round-to-nearest so host tests validate the same values the device produces") and (b) the inline comments at lines 32/36 that literally say "round to nearest even." A full 2³² scan against a correct RNE reference found **31744 mismatches** (30720 normal-range, 1024 subnormal-range) — e.g. `0x38801000` gives impl `0x0401` but round-to-even `0x0400`.

**Why it matters (determinism).** The NEON bulk path `vcvt_f16_f32` (`half.cpp:75`) rounds with FPCR default mode = round-to-nearest-even. So on a real arm64 device the NEON-converted bulk of a buffer and the scalar-converted tail (last <4 elements, `test_half.cpp:55`) would round ties **differently**, and host tests mispredict device output. The CI `half` test passes today only by luck — its inputs `(i%200-100)*0.137f` produce zero exact ties, and on the host build the bulk path is the same scalar function. **Impact is currently latent:** `float_to_half`/`f32_to_f16` are referenced only in `half.cpp`/`half.h`/`test_half.cpp` — the engine pipeline and JNI never call them (`npy_lut.cpp` has its own independent `rd_f16le`), so the float32 parity/export gate is untouched. It becomes a real scalar-vs-NEON pixel divergence and a failing `test_half` the moment the planned fp16 preview-proxy storage (PERF_ROADMAP #3) is wired up.

**Fix.** Implement true round-to-nearest-even. Normal path: `uint32_t rem = mant & 0x1FFF; uint32_t kept = mant >> 13; if (rem > 0x1000u || (rem == 0x1000u && (kept & 1u))) h += 1;`. Subnormal path: drop bits with the same tie-to-even rule. Add tie-valued inputs (e.g. `0x38801000`) to `test_half.cpp` so the scalar==NEON contract is actually exercised.

---

### Medium — nativeSimulate hardcodes input color space to ProPhoto and silently ignores the inCs tag; RAW (ACES2065-1) buffers run through the fixed ProPhoto→XYZ matrix

**Location:** `engine/spektra-core/src/main/cpp/spektra_jni.cpp:522,555`

**Problem.** `nativeSimulate` accepts a `jstring inCs` (line 522, `jstring /*inCs*/`) but never reads it, and builds the engine input with a hardcoded ProPhoto color space: `spk_image in_img{in_data, w, h, static_cast<int>(SPK_CS_PROPHOTO)};` (line 555). The filming stage applies a **fixed** `kProPhotoToXyzD55` matrix unconditionally (`filming.cpp:36-58`, `prophoto_rgb_to_tc_b`, called per pixel at `filming.cpp:407`); `in->color_space` is used only as a cache key / passthrough (`spektra.cpp:1082,1104,1516`). The RAW path violates the resulting "input is linear ProPhoto RGB" contract: LibRaw decodes to ACES2065-1 (`raw_decoder.cpp:536`), the app builds `LinearImage(..., colorSpace = result.colorSpace)` (`EngineHelpers.kt:224/231/252`) with **no ACES→ProPhoto conversion**, and `SpektraEngine.simulate` forwards `image.colorSpace` as `inCs` (`SpektraEngine.kt:131-132`). Because the bridge discards the tag, ACES2065-1 (AP0) pixels are run through the ProPhoto matrix. The verifier also found `getInputColorSpace` is never even marshalled by the JNI (`spektra_jni.cpp:404-417` reads `getInputCctfDecoding`/`getOutputColorSpace` only), so the UI "Input color space" dropdown is also inert.

**Why it matters (safety/correctness).** A wrong-primaries chromaticity error before spectral upsampling on **every successful native RAW decode** — a core default flow of a RAW editor. It is **off-parity and off-oracle**, but invisible to CI because all parity goldens feed `SPK_CS_PROPHOTO`. Bounded blast radius (LibRaw-success path only; the `decodeViaPlatform` fallback correctly emits ProPhoto), no crash. Root cause is in the app layer (missing conversion); the bridge currently launders the error.

**Fix.** Make the contract explicit at the boundary: convert ACES→linear ProPhoto in the RAW import path before the buffer reaches the engine, and assert the invariant in the bridge — or read `inCs`/add a color_space param and have the engine convert non-ProPhoto inputs. At minimum, validate the incoming tag and throw/log when it is not `ProPhoto RGB` so an ACES buffer cannot be silently misinterpreted.

---

### Medium — engine-parity CI harness discards test exit code; missing-asset / setup / crash failures silently report "ok"

**Location:** `.github/workflows/ci.yml:97-98`

**Problem.** The engine-parity job (the "real gate" per `CLAUDE.md`) runs each binary inside `if "/tmp/$name" "$@" 2>&1 | tee "/tmp/$name.out"; then :; fi` — the `if … then :; fi` wrapper exists to stop `set -euo pipefail` (line 82) from acting on the exit code, so a non-zero exit is discarded. The only pass/fail signal is `grep -qiE "FAIL"` on the captured output (line 98). But the test programs signal **setup** failures with `return 2` and messages that do **not** contain "FAIL": e.g. `test_simulate_e2e.cpp` "cannot open %s" (line 126), "input size mismatch" (130), "size mismatch: got…" (165); `test_filming.cpp` "profile missing filming fields" (127), "size mismatch vs goldens" (176). A crash/abort/segfault before any "FAIL" print is likewise reported `ok` (reproduced in a sandbox: a stand-in returning 2 → `cannot_open: ok`, fail=0; a segfaulting stand-in → `crash: ok`, outer exit 0).

**Why it matters (robustness).** A typo'd golden path, a missing/renamed bundled asset, a wrong argv, or an engine that fails to construct with a non-"fail" message would make the gate report green while doing nothing — the bit-exact parity gate is silently bypassed. **Two mitigations bound the exposure** (per the verifier): the `grep` is case-**insensitive** (`-i`), so every "…failed…" message ("engine create failed" line 111, "spk_simulate failed" line 158, "failed to load spectra LUT") **is** caught; and the compile step (lines 94-95) is **not** wrapped in `if`, so `set -e` still aborts on missing sources / compile errors. A genuine numeric divergence still prints "FAIL". All CI-referenced assets currently exist, so nothing is masked today — this is a latent false-green that would only fire on a future structural break.

**Fix.** Honor the exit code in `build_run`: `rc=0; "/tmp/$name" "$@" 2>&1 | tee "/tmp/$name.out"; rc=${PIPESTATUS[0]}` and set `fail=1` when `rc != 0` OR the output matches FAIL. Also treat an empty/absent output file as failure.

---

### Low — crop_image diverges from numpy for oversize crops: negative start clamped to 0 instead of wrapping from the end

**Location:** `engine/spektra-core/src/main/cpp/runtime/stages/crop_resize.cpp:295-310`

**Problem.** When the rounded crop extent exceeds the image dimension (`sz0 > h` or `sz1 > w`, i.e. crop_size fraction > ~1.0), the oracle sets `x0[0] = shape[0] - sz[0]` (negative) then slices `image[x0[0] : x0[0]+sz[0]]`, where numpy interprets the negative start as "from the end." The C++ instead clamps the negative start to 0 (line 305 `if (r0 < 0) r0 = 0;`) and the stop to `h`. Verified: 64px image, `crop_size.y=1.01` → `sz0=65>64`; oracle `x0[0]=-1` → `image[-1:64]` = 1 row at row 63; C++ → 64 rows from row 0. Both geometry and pixel content diverge completely. The comment at lines 300-302 claims to "Mirror" numpy slicing but does the opposite.

**Why it matters (parity).** A wrong-output parity break (indices stay in bounds, no crash). Reachable only via hand-authored preset/recipe JSON: `crop_size` is read verbatim (`Presets.kt:88-91`/`:280`), passed through `build_crop_resize` (`spektra.cpp:574-575`) with no clamp. The interactive `CropOverlay` caps the long-side fraction at exactly 1.0 (`CropOverlay.kt:18-19`) so the UI path is safe, and the crop golden uses `crop_size=(0.6,0.5)` which never overflows. Low because `crop_size>1.0` is a semantically degenerate request and the oracle's own output for it is a meaningless 1-pixel sliver.

**Fix.** Replicate numpy negative-start semantics: when `x00`/`x01` is negative after the `h-sz` adjustment, resolve it as a from-end index (`x00 += h`) before forming `r0/r1`, then apply numpy's stop clamp. Add a parity golden with `crop_size > 1.0`.

---

### Low — spk_simulate dereferences `in` before any null check (public C API)

**Location:** `engine/spektra-core/src/main/cpp/spektra.cpp:1466-1472`

**Problem.** `spk_simulate` validates `out` (1468) and `p` (1469) for null but reads `in->width`/`in->height` at line 1472 before any null check on `in` (or `eng`). The callees `run_scan_film`/`run_print` do guard `!in` (638/824), and both sibling entry points guard it — `spk_simulate_preview` at 1486, `spk_simulate_tap` at 1523 — so this is an inconsistency in the documented stable C surface (`spektra.h:12-13,299`). A caller passing `in==nullptr` crashes with a NULL deref instead of returning `SPK_ERR_BAD_ARGS`.

**Why it matters (safety).** Latent API-contract/robustness defect on an exported C API. **Not reachable in-tree:** every caller passes a stack `spk_image` (`spektra_jni.cpp:555` after validating `inBuf`; `spk_bake_cube_lut` builds its own; all host tests pass `&in_img`). Zero current impact — only a hypothetical external consumer could trigger it.

**Fix.** Add `if (!eng || !in || !in->data) return SPK_ERR_BAD_ARGS;` at the top of `spk_simulate`, matching the two siblings.

---

### Low — Dead store: dmax_frac[3][3] written but never read in apply_grain_to_density_layers

**Location:** `engine/spektra-core/src/main/cpp/model/grain.cpp:159,167`

**Problem.** `double dmax_frac[3][3]` is declared at line 159 and assigned at line 167 (`dmax_frac[sl][c] = frac;`) but never read; the live value is `frac`, consumed inline for `dmin_layers` and `n_ppp`. Pure dead code, confirmed by grep (only references are the decl and the write) and by `g++ -Wall` (`warning: variable 'dmax_frac' set but not used`).

**Why it matters.** Zero behavioral/parity impact; thread-invariance preserved. The real engine build does not even emit the warning (`CMakeLists.txt:12` uses only `-O3 -ffast-math -fno-finite-math-only`, no `-Wall`). A pure compiler-hygiene nit.

**Fix.** Delete the declaration and the assignment; `frac` is already used directly.

---

### Low — recipeKey recomputes a SHA-256 hash on every recomposition (main thread)

**Location:** `app/src/main/java/com/spectrafilm/app/MainActivity.kt:416`

**Problem.** `val recipeKey = Recipes.keyFor(sourceUri)` is a plain `val` with no `remember` (lines 412-415 immediately above all use `remember`). `Recipes.keyFor` runs `MessageDigest.getInstance("SHA-256").digest(...)` plus a 32-byte hex join (`Recipes.kt:43-46,126-129`). `EditorScreen` (a restartable composable) reads frequently-changing state in its body (`renderKey = previewTick` line 1200, `exporting`, `previewBusy`), so it re-runs top-to-bottom on every `previewTick` bump and continuously during slider drags (`previewTick++` at 995-997), re-executing the unmemoized hash each time.

**Why it matters.** Not a correctness bug — `recipeKey` is content-stable so the three `LaunchedEffect`s keyed on it do not relaunch. Purely wasted main-thread CPU, but per-call cost is microseconds, dwarfed by the ~1s all-core engine render per settle.

**Fix.** `val recipeKey = remember(sourceUri) { Recipes.keyFor(sourceUri) }`.

---

### Low — Preset/recipe persistence runs synchronous disk IO on the main thread

**Location:** `app/src/main/java/com/spectrafilm/app/MainActivity.kt:1270-1289`

**Problem.** Several PresetPanel/SourcePanel callbacks invoke blocking file-IO helpers directly on the Compose/main thread: `onSave` → `Presets.save` (writeText) + `refreshPresets`→`listFiles`; `onApply` → `Presets.load` (readText); `onDelete` → `Presets.delete`; `onImport` → `Presets.import` (`openInputStream().readBytes()`) in the SAF result callback (line 588); `onResetEdits` → `Recipes.delete` (file.delete) at line 1351. The debounced auto-save effect correctly wraps the same work in `withContext(Dispatchers.IO)` (line 1015), so the off-main pattern is known but not applied here. (The original claim's attribution of disk IO to `Recipes.resetToDefaults` is wrong — that is pure in-memory; but `Recipes.delete` on the same line does hit disk.)

**Why it matters.** StrictMode disk-on-main violations, but StrictMode is configured nowhere in the repo, the targets are tiny app-private files in `filesDir` on local flash, and these are discrete user taps, not hot paths. ANR risk is negligible.

**Fix.** Move the `Presets.save/load/delete/import` and `Recipes.delete` calls into `scope.launch { withContext(Dispatchers.IO) { ... } }`, posting status back on the main dispatcher — mirroring the auto-save effect.

---

### Low — ROI/magnifier render jobs survive navigation and can orphan a bitmap

**Location:** `app/src/main/java/com/spectrafilm/app/MainActivity.kt:811-857`

**Problem.** `renderRoi()` (811) and `openMagnifier()` (764) launch in `scope = lifecycleScope` (Activity-scoped, line 258), not a composition-scoped coroutine, and navigation (`screen = SETTINGS/ABOUT`, lines 169-170) only switches the `when(screen)` branch — it never cancels `roiJobRef`/`magnifierJobRef`. The `isActive` guards (785/838/846) only catch explicit cancellation. So a navigated-away job still reaches `magnifierBitmap = it` (792) / `roiOverlay = RoiOverlay(bmp,...)` (855), writing a fresh bitmap into now-disposed remembered state whose `DisposableEffect` recyclers (371-374, 1394-1398) already ran. That bitmap is never `recycle()`d and is left to GC. No Activity `onPause`/`onStop` recycle exists.

**Why it matters (resource leak).** A narrow timing window (navigate during an in-flight render), a single bounded bitmap (`MAGNIFIER_CROP_PX=512 ≈ 1 MB`; `ROI_RENDER_MAX_PX=1600 ≈ a few MB`), does not accumulate across visits, and the bitmap remains GC-eligible. A deterministic-recycle gap, not a true leak.

**Fix.** Cancel in-flight jobs from a `DisposableEffect(Unit){ onDispose { roiJobRef.value?.cancel(); magnifierJobRef.value?.cancel() } }` in `EditorScreen`, or launch these renders in a `rememberCoroutineScope()` so they cancel and their results are recycled by the existing `onDispose`.

---

### Low — constrainToAspect ignores the anchor handle: aspect-locked TL/TR corner drags move the wrong edge

**Location:** `app/src/main/java/com/spectrafilm/app/CropOverlay.kt:334-361`

**Problem.** `constrainToAspect` takes `anchor: Handle` but uses it **only** in the line-350 overflow guard; the final placement (356-359) unconditionally pins top-left (`l = rect.left; t = rect.top; r = l + nw; b = t + nh`), i.e. always grows down/right. Numeric simulation confirms TL and TR corner drags do **not** pivot about the opposite corner — the bottom edge floats (~5% of image dimension). The verifier corrected the original claim: **BL and BR are actually correct** (the top-left anchor coincides with the right pivot for the two bottom corners), so the defect is confined to **TL and TR**, not three corners.

**Why it matters (correctness).** Reachable and user-facing (5 non-Free aspect presets, `CropAspect` lines 71-78; no unit test covers this). But the resulting Rect is always valid and in-bounds — no crash, no invalid crop, purely a gesture/UX imperfection on 2 of 4 corners.

**Fix.** Use `anchor` to choose the fixed corner before recomputing: for bottom-anchored handles keep `t` and set `b = t + nh`; for top-anchored handles keep `rect.bottom` and set `t = rect.bottom - nh`; pick left vs right likewise, then apply the in-bounds clamps.

---

### Low — Int overflow in allocRotBuf managed-fallback size (floats * 4) for export-scale rotations

**Location:** `app/src/main/java/com/spectrafilm/app/Rotation.kt:25-33`

**Problem.** The off-heap branch widens correctly (`floats.toLong() * 4`, line 27) but the managed fallback `ByteBuffer.allocateDirect(floats * 4)` (line 32) does Int×Int arithmetic. Reachable on the full-res export path (`EXPORT_MAX_EDGE_PX = 16384`, with the second edge uncapped by the long-edge clamp). Once `floats > Int.MAX/4` (~536.87M floats, e.g. 16384×10923 ≈ 179 MP), `floats * 4` overflows negative and `allocateDirect` throws `IllegalArgumentException` instead of a graceful OOM.

**Why it matters (robustness).** Low because line 32 is reached only after the off-heap native `malloc` of the same ~2.15 GB already failed and returned null — so the managed allocation would fail anyway, and the user-visible outcome (export fails, caught by `runCatching` at `MainActivity.kt:1124`, toast at 1167) is identical regardless of exception type. Concrete, reachable overflow that is inconsistent with its own Long-widened sibling one line above, but with no behavioral consequence beyond a misleading exception.

**Fix.** `val bytes = floats.toLong() * 4; if (bytes > Int.MAX_VALUE) throw OutOfMemoryError(...) else ByteBuffer.allocateDirect(bytes.toInt())`.

---

### Low — Malformed preset import partially mutates ParamsState (throwing getDouble on short arrays)

**Location:** `app/src/main/java/com/spectrafilm/app/Presets.kt:85-90, 106`

**Problem.** `triOf()`/`pairOf()`/`jsonToPoints()` guard only against a missing/non-array key (`optJSONArray(key) ?: return def`) then call `a.getDouble(0/1/2)`, which throws `JSONException` for a present-but-short array (e.g. `filterUv` length 2, `unsharpMask` length 1, tone point `[0.5]`). `fromJson` mutates the live `ParamsState` in document order with no staging object, so a throw mid-walk leaves earlier fields applied and later fields untouched. All three call sites swallow the exception (`applySnapshot` 443, `Recipes.load` 516, `Presets.import` 588-590, surfacing only "import failed"). `BuiltInPresets` uses the non-throwing `optDouble` for the same data (`BuiltInPresets.kt:242,264,268`), so the two readers have inconsistent robustness.

**Why it matters (robustness).** Low because the app's own serializer always emits full-length arrays, so this never fires on app-produced presets/recipes or on undo/redo; it triggers only on hand-edited/third-party malformed imports, never crashes, and the inconsistent state is transient and recoverable by re-applying a valid preset.

**Fix.** Use `optDouble(idx, default)` in `triOf`/`pairOf`/`jsonToPoints` (mirroring `BuiltInPresets`) and skip points with <2 elements — or decode into a throwaway `ParamsState` and copy into the live state only on full success.

---

### Low — Undo/redo restoring flag has a timing window that can silently drop one undo step

**Location:** `app/src/main/java/com/spectrafilm/app/MainActivity.kt:1037-1053`

**Problem.** The capture effect keys on `snapshot, recipeKey, recipeReady, rotation, state.raw*` but **not** on `restoring` (a `mutableStateOf`, line 432, set by `applySnapshot`/recipe-restore/reset, cleared only inside the effect after a 500ms settle). If the user makes a real edit within ~500ms of an undo/redo/restore, the new edit relaunches the effect and cancels the pending delay before its body runs, so `restoring` is still true when it finally fires; the `restoring -> { committedSnapshot = now; restoring = false }` branch (1043-1046) unconditionally adopts the edited state and does **not** push the pre-edit baseline onto the undo stack — without any equality check that `now` matches the restored snapshot.

**Why it matters (correctness).** Live state stays correct and redo still works; exactly one undo granularity level (the post-undo/pre-edit state) becomes unreachable. The sub-500ms window is genuinely user-reachable (preview render does not disable controls). Minor: no corruption, no crash.

**Fix.** Clear `restoring` deterministically as part of `applySnapshot`/restore (immediately after the synchronous mutation), or guard the branch by comparing the settled snapshot against the snapshot actually restored, so a fast follow-up edit cannot be absorbed.

---

### Low — GPU LUT preview renders garbage/black after background→foreground (EGL context recreation leaves haveProxy/haveLut stale)

**Location:** `app/src/main/java/com/spectrafilm/app/LutGpuPreview.kt:144-156`

**Problem.** On EGL context recreation, `onSurfaceCreated` regenerates fresh texture names into `proxyTex`/`lutTex` but does not reset `haveProxy`/`haveLut` nor re-arm `pendingProxy`/`pendingLut`. The `GLSurfaceView` uses the default preserve-context-on-pause = false (no `setPreserveEGLContextOnPause(true)` in the factory, 104-108) and the renderer is `remember{}`'d (line 100), so its fields survive pause/resume. On return, `proxyTex`/`lutTex` name brand-new uninitialized textures, but `haveProxy`/`haveLut` are stale-true and `pendingProxy`/`pendingLut` were already nulled (165-166), so the `(!haveProxy||!haveLut)` guard (170) passes and `onDrawFrame` samples empty textures. It does not self-heal: the only re-upload trigger, `submit()`, fires only from `AndroidView.update` on proxy/lut state change, which a background return does not cause. No `onPause`/`onResume` lifecycle wiring exists (confirmed by grep); `HANDOFF.md:8-19` already records this surface "could show a black surface."

**Why it matters (correctness).** Low because the feature is default-OFF/opt-in experimental (`AppSettings.kt:53-55`, `AUDIT.md:231-234` "unverified on a real GPU"), it is not the parity/export path, and the failure is a recoverable black preview (any slider touch re-arms `submit`), not a crash or data loss.

**Fix.** In `onSurfaceCreated` after a successful program build, set `haveProxy = false; haveLut = false` and re-arm `pendingProxy = lastProxy; pendingLut = lastLut` (cache the most recent `submit()` args). Optionally also `setPreserveEGLContextOnPause(true)`, but still reset the flags as a fallback since preserve-context is not guaranteed by all drivers.

---

### Low — Diagnostics screen runs blocking logcat subprocess + file reads on the main thread (ANR risk)

**Location:** `app/src/main/java/com/spectrafilm/app/DiagnosticsScreen.kt:70,77`

**Problem.** The "Capture logcat" and "Share diagnostics report" buttons invoke `Diagnostics.captureLogcat()` / `Diagnostics.buildReport()` directly in their `onClick` lambdas (main thread, no coroutine/`Dispatchers.IO` anywhere in the file — the only screen that touches IO without a dispatcher). `captureLogcat()` does `ProcessBuilder(["logcat","-d",...]).start()` + full-stdout read + `proc.waitFor()` (`Diagnostics.kt:100-112`) and recompiles a `Regex` per log line (line 107). `buildReport()` additionally calls `lastCrash()` + `captureLogcat()` again. `DiagnosticsScreen.kt:41` also reads `lastCrash` synchronously in the composable body (tiny file).

**Why it matters (concurrency).** Contradicts the project's pervasive off-main convention. Low because it is a user-initiated tap on a rarely-visited screen, work is bounded (`maxLines=500`, `logcat -d` dumps-and-exits), and the worst realistic outcome is brief jank, with a true ANR only on a pathologically busy device.

**Fix.** Wrap the calls in `rememberCoroutineScope().launch { withContext(Dispatchers.IO) { ... } }` (and `LaunchedEffect` for the initial `lastCrash` read), posting results back to Compose state; hoist the `Regex` out of the per-line loop.

---

### Low — RawCoilDecoder leaks the off-heap native RAW buffer on every decode (no freeOffHeap)

**Location:** `lib/libraw/src/main/kotlin/com/spectrafilm/libraw/RawCoilDecoder.kt:57-68`

**Problem.** `RawDecoder.decodeToLinear` returns a `LinearResult` whose `.data` is a native off-heap buffer (`malloc` + `NewDirectByteBuffer`, `raw_decoder_jni.cpp:108-115`) that the facade documents (`RawDecoder.kt:165-172`) is "NOT reclaimed by the GC — the owner must free it explicitly" via `freeOffHeap`. `RawCoilDecoder.decode()` consumes `linear.data` only to build a Bitmap in `toDisplayBitmap()` and then drops the `LinearResult` — no `freeOffHeap`/close/finally anywhere (grep: no matches). The correct app path (`EngineHelpers.kt:225/230/251`) frees the same buffer on every branch, confirming the violated contract.

**Why it matters (resource leak).** Low because `RawCoilDecoder` is **dead/unreachable** in the shipped product: grep finds zero instantiations of `RawCoilDecoder.Factory()`, the `:app` module has no Coil dependency, and `:lib:libraw` only `compileOnly`'s coil3 (so it is not even on the runtime classpath). The class header and `AUDIT.md:236` describe it as the "reference sketch the host wires up" for an ImageToolbox host that does not exist in `settings.gradle.kts`. The leak (`w*h*3*4` bytes per decode, ~140 MB for 12 MP, ~600 MB for 50 MP, accumulating unbounded off-heap) becomes live the instant `RawCoilDecoder.Factory()` is registered in a Coil `ComponentRegistry`.

**Fix.** `try { val bmp = linear.toDisplayBitmap(); DecodeResult(bmp.asImage(), isSampled = ...) } finally { RawDecoder.freeOffHeap(linear.data) }`. The bitmap already holds its own pixel copy, so freeing the source immediately is safe.

## 4. Clean areas

Coverage was real: 24 units reviewed (15 engine C++, 8 Kotlin, 1 build/CI). The following came back with **no findings** after adversarial verification:

- **`kernels-b`** — kernels (spectral upsampling, gaussian/exponential filters, interp/lut3d, exp10).
- **`ml`** — machine-learning / surface-fit components.
- **`io-profiles`** — profile JSON + `.npy`/`.lut` asset loaders (`io/`, `profiles/`).

The uncompiled, non-shipped module `feature/film-emulation/` was intentionally skipped (not in `settings.gradle.kts`, per `CLAUDE.md`).

## 5. Recommended next actions

Prioritized checklist:

1. **[Parity, default path] Fix the autoexposure AA prefilter** (`autoexposure.cpp:284-312`). This is the only confirmed above-tolerance divergence on the default render path (every real RAW import). Port `crop_resize`'s gaussian prefilter to the order=0 path and add a golden with `auto_exposure` ON and a >256px source.
2. **[CI integrity] Honor the test exit code in `engine-parity`** (`ci.yml:97-98`). Cheap, high-leverage: it re-arms the gate against future asset/argv/crash false-greens. Do this before relying on CI to catch action #1's new golden.
3. **[Color correctness] Resolve the RAW input-color-space contract** (`spektra_jni.cpp:522,555` + `EngineHelpers.kt`). Add ACES2065-1→ProPhoto conversion in the RAW import path and assert the invariant at the JNI boundary; also marshal `getInputColorSpace` so the UI dropdown is not inert.
4. **[Parity, latent] Harden `np_interp` for non-monotonic axes** (`couplers.cpp:27-44`) — switch to a numpy-matching linear scan and add an `le0`-inverting golden; it is reachable via the shipping coupler sliders.
5. **[Determinism, pre-fp16] Fix `float_to_half` to round-to-nearest-even** (`half.cpp:32,36-39`) before any fp16 preview-proxy work lands, and add tie-valued test inputs.
6. **[Engine hygiene] Quick wins:** add the `!in` null guard to `spk_simulate` (`spektra.cpp:1472`); fix the oversize-crop numpy semantics (`crop_resize.cpp:295-310`); delete the `dmax_frac` dead store (`grain.cpp:159,167`).
7. **[Kotlin/UI] Batch the low-risk app fixes:** `remember(sourceUri)` for `recipeKey`; move preset/recipe and diagnostics IO off the main thread; cancel ROI/magnifier jobs on dispose; honor the crop anchor for TL/TR; widen the `allocRotBuf` fallback to Long; use `optDouble` in `Presets` readers; deterministic `restoring`-flag clear; reset GPU `have*` flags on `onSurfaceCreated`.
8. **[Before any ImageToolbox host wiring] Add `freeOffHeap` to `RawCoilDecoder`** (`RawCoilDecoder.kt:57-68`) — dormant today, but a large unbounded native leak the moment the factory is registered.