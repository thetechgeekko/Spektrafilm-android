# SpectraFilm for Android — diffusion-filter golden generator.
# Copyright (C) 2026 SpectraFilm Android contributors.
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See <https://www.gnu.org/licenses/>.
#
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
# spektrafilm.
"""Generate a golden for the optical diffusion-filter PSF stage.

The diffusion filter (``spektrafilm.model.diffusion.apply_diffusion_filter_um``)
is not a DebugParams tap — it runs mid-pipeline on the float64 pre-log irradiance.
This script isolates it: it builds a deterministic float64 RGB irradiance image,
runs the oracle ``apply_diffusion_filter_um`` for a NON-default diffusion-filter
setting, and writes

    goldens/diffusion_bpm/input_rgb.f64        # float64 input irradiance (h,w,3)
    goldens/diffusion_bpm/diffusion_out.spkvec # float32 oracle output (h,w,3)
    goldens/diffusion_bpm/manifest.json        # params + tolerances

The C++ host parity test ``tests/test_diffusion.cpp`` reads the .f64 input, runs
the native ``spk::apply_diffusion_filter_um`` (double internals, float32 on
store), and checks it against the golden under the manifest tolerances
(max_abs <= 1e-4, rms <= 1e-5).

Requires a working spektrafilm environment (see tools/parity/setup_env.sh):
    PYTHONPATH=/home/user/spektrafilm/src:/tmp/spkstubs \
        python tools/parity/gen_diffusion_golden.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
GOLDENS_DIR = HERE / "goldens"

SEED = 20260529
SIZE = 64
FILM_FORMAT_MM = 35.0

# NON-default diffusion-filter setting under test. active=True flips the schema
# no-op; the rest are the schema/family defaults for black_pro_mist (the schema
# default family) so this is the "just turn it on" case.
CASE_ID = "diffusion_bpm"
FAMILY = "black_pro_mist"
STRENGTH = 0.5
SPATIAL_SCALE = 1.0
HALO_WARMTH = 0.0


def make_diffusion_input(size: int):
    """Deterministic float64 irradiance image with bright highlights + a ramp.

    The diffusion filter only does visible work around bright pixels (it spreads
    a fraction of their energy into a halo/bloom), so the fixture mixes a smooth
    diagonal ramp with a few very bright point/patch sources to exercise the PSF
    tails — not just the flat-field (which any energy-conserving kernel leaves
    unchanged)."""
    import numpy as np

    rng = np.random.default_rng(SEED)
    h = w = int(size)
    img = np.zeros((h, w, 3), dtype=np.float64)

    # Diagonal RGB ramp (scene-linear-ish).
    xx, yy = np.meshgrid(np.linspace(0.0, 1.0, w), np.linspace(0.0, 1.0, h))
    img[..., 0] = 0.2 + 0.6 * xx
    img[..., 1] = 0.2 + 0.6 * yy
    img[..., 2] = 0.2 + 0.3 * (xx + yy)

    # A handful of bright sources (highlights) the halo/bloom acts on.
    bright = [(12, 12, (8.0, 8.0, 8.0)),
              (50, 18, (10.0, 2.0, 2.0)),
              (20, 48, (2.0, 9.0, 3.0)),
              (44, 46, (3.0, 3.0, 12.0))]
    for cy, cx, (r, g, b) in bright:
        img[cy - 1:cy + 2, cx - 1:cx + 2, 0] = r
        img[cy - 1:cy + 2, cx - 1:cx + 2, 1] = g
        img[cy - 1:cy + 2, cx - 1:cx + 2, 2] = b

    # Bounded dither so the field is not piecewise constant.
    img += (rng.random((h, w, 3)) - 0.5) * 1e-3
    np.clip(img, 0.0, None, out=img)
    return np.ascontiguousarray(img, dtype=np.float64)


def main() -> int:
    try:
        import numpy as np
        from spektrafilm.model.diffusion import apply_diffusion_filter_um
        from spektrafilm.runtime.params_schema import DiffusionFilterParams
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write(
            "ERROR: could not import spektrafilm.\n"
            f"  ({type(exc).__name__}: {exc})\n"
            "Run tools/parity/setup_env.sh then re-run with PYTHONPATH set "
            "(see module docstring).\n")
        return 2

    import spkvec  # local module

    img = make_diffusion_input(SIZE)
    pixel_size_um = FILM_FORMAT_MM * 1000.0 / float(SIZE)

    df = DiffusionFilterParams(
        active=True,
        filter_family=FAMILY,
        strength=STRENGTH,
        spatial_scale=SPATIAL_SCALE,
        halo_warmth=HALO_WARMTH,
    )
    out = apply_diffusion_filter_um(img.copy(), df, pixel_size_um)
    out = np.ascontiguousarray(out, dtype=np.float64)

    case_dir = GOLDENS_DIR / CASE_ID
    case_dir.mkdir(parents=True, exist_ok=True)

    # float64 input fixture (binary, C-contiguous (h,w,3) doubles).
    in_path = case_dir / "input_rgb.f64"
    img.tofile(in_path)

    # float32 golden (matches the .spkvec / parity store convention).
    out_path = case_dir / "diffusion_out.spkvec"
    spkvec.write(out_path, out.astype(np.float32))

    manifest = {
        "case_id": CASE_ID,
        "stage": "apply_diffusion_filter_um (camera/enlarger optical diffusion)",
        "image": {"size": SIZE, "shape": [SIZE, SIZE, 3], "seed": SEED,
                  "dtype_input": "float64", "kind": "ramp+bright_sources"},
        "pixel_size_um": pixel_size_um,
        "params": {
            "active": True,
            "filter_family": FAMILY,
            "strength": STRENGTH,
            "spatial_scale": SPATIAL_SCALE,
            "halo_warmth": HALO_WARMTH,
        },
        "tolerance": {"max_abs": 1e-4, "rms": 1e-5},
        "spkvec_version": 1,
        "generator": "gen_diffusion_golden.py",
    }
    with open(case_dir / "manifest.json", "w") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)

    print(f"wrote {in_path.relative_to(HERE)}  ({img.nbytes} bytes f64)")
    print(f"wrote {out_path.relative_to(HERE)}  shape={out.shape}")
    print(f"wrote {(case_dir / 'manifest.json').relative_to(HERE)}")
    print(f"pixel_size_um={pixel_size_um:.6f}  out range "
          f"[{out.min():.6f}, {out.max():.6f}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
