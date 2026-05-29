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

## M1 — Runnable app  ✅ (v0.1.0)
Shipped as a **standalone Compose app** wrapping the engine (pivot from vendoring ImageToolbox —
see `DECISION.md`). Android SDK + NDK build verified; `:app:assembleDebug` produces a 23 MB APK
with `libspektra.so` for all ABIs + bundled assets; CI assembles it on every push.
- **Done:** app builds + installs; profile pickers, scan/print toggle, exposure, live render.
- Follow-up: richer editing UI + optional ImageToolbox host integration (engine module is ready).

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
- **Landed (2026-05-29):** RAW white-balance UI (Temperature/Tint sliders + WB-mode dropdown
  + reset-to-as-shot) — see progress note below. `scan_film` path itself was already
  bit-exact in v0.1.0; remaining M3 small items are AAssetManager path + non-sRGB wiring.

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
>
> **M3 backlog item landed (2026-05-29):** **RAW white-balance UI** — Temperature/Tint sliders
> + a WB-mode dropdown (as-shot / daylight / tungsten / custom) + reset-to-as-shot, shown only
> for RAW/DNG sources. Wired to the existing LibRaw decoder: changing the mode or sliders
> re-decodes the preview automatically. Default (as-shot) behaviour is unchanged; parity
> preserved. (Issue #6 note: the `rawTemperature`/`rawTint` fields were already in `RawDecoder`
> — this completes the UI surface for that path.)

## M4 — Full negative→print→scan + look effects  🚧 in progress
Add printing stage, DIR couplers, grain, halation/scatter, glare, diffusion filters.
- Golden vectors green for print density + final RGB across ≥3 film/paper pairs.
- **Done when:** full pipeline matches spektrafilm baselines; grain visible on upscaled crops.
- **Landed (2026-05-29, M-geometry / issue #6 partial):** crop/resize geometry stage ported
  (bit-exact). Remaining gated stages for #6: diffusion filters, lens blur, auto-exposure,
  LUT acceleration — see progress note below.

> **Progress:** the **printing stage** (enlarger spectral calc + dichroic Y/M/C filters +
> print expose/develop) and the full **negative→print→scan route** are ported and **bit-exact**
> vs the `print_portra` goldens: `print_density_cmy` max_abs ≈ 2.4e-7, `final_rgb` ≈ 4.2e-7;
> end-to-end `spk_simulate(scan_film=false)` ≈ 5.6e-7. DIR couplers already done (M3).
> **Known limitation:** the per-(film,paper) **neutral dichroic CC values** and the **midgray
> exposure factor** are currently *baked* from the oracle for the portra & ektar pairs (other
> pairs return an error). Generalizing requires a native digest of `neutral_print_filters.json`
> + the filming-midgray balance — the next M4 task. Then: grain (Poisson-binomial), halation/
> scatter + diffusion filters (spatial branches), and stochastic/spatial golden cases.
>
> **M4/M-geometry item landed (2026-05-29) — partial fix for issue #6:** **crop/resize geometry
> stage ported** (bit-exact). The previously-inert `IOParams` crop fields (`crop`,
> `crop_center`, `crop_size`) and the cubic `upscale_factor` are now a live pre-process stage
> running before filming in both the scan and print routes, matching spektrafilm's
> `_preprocess` step (`runtime/services/resize.py` + `utils/crop_resize.py`). Defaults remain
> a strict no-op; parity on all 11 existing goldens is byte-identical. A new
> `scan_portra_crop` golden gates the non-default path (max_abs ≈ 2e-7). Host suite: 12/12
> PASS.
>
> **Remaining gated stages for #6** (params still shown as "not yet active" in UI):
> diffusion filters, lens blur, auto-exposure, and LUT acceleration.
> Downscale (`upscale_factor < 1`) anti-aliasing prefilter is a documented follow-up.

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

## M7 — Final polish & presentation (requested)
The "do this when literally everything else is done" list:
- ✅ **Welcome / onboarding screen** — shipped in v0.2.0. A multi-page intro covering the
  project story, how the app works, and a guided tour. The last page links to Settings and the
  "Report an issue" GitHub shortcut.
- ✅ **Settings page** — shipped in v0.2.0. Default output color space, preview resolution,
  default film/print, export format/quality + save location, theme (light/dark/dynamic), reset,
  and a "Report an issue / Flag on GitHub" entry opening
  https://github.com/thetechgeekko/Spectrafilmandroid/issues/new.
- ✅ **In-app About section** — shipped in v0.2.0. Credits (spektrafilm/ImageToolbox/
  colour-science/LibRaw), the pixls.us dedication, author links (Akshay — Instagram/YouTube),
  version, license, and source link.
- **GitHub repo "About"** — repository description, topics, and homepage must be set manually
  at https://github.com/thetechgeekko/Spectrafilmandroid/ by the maintainer (the env cannot
  push via the API). Ready-to-paste text is in `docs/RELEASE_CHECKLIST.md`.
- **Remaining (M7/polish):** port the gated engine stages so those params go live (diffusion
  filters, lens blur, auto-exposure — see issue #6); signed release key (currently debug-signed
  fallback; see `docs/RELEASE_CHECKLIST.md`).

## Cross-cutting
- CI: build all ABIs; run golden-vector parity tests; lint.
  → Implemented in `.github/workflows/ci.yml` (see `.github/workflows/README.md`): the
  `engine-native`, `parity`, and `python-lint` jobs run today; the `android` job auto-activates
  when the host is seeded at M1 (guarded on `settings.gradle.kts`).
- Each engine PR cites which golden vectors it turned green.
