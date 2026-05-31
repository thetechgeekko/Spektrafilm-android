# Decision Record — How we port Spektrafilm to Android

Status: **Accepted** (milestone 0). Author: project lead. Date: 2026-05-29.

## Context

We must deliver "spektrafilm, but on Android" with the ability to **open and edit RAW DNG
files**. We have three repos: the GPLv3 Python engine `spektrafilm`, the Apache-2.0 Android
editor `ImageToolbox`, and this (initially empty) target repo `Spectrafilmandroid`.

Two non-obvious facts drove the decision, both verified against the source:

1. **spektrafilm's RAW reader is `rawpy`, and `rawpy` is a binding to LibRaw.** So "RAW/DNG
   editing like spektrafilm" reduces to "compile LibRaw for Android and call it with the same
   options" — exact parity is achievable. (`DngCreator`, Android's built-in DNG API, only
   *writes* DNG; it does not decode arbitrary RAW, so it is not our decode path.)
2. **spektrafilm's engine is ~6,000 LOC of numerical code** (NumPy / SciPy / Numba), with the
   hot paths (Gaussian/exponential filters, 1D/3D LUT interpolation, Poisson-binomial grain)
   already written as Numba-JIT kernels — i.e. they are already "C-shaped" and port cleanly
   to C++.

## Options considered

### Option A — Fork ImageToolbox as host + native C++ engine  ✅ CHOSEN
Take ImageToolbox as the application shell. Add:
- `engine/spektra-core` — **C++ (NDK)** port of spektrafilm `runtime` + `model`, with a thin
  JNI/Kotlin facade mirroring `create_params` / `simulate`.
- `lib:libraw` — **NDK** module wrapping LibRaw for full-res RAW/DNG → linear RGB.
- `feature:film-emulation` — Compose screen wiring the engine into ImageToolbox navigation.
- Ship the 28 profiles + LUT binaries as assets.

**Why:** ImageToolbox already solves everything that is *not* film science — SAF file access,
gallery/media picker, Coil decode pipeline, 16-bit handling, EXIF, export with metadata,
zoomable canvas, gesture handling, Material3 Compose UI, settings, crash reporting. Its NDK/ABI
build is already configured (`armeabi-v7a, arm64-v8a, x86_64`) and it already ships native
`.so` codec libs, so adding two more native modules is idiomatic. C++ for the engine gives us
(a) performance for per-pixel spectral integration over the 81-band spectral shape, (b) co-location with
LibRaw (also C++), and (c) a near-mechanical translation of the existing Numba kernels.

**Cost:** Large host codebase to carry; must relicense the combined work to GPLv3 (acceptable).

### Option B — Greenfield Compose app + pure-Kotlin engine
Build a minimal app from scratch; reimplement the engine in Kotlin.

**Rejected because:** We would reinvent RAW handling, 16-bit image I/O, export, gallery, and
UI that ImageToolbox already provides and battle-tested. Pure-Kotlin numerics for 81-band
spectral integration would be slow and would still need NDK for LibRaw anyway — so we pay the
JNI cost regardless. Loses the explicit "maybe use ImageToolbox" steer from the brief.

### Option C — Implement film look as a single ImageToolbox "filter"
Add film emulation as one more entry in the 295-filter framework (`@FilterInject`).

**Rejected as the primary architecture:** spektrafilm is a *multi-stage scene-referred
pipeline* (RGB → spectral → negative density → enlarger → print density → scan), not a single
LUT/bitmap transform. The filter framework operates on display-referred `Bitmap`s; forcing the
pipeline into it would lose the linear/spectral intermediates that make the look correct.
However, we *will* reuse the filter framework for finishing touches, and we may expose a
"bake to 3D LUT" export so the look is also usable as a cheap filter elsewhere.

## Decision

Adopt **Option A**. Engine in C++/NDK, host = forked ImageToolbox, RAW via LibRaw/NDK, UI via a
new Compose feature module. Combined license = **GPLv3**.

## Update (v0.1.0): shipped as a standalone app

The engine port came in bit-exact and ahead of expectations, so v0.1.0 ships as a **buildable
standalone Compose app** (`app` + `engine:spektra-core`) rather than waiting on a full
ImageToolbox vendor. The native engine module is self-contained and **still drops into
ImageToolbox unchanged** when we want the richer host — this just made a real, installable APK
possible now (Android SDK + NDK build verified; CI assembles the APK). Option A's reasoning
stands; only the *host shell* was deferred.

## Consequences

- The next milestone (M1) seeds this repo with the ImageToolbox tree (the host) and wires the
  empty `spektra-core` and `libraw` Gradle modules — see `tools/bootstrap.md`.
- The engine is ported stage-by-stage with a Python↔C++ regression harness driven by
  spektrafilm's existing `.npz` baselines (`tests/baselines/`) so we can prove numerical parity.
- APK grows by ~17 MB of assets (profiles + LUTs) plus LibRaw `.so` per ABI.

---

# Decision Record — NEON SIMD on the spectral-integration loops (M6)

Status: **Deferred / declined** (M6). Date: 2026-05-31. Supersedes the ROADMAP's
"native SIMD (NEON)" bullet as a near-term item.

## Context

After the M6 threading change (per-pixel fork-join, ~3.2× on a 12 MP scan, bit-exact), the
next proposed M6 perf item was hand-vectorising the spectral-integration loops with ARM NEON,
"stacking on the threading for another big export win, while staying bit-exact." We profiled
the loops before committing to it.

## What the measurements said (12 MP scan, 1 thread, host x86_64)

1. **`pow(10, −spectral)` dominates the `scan()` band loop: ~79%** of its time (microbench:
   1908 ms → 401 ms when `pow` is removed). The X/Y/Z spectral integral is the heavy stage
   (~half the scan-route runtime); `expose()` (RGB→spectrum upsampling) is the other half.
2. A **byte-exact** SIMD must keep `std::pow` as scalar libm (no vectorised `exp10` reproduces
   libm `pow` bit-for-bit), so it can only touch the ~21% of madd/accumulate work — and the
   X/Y/Z accumulation is a cross-band reduction whose order must be preserved. Net byte-exact
   ceiling on that loop is **single-digit %**, easily lost in restructure overhead.
3. `expose()` is `pow`-free but bottlenecked on **per-pixel, data-dependent gathers** (bicubic
   `tc_lut` lookups at per-pixel coordinates), which portable SIMD / NEON cannot vectorise
   bit-exactly (no efficient portable gather; indices differ per lane).
4. A vectorised `exp10` *can* reach the rest, but a prototype was **1.6e-7 relative error**
   (≈ float32 epsilon — within the 1e-4 parity tolerance but **not** byte-identical to libm)
   and, naively, **0.6× — slower than scalar `pow`**. **NEON has only 2-wide float64**, so the
   on-device ceiling is 2× on a fraction of a fraction of the runtime.
5. **No ARM build/run capability** in the dev environment or CI (both x86_64), so any NEON
   intrinsics — or even portable vector code's NEON lowering — cannot be *perf*-validated on
   the target architecture from here.
6. The engine already ships an **opt-in scanner 3D-LUT** (`use_scanner_lut`, default OFF,
   ~5e-5) that accelerates exactly this density→log_xyz integral. The established project
   philosophy is therefore **bit-exact by default; approximate acceleration is opt-in**.

## Decision

**Do not pursue NEON SIMD on these loops now.** "Big win **and** byte-exact" is not achievable
here: the byte-exact gain is marginal (pow-dominated, NEON 2-wide f64, gather-bound `expose`),
and the only path to a large gain (a vectorised `exp10`) sacrifices byte-identity for a
~1e-7 drift and an on-device speedup that cannot be validated from this environment. The
threading change already captured the available bit-exact parallelism.

## Consequences / if revisited

- A vectorised-`exp10` fast-path, if ever wanted, should follow the scanner-LUT precedent:
  **opt-in, default-OFF**, accuracy-bounded, and **perf-validated on a real ARM device** before
  being recommended — not a silent change to the default numerical path.
- Higher-value remaining M6 work is unblocked instead: downscale anti-aliasing prefilter
  (small, self-contained, bit-exact), profile-catalog UI, APK-size review, and (longer-horizon)
  the optional GPU **preview** accelerator validated to a *visual* tolerance (never the parity
  gate). Memory tiling for very large RAW remains the open half of old issue #7.
