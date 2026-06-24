# Spektrafilm Android — Session Handoff

## State (2026-06-24 #4, LATEST, branch `claude/exciting-hamilton-hya62`, PR #105 DRAFT) — P2 #5 (input gamut compression: radial-to-locus tc_lut bake, OPT-IN / DEFAULT-OFF)

Continuation on the same branch/PR. **P2 #5 (input-side gamut compression) is now done**, parity-safe:
the default render/export path is **byte-identical** to oracle `c1d0e44` (the full host engine-parity
suite is **31/31 gates green, fail=0** — every prior gate byte-identical + the new `test_gamut_in_xy`).
`:engine:spektra-core:externalNativeBuildDebug` **BUILD SUCCESSFUL** for all 3 ABIs (NDK r27). No
Kotlin/JNI/facade/UI change — the feature is engine-internal and **dormant** (callers pass the default
`kOff`), exactly like P1 #3 (aces_rgc) left `output_gamut_compress` dormant. The cross-cutting activation
(thread the flag through `engine_tc_lut`'s cache key + the print-route `compute_film_cache_key` digest +
JNI/facade/UI) is the **deferred P2 #7 umbrella**.

### What shipped (P2 #5 — input gamut compression, `algorithm="xy"` radial-to-locus, OPT-IN / DEFAULT-OFF)
- **`model/gamut_compression.{h,cpp}` extended** with the input side: new `InputGamutCompress` enum
  (`kOff`=0 DEFAULT sentinel, `kXy`=1, `kOklch`=2 reserved/unported), `spectral_locus_xy()` (the closed
  **66-vertex** CIE 1931 2° locus polygon, 380..700 nm @ 5 nm, captured as a baked constant from
  colour-science via the gen script), and `compress_pixel_xy`/`compress_xy_radial` (ports
  `gamut_compression.py::compress_xy_radial`: ray-polygon distance to the locus + the **shared
  `reinhard_knee`** from P1 #3, radial rescale, at-white `dist<1e-9` passthrough mirroring `np.where`,
  `0*inf==NaN`-on-miss preserved). All double, matching the oracle.
- **`kernels/spectral_upsampling.{h,cpp}`**: new `remap_tc_lut_for_compression` — the LUT bake
  (`new_lut[cell] = old_lut[tri2quad(compress_xy_radial(quad2tri(cell_tc)))]`) with a bilinear
  edge-clamp resampler that bit-matches `scipy.ndimage.map_coordinates(order=1, mode='nearest')`. Reuses
  the existing `tri2quad`/`quad2tri` (already byte-identical to the oracle's `_tri2quad`/`_quad2tri`).
- **`runtime/stages/filming.cpp::build_filming_tc_lut`** hooks the bake in right before `return tc_lut`
  (mirrors the oracle appending the remap in `compute_hanatos2025_tc_lut`), gated on the new defaulted
  `input_gamut_compress` arg. **Default `kOff` skips it entirely → tc_lut byte-identical** → every golden
  stays bit-exact. `white_xy` reuses the same `illuminant·kCieCmf1931→xy` the (parity-gated) surface
  branch computes. New args are defaulted so the sole production caller (`engine_tc_lut`) is unchanged.
- **SCOPE finding (verified vs oracle git history):** the roadmap's "CAT02→CAT16 + xy-clip removal +
  locus bake" bundles **three independent oracle commits**. Only the **locus bake** is opt-in-shaped and
  self-contained. CAT02→CAT16 (`eac6b85`) and the runtime xy-clip removal (`30a32a8`) are **UNCONDITIONAL
  default-path math changes** in `_rgb_to_tc_b` that belong to the deferred P3 Strategy-B rebaseline —
  **NOT touched here.** Our `rgb_to_tc_b` stays on CAT02 + xy-clip (the pre-feature oracle state). The ON
  path is therefore a defensible local composition (CAT02+clip front-end + locus bake), not bit-identical
  to any single oracle commit — so #5 gates the **primitives**, not an e2e ON render (matching how #3
  gated `compress_rgb_aces_rgc` at the function level rather than a render).
- **Gate `test_gamut_in_xy`** (`tools/parity/gen_gamut_in_golden.py` → `tests/gamut_in_cases.bin`, oracle
  HEAD `27bd085`): 3 sections — `spectral_locus_xy` (66 verts, **max_abs 0.0** vs oracle), `compress_xy`
  (45 cases / 2439 pts, **max_abs 2.22e-16**), and the `remap_tc_lut` bake (4 synthetic LUTs, **max_abs
  7.33e-15**), plus a NaN-disagreement guard. **Adversarially re-checked** with a fresh seed (20260625),
  extreme out-of-locus / negative xy, D50/D75/E whites, and asymmetric LUT shapes (7×13 vs 13×7 — which
  directly validate the bilinear axis order): bit-exact (1e-16 / 1e-13). The gen script reproduces the
  golden **byte-identically** (deterministic) and uses the same matplotlib-shim oracle-load as the aces
  gen + a parity-true `spektrafilm.utils.spectral_upsampling` shim (the real `_tri2quad`/`_quad2tri`).
  Degenerate 1×N LUTs (oracle `0/0`→NaN) are out of scope: the engine's tc_lut is always 192×192 and the
  C++ guards `H>1`/`W>1` defensively.

### NEXT — resume here (priority order = `docs/PRIORITY_ROADMAP_2026-06-24.md`)
- **P0 + P1 + P2 #5 — all done.** (#1/#2 P0, #3/#4 P1, **#5 this section**.)
- **P2 #6 — perceptual output gamut algos** (cam16ucs/oklch/oklrab/jzazbz, default-OFF, effort XL): the
  heavy `model/gamut_compression` color-appearance util on the aces_rgc hook. Most users won't need it
  over aces_rgc, so it trails the cheaper gamut work.
- **P2 #7 — gamut stage-integration umbrella** (effort S, now partially actionable): wire BOTH
  `output_gamut_compress` (from #3) AND `input_gamut_compress` (from #5) through the engine to the UI —
  add `spk_params` fields + JNI marshal + Kotlin facade + UI, set `ScanningParams.output_gamut_compress`
  and thread `input_gamut_compress` through `engine_tc_lut` **(fold the flag into its cache key)** +
  `build_filming_tc_lut`, and **fold `input_gamut_compress` into `compute_film_cache_key`** (the
  print-route film-density memo) so the bake can't alias across the flag. #6 must land for the perceptual
  algos, but the aces_rgc + xy wiring can proceed now.
- **P2 (Kotlin)**: off-main-thread preset/diagnostics IO; undo/redo restoring-flag timing. **P3-quickwins**
  (recipeKey remember, ROI/magnifier dispose-cancel, crop anchor, optDouble import, GPU re-arm,
  allocRotBuf overflow, RawCoilDecoder leak). **P3-defer**: the Strategy-B rebaseline cluster — now
  explicitly **including** the CAT02→CAT16 swap + the `_rgb_to_tc_b` xy-clip removal (default-path math;
  must move as one coordinated baseline bump; trigger = upstream settling its WB-norm baselines).

### Verification recap (this session)
Full host engine-parity suite replayed from `ci.yml` argv: **31/31 gates green, fail=0** (all prior
goldens byte-identical incl. `simulate_e2e` warm==cold; `test_parallel` proves 1≡8 thread-invariance;
the new `test_gamut_in_xy` bit-exact + adversarially re-checked). NDK r27 `externalNativeBuildDebug`
green (arm64-v8a/armeabi-v7a/x86_64). Oracle reachable at `/home/user/spektrafilm` (HEAD `27bd085`).

### Background watches (session-scoped; gone on restart)
PR #105 subscription + any cron die when the session ends — re-subscribe / re-arm if still wanted.

---

## State (2026-06-24 #3, branch `claude/exciting-hamilton-hya62`, PR #105 DRAFT) — P1 #3 (aces_rgc output gamut compression) + P1 #4 (float_to_half RNE)

Continuation on the same branch/PR. **Both P1 items from the roadmap are now done.** Both are parity-safe:
the default render/export path is **byte-identical** to oracle `c1d0e44` (the full host engine-parity suite
is **31 gates, all green** — the prior 30 + the new `test_gamut_out_aces`; `test_half` was strengthened,
not added). `:engine:spektra-core:externalNativeBuildDebug` **BUILD SUCCESSFUL** for all 3 ABIs (NDK r27).
No Kotlin/JNI/facade change (both items are engine-internal; the UI/JNI wiring is the deferred P2 #7 umbrella).

### What shipped (P1 #3 — `aces_rgc` output gamut compression, OPT-IN / DEFAULT-OFF)
- **New `model/gamut_compression.{h,cpp}`** ports `utils/gamut_compression.py::reinhard_knee` (the shared
  Reinhard knee the input/perceptual gamut items P2 #5/#6 will reuse) + `compress_rgb_aces_rgc` (ACES
  Reference Gamut Compression v1.3, per-channel knee on the achromatic distance `d=(max(R,G,B)-c)/max(R,G,B)`,
  preserving the achromatic max; near-black `ach<=1e-12` passes through). All in double, matching the oracle.
- **The `OutputGamutCompress` enum carries the `kLegacyClip` sentinel as its DEFAULT** — the engine's existing
  behavior (no compression; scanning's final `np.clip(0,1)`). The newer oracle *removed* that clip and replaced
  it with gamut compression; `kLegacyClip` keeps the engine on the old, golden-matching path. `kAcesRgc` opts
  in; `kOff` + the four perceptual algos (oklch/oklrab/jzazbz/cam16ucs) are reserved (not ported).
- **Scanning hook** (`runtime/stages/scanning.{h,cpp}`): `ScanningParams.output_gamut_compress` (+ knee
  threshold/limit/power, default 0/1/6). Applied in the linear output space at the oracle's exact position
  (right after XYZ→RGB, before blur/unsharp). The hook is a branch gated on `kAcesRgc`; default `kLegacyClip`
  **skips it entirely** → `lin_rgb` untouched → byte-identical. `CMakeLists.txt` + `ci.yml` updated.
- **Gate `test_gamut_out_aces`** (`tools/parity/gen_gamut_aces_golden.py` → `tests/gamut_aces_cases.bin`,
  28 cases/1584 pixels, 4 knee triples, captured from oracle HEAD `27bd085`): C++ vs oracle **max_abs 6.66e-16**
  (machine epsilon); `reinhard_knee` pinned at probe points too. This **establishes the newer-oracle-golden
  gating pattern** (golden from a SHA *newer* than the pinned `c1d0e44` baseline, but default-OFF so no
  existing golden moves) the roadmap wanted from this first gamut port. **Adversarially re-checked** vs the
  oracle over 400 fresh random vectors (max_abs ~1e-16).

### What shipped (P1 #4 — `float_to_half` round-to-nearest-even)
- **`kernels/half.cpp::float_to_half`**: both the normal and subnormal branches rounded **half-UP**
  (the `mant & 0x1000` tie-break) — and the subnormal branch also dropped the sticky bits the `>> (1-exp)`
  shift discarded. arm64 NEON `vcvt_f16_f32` uses **RNE** (FPCR default), so scalar(host/tail) vs NEON(bulk)
  pixels would diverge on exact ties once the fp16 preview-proxy storage (PERF_ROADMAP #3) lands. Fixed both
  to true RNE with a full sticky bit.
- **`tests/test_half.cpp`** strengthened: an authoritative, self-contained `is_rne` tie-to-even checker
  (independent of `float_to_half`'s bit-twiddling — uses `half_to_float` on the result + its two neighbours),
  a targeted exact-tie table (incl. subnormal ties), and a 300k-value finite-range sweep. **Cross-checked vs
  `numpy.float16` (RNE): bit-identical over 33,010 finite inputs** (8000 exact ties + subnormals + the
  65504/65520 overflow boundary). This is the prereq the roadmap flagged for the fp16 preview proxy.

### NEXT — resume here (priority order = `docs/PRIORITY_ROADMAP_2026-06-24.md`)
- **P0 + P1 — all done.** P0 #1/#2 (prior section) and P1 #3/#4 (this section).
- **P2 #5 — input gamut compression** (filming side: CAT02→CAT16 + xy-clip removal + locus bake, default-OFF).
  Reuses the `reinhard_knee` shipped here; the heavier `model/gamut_compression` color-appearance util comes
  with P2 #6 (perceptual algos). **P2 #7** is the umbrella that finally wires `output_gamut_compress` through
  JNI/facade/UI and confirms both `ScanningParams` sites + both filming expose sites + the print-route digest
  carry the flags — only actionable once #5/#6 land.
- **P2 (Kotlin)**: off-main-thread preset/diagnostics IO; undo/redo restoring-flag timing. **P3-quickwins**:
  the batch of UI nits (recipeKey remember, ROI/magnifier dispose-cancel, crop anchor, optDouble import, GPU
  re-arm, allocRotBuf overflow, RawCoilDecoder leak). **P3-defer**: the Strategy-B rebaseline cluster
  (unchanged — must move as one coordinated baseline bump; trigger = upstream settling its WB-norm baselines).

### Verification recap (this session)
Full engine-parity suite replayed locally from `ci.yml` argv: **31/31 gates green, fail=0** (all 28 prior
goldens byte-identical + morph + np_interp + the new gamut gate; `test_parallel` proves 1≡8 thread-invariance).
NDK r27 `externalNativeBuildDebug` green (arm64-v8a/armeabi-v7a/x86_64). Oracle reachable at
`/home/user/spektrafilm` (HEAD `27bd085`; has the full `utils/gamut_compression.py`); load it standalone with
the matplotlib-shim pattern in `gen_gamut_aces_golden.py` (colour imports for real first, then shim
`matplotlib.path`, register the module in `sys.modules` before exec or its dataclass decorator throws).

### Background watches (session-scoped; gone on restart)
PR #105 subscription + any cron die when the session ends — re-subscribe / re-arm if still wanted.

---

## State (2026-06-24 #2, branch `claude/exciting-hamilton-hya62`, PR #105 DRAFT) — P0 #1 ACES→ProPhoto RAW colorspace fix

Continuation on the same branch/PR. **Both P0 items from the roadmap are now done** (P0 #2 np_interp
shipped in the section below; P0 #1 ACES→ProPhoto ships here). The fix **visibly changes native
RAW/DNG renders** — a correction back onto the oracle — and moves **no parity golden** (no host
golden feeds a RAW buffer). All prior 29 engine-parity gates remain green; both native libs
(`libsfraw`, `libspektra`) and `:app`/`:engine` Kotlin compile.

### What shipped (P0 #1 — RAW input colorspace)
- **The bug:** LibRaw decoded RAW to **ACES2065-1**, but the engine ingests **linear ProPhoto RGB**
  (runtime `InputColorSpace` has a single value, `kProPhotoRGB`) and `nativeSimulate` hardcoded the
  input tag to ProPhoto while *discarding* the buffer's actual `inCs` tag — so every native RAW/DNG
  decode pushed ACES pixels through the ProPhoto primaries (wrong chromaticity on the core editor flow).
- **The fix (mirrors the oracle exactly):** the oracle's `raw_file_processor.load_and_process_raw_file`
  decodes ACES then `colour.RGB_to_RGB(..., output_colorspace="ProPhoto RGB")`. Ported that final step
  into **`raw_decoder.cpp`** (the architectural twin of `raw_file_processor.py`): new
  `kAcesToProPhoto` CAT02 matrix + `aces2065ToProPhotoRGB()`, applied per-pixel **after** the ACES-space
  WB adaptation (matching oracle order), retagging `result.colorSpace = "ProPhoto RGB"`. The conversion
  does **not** clamp (AP0 is wider than ProPhoto; out-of-gamut negatives are faithful to the oracle —
  gamut compression is the separate P1/P2 work).
- **Verification (the matrix IS the gate — libraw isn't in the host parity suite):** new host test
  `lib/libraw/src/test/cpp/test_aces_prophoto.cpp` checks `aces2065ToProPhotoRGB` against
  **colour-science 0.4.7** reference vectors (the oracle's pinned version) → **`max_abs 5.96e-08`**, and
  preserves the out-of-gamut negative. The HANDOFF matrix was re-derived from `colour.matrix_RGB_to_RGB`
  and confirmed authoritative (its non-unit row sums 1.00018/0.99996/1.00027 ARE colour's CAT02 output —
  do not "correct" them).
- **Hardening:** `nativeSimulate` now reads `inCs` and **throws** on any non-ProPhoto tag (empty = legacy
  ProPhoto) instead of silently re-interpreting — closing the hole that hid this bug. All decode paths
  (LibRaw, platform/photo `bitmapToLinearProPhoto`, synthetic) emit ProPhoto, so the throw is safe.
- **Honesty:** the inert UI "Input color space" dropdown (engine only does ProPhoto) is now wrapped in a
  `GatedBlock` disclaimer, mirroring the existing MALLETT2019 treatment. `:lib:libraw` docs +
  `RawDecoder`/`LinearResult`/`EngineHelpers` doc-comments updated (the module now returns ProPhoto, ACES
  is intermediate only). `RawCoilDecoder` (dead/unreferenced) left as-is.
- **Verified:** `test_aces_prophoto` + `test_dng_sniffer` green; engine `test_simulate_e2e` +
  `test_parallel` green (engine core source set is byte-unchanged); `:lib:libraw` + `:engine`
  `externalNativeBuildDebug` BUILD SUCCESSFUL (all 3 ABIs, NDK r27); `:app`+`:engine compileDebugKotlin`
  BUILD SUCCESSFUL. CI re-running.

### NEXT — resume here (priority order = `docs/PRIORITY_ROADMAP_2026-06-24.md`)
- **P0 — both done.** P0 #1 (ACES→ProPhoto, this section) + P0 #2 (np_interp, below).
- **P1 #3 — `aces_rgc` output gamut compression** (default-OFF, LEGACY_CLIP sentinel). The recommended
  first gamut port: establishes the newer-oracle-golden gating pattern + builds the shared
  `reinhard_knee` helper that the input/perceptual gamut items (P2 #5/#6) reuse. Zero default-path impact.
- **P1 #4 — `float_to_half` round-to-nearest-even** (`half.cpp`): cheap/isolated, must land before the
  fp16 preview-proxy storage turns the latent scalar-vs-NEON divergence into a failing `test_half`.
- **P2/P3** unchanged from the section below (input/perceptual gamut, Kotlin UI quick-wins, reflectance/
  Mallett2019, and the deferred Strategy-B rebaseline cluster).

### Background watches (session-scoped; gone on restart)
PR #105 subscription + cron `7b2e5907` were set up in the prior session and **die when that session ended**
— re-subscribe / re-arm in this session if still wanted.

---

## State (2026-06-24, branch `claude/exciting-hamilton-hya62`, PR #105 DRAFT) — review fixes + upstream-sync + print-curve morph + np_interp fix

All pushed to `claude/exciting-hamilton-hya62` (PR #105, repo moved to
`thetechgeekko/Spektrafilm-android`). Host engine-parity verified green at every step
(now **29 gates**: the prior 27 + `test_print_curves_morph` + `test_np_interp`).
Default render/export path is byte-identical to oracle `c1d0e44` except the
auto-exposure metering fix (a correction back onto the oracle). Android `:app` +
`:engine` Kotlin compile (`compileDebugKotlin` BUILD SUCCESSFUL). CI re-running.

### Commits this session (oldest→newest)
1. `9b84e1e` Auto-exposure metering parity fix + 4 review follow-ups + review report.
2. `449cca7` `docs/UPSTREAM_SYNC_2026-06-24.md` (merged-main + experimental-branch plan).
3. `a33610a` Print density-curve morph (s023) engine core (opt-in, default-off).
4. `5a31edf` Morph param chain: `spk_params`→JNI→Kotlin facade.
5. `7c638ea` CHANGELOG for the morph.
6. `b800ccd` `docs/PRIORITY_ROADMAP_2026-06-24.md` (ranked P0..P3).
7. `4b745b1` Morph editor UI (`ParamsState`/`Presets`/`MainActivity` Experimental section).
8. `ad4b2e4` **`np_interp` non-monotonic DIR-coupler fix (P0 #2)**.

### What shipped + verified
- **Codebase review** (`docs/CODE_REVIEW_2026-06-24.md`): 18 confirmed findings. 5 FIXED in
  `9b84e1e`: auto-exposure `small_preview` AA prefilter (only default-path divergence;
  gate `test_small_preview_aa`), CI `build_run` honors test exit code, `crop_image`
  oversize NumPy slice, `spk_simulate` null guard, `grain.cpp` dead store.
- **Print-curve morph (s023)** — first opt-in feature from the sync plan. `model/morph_curves.{h,cpp}`
  ports `apply_print_curves_morph` (coupled gamma + Gumbel blend + scipy-style `brentq` D(0)
  offset). `profiles/profile.cpp` now parses `data.density_curves_model`. Integrated in
  `printing.cpp::print_develop` (default-off → stored table, byte-identical). Wired through
  `spk_params` (`print_morph_*`)→`spektra_jni`→`PrintCurvesMorphParams` facade→`ParamsState`→
  editor (Experimental section, enable switch + 7 sliders)→`Presets`. Gate
  `test_print_curves_morph` **bit-exact (max_abs 0.0)** vs oracle golden
  (`tools/parity/gen_print_curves_morph_golden.py`). Default-off keeps all goldens green.
- **`np_interp` coupler fix (P0 #2)** — `couplers.cpp` used a plain ascending binary search;
  the DIR-coupler axis `le0 = le - silver@M` can be NON-monotonic (diverged up to ~0.44 at
  amount≥2 from the shipping sliders). Now ports numpy's order-dependent
  `binary_search_with_guess` + batched `arr_interp` (`np_interp_array`, exposed in `couplers.h`).
  Gate `test_np_interp`: **bit-exact (max_abs 0.0) vs `np.interp`** over 82 cases/3175 pts
  (77 non-monotonic). NOTE: a per-element linear scan does NOT match numpy (964/4000 random
  fails) — the carried guess matters; keep the batched form.

### NEXT — resume here (priority order = `docs/PRIORITY_ROADMAP_2026-06-24.md`)
- **P0 #1 — ACES→ProPhoto RAW colorspace fix (READY, awaiting go; CHANGES RAW OUTPUT).**
  Bug: LibRaw decodes RAW to ACES2065-1 (`raw_decoder.cpp:536`) but the engine treats input as
  ProPhoto (`spektra_jni.cpp` discards `inCs`, hardcodes `SPK_CS_PROPHOTO`); ACES pixels run
  through the ProPhoto→XYZ matrix → wrong primaries on every native RAW decode. Oracle decodes
  ACES then `colour.RGB_to_RGB`→ProPhoto (engine `input_color_space` default = "ProPhoto RGB").
  FIX: bake the matrix below, apply per-pixel in `raw_decoder.cpp` where ACES is produced (one
  place), set `result.colorSpace="ProPhoto RGB"`; also marshal `getInputColorSpace` (currently
  inert). No host golden covers RAW, so correctness = the matrix (verified vs colour-science
  0.4.7). The `decodeViaPlatform` fallback already emits ProPhoto. Caveat: `libraw` module is
  NOT in the host parity suite — only the Android CI build compiles it; write the per-pixel loop
  carefully. This is the only P0 item that VISIBLY changes RAW renders (a correction). Matrix
  `ACES2065-1 → ProPhoto RGB` (CAT02, `colour.RGB_to_RGB` default), row-major:
  ```
  { 1.2393803418, -0.1639678228, -0.0752333838}
  { 0.0036113619,  1.0896136492, -0.0932657921}
  {-0.0020596793, -0.0022515883,  1.0045855773}
  ```
- **P1**: `float_to_half` round-to-nearest-even (`half.cpp`, latent until fp16 proxy); then the
  `aces_rgc` **output gamut compression** hook (`scanning.cpp`, opt-in/default-off via a
  LEGACY_CLIP sentinel — establishes the shared `reinhard_knee` + newer-oracle-golden gating the
  input/perceptual gamut items reuse). See roadmap items 3–7 + dependency notes.
- **P2/P3**: input/perceptual gamut compression; batch the Kotlin UI quick-wins (main-thread IO,
  ROI bitmap cancel, crop anchor, `allocRotBuf` overflow, preset `optDouble`, undo timing, GPU
  flags, `RawCoilDecoder` leak); reflectance/Mallett2019 upsampling (P3, blocked on a stable new
  oracle SHA). DEFERRED (P3, Strategy-B baseline move, parityRisk high): the 28/29 profile refit
  + default-ON engine math + WB-norm + B&W N-channel + Langmuir + grain overhaul — must move as
  ONE coordinated rebaseline (re-pin `tools/parity/setup_env.sh`, regen every golden); trigger =
  upstream settling its WB-norm regression baselines.

### How to verify (host parity — the real gate)
`engine/spektra-core/src/main/cpp`; compile each TU once to objects then link each test (much
faster than re-compiling the source set per test). Replay the exact CI argv:
`grep -E '^\s*build_run ' .github/workflows/ci.yml` and run each (`$ASSET` =
`engine/.../assets/spektra`, `$G` = `tools/parity/goldens`, `$LUT` =
`$ASSET/luts/spectral_upsampling/irradiance_xy_tc.npy`). PASS = no `FAIL` line + exit 0. Kotlin:
`ANDROID_SDK_ROOT=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew
:app:compileDebugKotlin --no-daemon -Dkotlin.compiler.execution.strategy=in-process` (the gradle
daemon crashes on GC auto-select in this env; `--no-daemon` + in-process works). Oracle for new
goldens at `/home/user/spektrafilm` (HEAD `3bb2c2d`; baseline `c1d0e44`); top-level
`import spektrafilm` fails (lensfunpy missing) — load single modules directly with stubbed deps
(see the morph/np_interp gen scripts). `andreavolpato/spektrafilm` git is policy-blocked (403);
its branches were pushed to the `thetechgeekko/spektrafilm` fork (`reflectance-upsampling-methods`,
`non-linear-couplers`, `dev`) and analyzed (Part B of the sync doc = track-only).

### Background watches (session-scoped; gone on restart)
PR #105 is subscribed for CI/review webhooks; cron job `7b2e5907` (hourly :47) re-checks CI/merge
state. Both die when the session ends — re-subscribe / re-arm in a new session if still wanted.

---

## State (2026-06-05, branch `claude/exciting-hamilton-hya62`, PR #85 DRAFT) — GPU reverted, battery debounce, brutalist-re skill

Continuation of the PR #85 work below. All pushed; CI green. Net since that section:

- **GPU preview REVERTED to default OFF** (commit `6494f21`). On SM-S948W the default-on GPU
  fit preview broke the editor: its `GLSurfaceView` (`GpuPreviewSurface`) churned (BLASTBufferQueue
  "rejecting buffer" every frame, 87 dropped frames, 898ms Davey) as the preview area resized during
  panel animations, grew over the bottom controls (hid the export button), and could show a black
  surface — user saw "can't render" + "export not clickable". Root-caused by a 3-agent swarm:
  (1) the preview Box has `weight(1f)` and the AdjustmentPanel `AnimatedVisibility(expandVertically)`
  grows in the SAME Column → preview shrinks every frame → SurfaceView reallocates every frame; fix =
  float the panel as a bottom **overlay** so preview height is constant. (2) `LutRenderer` uses
  `RENDERMODE_WHEN_DIRTY` + a redraw only in the AndroidView `update` lambda → can stay black; needs a
  guaranteed redraw. Export button is NOT overlaid (it's in EditorTopBar); it was greyed only because
  `canExport = engine!=null && !previewBusy && !exporting` and `previewBusy` stuck true under the jank
  — the `exporting` flag resets fine via `ExportMask` onDismiss (MainActivity ~1369-1373; agent's
  "lockout bug" was a false positive). GPU code kept as an opt-in toggle (Settings, experimental).
- **Battery: settle debounce raised** (commit `72cc807`) — preview render 350→500ms (MainActivity
  ~819), Lightroom-zoom ROI 280→500ms (Viewer.kt ~178). Device logcat showed the drain is active-use
  all-core CPU: each render maxes all cores ~1s; renders started then cancelled ("coroutine scope left
  the composition"); ONE pinch fired 5 overlapping full RAW 2048px decodes (ROI re-fires every 280ms,
  each cancels the coroutine but LibRaw keeps decoding on its thread). Debounce trims frequency only.
  STILL OPEN: (a) single-flight the zoom 2048 decode (one decode per gesture, in a stable scope so a
  cancelled ROI render doesn't abandon+rerun it); (b) resize-safe GPU offload (the real per-edit win).
- **Install/signing fixed (device).** Committed a stable `debug.keystore` + pinned the debug
  signingConfig (commit `58ed0be`) so every build shares one signature (no more "App not installed"
  signature-mismatch). CRITICAL: build distributable debug APKs with plain `./gradlew :app:assembleDebug`
  — do NOT use `-Pandroid.injected.build.abi=...` (it stamps `android:testOnly=true`, which blocks
  tap-install; only `adb install -t` works).
- **NEW skill: `brutalist-re`** at `.claude/skills/brutalist-re/` — Android reverse-engineering
  (jadx/vineflower/dex2jar/apktool; decompile + exhaustive API/secret/manifest extraction), adapted
  from SimoneAvogadro/android-reverse-engineering-skill (Apache-2.0) with a blunt, exhaustive
  "brutalist" operating mode. Scoped to user-provided / authorized targets. Unrelated to the film app;
  parked here only because this is the persistent repo. (User asked to persist it.)

**Next-session TODO:** single-flight zoom decode (battery); re-enable GPU properly (panel-as-overlay +
guaranteed GL redraw) and device-verify before default-on; on-device smoke of zoom/presets/export.

---

## State (2026-06-05, branch `claude/exciting-hamilton-hya62`) — PR #85 (DRAFT, unmerged): zoom/OOM fix, GPU fit preview, preset rebuild, grain/halation verification

Triggered by a device logcat showing an `OutOfMemoryError` storm ("Failed to allocate a
36000019 byte allocation", `7(205MB) LOS objects`) and the report that halation/grain look
weak vs upstream and presets feel thin. All work is **Kotlin/asset only — NO engine C++
changed**, so the 26-test host parity suite is untouched (CI engine-parity stays green).
Four commits on PR #85 (base `main`):

- **Zoom/OOM (commit `7ea73df`).** The Lightroom zoom (`renderRoi`) + 100% magnifier called
  `loadSource(MAX_EDGE_PX)` UNCACHED — every pan/zoom settle re-ran a ~1s LibRaw decode + a
  36MB managed `LinearImage` (1500×2000×3×4B), stacking past GC → ART-heap OOM. Added a
  dedicated single-entry `zoomSourceCache` (separate from the 640px preview cache so they don't
  evict each other), `android:largeHeap`, and a **crop-scale fix**: the engine derives
  `pixel_size_um = film_format_mm·1000/max(w,h)` from the crop's own pixel count, so a sub-frame
  crop was treated as a whole 35mm frame → grain/halation (µm-based) too weak when zoomed. New
  `toParams(filmFormatMmOverride)` scales the effective film format by the crop fraction so
  zoomed crops show grain/halation at the proxy's true strength. Export/full-frame unaffected.

- **Preset mapper (commit `39264ee`) + rebuild (commit `f59792f`).** `BuiltInPresets.applyParams`
  now also maps camera/enlarger `diffusionFilter` + `toneCurve` (it already covered everything
  else). Replaced the 21 sparse presets (only ~10 of ~90 params; every stock on Portra-400
  grain) with **28 redesigned looks** in `assets/spektra/presets.json` across 8 groups, each
  with per-stock grain + honest halation/DIR/diffusion/tone-curve/glare. **Only engine-HONORED
  fields are set** — verified against `spektra.cpp` apply_user_*: grain is fully tunable;
  halation uses `halationAmount`/`scatterAmount`/`boostEv` (NOT `halationStrength`/
  `halationFirstSigmaUm`, which are BAKED per-profile from `use`/`antihalation` and ignored);
  DIR uses amount/inhibition/diffusion (gamma matrices baked); diffusion `filterFamily` is fixed
  to black_pro_mist by the C API. New `BuiltInPresetsAssetTest` guards JSON/profile-refs/no-op
  fields (the asset had zero coverage).

- **GPU fit preview (commit `6831939`).** Promoted the GPU LUT preview from off-by-default beta
  to the **default fit-view renderer** (instant look via the baked 3D LUT on-GPU, no ~1s CPU
  re-render per edit). Made it safe: `LutRenderer.onUnavailable` reports GL program-build failure
  → editor latches `gpuBroken` → CPU fallback (no black screen); **fixed an aspect-stretch bug**
  (full-screen quad → letterboxed `uScale` + triangle-strip quad); new `GpuPreviewSurface` adds
  tap (→magnifier) + pinch/double-tap (→ hand off to CPU `ZoomableImage`, which renders the zoom
  region with grain via the ROI path; handoff resets on return-to-fit). EXPORT stays the exact
  CPU engine — GPU is preview-only, parity path untouched. **NOT GL-verified on a device** (host
  can't); the fallback is what makes default-on safe — needs a device sanity-check.

- **Grain/halation (Stage 2) — VERIFIED, no change.** Audited all 28 profiles: every film stock
  HAS valid `use`/`antihalation` tags (halation is active for all), and the baked strengths
  (`strong→(0.015,0.005,0)`, `weak→(0.08,0.02,0)`, `no→(0.30,0.10,0.015)`) EXACTLY mirror the
  oracle's `_apply_halation_preset` (params.cpp:164-188). With the parity gate, **export grain/
  halation already match spektrafilm**; the "too small" was the 640px preview averaging µm-scale
  effects (grain std ∝ 1/√(particles-per-px); ~15k/px at 640 vs ~170/px full-res). Addressed by
  the zoom crop-scale fix + correct export; an engine change would BREAK parity, so none made.

**Open / next session:**
- **Device sanity-check the GPU path** (different vendors/drivers): confirm the fit preview
  renders correctly and falls back cleanly; the user can toggle it in Settings → GPU preview.
- Optional: a true native-resolution 1:1 magnifier (currently crops the 2048 proxy; export-grade
  grain would need a native-crop decode) and/or a modest `previewMaxSize` bump — both have
  memory/perf trade-offs, deferred.
- PR #85 is a DRAFT awaiting on-device validation of the zoom-OOM fix + GPU preview.

---

## State (2026-06-05, branch `claude/exciting-hamilton-hya62`) — highlight-boost WIRED + LUT-load speedup; arm64 APK to device

Parallel session, ran ALONGSIDE the `intelligent-johnson` param-wiring session below. Trunk stays
v0.7.0 / versionCode 9. **Two PRs, BOTH MERGED to `main`:**

- **#82 (merge `c913b45`, commit `7a87cad`) — Highlight boost WIRED.** This **CLOSES open
  param-wiring finding #1** below ("`boost_ev`/`boost_range`/`protect_ev` INERT … the next one to
  do"). Ported `utils/numba_boost_hightlights.py::boost_highlights` →
  `model/diffusion.cpp::apply_highlight_boost`, called from `filming.cpp::expose` right after the
  EV-comp scale and BEFORE diffusion/lens-blur/halation (matching `filming.py:58-60`, midgray=0.184).
  **Key plumbing fix:** the boost params were only threaded into `fparams.halation` inside the
  `if(spatial)` block, so they were dropped on the default spatial-OFF path — now threaded
  UNCONDITIONALLY in BOTH `run_scan_film` and `run_print`, and folded into `compute_film_cache_key`
  (the print-route film-density memo). New `scan_portra_boost` golden (oracle `c1d0e44`) +
  `test_highlight_boost_e2e` (film taps + final within tol, on-vs-off ACTIVE, thread-invariant 1≡8).
  `boost_ev=0` default → strict no-op; full suite **26/26 byte-identical**. So param-wiring #1 is
  DONE; **#2–#5 (Mallett2019, spatial-conflation, print-route grain/spatial, dead sliders) remain
  open** per the section below — those are the other branch's findings.

- **#83 (merge `540b257`, commit `cfa4e16`) — spectra-LUT load ~2× faster.** Replaced the per-element
  `std::ldexp` in `io/npy_lut.cpp::rd_f16le` (~3M libm calls loading the 192×192×81 spectra LUT) with
  branchless integer half→binary32→double. **BIT-IDENTICAL** — verified EXHAUSTIVELY over all 65536
  half patterns + the asset's ~3M elements (0 mismatches), full parity suite stays byte-identical.
  3.1× faster conversion; one-time engine-create LUT load **36ms→17ms** (host). One-time/cached cost,
  NOT a per-render lever. Also corrected a stale `docs/AUDIT.md` §C claim + added a PERF note.

**On-device APK delivered (user's Galaxy S26 Ultra / arm64).** The container had **NO Android SDK** —
installed NDK r27 (`27.0.12077973`) / CMake 3.22.1 / build-tools 35 at `/opt/android-sdk` (may NOT
persist across containers). Built an **arm64-v8a debug APK** via
`./gradlew :app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a` → outputs to
**`app/build/intermediates/apk/debug/app-debug.apk`** (NOT the usual `outputs/`). Verified
`com.spectrafilm.app` v0.7.0/vc9, 19.4 MB, debug-signed, **16KB-aligned**, all 4 engine libs
(libspektra/libsfraw/libsftiff/libsfpng) present. Sent to the user. **AWAITING their in-app logcat**
(tags `Spektra`/`sfraw`) from a RAW import → render → boost-adjust → export pass — to verify boost +
startup on-device and start closing the device-gated items.

**Environment (reproduce next session):**
- Host parity suite runs here (g++). **Oracle reproduction VALIDATED:** pip-installed numpy 2.4.6 /
  scipy 1.17.1 / numba 0.65.1 / colour 0.4.7 / skimage 0.26.0, cloned oracle to
  `/tmp/spektrafilm_clone` @ `c1d0e44`, and regenerating `scan_portra` reproduced the committed
  golden **bit-identically**. `/tmp` + the pip env do NOT persist — re-clone + re-install via
  `tools/parity/setup_env.sh` (which `pip install`s `numba opt_einsum scikit-image exiv2`; add
  `colour-science`). The "do NOT pip install" note = don't install the full spektrafilm package, the
  targeted math-stack install IS the sanctioned path.
- **Profiles fully ported:** all 28 profile JSON + 184 supporting assets are **byte-identical** to
  upstream `c1d0e44` (sha256-verified). Only upstream file not shipped: the build-only coeffs `.lut`
  (runtime loads only the pre-baked `.npy`) — correct to omit.
- `gen_goldens.py` now carries a `scan_portra_boost` case (Case fields boost_ev/boost_range/protect_ev).

**User directives this session:** (1) **Do NOT change any `.github/workflows/` files** — "everything
works there." (2) On the `.lut`→`.bin` idea: do NOT convert (measured net-negative — the `.lut` isn't
even a runtime file; the runtime `.npy` load is one-time/cached and dominated by f16→f64 conversion,
which the rd_f16le opt already fixed).

**Remaining ledger (honest):**
- ✅ **By-design closed (verified):** enlarger lens blur (NO oracle call site — `lens_blur_um` has
  exactly one consumer, `filming.py:66`, the CAMERA blur, already ported); glare-on-print (stochastic).
- 🔒 **Hardware-gated (the S26 now partly unblocks these):** instrumented `androidTest`, GPU
  on-device verify, R8 Stage-2 + on-device smoke, on-device NEON timing, RAW-tiling validation.
- 📋 **Feature-adds (scope decisions, not gaps):** 32-bit-float TIFF export (feasible host-side, no
  new dependency — the next clean candidate), EXR export (needs a new native encoder dependency),
  native RAW tiling for pathologically large DNGs.
- The 4 OPEN param-wiring findings (#2–#5) below are the **other branch's** — coordinate so the two
  sessions don't collide on shared engine files (do engine fixes ONE AT A TIME).

**Next steps:** (1) receive + analyze the S26 logcat → verify boost/startup on-device. (2) Offer a
release(R8) APK if the user wants the minified path re-validated with the new code. (3) 32-bit-float
TIFF export if wanted. (4) Param-wiring #2 (Mallett2019) needs a user DECISION (implement vs remove
the dropdown) before touching it.

## State (2026-06-05) — full param-wiring audit + print EV-comp fix; R8 validated on-device

Continuation of the 2026-06-04 session on branch `claude/intelligent-johnson-DEOqK`. All PRs below
**merged to `main`** (trunk still v0.7.0 / versionCode 9). Every engine change was validated against
four gates (default byte-identity of all pre-existing goldens, feature-on within parity tol
`max_abs≤1e-4`/`rms≤1e-5` vs an oracle golden, thread-invariance `SPK_NUM_THREADS` 1 vs 8, JVM unit
tests). **Parity oracle stays pinned at `c1d0e44`** — regenerate goldens ONLY at that SHA
(`tools/parity/setup_env.sh`); upstream tip has drifted (`~4.44` on `film_log_raw`).

**Merged this run:** #77 downscale AA-prefilter fix (a real parity bug — minification on the export
path diverged ~0.18–0.4 from the oracle because the C++ skipped skimage's `anti_aliasing` gaussian
prefilter); #78 diagnostic `Spektra`-tagged logging breadcrumbs (engine create / decode / render /
export); #79 recorded the **R8 release-build on-device validation**; #80 **print EV-compensation
fix** (below).

**R8 / release is validated on-device (2026-06-04, SM-S948W / Android 16 / arm64).** A minified
release APK did full RAW import → preview render → full-res 12 MP **PNG + TIFF export**, with
`libsftiff.so` loading via `nativeloader … ok` before the TIFF write — i.e. the name-based JNI
keep-rules resolve at runtime under R8, no `UnsatisfiedLinkError`/crash/OOM. The `AdrenoVK
shaderType 0/6` logcat lines are benign Compose-popup driver noise (the engine Vulkan compute path
is an OFF/stub, never called; the GLES LUT preview is fragment-only + default-off), NOT app code.
Remaining R8 work is optional: Stage-2 obfuscation, `shrinkResources`, a subjective visual pass.

### ⭐ Full param-wiring audit (2026-06-05) — the live work item
A 3-crew end-to-end sweep traced EVERY `spk_params` field UI→facade→JNI→engine-consumer vs the
oracle at `c1d0e44`. Most params are correctly WIRED. **#1 (print EV-compensation) is FIXED (#80);
the rest are open findings — full detail in `docs/AUDIT.md` §A "Full param-wiring audit".**

- ✅ **#1 DONE (#80):** print midgray balance ignored `exposure_compensation_ev` /
  `normalize_print_exposure` / `print_exposure_compensation` — native hardcoded EV=0 and always
  returned the uncompensated factor. Ported the oracle's 4-case midgray branch
  (`printing.py:104-118` + `filming.py:125-134`, compensated gray `0.184·2^EV`) into
  `runtime/print_digest.cpp`. EV=0 default stays bit-exact (25-test suite `fail=0`); goldens
  `print_portra_evcomp{,_nonorm}` at c1d0e44 + `test_print_evcomp_e2e`.
- **OPEN, severity-ranked (each needs its own oracle golden + default-no-op/thread-invariant):**
  1. 🟠 **Highlight-boost `boost_ev`/`boost_range`/`protect_ev` — INERT** (filming). Oracle
     `boost_highlights` (`filming.py:58-60`, unconditional, gated `boost_ev>0`) is unported
     (`diffusion.cpp:55` "Not applied here"). **Pure parity fix, no decision** — the next one to do.
  2. 🔴 **`rgb_to_raw_method=MALLETT2019` — MIS-WIRED** (filming). No Mallett path in C++; always
     runs Hanatos2025. **DECISION: implement vs remove the dropdown option.**
  3. 🟡 **Spatial effects conflated under `halation_active`** (filming+scanning). Camera lens-blur,
     camera diffusion filter, DIR diffusion, scanner unsharp + lens-blur all die when halation is
     OFF; oracle gates each independently. **DECISION: keep "halation = master" (+disclose) vs
     decouple per-effect.**
  4. 🟡 **Print route hard-forces film grain + spatial OFF** (filming, `run_print`). Oracle's normal
     print path keeps them. **DECISION: keep vs honor toggles on the print route.**
  5. ⚪ **Dead-but-oracle-consistent sliders** (UX, not parity): DIR-coupler gamma sliders,
     `enlarger_lens_blur`, film-side `glare_*` — all present but do nothing (and the oracle doesn't
     consume them either). **DECISION: dim+disclose vs remove.**

**Suggested defaults proposed to the user (awaiting confirmation):** remove the MALLETT option;
decouple the spatial gating to match the oracle; keep the print-route behavior but disclose;
dim+disclose the dead sliders. Do highlight-boost (#1 above) first — it needs no decision.
**Sequence engine fixes ONE AT A TIME on the branch** (they collide on shared files / the PR).

## State (2026-06-04) — oracle pin + finish all inert engine params + positive-film coupler fix

A web/CI session on branch `claude/intelligent-johnson-DEOqK`. **Ten PRs (#67–#76), ALL MERGED to
`main`.** Trunk stays v0.7.0 / versionCode 9. Every engine change was validated against four gates:
(1) default byte-identity — all pre-existing goldens stay bit-exact, (2) feature-on within parity
tol (`max_abs ≤ 1e-4`, `rms ≤ 1e-5`) vs an oracle golden, (3) thread-invariance at
`SPK_NUM_THREADS` 1 vs 8, (4) JVM unit tests.

**Environment note (this container):** the **host g++ parity suite runs here** (unlike the Windows
box described in the 2026-06-02 section, which had no host libc++). The **spektrafilm oracle is
reachable**: `git clone https://github.com/andreavolpato/spektrafilm /tmp/spektrafilm_clone &&
git -C /tmp/spektrafilm_clone checkout c1d0e44`, then `export
SPEKTRAFILM_SRC=/tmp/spektrafilm_clone/src && source tools/parity/setup_env.sh` (Python 3.11
PYTHONPATH shim; do NOT `pip install`; SEED=20260529). Goldens regenerate via
`tools/parity/gen_goldens.py`.

**① Parity oracle PINNED (#67) — the keystone.** The committed goldens had no recorded provenance
and upstream had drifted: regenerating from today's tip diverged `film_log_raw ~4.44`. Bisected the
upstream history and pinned the oracle to **`c1d0e44`** — at that SHA all 9 `gen_goldens` cases ×
all taps reproduce **bit-exactly** (`max_abs=0`). Drift commit identified: **`a9bccd6`** ("tap
inject/collect system", sole parent `c1d0e44`) changed the filming raw-scaling. Recorded
`SPEKTRAFILM_ORACLE_SHA` in `tools/parity/setup_env.sh` + provenance in `tools/parity/README.md`.
**This made every subsequent golden reproducible** — do NOT regenerate goldens from upstream tip; use `c1d0e44`.

**② All inert marshalled engine params now WIRED + gated (audit action #2 CLOSED).** Each was a UI
slider + JNI-marshalled param that NO engine stage consumed (the slider lied to the user). Spec for
each verified by reading the oracle at `c1d0e44` (NOT trusting `ENGINE_WIRING_PLAN.md`, which was
wrong about the surface term):
- `spectral_gaussian_blur` (#68) — blurs the Hanatos2025 spectra LUT along its spectral axis in
  `build_filming_tc_lut`; default 0 = strict no-op.
- `apply_hanatos2025_window/surface` (#69) — window = erf4 bandpass; **surface = per-LUT-cell
  degree-4 2D polynomial** (`2**surface`), NOT an erf4 (plan was wrong).
- camera UV/IR cut (#72) — `filter_uv*filter_ir` band-pass on sensitivity in `build_filming_tc_lut`.
- enlarger preflash (#73) — print-stage uniform pre-exposure; correctly NOT folded into the
  film-density cache key (print-expose is never cached), only the print route.
- scanner white/black corrections (#74) — three route-gated corrections sharing one
  `_correction_function` across filming/printing/scanning; new `runtime/color_reference.{h,cpp}`.
- §4 enlarger lens blur stays **honestly gated** (no oracle call site) — do not wire.

**③ Positive-film DIR-coupler parity gap FIXED (#75).** Surfaced by #74: positive film
(Provia/Velvia) via the `scan_film` route with DIR couplers ON diverged **~0.32** from the oracle
(negatives were always fine). Root cause: `digest_filming_params` applied the generic positive
coupler-gamma default `(0.12,0.08,0.06)` but omitted the oracle's **per-stock override**
(`params_builder._apply_film_specifics`) → provia `(0.156,0.104,0.078)`, velvia
`(0.108,0.072,0.054)`. Fix threads the stock string through and applies the override after the
positive branch — stock-gated, so negatives stay bit-exact. Golden regenerated from `c1d0e44`
**byte-identically** + upstream gamma values confirmed line-by-line. (NB: the first crew on this
left it uncommitted with a garbled report; it was independently re-verified before shipping.)

**④ Docs synced (#70, #71, #76).** `RELEASE_CHECKLIST`/`ROADMAP` were already mostly fixed; the real
drift was R8 — **`isMinifyEnabled` was flipped to `true`** (release minify ON via
`proguard-rules.pro`), so `CLAUDE.md` + docs were corrected. `CLAUDE.md`'s engine-parity gate list
synced to the actual **23 tests** in `ci.yml`.

**CI gotcha seen this session:** the `android` assemble job intermittently flakes on a corrupt
Android-SDK/emulator download during `setup-android` ("Error on ZipFile unknown archive") — NOT a
code failure. If engine host build + engine-parity are green but `android` is red with that log,
re-run the failed job (`rerun_failed_jobs`); it can't be re-run until the run's other jobs finish.

**Remaining backlog — DEVICE-GATED (cannot be done in a no-hardware container):**
1. **R8 on-device validation.** Release minify is now ON, but CI only assembles **debug** (minify
   off), so the R8 shrink path is **unexercised** — a wrong keep-rule in `proguard-rules.pro`
   surfaces only as a runtime crash. Smoke-test a **release** build on a real device before the next
   tag. (`-dontobfuscate` + JNI/enum keep-rules are in place.)
2. **Instrumented (`androidTest`) coverage** for JNI marshalling + export quantisation
   (`ImagePipeline`/`DecodedSourceCache`/`EngineHelpers`/`RawDecoder`/`PngWriter`) — needs a
   device or Robolectric.
3. Lower priority (non-device, from AUDIT): memory tiling for very large RAW; downscale
   (`upscale_factor < 1`) anti-aliasing prefilter; GPU preview on-device verification.

## State (2026-06-03) — audit + cleanup + lifecycle fixes + render-speed/zoom (PR #60)
All this session's work is on branch `claude/intelligent-johnson-DEOqK` (draft **PR #60**, **CI
green** on every job — engine-parity, parity comparator, python-lint, engine-native, android
assemble; emulator skipped as designed). **6 commits, +845 / −367, working tree clean.** Trunk is
still v0.7.0 / versionCode 9; nothing here is merged (merging is policy-gated — needs user
go-ahead).

**Environment note:** this container now has the **full Android toolchain installed at
`/opt/android-sdk`** (NDK `27.0.12077973`, CMake 3.22.1, build-tools 35, platforms 34+35) — so a
web/CI session can build the app AND run `:app:testDebugUnitTest` end-to-end. `local.properties`
(git-ignored) points `sdk.dir` there. A freshly-built arm64 `libspektra.so` was confirmed
`0x4000`-aligned (the current build IS 16 KB-correct; only the old committed `dist/` APKs were not).

What landed (commit → what):
- `213a421` **chore(cleanup/license):** removed the committed `dist/*.apk` (+`.sha256`) — stale
  (v0.1–0.3), **16 KB-page-misaligned** (`dlopen`-fail on Android 15 16 KB devices), debug-signed;
  dropped the `!dist/*.apk` un-ignore. Closed the **ICC license gap** (`NOTICE.md` ICC section +
  `icc/ellelstone/LICENSE-CC-BY-SA-3.0` + `icc/saucecontrol/LICENSE-MIT`). Fixed a false
  `scanning.cpp` comment + CLAUDE.md version.
- `cf8f2d1` **fix(app): three 🔴 Android lifecycle bugs** (compile+CI verified): native engine
  leaked on every config change → process-scoped `EngineHolder` singleton (engine is immutable +
  thread-safe, so never closed mid-life → no use-after-free); edit session lost on process death →
  `rememberSaveable` for source URI/kind/name + rotation; `DecodedSourceCache.put/invalidate` now
  `close()` evicted entries; `SpektraEngine.close()` made idempotent.
- `3b33904` + `6a0f5dc` **docs: full re-sync to v0.7.0 reality** (two passes, agent-swarm + verified
  against code): engine README "M0 contract only" → "shipped"; `ENGINE_WIRING_PLAN.md` §3 enlarger
  LUT marked **wired** + fixed wrong fn name (`build_filming_tc_lut`); **`RELEASE_CHECKLIST.md`
  rewritten** (was telling maintainers to commit APKs to the removed `dist/`, no 16 KB check → now
  the real `release.yml` flow); AUDIT/ASSETS/CHANGELOG (added the missing v0.6.x RAW-OOM entry)/
  RAW_DNG; `tools/parity/cases.md` (+lensblur case); `RESEARCH_MCRAW` line-refs; GPU/fp16 framing;
  ⚠️ banners on the never-built ImageToolbox-host docs (ARCHITECTURE, IMAGETOOLBOX_MAP, bootstrap,
  SCREEN_REGISTRATION).
- `54d4d3d` **perf(engine): profile + filming tc_lut cache** (PARITY-SAFE). Every `simulate` was
  re-parsing the profile JSON and rebuilding the tc_lut; now memoized on `spk_engine` keyed by
  **immutable profile id** → byte-identical (a memo, not an approximation). **Verified byte-exact:**
  host parity suite ALL-PASS unchanged + a new `test_simulate_e2e` **warm-engine-vs-fresh-engine
  `memcmp`** assertion; thread-invariance intact. This is the down-payment on the bigger §3 stage
  cache.
- `af09449` **feat(viewer): Lightroom-style zoom** — zoom past fit now renders the **visible region
  at ~screen resolution** (ROI render via the existing magnifier primitive `cropLinearImageRect` +
  `simulatePreview`, debounced last-wins, overlaid sharp on the scaled proxy), instead of
  GPU-scaling the 640px proxy. **Zero engine/C++ change**, memory bounded to screen res (sidesteps
  full-res OOM). New `ZoomViewportTest` (7 cases) proves the transform math.

**Verified this session:** host engine-parity ALL-PASS + byte-identical (1 vs 8 threads);
`:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL, 37/37 unit tests** (incl. new
`ZoomViewportTest`); PR #60 CI green.

**GPU rendering — investigated (no code).** Verdict: GPU can be a **preview-only accelerator,
never export** (parity is bit-exact-vs-oracle AND byte-identical-across-threads; GPU float varies by
vendor, the expose integrals are float64, and `-fno-finite-math-only` NaN handling is
implementation-defined on GPU). The existing `gpu/vulkan_compute.cpp` + SPIR-V is **dead scaffolding**
(default-OFF, zero callers) and covers the **scan** stage — NOT the measured hotspot (the filming/
print **expose** integrals, which have no GPU kernel). `LutGpuPreview.kt` is a default-off pointwise
`.cube` loupe, not a pipeline render. A real GPU render path is **XL and hardware-blocked** (no arm64
GPU here to validate cross-vendor).

**Next steps (ranked, best speed-per-effort first):**
1. **§3 per-stage cache** (cache `film_density_cmy` so print/scan-only edits skip the two most
   expensive stages) — biggest interactive win, **bit-exact**, host-verifiable. Builds directly on
   `54d4d3d`. Needs a double-render correctness test like the one added this session.
2. **Filming-expose LUT for preview** (the print side is wired as `use_enlarger_lut`; extend the LUT
   technique to the filming expose, where the cost actually is) — preview-only, exact path stays the
   gate.
3. **On-device verification pass** — the zoom UX (overlay registration, sharpness, gesture feel) and
   the lifecycle fixes are unit/compile/CI verified but need a real device/display (this container
   has none). Un-gate/fix the `android-emulator` CI job while there.
4. **fp16 preview buffers** (`kernels/half` exists + tested) — ~1.5–2×, preview-only.
5. **GPU compute** — last; only after 1–4 and with real devices. Target a fused filming→print→scan
   per-pixel kernel, NOT the existing scan-only shader; grain/spatial stay on CPU.
6. **Still deferred** (from the audit): inert marshalled engine params (UV/IR filter, preflash,
   spectral blur — wire-or-strip decision + a new oracle golden); latent int32 `npix*3` overflow
   (>715 MP, unreachable via the app's 16384px export cap); build posture (`targetSdk 34`→35, lint
   baseline masks 26 dep warnings, emulator CI gate is manual-only).

## State (2026-06-02)
- **`main` is the trunk.** Current on `main`: **`versionCode 9` / `versionName 0.7.0`** (PR #59
- **`main` is the trunk.** Current on `main`: **`versionCode 9` / `versionName 0.7.0`** (PR #59
  merged). **`v0.7.0` is tagged + RELEASED** — `release.yml` built and published the **signed**
  `Spektrafilm-v0.7.0.apk` (21.5 MB) + `.sha256` to the GitHub Release (run success, `apksigner
  verify` passed). Host + on-device parity suites **bit-exact / green**; `:app:assembleDebug`
  packages `libspektra.so` (+ `libsfraw`/`libsftiff`/`libsfpng`, opt-in `gpu`/`ml` stubs) for the
  3 ABIs.
- Workflow: feature branch → PR into `main` → merge. **Merging a PR and self-merging is
  policy-gated** in this env (the harness blocks it as "exceeds granted intent") — needs explicit
  user go-ahead; releases (tag push) are allowed once the user asks.
- Build/test/parity commands + engine architecture are in **`CLAUDE.md`** (read first). Prime
  directive: **bit-exact parity with the spektrafilm oracle** for the default/export path
  (`-fno-finite-math-only`; `test_*` gates; thread-invariant). Commit with
  `-c commit.gpgsign=false`. Keep "Film modeling powered by spektrafilm". Never put the model
  identifier in committed artifacts.

## 🟢 Shipped this session (v0.7.0) — engine completion, device-verified
Working copy lives at **`C:\Filmcam123\Spectrafilmandroid`** on the Windows laptop (NOT
`C:\Spectrafilm`, which is docs-only). A real **Galaxy S25 Ultra (SM-S948W, Android 16, arm64)**
is connected via `adb` (device `R5GL13Z3S6L`).
- **AAssetManager direct-load** (PR #59) — engine reads profiles/LUT/filters straight from the
  APK; the ~17 MB first-run extraction to `filesDir` is skipped. New
  `spk_engine_create_asset_manager` + JNI `nativeCreateFromAssets` + Kotlin
  `SpektraEngine.fromAssets`; `MainActivity` tries it first, falls back to `extractAssets`. All
  AAsset code is `#ifdef __ANDROID__`-guarded (host build unchanged). Verified on-device: fresh
  launch → **no `files/spektra` extraction**, demo renders ~640 ms.
- **`use_enlarger_lut` wired** (PR #59) — opt-in enlarger 3D-LUT in `printing.cpp::print_expose`
  (PCHIP LUT of `_film_cmy_to_print_log_raw` over `[-grain.density_min, nanmax(film density
  curves)]`), mirror of the scanner LUT. Default-off → direct `exp10_vec` path byte-identical.
  Gated by **`test_enlarger_lut_e2e`** in CI `engine-parity`. **Last reserved engine LUT flag is
  gone.** Accel bands are looser than the scanner LUT (print-expose integral is less smooth):
  res 17 ≈1.1e-4, res 64 ≈1.9e-6 vs direct.
- Doc truth: presets **21** (Neutral preset documented), AUDIT refreshed.

### ⭐ On-device parity runner (the key technique — reuse this)
This Windows box has **no host g++/MSVC stdlib** (only MSVC-target clang, no libc++) → the
documented host C++ parity gate **can't run locally**. Instead, build the parity tests for
**arm64 with the NDK clang and run them on the connected device** — same goldens, real target
arch. This is what made engine changes verifiable here:
```bash
NDK=C:/Users/thete/AppData/Local/Android/Sdk/ndk/27.0.12077973
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
cd engine/spektra-core/src/main/cpp
SRC="spektra.cpp kernels/*.cpp io/*.cpp model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp"
"$CC" --target=aarch64-linux-android24 -std=c++17 -O2 -I. -I../../../../../tools/parity \
  -DSPK_TEST_DIR='"/data/local/tmp/spk/tests"' tests/test_simulate_e2e.cpp $SRC -landroid \
  -o /c/Filmcam123/spk_arm64/test_e2e         # -landroid REQUIRED (AAsset/etc)
# push test + libc++_shared.so + assets/spektra + tools/parity/goldens + tests/*.f64 to
# /data/local/tmp/spk, then:
adb shell "cd /data/local/tmp/spk && LD_LIBRARY_PATH=. ./test_e2e assets/spektra \
  goldens/scan_portra tests/scan_portra_input_rgb.f64 goldens"   # expect: ALL PASS
```
Result this session: `test_simulate_e2e` + `test_enlarger_lut_e2e` **ALL PASS** on device
(max_abs 5.96e-08, identical to host figures). MSYS mangles `/sdcard`/`/data` paths → use
`export MSYS_NO_PATHCONV=1` (or the PowerShell tool) for `adb push/shell` with abs device paths.

### Build env (this laptop)
- `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` (JDK 21); SDK at
  `C:\Users\thete\AppData\Local\Android\Sdk`; NDK r27 / CMake 3.22.1 / build-tools 35 all present.
  `local.properties` (gitignored) has `sdk.dir`. `:app:assembleDebug`, `:app:lintDebug`,
  `:app:testDebugUnitTest` (30/30) all green here.
- **spektrafilm oracle** runs under **Python 3.13** (`C:\Users\thete\AppData\Local\Programs\
  Python\Python313`) via venv `C:\Filmcam123\spkenv` + PYTHONPATH to `C:\Filmcam123\spektrafilm-main\src`,
  with `C:\Filmcam123\spkstubs\sitecustomize.py` mocking the heavy IO deps (exiv2/rawpy/
  OpenImageIO/lensfunpy/pyfftw). Needed only to regenerate goldens; enlarger LUT did NOT need a
  new golden (it reuses `print_portra` within the accel band).

## 🟢 Just resolved — RAW export OOM (merged, device-confirmed)
- **RAW import/export OOM on a real Galaxy S25 (Android 16) — FIXED in PR #56 (merged), confirmed
  on-device.** Symptom (every build through v0.6.2): `OutOfMemoryError "Failed to allocate a
  149817619 byte allocation … growth limit 268435456"` on loading/exporting a DNG.
- **Root cause (two parts):** `149,817,619 ≈ 4080×3060×3×4` = the **full-res decoded linear float
  buffer** (~140 MB). On Android `ByteBuffer.allocateDirect` is a **non-movable `byte[]` on the
  ART managed heap** (256 MB growth limit) — NOT native memory — so the full-res RAW input plus
  the engine's equally large output buffer can't coexist there. The earlier `maxLongEdge` cap fixed
  only the **preview** path; export is uncapped by design and still OOMed.
- **Fix (PR #56):** learned from Lightroom (RE'd: zero `NewDirectByteBuffer`/`allocateDirect` in its
  native libs — all full-res pixels live in oneTBB `scalable_malloc`/`mmap` native memory behind a
  handle, only a compressed JPEG crosses to Java). We now allocate the RAW result
  (`raw_decoder_jni.cpp`) and engine output (`spektra_jni.cpp`) with **`malloc` + `NewDirectByteBuffer`**
  (true off-heap), freed explicitly; `LinearImage`/`SimResult` are `AutoCloseable`; export-scale
  buffers are closed by the caller while proxy/preview buffers stay managed/GC'd. Also folded #54
  (rethrow `RawDecodeException` → platform decoder; direct-buffer file fallback). Engine C++ math
  untouched → parity unaffected. Detail in `docs/RESEARCH_BIG_FILES.md`.
- **Device confirm (v0.6.3 (8), SM-S948W / Android 16):** export logged
  `sfraw: decoded 3060x4080 (halfSize=0) -> 3060x4080 (step=1)` twice with **no `149817619`, no
  OOM, no crash** — app stayed responsive.
- **Test DNG:** the user's `raw_test.bin` (~24.9 MB, 4080×3060 Bayer, from Google Drive). A decoded
  linear-ACES copy was at `/tmp/dng_lin_aces.f32` — `/tmp` does NOT persist across sessions;
  re-download from the user if needed.
- **PR housekeeping done:** #56 merged; #55 (Neutral preset) merged; #57 (this handoff's prior
  refresh) merged; #53/#54 closed (absorbed into #56). No RAW-OOM PRs remain open.

## What landed since v0.4.0 (merged to `main`)
- **Lightroom-RE feature wave** (from `docs/IMPROVEMENT_BACKLOG.md`, RE'd off `com.adobe.lrmobile`):
  preset **amount** slider (#35), **copy/paste settings** (#36), granular **resets** crop+grain
  (#39/#40), and the **tone-curve** stage — engine (#41, parity-gated, default identity) + JNI/
  Kotlin/presets plumbing (#42). Before/after `CompareSlider` already existed.
- **MotionCam `.mcraw`** RE: `docs/RESEARCH_MCRAW.md`, pure-Kotlin `McrawContainer` parser (#37),
  picker detection (#38).
- **Performance (toward Lightroom)**: `docs/PERF_ROADMAP.md` (#46, measured; bottleneck = the
  filming+print **expose** 81-band integrals, not the scan); preview-LUT fast-path (#47); and
  **scaffolding, all opt-in / default-off so parity holds**: Vulkan compute fast-path + a real
  SPIR-V port of the spectral scan integral (#48/#52), fp16 NEON conversion (#49), oneTBB backend +
  LiteRT ML stub (#50). **GPU speedup is UNPROVEN** — needs a physical arm64 GPU device (sandbox has
  no KVM/GPU; the x86 emulator is the wrong ISA and mis-traps our `-O2` vectorized copy).
- **Big-file RAW** (the OOM saga): fd decode (#43), half-size proxy (#44) covered the LibRaw
  *success/preview* path; the native `maxLongEdge` cap (#56) fixed preview; **off-heap `malloc` +
  `NewDirectByteBuffer` for the full-res RAW input + engine output (#56)** is the real export fix
  (device-confirmed) — the Lightroom-style "full-res pixels never on the Java heap" model.
- **`Neutral (Adobe-like)` preset** + `docs/RESEARCH_LIGHTROOM_RENDER.md` (#55).
- `CLAUDE.md` (#34); v0.5.0–0.6.3 release bumps.

## RE assets / where the analysis lives
- `docs/RESEARCH_LIGHTROOM_STACK.md` — LR stack (C++ "CR" engine + oneTBB + Vulkan/OpenCL/Metal +
  LiteRT + fp16, under a Kotlin/Java UI) vs ours, ranked "what to copy".
- `docs/RESEARCH_LIGHTROOM_RENDER.md` — LR default render (Adobe Color DCP + ProcessVersion +
  ToneCurvePV2012) vs ours on the test DNG; deltas + the `Neutral (Adobe-like)` preset rationale.
- `docs/RESEARCH_BIG_FILES.md`, `docs/IMPROVEMENT_BACKLOG.md`, `docs/AUDIT.md`.
- The Lightroom APK was decompiled in-env to `/tmp/lrx` (ephemeral) via the android-reverse-
  engineering skill; `aapt2` + `libLrAndroid.so` `strings`/`nm` were the evidence source.

## Known engine/app gaps (authoritative list: `docs/AUDIT.md`, updated 2026-06-02)
- ✅ **AAssetManager path wired** (PR #59, v0.7.0) — see "Shipped this session" above. No longer a gap.
- ✅ **`use_enlarger_lut` wired** (PR #59, v0.7.0) — opt-in, default-off, parity-gated. No reserved
  engine LUT flag remains.
- **Issue #7 — full-res RAW tiling/GPU still Open**: mitigations now = OOM ladder, fd + half-size
  proxy, `maxLongEdge` preview cap, and **off-heap native buffers for full-res export (#56)** —
  the export OOM is resolved for typical phone DNGs. Still no native **tiling/streaming**, so a
  *pathologically* large DNG (single buffer > native headroom) is unbounded; tiling is the
  remaining work. The fd-failure file fallback uses `allocateDirect` (still managed) → a possible
  off-heap follow-up.
- **Glare-on-print** wired but default-off (stochastic → not bit-exact); **enlarger lens blur**
  and **`upscale_factor<1` AA prefilter** intentionally unwired (no oracle call site).
- **No Kotlin/JVM instrumented (`androidTest`) UI tests** beyond the host C++ parity + **30 JVM
  unit tests** (6 suites, green). Robolectric/instrumented coverage of `ImagePipeline`/
  `EngineHelpers`/`RawDecoder` marshaling is still absent.
- Doc upkeep: `docs/PRESETS.md` fixed to **21**; `docs/DEVICE_TEST_REPORT.md` is the v0.4.0 device
  pass (historical — the v0.7.0 re-validation is recorded in `docs/AUDIT.md`). `docs/ROADMAP.md` /
  `docs/RELEASE_CHECKLIST.md` (maintainer-only) hold milestone + publish state.

## Doc map (what to read for what)
`CLAUDE.md` build/parity/arch · `docs/AUDIT.md` open items · `docs/IMPROVEMENT_BACKLOG.md` Lightroom
RE'd feature list · `docs/PERF_ROADMAP.md` perf plan+policy · `docs/RESEARCH_*` the RE studies
(stack, render, big-files, mcraw, film-character, lens-bokeh) · `docs/PRESETS.md`/`FILM_STOCKS.md`
content · `docs/maps/` source-project maps.

## Next steps
1. **Performance (the headline ask).** Bottleneck = the per-pixel 81-band **expose** integrals
   (filming + print), not the scan. Ranked levers (`docs/PERF_ROADMAP.md`):
   - **GPU (Vulkan) compute** port of expose/print/scan — 10–50×, the only path to Lightroom-class
     speed. Scaffolding exists (opt-in, default-off, **UNPROVEN**). Needs on-device arm64
     profiling/validation on the S25's Adreno; hold preview to a *visual* tolerance, keep export on
     CPU. **Policy: approximate proxy / exact export.**
   - Cheap preview-only wins: turn **`use_enlarger_lut` + `use_scanner_lut` ON for preview** (~3–8×
     on the expose hotspot, now both wired); **fp16** intermediate buffers (~1.5–2×, scaffolding
     exists).
   - "Feels faster", bit-exact: **pause/refresh render on gesture**, **per-stage caches**
     (re-run only the changed stage), **progressive pyramid**, **embedded-JPEG instant preview**
     (LibRaw `unpack_thumb`).
2. **Issue #7 — native RAW tiling/streaming** for *pathologically* large DNGs (export OOM resolved
   for typical phone DNGs via off-heap buffers; a single buffer > native headroom is still
   unbounded). Smaller follow-up: make the fd-failure file fallback off-heap (currently
   `allocateDirect` = managed).
3. **Robolectric/instrumented tests** for the app layer (`ImagePipeline` export quantisation,
   sidecar persistence, `RawDecoder`/`PngWriter` JNI marshaling) — only JVM unit tests exist today.
4. Lower priority: minor by-design items (glare-on-print stochastic; enlarger lens blur / `upscale
   <1` AA prefilter — no oracle call site).
