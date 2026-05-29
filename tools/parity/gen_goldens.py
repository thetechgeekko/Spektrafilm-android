# SpectraFilm for Android — golden-vector parity harness.
# Copyright (C) 2026 SpectraFilm Android contributors.
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
    params.debug.deactivate_stochastic_effects = case.deactivate_stochastic_effects
    params.debug.deactivate_spatial_effects = case.deactivate_spatial_effects
    params.film_render.grain.active = case.grain_active
    # Make exposure deterministic: disable auto-exposure so identical inputs map
    # to identical exposures regardless of metering tweaks.
    params.camera.auto_exposure = False
    params.camera.exposure_compensation_ev = 0.0
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
            "auto_exposure": False,
            "exposure_compensation_ev": 0.0,
            "grain_active": case.grain_active,
            "deactivate_stochastic_effects": case.deactivate_stochastic_effects,
            "deactivate_spatial_effects": case.deactivate_spatial_effects,
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
