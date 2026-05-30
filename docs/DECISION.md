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
