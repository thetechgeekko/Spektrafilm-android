#!/usr/bin/env python3
# Spektrafilm for Android — generate the ACES RGC v1.3 output-gamut-compression
# parity golden (model/gamut_compression.cpp::compress_rgb_aces_rgc / reinhard_knee)
# from the upstream oracle utils/gamut_compression.py. GPLv3. Film modeling powered
# by spektrafilm.
#
# The oracle's per-channel ACES Reference Gamut Compression v1.3 IS the spec for the
# native port. These cases pin compress_rgb_aces_rgc over a spread of in/out-of-gamut
# linear-RGB pixels (negatives, achromatic, super-white, near-black) across several
# knee parameter triples, captured directly from the oracle.
#
# Binary format (little-endian):
#   int32 num_cases
#   per case: f64 threshold, f64 limit, f64 power, int32 npix,
#             f64 rgb_in[npix*3], f64 expected_out[npix*3]
# Regenerate: python3 tools/parity/gen_gamut_aces_golden.py
#
# Import note: gamut_compression.py imports `matplotlib.path` at module level (used
# only by the locus / oklch paths, NOT by aces_rgc/reinhard_knee). matplotlib is not
# installed in the parity env, but `colour` degrades gracefully without it — so we
# import colour FIRST (real), then shim a minimal matplotlib.path so the module's top
# level imports, and register the module in sys.modules before exec (its dataclass
# decorator introspects sys.modules under `from __future__ import annotations`).
import importlib.util
import pathlib
import struct
import sys
import types

import numpy as np
import colour  # noqa: F401  — real import FIRST; degrades gracefully without matplotlib

_mpl = types.ModuleType("matplotlib")
_mpl.__path__ = []
_mplp = types.ModuleType("matplotlib.path")
_mplp.Path = object
_mpl.path = _mplp
sys.modules["matplotlib"] = _mpl
sys.modules["matplotlib.path"] = _mplp

ORACLE = "/home/user/spektrafilm/src/spektrafilm/utils/gamut_compression.py"
_spec = importlib.util.spec_from_file_location("gamut_compression", ORACLE)
gc = importlib.util.module_from_spec(_spec)
sys.modules["gamut_compression"] = gc  # dataclass decorator needs it registered
_spec.loader.exec_module(gc)

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
OUT = REPO / "engine/spektra-core/src/main/cpp/tests/gamut_aces_cases.bin"
rng = np.random.default_rng(20260624)


def knees():
    yield (0.0, 1.0, 6.0)   # oracle production default
    yield (0.2, 1.0, 4.0)   # alt threshold + power
    yield (0.0, 1.2, 8.0)   # alt limit (asymptote past the cube edge)
    yield (0.1, 1.0, 2.0)   # gentle low-power knee


def rgb_blocks():
    # Hand-picked edge pixels: in-gamut, achromatic, single-negative, all-negative
    # (identity), exact black (identity), super-white, primaries, sub-1e-12 near-black.
    yield np.array([
        [1.2, -0.1, 0.3], [0.5, 0.5, 0.5], [2.0, 0.1, -0.5], [0.0, 0.0, 0.0],
        [-0.2, -0.3, -0.1], [1.5, 1.5, 1.5], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0],
        [0.0, 0.0, 1.0], [1e-13, 1e-14, 1e-15], [3.0, -1.0, -2.0], [0.8, 0.2, -0.05],
    ], dtype=float)
    # Random wide-gamut pixels (some out of [0,1], some negative channels).
    for _ in range(6):
        yield rng.uniform(-0.5, 2.0, size=(64, 3))


def main():
    recs = []
    for t, l, p in knees():
        for rgb in rgb_blocks():
            rgb = np.asarray(rgb, dtype=float).reshape(-1, 3)
            out = np.asarray(
                gc.compress_rgb_aces_rgc(rgb, threshold=t, limit=l, power=p),
                dtype=float,
            )
            recs.append((t, l, p, rgb, out))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "wb") as fh:
        fh.write(struct.pack("<i", len(recs)))
        for t, l, p, rgb, out in recs:
            fh.write(struct.pack("<ddd", t, l, p))
            fh.write(struct.pack("<i", rgb.shape[0]))
            fh.write(rgb.astype("<f8").tobytes())
            fh.write(out.astype("<f8").tobytes())
    npix = sum(r[3].shape[0] for r in recs)
    print("wrote %d cases (%d pixels) -> %s (%d bytes)"
          % (len(recs), npix, OUT.name, OUT.stat().st_size))


if __name__ == "__main__":
    main()
