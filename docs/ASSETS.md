# Bundled assets

All ship under `engine/spektra-core/src/main/assets/spektra/`, sourced from spektrafilm's
`src/spektrafilm/data/` (GPLv3). Total ≈ **13 MB** (the dominant items are the 5.7 MB spectral LUT
and the 28 profile JSONs; the 4.1 MB coefficient `.lut` is **not** bundled — see below).

## Film & paper profiles (`profiles/`, 28 JSON files)

Each profile is `{ "metadata": ..., "info": ..., "data": ... }` containing spectral log
sensitivities, log-exposure axis, characteristic density curves (and per-sublayer curves),
dye absorption spectra, base/min densities, and reference/viewing illuminants.

**Negative films (color):** kodak_portra_160, kodak_portra_400, kodak_portra_800,
kodak_portra_800_push1, kodak_portra_800_push2, kodak_ektar_100, kodak_gold_200,
kodak_ultramax_400, fujifilm_c200, fujifilm_pro_400h, fujifilm_xtra_400.

**Motion-picture / specialty:** kodak_vision3_50d, kodak_vision3_250d, kodak_vision3_200t,
kodak_vision3_500t, kodak_verita_200d, kodak_2383 (print film), kodak_2393 (print film).

**Slide / reversal:** kodak_ektachrome_100, kodak_kodachrome_64, fujifilm_provia_100f,
fujifilm_velvia_100.

**Print papers:** kodak_portra_endura, kodak_supra_endura, kodak_ultra_endura,
kodak_endura_premier, kodak_ektacolor_edge, fujifilm_crystal_archive_typeii.

### Viewing-illuminant contract

Each profile's `info.viewing_illuminant` is an exact, case-sensitive runtime
identifier, not descriptive catalog text. Most bundled profiles declare `D50`;
`kodak_2383` and `kodak_2393` declare `K75P`, the Kinoton 75P cinema-projector
light source. The native profile loader resolves that identifier once through a
fail-closed registry. The registry owns the aligned 81-sample spectrum, XYZ
normalization, and per-output-space chromatic-adaptation matrices; an unknown or
misspelled identifier rejects the profile instead of silently falling back to D50.

The same resolved record is used by the direct CPU scanner, the scanner 3D-LUT
and its memo key, Vulkan fused/linear routes, viewing glare, and scanner
black/white reference measurement. The K75P spectrum and matrices are compiled
color-science constants; the profile JSON remains the source of which registered
illuminant a stock selects. Committed `print_kodak_2383_k75p` and
`print_kodak_2393_k75p` goldens lock both selections against the upstream oracle:
direct and LUT17 final RGB, isolated scans in all six output spaces, plus hashed
visual evidence under `tools/parity/goldens/viewing_illuminants/`. Malformed or
unknown identifiers surface as `SPK_ERR_PROFILE_INVALID` with caller-thread-local
detail (and as the same detail in the JNI exception); missing profile files remain
the distinct `SPK_ERR_PROFILE_NOT_FOUND` case.

## Spectral-upsampling LUTs (`luts/spectral_upsampling/`, ≈ 5.7 MB bundled)

| File | Size | Bundled? | Purpose |
|------|------|----------|---------|
| `irradiance_xy_tc.npy` | 5.7 MB | **yes** | Triangular-coordinate irradiance-spectra LUT (Hanatos2025 RGB→spectral). Loaded at runtime by the engine. |
| `hanatos_irradiance_xy_coeffs_250304.lut` | 4.1 MB | **no** (build/test input) | Polynomial coefficient LUT (deg-4 2D) the `.npy` is precomputed from. |

`.npy` is NumPy's array format (header + raw little-endian floats); the C++ loader parses the
header and mmaps the payload. The `.lut` binary is parsed per spektrafilm's
`utils/spectral_upsampling.py` struct layout.

> **Note (APK size):** the runtime only loads the precomputed `irradiance_xy_tc.npy`
> (`spk_engine_create` → `eng->spectra()`); it never reads the coefficient `.lut`. The `.lut`
> is therefore **not bundled in the APK** — it lives in the spektrafilm source tree
> (`spektrafilm/src/spektrafilm/data/luts/spectral_upsampling/`); nothing in this repo reads it
> (the local-only host test that once loaded it was removed on 2026-09-14). Dropping it from `assets/` saves ~4.1 MB in the APK. (As of v0.7.0 the engine reads bundled
> assets **directly from the APK** via `AAssetManager` — there is no longer a first-run extraction
> to `filesDir`; the extract path is kept only as a fallback.)

## Catalog & presets (loaded by the app, also under `spektra/`)
- `catalog.json` — the 28-stock film/paper catalog (id, display name, group, ISO, era) the UI
  stock-picker reads. 1:1 with the `profiles/*.json` files. See `docs/FILM_STOCKS.md`.
- `presets.json` — the 27 built-in look presets (film/print pairing + param tweaks). See
  `docs/PRESETS.md`.

## Color filters (`filters/`)
- `neutral_print_filters.json` — per-(film,paper) neutral Y/M/C dichroic settings (CC units),
  used when `settings.neutral_print_filters_from_database = true`. **This is the only `filters/`
  file loaded at runtime.**
- The reference CSV source data spektrafilm ships (`dichroics/`, `heat_absorbing/`,
  `lens_transmission/`) is **not bundled**: the engine bakes the equivalent constants
  (`model/color_filters.cpp`, `runtime/params.cpp`), and the CSVs live upstream at
  `spektrafilm/src/spektrafilm/data/filters/` (removed from this tree on 2026-09-14).

## ICC profiles (`icc/`)
The six output-color-space ICC profiles the app embeds on 16-bit TIFF/PNG export
(`OutputDescriptor.kt`): `saucecontrol/sRGB-v4.icc` (sRGB), `saucecontrol/AdobeCompat-v4.icc`
(Adobe RGB), `saucecontrol/ProPhoto-v4.icc` (ProPhoto), `saucecontrol/Rec2020-v4.icc`
(Rec.2020), `ellelstone/ACES-elle-V4-g10.icc` (ACES2065-1) and `ellelstone/sRGB-elle-V4-g10.icc`
(linear sRGB), plus the two licence files and `icc/README.md` (the attribution `NOTICE.md` links
to). The rest of the upstream profile sets (159 files, ~400 KB) was dropped from the APK on
2026-09-14; git history keeps them.

## Constants compiled into the engine (not assets)
- `SPECTRAL_SHAPE`: 380–780 nm @ 5 nm → 81 samples (SpectralShape(380,780,5)).
- `STANDARD_OBSERVER_CMFS`: CIE 1931 2° color-matching functions, shape `(81, 3)`.

## APK-size note
~13 MB of assets + the native `.so`s per ABI. If size matters we can (a) split per ABI, (b)
quantize/recompress LUTs, or (c) deliver profiles/LUTs as an on-demand asset pack.
