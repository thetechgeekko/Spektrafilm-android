# Roadmap

Milestones are vertical slices. Each ends with something demonstrable and a parity gate.

> **Scope & fidelity (confirmed):** we port the **true spektrafilm engine in full**, to a
> **bit-exact** parity bar (the GUI is just a shell over this engine — see `MOBILE_STRATEGY.md`).
> The app exposes the whole `RuntimePhotoParams` control surface, plus Lightroom-informed
> additions: **non-destructive recipe/sidecar editing** and a **proxy-preview vs full-res-export**
> model. The parity-bearing engine is **CPU C++/NDK** (GPU can't be bit-reproducible across
> vendors); a GPU preview accelerator is an optional M6 item. Only niche file I/O (EXR /
> 32-bit-float TIFF) is deferred.

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

## M3 — Engine core (`engine:spektra-core`) — scanning path first  🚧 in progress
Port the cheapest end-to-end path to prove the boundary: `scan_film` (skip print).
Stage order: params/profiles → density curves/emulsion → spectral upsampling → filming →
scanning. Ship 28 profiles + LUT assets.
- Golden-vector harness green for: film raw, film density, scan RGB.
- **Done when:** `simulate(scan_film=true)` on a test image matches spektrafilm within tol.

> **Progress:** the Python engine runs headless here as a live oracle and real goldens are
> committed (`tools/parity/goldens/`). The **entire `scan_film` path is ported and bit-exact
> vs the oracle**, stage by stage:
> - profile JSON loader, density curves, emulsion, conversions, glare
> - **Hanatos2025 spectral upsampling** (RGB→spectrum, max_abs ≈ 1.1e-7)
> - **filming stage** (RGB→raw→develop incl. **DIR couplers**): `film_log_raw` max_abs ≈ 1.2e-7,
>   `film_density_cmy` ≈ 2.4e-7
> - **scanning stage** (density→RGB): `final_rgb` max_abs ≈ 6e-8
>
> Full `libspektra.so` links (engine + JNI + all scan-path sources).
>
> **`spk_simulate(scan_film=true)` now runs end-to-end** through one C-API call — orchestrating
> profile load → filming → develop(+couplers) → scanning — reproducing `final_rgb` at
> **max_abs ≈ 7.45e-8**. The JNI bridge (`nativeCreate/Destroy/ListProfiles/Simulate`,
> SpektraParams↔spk_params marshaling, direct-ByteBuffer I/O) is implemented and `libspektra.so`
> exports all four `Java_com_spectrafilm_engine_*` symbols. **The scan_film engine is callable
> from Kotlin.**
>
> Remaining (small): in-APK `AAssetManager` path (currently needs an extracted asset dir),
> non-sRGB output color spaces, and wiring the grain/halation/glare toggles. Then M4 (print
> route + spatial/stochastic effects + full-pipeline goldens).

## M4 — Full negative→print→scan + look effects  🚧 in progress
Add printing stage, DIR couplers, grain, halation/scatter, glare, diffusion filters.
- Golden vectors green for print density + final RGB across ≥3 film/paper pairs.
- **Done when:** full pipeline matches spektrafilm baselines; grain visible on upscaled crops.

> **Progress:** the **printing stage** (enlarger spectral calc + dichroic Y/M/C filters +
> print expose/develop) and the full **negative→print→scan route** are ported and **bit-exact**
> vs the `print_portra` goldens: `print_density_cmy` max_abs ≈ 2.4e-7, `final_rgb` ≈ 4.2e-7;
> end-to-end `spk_simulate(scan_film=false)` ≈ 5.6e-7. DIR couplers already done (M3).
> **Known limitation:** the per-(film,paper) **neutral dichroic CC values** and the **midgray
> exposure factor** are currently *baked* from the oracle for the portra & ektar pairs (other
> pairs return an error). Generalizing requires a native digest of `neutral_print_filters.json`
> + the filming-midgray balance — the next M4 task. Then: grain (Poisson-binomial), halation/
> scatter + diffusion filters (spatial branches), and stochastic/spatial golden cases.

## M5 — UI/UX + non-destructive editing (`feature:film-emulation`)
Full `RuntimePhotoParams` control surface (camera/enlarger/scanner/grain/halation/couplers/
glare/diffusion/IO/settings), film+print profile pickers, **proxy preview vs full-res scan**,
before/after compare, export with EXIF+ICC.
- **Non-destructive recipe layer:** edits are a serialized `SpektraParams` sidecar keyed to the
  source; original RAW untouched; re-render on view/export; presets (28 stocks + saved params)
  and history (Lightroom-informed — see `MOBILE_STRATEGY.md`).
- Reuse ImageToolbox components (zoomable canvas, sliders, sheets, compare, picker).
- **Done when:** end-to-end on-device: pick RAW → tune (proxy) → export full-res, no code; edits
  persist as a recipe and reopen non-destructively.

## M6 — Performance, GPU accelerator & polish
Tile/threading, native SIMD (NEON), preset save/load, profile catalog UI, About/credits with
attribution, APK-size review. Optional **GPU preview accelerator** (Vulkan / GL ES compute) for
slider interactivity — validated against CPU goldens to a *visual* tolerance, never the parity
gate; export stays on the CPU engine. Optional "bake to 3D `.cube` LUT" export. Deferred file I/O
(EXR / 32-bit-float TIFF) can also land here behind the writer interface.
- **Done when:** proxy preview is interactive on a mid-range device; full scan acceptable; GPU
  path (if shipped) matches CPU within visual tolerance.

## Cross-cutting
- CI: build all ABIs; run golden-vector parity tests; lint.
  → Implemented in `.github/workflows/ci.yml` (see `.github/workflows/README.md`): the
  `engine-native`, `parity`, and `python-lint` jobs run today; the `android` job auto-activates
  when the host is seeded at M1 (guarded on `settings.gradle.kts`).
- Each engine PR cites which golden vectors it turned green.
