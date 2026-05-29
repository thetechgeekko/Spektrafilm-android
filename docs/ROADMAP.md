# Roadmap

Milestones are vertical slices. Each ends with something demonstrable and a parity gate.

> **Progress note (2026-05-29):** beyond M0, a parallel scaffolding wave has landed real,
> compiling code ahead of the host bootstrap: the C++ engine's pure-math + kernel layer
> (`engine/spektra-core/src/main/cpp/{model,kernels}`: Gaussian/interp kernels, density curves,
> emulsion, conversions, glare, and the **corrected 81-sample 380–780@5 spectral grid + CIE
> CMFs** — a grid bug from the initial map was caught and fixed here), the `lib:libraw` decode
> module (M2), the `feature:film-emulation` Compose UI (M5), and the `tools/parity` golden-vector
> harness (the M3/M4 gate). These compile/are structurally complete in isolation; they wire
> together and build as an app at **M1** (host bootstrap).

## M0 — Foundation  ✅ (this commit)
Architecture decided, both repos mapped, port plan written, RAW/licensing strategy fixed,
engine API contract drafted. No build yet.
- **Done when:** docs + engine contract reviewed and merged.

## M1 — Host bootstrap
Seed the repo with the ImageToolbox tree (the host) and wire empty Gradle modules.
- Fork ImageToolbox source into the repo (`tools/bootstrap.md`).
- Add `engine:spektra-core`, `lib:libraw`, `feature:film-emulation` to `settings.gradle.kts`
  with minimal `build.gradle.kts` (convention plugins) — modules compile empty.
- Register a `FilmEmulation` screen stub in `Screen.kt`; it appears on the home grid and opens.
- **Done when:** app builds + installs; the (empty) SpectraFilm screen opens.

## M2 — RAW/DNG decode (`lib:libraw`)
LibRaw compiled for all ABIs; JNI decode of RAW/DNG → linear 16-bit RGB with rawpy-parity
options; WB modes (as-shot/daylight/tungsten/custom).
- Decode a DNG and a CR2/NEF to a linear buffer; verify against rawpy output on the same file.
- Optional: register `RawDecoder` in Coil for full-res gallery open.
- **Done when:** a DNG opens in-app at full resolution; linear values match rawpy within tol.

## M3 — Engine core (`engine:spektra-core`) — scanning path first
Port the cheapest end-to-end path to prove the boundary: `scan_film` (skip print).
Stage order: params/profiles → density curves/emulsion → spectral upsampling → filming →
scanning. Ship 28 profiles + LUT assets.
- Golden-vector harness green for: film raw, film density, scan RGB.
- **Done when:** `simulate(scan_film=true)` on a test image matches spektrafilm within tol.

## M4 — Full negative→print→scan + look effects
Add printing stage, DIR couplers, grain, halation/scatter, glare, diffusion filters.
- Golden vectors green for print density + final RGB across ≥3 film/paper pairs.
- **Done when:** full pipeline matches spektrafilm baselines; grain visible on upscaled crops.

## M5 — UI/UX (`feature:film-emulation`)
Film/print profile pickers, parameter sliders grouped (camera/enlarger/scanner/grain/halation),
fast preview vs full scan, before/after compare, export with EXIF+ICC.
- Reuse ImageToolbox components (zoomable canvas, sliders, sheets, compare, picker).
- **Done when:** end-to-end on-device: pick RAW → tune → export, no code.

## M6 — Performance & polish
Tile/threading, native SIMD, optional LUT bake-to-`.cube` export, preset save/load, profile
catalog UI, About/credits with attribution. APK size review.
- **Done when:** ~6 MP preview interactive on mid-range device; full scan acceptable.

## Cross-cutting
- CI: build all ABIs; run golden-vector parity tests; lint.
- Each engine PR cites which golden vectors it turned green.
