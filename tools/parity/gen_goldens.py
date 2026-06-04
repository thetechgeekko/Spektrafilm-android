# Spektrafilm for Android — golden-vector parity harness.
# Copyright (C) 2026 Spektrafilm Android contributors.
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See <https://www.gnu.org/licenses/>.
#
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
# spektrafilm.
"""Generate golden intermediate vectors from the Python ``spektrafilm`` engine.

For each parity *case* (a film/print profile pair plus toggles) this script runs
``spektrafilm.simulate`` once per debug *tap*, capturing the intermediate buffer
the engine exposes via ``DebugParams`` (``output_film_log_raw``,
``output_film_density_cmy``, ``output_print_density_cmy``) as well as the final
RGB scan. Each buffer is written to ``goldens/<case>/<tap>.spkvec`` and the case
is described in ``goldens/<case>/manifest.json``.

The C++ engine port reproduces each tap via ``spk_simulate_tap`` and is checked
against these goldens by ``compare_main.cpp`` within a documented tolerance.

This module REQUIRES a working ``spektrafilm`` environment (see the upstream
README — install the package and its data assets). The import is guarded so the
failure mode is a clear, actionable message rather than a traceback. The rest of
the harness (format spec, readers, C++ comparator) does NOT need spektrafilm.

Usage::

    python gen_goldens.py                      # default cases (see CASES below)
    python gen_goldens.py --case print_portra  # one named case
    python gen_goldens.py --list               # list available cases
    python gen_goldens.py --size 64            # test-image edge length (px)
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
GOLDENS_DIR = HERE / "goldens"

# Deterministic seed for the synthetic image and any stochastic engine effects.
SEED = 20260529

# Taps exposed by spektrafilm DebugParams, in pipeline order. The final RGB scan
# is captured with debug off ("final_rgb"). The mapping value is the DebugParams
# field that selects the tap; None means "run the full pipeline, no tap".
TAPS = {
    "film_log_raw": "output_film_log_raw",
    "film_density_cmy": "output_film_density_cmy",
    "print_density_cmy": "output_print_density_cmy",
    "final_rgb": None,
}

# Taps that do not exist when scan_film is on (no print stage). For scan_film
# cases we capture the negative taps and the final RGB scan only.
SCAN_FILM_SKIP_TAPS = {"print_density_cmy"}


@dataclass
class Case:
    """A single parity case: one profile pair + a set of deterministic toggles."""

    case_id: str
    film_profile: str
    print_profile: str
    scan_film: bool = False
    # Engine toggles. We deliberately disable stochastic + spatial effects by
    # default so goldens are bit-stable across hosts; grain-on cases are opt-in
    # and compared by statistics/tolerance (see README "Grain & stochastic taps").
    deactivate_stochastic_effects: bool = True
    deactivate_spatial_effects: bool = True
    grain_active: bool = False
    # Crop / resize geometry (io params). Defaults are a strict no-op; non-default
    # values exercise the crop_and_rescale (crop + cubic upscale) preprocess.
    crop: bool = False
    crop_center: tuple = (0.5, 0.5)
    crop_size: tuple = (0.1, 0.1)
    upscale_factor: float = 1.0
    # Auto-exposure (camera.auto_exposure / auto_exposure_method). Default OFF for
    # determinism; a non-default case turns it ON to exercise the metering stage.
    auto_exposure: bool = False
    auto_exposure_method: str = "center_weighted"
    # Camera lens blur in micrometres (camera.lens_blur_um). Default 0.0 (strict
    # no-op). A non-default case sets it to exercise the µm Gaussian lens-blur pass
    # in FilmingStage.expose (applied between the diffusion filter and halation).
    # lens_blur_um is only honoured when deactivate_spatial_effects is False (the
    # oracle's digest_params zeroes it otherwise), so a lens-blur case must keep
    # spatial effects on.
    lens_blur_um: float = 0.0
    # Camera optical diffusion filter (camera.diffusion_filter). Default OFF
    # (active=False is a strict no-op). A non-default case turns it ON with a
    # real filter family + strength to exercise apply_diffusion_filter_um END TO
    # END through the pipeline (filming.expose), not just in stage isolation.
    # The oracle's digest_params zeroes camera.diffusion_filter.active when
    # deactivate_spatial_effects=True, so a diffusion full-pipeline case MUST keep
    # spatial effects on (deactivate_spatial_effects=False), exactly like the
    # lens-blur case.
    diffusion_active: bool = False
    diffusion_family: str = "black_pro_mist"
    diffusion_strength: float = 0.5
    diffusion_spatial_scale: float = 1.0
    diffusion_halo_warmth: float = 0.0
    # Spectral-domain Gaussian blur of the reconstructed-spectra LUT
    # (settings.spectral_gaussian_blur). Default 0.0 is a strict no-op. A non-zero
    # value blurs HANATOS2025_SPECTRA_LUT along its spectral axis with
    # scipy.ndimage.gaussian_filter(sigma=(0,0,sigma)) inside
    # compute_hanatos2025_tc_lut, changing the filming tc_lut and therefore every
    # downstream tap. It is NOT gated by deactivate_spatial_effects (it lives in
    # the LUT-build path, not the per-pixel spatial branch), so a blur case can
    # keep spatial/grain off and stay bit-stable.
    spectral_gaussian_blur: float = 0.0
    # Hanatos2025 sensitivity-adaptation toggles
    # (settings.apply_hanatos2025_adaptation_window / _surface). The defaults match
    # the schema (window ON, surface OFF) so a default case reproduces the existing
    # goldens. A non-default case turns the surface ON: the filming tc_lut is
    # multiplied by 2**surface, where surface is the per-LUT-cell, per-channel poly4
    # log-exposure correction (eval_poly4_log_exposure_surface) evaluated at the
    # film reference-illuminant chromaticity, inside compute_hanatos2025_tc_lut. It
    # lives in the LUT-build path (not the spatial branch), so a surface case can
    # keep spatial/grain off and stay bit-stable.
    apply_hanatos_window: bool = True
    apply_hanatos_surface: bool = False
    # Camera UV/IR cut band-pass filter (camera.filter_uv / filter_ir), each
    # (amp, wavelength_nm, width_nm). Defaults (amp 0) are a strict no-op. A non-
    # default case sets a non-zero amplitude to exercise compute_band_pass_filter in
    # FilmingStage._rgb_to_film_raw: the band-pass multiplies the profile sensitivity
    # (with per-channel white-balance normalisation) BEFORE the spectra contraction,
    # changing the filming tc_lut and every downstream tap. It lives in the LUT-build
    # path (not the spatial branch), so a band-pass case can keep spatial/grain off
    # and stay bit-stable.
    filter_uv: tuple = (0.0, 410.0, 8.0)
    filter_ir: tuple = (0.0, 675.0, 15.0)
    # Enlarger PREFLASH (enlarger.preflash_exposure / preflash_y_filter_shift /
    # preflash_m_filter_shift): a uniform pre-exposure flash of the print paper
    # before the image exposure. Default preflash_exposure 0.0 is a strict no-op
    # (the oracle's `if preflash_exposure > 0` guard returns a zero raw_preflash).
    # A non-default case turns it on with its own y/m filter shifts to exercise
    # printing.py::_compute_raw_preflash + preflash_filtered_illuminant. Lives in
    # the PRINT route only (scan_film=False), so a preflash case must set
    # scan_film=False; spatial/grain stay off so it is deterministic/bit-stable.
    preflash_exposure: float = 0.0
    preflash_y_filter_shift: float = 0.0
    preflash_m_filter_shift: float = 0.0
    notes: str = ""
    taps: tuple = field(default=tuple(TAPS.keys()))


# Initial parity matrix (mirrors cases.md). Kept small + deterministic.
CASES = [
    Case(
        case_id="print_portra",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=False,
        notes="Baseline negative->print->scan, all stochastic/spatial effects off.",
    ),
    Case(
        case_id="scan_portra",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        notes="scan_film route: negative scanned directly, no print stage.",
    ),
    Case(
        case_id="print_ektar",
        film_profile="kodak_ektar_100",
        print_profile="kodak_supra_endura",
        scan_film=False,
        notes="Second negative/paper pair to catch profile-coupled regressions.",
    ),
    Case(
        case_id="scan_portra_spatial",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=False,   # halation + in-emulsion scatter + coupler diffusion ON
        deactivate_stochastic_effects=True,  # grain OFF -> still deterministic/bit-stable
        grain_active=False,
        notes="scan_film with spatial effects ON (halation/scatter/diffusion), grain off. "
              "Deterministic target for porting the spatial branches.",
    ),
    Case(
        case_id="scan_portra_lensblur",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=False,   # required: keeps camera.lens_blur_um alive
        deactivate_stochastic_effects=True,  # grain OFF -> deterministic/bit-stable
        grain_active=False,
        lens_blur_um=1650.0,                # NON-default camera lens blur (µm)
        notes="scan_film with the camera lens blur ON (camera.lens_blur_um=1650µm) "
              "plus the rest of the spatial branch (halation/scatter/coupler "
              "diffusion). Exercises apply_gaussian_blur_um in FilmingStage.expose, "
              "applied between the optical diffusion filter and halation: a µm "
              "Gaussian with sigma = lens_blur_um/pixel_size_um broadcast across the "
              "3 channels. For the 64px image pixel_size_um=546.875, so sigma~=3.017 "
              "px -> the Young-van Vliet IIR path (sigma>=SMALL_SIGMA_MAX=3) is "
              "exercised, a clearly non-trivial blur. Grain off so it stays "
              "deterministic/bit-stable.",
    ),
    Case(
        case_id="scan_portra_crop",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=True,
        deactivate_stochastic_effects=True,
        grain_active=False,
        crop=True,
        crop_center=(0.45, 0.55),
        crop_size=(0.6, 0.5),
        upscale_factor=1.75,
        notes="scan_film with a NON-default crop + cubic upscale geometry stage "
              "(crop_and_rescale): crop_center=(0.45,0.55), crop_size=(0.6,0.5), "
              "upscale_factor=1.75. Spatial/grain off so it stays bit-stable; "
              "exercises the crop integer-slice + skimage rescale(order=3) port.",
    ),
    Case(
        case_id="scan_portra_autoexp",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=True,
        deactivate_stochastic_effects=True,
        grain_active=False,
        auto_exposure=True,                 # NON-default: metering ON
        auto_exposure_method="center_weighted",  # schema default pattern
        notes="scan_film with AUTO-EXPOSURE ON (center_weighted metering). "
              "Exercises FilmingStage.auto_exposure in pipeline._preprocess: the "
              "image is metered (small_preview luminance -> -log2(Y_meter/0.184) "
              "EV) and globally scaled by 2**ev before crop/rescale. For the 64px "
              "synthetic image small_preview is a no-op (long edge 64 <= 256), so "
              "metering runs on the full image. Spatial/grain off so it stays "
              "bit-stable.",
    ),
    Case(
        case_id="scan_portra_autoexp_matrix",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=True,
        deactivate_stochastic_effects=True,
        grain_active=False,
        auto_exposure=True,                 # NON-default: metering ON
        auto_exposure_method="matrix",      # NON-center_weighted pattern
        notes="scan_film with AUTO-EXPOSURE ON using the MATRIX metering pattern "
              "(a NON-center_weighted pattern; scan_portra_autoexp only covers "
              "center_weighted). Exercises the 5x5 raised-cosine zone-weighted "
              "branch of utils.autoexposure.measure_autoexposure_ev: the image is "
              "split into a 5x5 grid, each zone mean weighted by 0.5*(1+cos(pi*"
              "dist)) of its normalised distance from centre, the weighted average "
              "/ 0.184 -> -log2(exposure) EV, then the image is globally scaled by "
              "2**ev before crop/rescale. For the 64px image small_preview is a "
              "no-op (long edge 64 <= 256). Spatial/grain off so it stays "
              "bit-stable. Tested by tests/test_autoexposure.cpp.",
    ),
    Case(
        case_id="scan_diffusion",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=False,   # required: keeps camera.diffusion_filter alive
        deactivate_stochastic_effects=True,  # grain OFF -> deterministic/bit-stable
        grain_active=False,
        diffusion_active=True,              # NON-default: camera optical diffusion ON
        diffusion_family="black_pro_mist",
        diffusion_strength=0.8,             # NON-default strength (schema default is 0.5)
        diffusion_spatial_scale=1.0,
        diffusion_halo_warmth=0.0,
        notes="scan_film with the camera OPTICAL DIFFUSION FILTER ON end-to-end "
              "(camera.diffusion_filter: active=True, family=black_pro_mist, "
              "strength=0.8 -- a NON-default strength) plus the rest of the spatial "
              "branch (halation/scatter/coupler diffusion), grain off. Exercises "
              "apply_diffusion_filter_um inside FilmingStage.expose run through the "
              "WHOLE pipeline (the diffusion_bpm case only tests the filter in "
              "isolation). The oracle's digest_params zeroes "
              "camera.diffusion_filter.active under deactivate_spatial_effects=True, "
              "so spatial effects are kept on. Grain off so it stays "
              "deterministic/bit-stable. Tested by tests/test_diffusion_e2e.cpp.",
    ),
    Case(
        case_id="scan_spectral_blur",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=True,    # blur lives in the LUT build, not the
        deactivate_stochastic_effects=True, # spatial branch -> can stay all-off
        grain_active=False,
        spectral_gaussian_blur=5.0,         # NON-default: sigma=5 (band-index units)
        notes="scan_film with the SPECTRAL GAUSSIAN BLUR ON "
              "(settings.spectral_gaussian_blur=5.0). Exercises the optional blur in "
              "compute_hanatos2025_tc_lut: HANATOS2025_SPECTRA_LUT is blurred along "
              "its spectral axis (axis 2) with scipy.ndimage.gaussian_filter("
              "sigma=(0,0,5.0)) (SciPy defaults order=0, mode='reflect', "
              "truncate=4.0) BEFORE the sensitivity contraction, changing the "
              "filming tc_lut and therefore film_log_raw / film_density_cmy / "
              "final_rgb. The blur is independent of deactivate_spatial_effects "
              "(it is in the LUT-build path, not the per-pixel spatial branch), so "
              "spatial + grain are kept off and the case stays deterministic/"
              "bit-stable. Tested by tests/test_spectral_blur_e2e.cpp.",
    ),
    Case(
        case_id="scan_portra_surface",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=True,    # surface lives in the LUT build, not the
        deactivate_stochastic_effects=True, # spatial branch -> can stay all-off
        grain_active=False,
        apply_hanatos_window=True,          # window stays ON (schema default)
        apply_hanatos_surface=True,         # NON-default: log-exposure surface ON
        notes="scan_film with the HANATOS2025 LOG-EXPOSURE-CORRECTION SURFACE ON "
              "(settings.apply_hanatos2025_adaptation_surface=True; window left ON). "
              "Exercises the apply_surface branch of compute_hanatos2025_tc_lut: "
              "after the window-weighted spectra contraction, the filming tc_lut is "
              "multiplied per-cell, per-channel by 2**surface, where surface = "
              "eval_poly4_log_exposure_surface(profile.hanatos2025_adaptation_surface"
              "_params, illuminant_xy=_illuminant_to_xy(film.reference_illuminant), "
              "model='poly4') — a degree-4 2D polynomial over the (L,L) tc grid, "
              "centred at tri2quad(illuminant_xy), passed through hanika_sigmoid("
              "raw, max=2.0). This changes the filming tc_lut and therefore "
              "film_log_raw / film_density_cmy / final_rgb. The surface is "
              "independent of deactivate_spatial_effects (it is in the LUT-build "
              "path), so spatial + grain are kept off and the case stays "
              "deterministic/bit-stable. Tested by tests/test_hanatos_surface_e2e.cpp.",
    ),
    Case(
        case_id="scan_portra_nowindow",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=True,
        deactivate_stochastic_effects=True,
        grain_active=False,
        apply_hanatos_window=False,         # NON-default: erf4 window OFF
        apply_hanatos_surface=False,
        notes="scan_film with the HANATOS2025 ERF4 WINDOW OFF "
              "(settings.apply_hanatos2025_adaptation_window=False, surface also off). "
              "Exercises the apply_window=False branch of compute_hanatos2025_tc_lut: "
              "the spectra LUT is contracted against the BARE sensitivity (no erf4 "
              "band-pass and no white-balance normalisation), changing the filming "
              "tc_lut and therefore film_log_raw / film_density_cmy / final_rgb. "
              "Confirms the window toggle is genuinely wired (its default-on path is "
              "the existing scan_portra golden). Independent of the spatial branch, "
              "so spatial + grain stay off and the case is deterministic/bit-stable. "
              "Tested by tests/test_hanatos_surface_e2e.cpp.",
    ),
    Case(
        case_id="scan_portra_uvir",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=True,
        deactivate_spatial_effects=True,
        deactivate_stochastic_effects=True,
        grain_active=False,
        filter_uv=(1.0, 410.0, 8.0),        # NON-default: UV cut ON (amp=1)
        filter_ir=(1.0, 675.0, 15.0),       # NON-default: IR cut ON (amp=1)
        notes="scan_film with the CAMERA UV/IR CUT BAND-PASS ON "
              "(camera.filter_uv=(1,410,8), filter_ir=(1,675,15)). Exercises "
              "compute_band_pass_filter in FilmingStage._rgb_to_film_raw: the "
              "band-pass = filter_uv*filter_ir (each 1-amp + amp*sigmoid_erf) "
              "multiplies the profile sensitivity with a per-channel white-balance "
              "normalisation against the film reference illuminant, BEFORE the "
              "spectra contraction inside compute_hanatos2025_tc_lut. It changes the "
              "filming tc_lut and therefore film_log_raw / film_density_cmy / "
              "final_rgb. Lives in the LUT-build path (not the spatial branch), so "
              "spatial + grain stay off and the case is deterministic/bit-stable. "
              "Tested by tests/test_camera_uvir_e2e.cpp.",
    ),
    Case(
        case_id="print_portra_preflash",
        film_profile="kodak_portra_400",
        print_profile="kodak_portra_endura",
        scan_film=False,                    # PRINT route: preflash is a print-stage effect
        deactivate_spatial_effects=True,
        deactivate_stochastic_effects=True,
        grain_active=False,
        preflash_exposure=0.15,             # NON-default: enlarger preflash ON
        preflash_y_filter_shift=-10.0,      # NON-default preflash Y filter shift (Kodak CC)
        preflash_m_filter_shift=5.0,        # NON-default preflash M filter shift (Kodak CC)
        notes="print route with the ENLARGER PREFLASH ON "
              "(enlarger.preflash_exposure=0.15, preflash_y_filter_shift=-10, "
              "preflash_m_filter_shift=5). Exercises printing.py::_compute_raw_preflash "
              "+ filter_enlarger_source.preflash_filtered_illuminant: a uniform "
              "pre-exposure flash of the print paper. The preflash filtered illuminant "
              "is color_enlarger(enlarger source, CC=[c_neutral, m_neutral+preflash_m, "
              "y_neutral+preflash_y]) — its OWN filter shifts, NOT the image-exposure "
              "shifts. print_expose adds a constant per-channel raw_preflash 3-vector "
              "(= sum_l 10^-base_density[l] * preflash_illuminant[l] * sens[l,k]) * "
              "preflash_exposure to the print raw, AFTER the midgray factor and BEFORE "
              "the log10, changing print_density_cmy and final_rgb. Print stage only "
              "(scan_film=False); spatial+grain off so it is deterministic/bit-stable. "
              "Tested by tests/test_preflash_e2e.cpp.",
    ),
]


def _import_spektrafilm():
    """Import spektrafilm or exit with an actionable message."""
    try:
        import spektrafilm  # noqa: F401
        from spektrafilm import init_params, simulate  # noqa: F401
        return sys.modules["spektrafilm"]
    except Exception as exc:  # ImportError or asset/runtime errors
        sys.stderr.write(
            "ERROR: could not import 'spektrafilm'.\n"
            f"  ({type(exc).__name__}: {exc})\n\n"
            "gen_goldens.py needs a working spektrafilm environment to PRODUCE\n"
            "goldens. Set it up per the spektrafilm README (clone the repo,\n"
            "install the package and its data assets), then run this script from\n"
            "that environment, e.g.:\n\n"
            "    cd /path/to/spektrafilm && pip install -e .\n"
            "    python /path/to/Spectrafilmandroid/tools/parity/gen_goldens.py\n\n"
            "Reading/inspecting existing .spkvec goldens and building the C++\n"
            "comparator do NOT require spektrafilm.\n"
        )
        raise SystemExit(2)


def make_test_image(size: int) -> "object":
    """Build a deterministic synthetic linear-RGB test image, no external files.

    Layout (so a single image stresses several pipeline behaviours):
      * a smooth diagonal RGB ramp across the frame (low -> high exposure),
      * a coarse grid of saturated/neutral patches (Macbeth-like) in the center,
      * a few near-black and near-white tiles for shadow/highlight clipping.

    Values are scene-linear in [0, ~1.2] (ProPhoto RGB linear, the spektrafilm
    default input space). Fully deterministic for a given ``size``.
    """
    import numpy as np

    rng = np.random.default_rng(SEED)
    h = w = int(size)
    img = np.zeros((h, w, 3), dtype=np.float64)

    # 1) Diagonal RGB ramp: R increases with x, G with y, B with x+y.
    xx, yy = np.meshgrid(np.linspace(0.0, 1.0, w), np.linspace(0.0, 1.0, h))
    img[..., 0] = xx
    img[..., 1] = yy
    img[..., 2] = 0.5 * (xx + yy)

    # 2) Macbeth-like 6x4 grid of fixed patches in the central region.
    patches = [
        (0.05, 0.05, 0.05), (0.20, 0.20, 0.20), (0.45, 0.45, 0.45), (0.90, 0.90, 0.90),
        (0.55, 0.10, 0.10), (0.10, 0.45, 0.12), (0.10, 0.15, 0.55), (0.70, 0.60, 0.10),
        (0.40, 0.12, 0.45), (0.10, 0.50, 0.55), (0.65, 0.30, 0.12), (0.20, 0.25, 0.65),
        (0.85, 0.30, 0.20), (0.25, 0.55, 0.30), (0.75, 0.15, 0.30), (0.90, 0.75, 0.10),
        (0.55, 0.20, 0.55), (0.15, 0.55, 0.70), (1.10, 1.10, 1.10), (0.01, 0.01, 0.01),
        (0.30, 0.30, 0.10), (0.10, 0.30, 0.30), (0.30, 0.10, 0.30), (0.50, 0.50, 0.20),
    ]
    cols, rows = 6, 4
    gw = max(1, w // 2 // cols)
    gh = max(1, h // 2 // rows)
    y0 = (h - gh * rows) // 2
    x0 = (w - gw * cols) // 2
    for idx, (r, g, b) in enumerate(patches):
        gr, gc = divmod(idx, cols)
        ys, xs = y0 + gr * gh, x0 + gc * gw
        img[ys:ys + gh, xs:xs + gw, 0] = r
        img[ys:ys + gh, xs:xs + gw, 1] = g
        img[ys:ys + gh, xs:xs + gw, 2] = b

    # 3) Tiny deterministic dither so AE/normalization paths see variety, but
    #    bounded so the image stays reproducible and physically sane.
    img += (rng.random((h, w, 3)) - 0.5) * 1e-3
    np.clip(img, 0.0, None, out=img)
    return img.astype(np.float64)


def _build_params(sf, case: Case):
    """Build digested-ready params for a case (returns undigested RuntimePhotoParams)."""
    params = sf.init_params(
        film_profile=case.film_profile,
        print_profile=case.print_profile,
    )
    params.io.scan_film = case.scan_film
    params.io.crop = case.crop
    params.io.crop_center = tuple(case.crop_center)
    params.io.crop_size = tuple(case.crop_size)
    params.io.upscale_factor = case.upscale_factor
    params.debug.deactivate_stochastic_effects = case.deactivate_stochastic_effects
    params.debug.deactivate_spatial_effects = case.deactivate_spatial_effects
    params.film_render.grain.active = case.grain_active
    # Auto-exposure: OFF by default (deterministic — identical inputs map to
    # identical exposures regardless of metering tweaks). A case may turn it ON
    # to exercise the metering stage; the synthetic image is fully deterministic
    # so the metered EV is reproducible.
    params.camera.auto_exposure = case.auto_exposure
    params.camera.auto_exposure_method = case.auto_exposure_method
    params.camera.exposure_compensation_ev = 0.0
    # Camera lens blur (µm). Only meaningful when spatial effects are kept on
    # (digest_params zeroes it under deactivate_spatial_effects=True).
    params.camera.lens_blur_um = case.lens_blur_um
    # Camera optical diffusion filter. Only honoured when spatial effects are kept
    # on (digest_params zeroes camera.diffusion_filter.active under
    # deactivate_spatial_effects=True). When active we set a real family + a
    # non-default strength so the filter does visible work end to end.
    params.camera.diffusion_filter.active = case.diffusion_active
    params.camera.diffusion_filter.filter_family = case.diffusion_family
    params.camera.diffusion_filter.strength = case.diffusion_strength
    params.camera.diffusion_filter.spatial_scale = case.diffusion_spatial_scale
    params.camera.diffusion_filter.halo_warmth = case.diffusion_halo_warmth
    # Spectral-domain Gaussian blur of the spectra LUT. Lives in settings, not the
    # spatial branch, so it is honoured regardless of deactivate_spatial_effects.
    params.settings.spectral_gaussian_blur = case.spectral_gaussian_blur
    # Hanatos2025 sensitivity-adaptation toggles. Window defaults ON, surface
    # defaults OFF (schema defaults); a case may flip the surface on to exercise the
    # poly4 log-exposure-correction surface in compute_hanatos2025_tc_lut.
    params.settings.apply_hanatos2025_adaptation_window = case.apply_hanatos_window
    params.settings.apply_hanatos2025_adaptation_surface = case.apply_hanatos_surface
    # Camera UV/IR cut band-pass filter. Lives in the LUT-build path (the band-pass
    # is folded into the sensitivity before compute_hanatos2025_tc_lut), so it is
    # honoured regardless of deactivate_spatial_effects. Defaults (amp 0) no-op.
    params.camera.filter_uv = tuple(case.filter_uv)
    params.camera.filter_ir = tuple(case.filter_ir)
    # Enlarger preflash (print route only). Default exposure 0.0 is a strict no-op.
    params.enlarger.preflash_exposure = case.preflash_exposure
    params.enlarger.preflash_y_filter_shift = case.preflash_y_filter_shift
    params.enlarger.preflash_m_filter_shift = case.preflash_m_filter_shift
    return params


def _run_tap(sf, params, tap_name: str, image):
    """Run simulate once configured for ``tap_name`` and return the buffer."""
    import copy

    p = copy.deepcopy(params)
    field = TAPS[tap_name]
    if field is None:
        # Final RGB: full pipeline, debug off.
        p.debug.debug_mode = "off"
        p.debug.output_film_log_raw = False
        p.debug.output_film_density_cmy = False
        p.debug.output_print_density_cmy = False
    else:
        p.debug.debug_mode = "output"
        p.debug.output_film_log_raw = False
        p.debug.output_film_density_cmy = False
        p.debug.output_print_density_cmy = False
        setattr(p.debug, field, True)
    out = sf.simulate(image, p)  # digests params internally
    import numpy as np

    return np.ascontiguousarray(np.asarray(out, dtype=np.float32))


def generate_case(sf, case: Case, size: int) -> dict:
    """Generate all taps for one case; write .spkvec files + return manifest dict."""
    import spkvec  # local module, see spkvec_format.md

    image = make_test_image(size)
    params = _build_params(sf, case)

    case_dir = GOLDENS_DIR / case.case_id
    case_dir.mkdir(parents=True, exist_ok=True)

    written = {}
    for tap_name in case.taps:
        if case.scan_film and tap_name in SCAN_FILM_SKIP_TAPS:
            continue
        buf = _run_tap(sf, params, tap_name, image)
        path = case_dir / f"{tap_name}.spkvec"
        spkvec.write(path, buf)
        written[tap_name] = {
            "file": f"{tap_name}.spkvec",
            "shape": list(buf.shape),
            "min": float(buf.min()),
            "max": float(buf.max()),
            "mean": float(buf.mean()),
        }
        print(f"  wrote {path.relative_to(HERE)}  shape={buf.shape}")

    manifest = {
        "case_id": case.case_id,
        "film_profile": case.film_profile,
        "print_profile": case.print_profile,
        "scan_film": case.scan_film,
        "image": {
            "kind": "synthetic_ramp_macbeth",
            "size": size,
            "shape": [size, size, 3],
            "input_color_space": "ProPhoto RGB (linear)",
            "seed": SEED,
        },
        "toggles": {
            "auto_exposure": case.auto_exposure,
            "auto_exposure_method": case.auto_exposure_method,
            "exposure_compensation_ev": 0.0,
            "grain_active": case.grain_active,
            "deactivate_stochastic_effects": case.deactivate_stochastic_effects,
            "deactivate_spatial_effects": case.deactivate_spatial_effects,
            "lens_blur_um": case.lens_blur_um,
            "diffusion_active": case.diffusion_active,
            "diffusion_family": case.diffusion_family,
            "diffusion_strength": case.diffusion_strength,
            "diffusion_spatial_scale": case.diffusion_spatial_scale,
            "diffusion_halo_warmth": case.diffusion_halo_warmth,
            "spectral_gaussian_blur": case.spectral_gaussian_blur,
        },
        "notes": case.notes,
        "taps": written,
        # Default tolerances the C++ comparator should gate on. Grain-off /
        # spatial-off cases are deterministic, so these are tight.
        "tolerance": {"max_abs": 1e-4, "rms": 1e-5},
        "spkvec_version": 1,
        "generator": "gen_goldens.py",
    }
    with open(case_dir / "manifest.json", "w") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)
    print(f"  wrote {(case_dir / 'manifest.json').relative_to(HERE)}")
    return manifest


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--case", action="append", default=None,
                        help="case id to generate (repeatable); default = all")
    parser.add_argument("--size", type=int, default=64,
                        help="edge length of the square synthetic test image (px)")
    parser.add_argument("--list", action="store_true",
                        help="list available cases and exit (no spektrafilm needed)")
    args = parser.parse_args(argv)

    by_id = {c.case_id: c for c in CASES}

    if args.list:
        for c in CASES:
            route = "scan_film" if c.scan_film else "print"
            print(f"{c.case_id:16s}  {c.film_profile} / {c.print_profile}  [{route}]")
        return 0

    if args.case:
        unknown = [c for c in args.case if c not in by_id]
        if unknown:
            sys.stderr.write(f"unknown case(s): {', '.join(unknown)}\n")
            return 2
        cases = [by_id[c] for c in args.case]
    else:
        cases = CASES

    sf = _import_spektrafilm()
    GOLDENS_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Generating goldens into {GOLDENS_DIR} (image {args.size}x{args.size})")
    for case in cases:
        print(f"[case] {case.case_id}")
        generate_case(sf, case, args.size)
    print("Done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
