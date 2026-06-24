#!/usr/bin/env python3
# Spektrafilm for Android — generate the numpy.interp parity golden for the
# DIR-coupler non-monotonic interpolation (model/couplers.cpp::np_interp_array).
# Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
#
# np.interp IS the oracle for the coupler interpolation. Its result on a
# NON-MONOTONIC xp depends on numpy's order-dependent binary_search_with_guess, so
# these cases (ascending query x, non-monotonic xp — exactly the coupler shape
# le0 = le - silver@M) pin what the C++ np_interp_array must reproduce. Cases are
# written as a flat little-endian binary:
#   int32 num_cases
#   per case: int32 n, int32 nx, f64 xp[n], f64 fp[n], f64 x[nx], f64 expected[nx]
# Regenerate: python3 gen_np_interp_golden.py
import pathlib
import struct

import numpy as np

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
OUT = REPO / "engine/spektra-core/src/main/cpp/tests/np_interp_cases.bin"
rng = np.random.default_rng(20260624)


def cases():
    # 1) the review's worked example (non-monotonic dip at high density)
    yield (np.array([0.0, 0.2, 0.4, 0.6, 0.55, 0.5, 0.45, 1.0]),
           np.array([0.0, 5.0, 10.0, 20.0, 30.0, 17.0, 25.0, 40.0]),
           np.linspace(0.0, 1.0, 21))
    # 2) randomized: ascending query x, non-monotonic xp (cumsum of signed steps),
    #    varying lengths to hit the len<=4 linear path, the guess 3-neighbour
    #    checks, the LIKELY_IN_CACHE restrict, and out-of-range clamps.
    for _ in range(80):
        n = int(rng.integers(2, 48))
        nx = int(rng.integers(2, 70))
        xp = np.cumsum(rng.uniform(-0.35, 0.5, size=n)) + rng.uniform(-1, 1)
        fp = rng.uniform(-6, 6, size=n)
        # ascending queries spanning a bit beyond [min,max] to exercise clamping
        lo, hi = float(min(xp.min(), 0)) - 0.3, float(max(xp.max(), 0)) + 0.3
        x = np.sort(rng.uniform(lo, hi, size=nx))
        yield xp.astype(float), fp.astype(float), x.astype(float)
    # 3) coincident-node + duplicate-x edge cases
    yield (np.array([0.0, 0.5, 0.5, 1.0]), np.array([0.0, 2.0, 9.0, 4.0]),
           np.array([-1.0, 0.25, 0.5, 0.75, 2.0]))


def main():
    recs = []
    for xp, fp, x in cases():
        expected = np.interp(x, xp, fp).astype("<f8")
        recs.append((xp.astype("<f8"), fp.astype("<f8"), x.astype("<f8"), expected))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "wb") as fh:
        fh.write(struct.pack("<i", len(recs)))
        for xp, fp, x, expected in recs:
            fh.write(struct.pack("<ii", len(xp), len(x)))
            fh.write(xp.tobytes()); fh.write(fp.tobytes())
            fh.write(x.tobytes()); fh.write(expected.tobytes())
    nonmono = sum(1 for xp, _, _, _ in recs if np.any(np.diff(xp) < 0))
    print("wrote %d cases (%d with non-monotonic xp) -> %s (%d bytes)"
          % (len(recs), nonmono, OUT.name, OUT.stat().st_size))


if __name__ == "__main__":
    main()
