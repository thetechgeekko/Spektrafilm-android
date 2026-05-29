# Bundled assets

All ship under `engine/spektra-core/src/main/assets/spektra/`, sourced from spektrafilm's
`src/spektrafilm/data/` (GPLv3). Total ≈ **17 MB**.

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

## Spectral-upsampling LUTs (`luts/spectral_upsampling/`, ≈ 10 MB)

| File | Size | Purpose |
|------|------|---------|
| `irradiance_xy_tc.npy` | 5.7 MB | Triangular-coordinate irradiance-spectra LUT (Hanatos2025 RGB→spectral). |
| `hanatos_irradiance_xy_coeffs_250304.lut` | 4.1 MB | Polynomial coefficient LUT (deg-4 2D) for spectral reconstruction. |

`.npy` is NumPy's array format (header + raw little-endian floats); the C++ loader parses the
header and mmaps the payload. The `.lut` binary is parsed per spektrafilm's
`utils/spectral_upsampling.py` struct layout.

## Color filters (`filters/`)
- `neutral_print_filters.json` — per-(film,paper) neutral Y/M/C dichroic settings (CC units),
  used when `settings.neutral_print_filters_from_database = true`.

## ICC profiles (`icc/`)
Output-color-space ICC profiles (sRGB, Adobe RGB, ProPhoto, Rec.2020, ACES variants) embedded
on export to match spektrafilm's color management.

## Constants compiled into the engine (not assets)
- `SPECTRAL_SHAPE`: 380–780 nm @ 5 nm → 81 samples (SpectralShape(380,780,5)).
- `STANDARD_OBSERVER_CMFS`: CIE 1931 2° color-matching functions, shape `(81, 3)`.

## APK-size note
~17 MB of assets + LibRaw `.so` per ABI. If size matters we can (a) split per ABI, (b)
quantize/recompress LUTs, or (c) deliver profiles/LUTs as an on-demand asset pack. Tracked in M6.
