# SpectraFilm for Android — LUT-acceleration golden generator.
# Copyright (C) 2026 SpectraFilm Android contributors.
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See <https://www.gnu.org/licenses/>.
#
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
# spektrafilm.
"""Generate a golden for the OPT-IN LUT-acceleration path.

The spektrafilm scanner/enlarger density->light->XYZ transforms can be
accelerated with a precomputed 3D LUT (settings.use_scanner_lut /
use_enlarger_lut, settings.lut_resolution). The oracle builds the LUT with
``utils.lut.compute_with_lut`` (a per-channel uniform grid over [xmin, xmax],
evaluated by the spectral function) and applies it with
``utils.fast_interp_lut.apply_lut_3d`` whose DEFAULT interpolation method is
``pchip`` (monotone cubic Hermite with precomputed per-axis slopes and
per-cell clamping).

This script isolates the LUT machinery against a fixed, analytic vector function
(no spectral assets needed) so the C++ port of compute_with_lut + the PCHIP 3D
interpolator can be checked for NUMERICAL EQUIVALENCE to the oracle. It writes:

    goldens/lut_accel/input_norm.f64    # float64 normalized data (h,w,3) in [0,1]
    goldens/lut_accel/lut.f64           # float64 LUT (steps,steps,steps,3)
    goldens/lut_accel/direct.spkvec     # float32 direct function evaluation
    goldens/lut_accel/lut_out.spkvec    # float32 oracle pchip-LUT output
    goldens/lut_accel/manifest.json     # bounds, steps, tolerances

The analytic function mimics the SHAPE of the density->log_xyz transform: each
output channel is a smooth, monotone-ish combination of the three inputs over the
density domain [xmin, xmax]. The C++ test (tests/test_lut_accel.cpp) rebuilds the
same LUT from the same function, runs the native PCHIP interpolator on the same
normalized input, and asserts:
  * native pchip-LUT output ~= oracle pchip-LUT output  (interpolator parity),
  * documents native LUT vs. direct error  (the opt-in acceleration tolerance).

Requires a working spektrafilm environment (tools/parity/setup_env.sh):
    PYTHONPATH=/home/user/spektrafilm/src:/tmp/spkstubs \
        python tools/parity/gen_lut_golden.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
GOLDENS_DIR = HERE / "goldens"

SEED = 20260529
SIZE = 48
STEPS = 17  # == params_schema.SettingsParams.lut_resolution default

# Density-like domain per channel (mirrors the scanner bounds magnitude:
# density_min ~ -grain.density_min, density_max ~ nanmax(density_curves)).
XMIN = (-0.12, -0.10, -0.08)
XMAX = (2.4, 2.2, 2.0)


def analytic_transform(x):
    """A fixed, smooth, monotone-per-axis vector function R^3 -> R^3.

    Shaped like a density->log-light transform: each output decreases smoothly
    with its dominant input (more density -> less light) with mild cross-channel
    coupling, so the per-axis LUT lines are monotone (the oracle's PCHIP path is
    designed for monotone data) and the interpolation error is representative.
    """
    import numpy as np

    r = x[..., 0]
    g = x[..., 1]
    b = x[..., 2]
    out = np.empty_like(x)
    out[..., 0] = -1.0 * r - 0.15 * g - 0.05 * b + 0.10 * np.tanh(r)
    out[..., 1] = -0.10 * r - 1.0 * g - 0.10 * b + 0.10 * np.tanh(g)
    out[..., 2] = -0.05 * r - 0.15 * g - 1.0 * b + 0.10 * np.tanh(b)
    return out


def main() -> int:
    try:
        import numpy as np
        from spektrafilm.utils.lut import compute_with_lut
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write(
            "ERROR: could not import spektrafilm.\n"
            f"  ({type(exc).__name__}: {exc})\n"
            "Run tools/parity/setup_env.sh then re-run with PYTHONPATH set "
            "(see module docstring).\n")
        return 2

    import spkvec  # local module

    rng = np.random.default_rng(SEED)
    h = w = SIZE
    xmin = np.asarray(XMIN, dtype=np.float64)
    xmax = np.asarray(XMAX, dtype=np.float64)

    # Data spanning [xmin, xmax] with a smooth ramp + random fill so the LUT is
    # exercised across the whole cube including interior cells.
    xx, yy = np.meshgrid(np.linspace(0.0, 1.0, w), np.linspace(0.0, 1.0, h))
    norm = np.empty((h, w, 3), dtype=np.float64)
    norm[..., 0] = xx
    norm[..., 1] = yy
    norm[..., 2] = 0.5 * (xx + yy)
    norm += (rng.random((h, w, 3)) - 0.5) * 0.05
    np.clip(norm, 0.0, 1.0, out=norm)  # stay inside the LUT domain
    data = xmin + norm * (xmax - xmin)

    # Direct (ground-truth) evaluation.
    direct = analytic_transform(data)

    # Oracle LUT path: build the LUT and apply it with the DEFAULT pchip method.
    lut_out, lut = compute_with_lut(
        data, analytic_transform, xmin=tuple(XMIN), xmax=tuple(XMAX), steps=STEPS)

    case_dir = GOLDENS_DIR / "lut_accel"
    case_dir.mkdir(parents=True, exist_ok=True)

    np.ascontiguousarray(norm, dtype=np.float64).tofile(case_dir / "input_norm.f64")
    np.ascontiguousarray(lut, dtype=np.float64).tofile(case_dir / "lut.f64")
    spkvec.write(case_dir / "direct.spkvec",
                 np.ascontiguousarray(direct, dtype=np.float32))
    spkvec.write(case_dir / "lut_out.spkvec",
                 np.ascontiguousarray(lut_out, dtype=np.float32))

    lut_vs_direct = float(np.max(np.abs(lut_out - direct)))
    manifest = {
        "case_id": "lut_accel",
        "stage": "LUT-accelerated density->log_xyz (scanner/enlarger), pchip 3D LUT",
        "image": {"size": SIZE, "shape": [SIZE, SIZE, 3], "seed": SEED,
                  "dtype_input": "float64 (normalized [0,1])"},
        "lut": {"steps": STEPS, "shape": [STEPS, STEPS, STEPS, 3], "method": "pchip"},
        "bounds": {"xmin": list(XMIN), "xmax": list(XMAX)},
        # Interpolator-parity tolerance: native PCHIP must reproduce the oracle's
        # PCHIP output very tightly (same algorithm, double precision; only float32
        # store + fp ordering differ).
        "tolerance_interp": {"max_abs": 1e-4, "rms": 1e-5},
        # Acceleration tolerance: LUT vs. direct. Inherently NON-bit-exact (it is an
        # interpolation); documented here for the opt-in path. NOT a gate.
        "lut_vs_direct_max_abs": lut_vs_direct,
        "spkvec_version": 1,
        "generator": "gen_lut_golden.py",
    }
    with open(case_dir / "manifest.json", "w") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)

    print(f"wrote {(case_dir / 'input_norm.f64').relative_to(HERE)}")
    print(f"wrote {(case_dir / 'lut.f64').relative_to(HERE)}  shape={lut.shape}")
    print(f"wrote {(case_dir / 'direct.spkvec').relative_to(HERE)}")
    print(f"wrote {(case_dir / 'lut_out.spkvec').relative_to(HERE)}")
    print(f"wrote {(case_dir / 'manifest.json').relative_to(HERE)}")
    print(f"LUT(pchip) vs direct max_abs = {lut_vs_direct:.6e} "
          f"(steps={STEPS}, opt-in acceleration tolerance)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
