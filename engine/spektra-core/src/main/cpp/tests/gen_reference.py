#!/usr/bin/env python3
# Spektrafilm for Android - generate reference spectra for the Hanatos2025
# RGB->spectrum upsampling C++ parity test.
#
# Runs the real spektrafilm engine as an oracle and dumps reference spectra for a
# handful of fixed linear RGB inputs into spectral_upsampling_ref.spkvec, the
# committed reference the C++ host test compares against.
#
# The reference path is exactly the project's canonical cubic 2D LUT interpolator
# (utils.fast_interp_lut.cubic_interp_lut_at_2d, the per-pixel core of
# apply_lut_cubic_2d used by rgb_to_raw_hanatos2025) applied to the Hanatos2025
# spectra LUT (irradiance_xy_tc.npy, 192x192x81), preceded by the exact
# _rgb_to_tc_b coordinate mapping and followed by the *b irradiance rescale.
#
# Usage (from repo root):
#   PYTHONPATH=/home/user/spektrafilm/src:/tmp/spkstubs \
#     python3 engine/spektra-core/src/main/cpp/tests/gen_reference.py
#
# Output rows are in lockstep with kTestRgbs[] in test_spectral_upsampling.cpp.

import os
import sys
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "..",
                                "..", "..", "tools", "parity"))
import spkvec  # noqa: E402

from spektrafilm.utils.spectral_upsampling import (  # noqa: E402
    HANATOS2025_SPECTRA_LUT,
    _rgb_to_tc_b,
)
from spektrafilm.utils.fast_interp_lut import cubic_interp_lut_at_2d  # noqa: E402

RGBS = [
    [0.5, 0.5, 0.5],
    [0.18, 0.18, 0.18],
    [0.9, 0.1, 0.1],
    [0.1, 0.8, 0.1],
    [0.1, 0.1, 0.9],
    [0.0, 0.0, 0.0],
]


def upsample(rgb):
    lut = np.ascontiguousarray(HANATOS2025_SPECTRA_LUT)
    tc, b = _rgb_to_tc_b(
        np.asarray(rgb, dtype=float),
        color_space="ITU-R BT.2020",
        apply_cctf_decoding=False,
        reference_illuminant="D55",
    )
    scale = lut.shape[0] - 1
    spec = cubic_interp_lut_at_2d(lut, tc[0] * scale, tc[1] * scale) * b
    return np.asarray(spec, dtype=np.float64)


def main():
    out = np.stack([upsample(rgb) for rgb in RGBS], axis=0).astype(np.float32)
    ref_path = os.path.join(os.path.dirname(__file__), "spectral_upsampling_ref.spkvec")
    spkvec.write(ref_path, out)
    print("wrote", ref_path, "shape", out.shape)


if __name__ == "__main__":
    main()
