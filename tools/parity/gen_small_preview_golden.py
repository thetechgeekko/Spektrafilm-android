#!/usr/bin/env python3
# Spektrafilm for Android — generate the focused small_preview anti-aliasing golden.
# Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
#
# ResizingService.small_preview(image, max_size=256) is literally
#   rescale(image, max_size / max(H, W), channel_axis=2, order=0)
# (spektrafilm/runtime/services/resize.py). For a float image whose long edge
# exceeds max_size, skimage 0.26 leaves anti_aliasing at its default (None) which
# resolves to True on a downscale and runs a scipy.ndimage.gaussian_filter
# prefilter (sigma = max(0, (in/out - 1)/2) per spatial axis, mode='mirror',
# truncate=4.0) BEFORE the nearest resample. This script pins that exact output so
# tests/test_small_preview_aa.cpp can prove the C++ small_preview reproduces it.
#
# This golden is STAGE-LOCAL (depends only on skimage, not the negative->print->
# scan pipeline), so unlike the end-to-end goldens it is independent of the
# spektrafilm oracle SHA. Regenerate with: python3 gen_small_preview_golden.py
import json
import pathlib
import sys

import numpy as np
from skimage.transform import rescale

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import spkvec  # noqa: E402  (local module, see spkvec_format.md)
from gen_goldens import make_test_image  # noqa: E402  (deterministic test image)

MAX_SIZE = 256
REPO = HERE.parent.parent
CPP_TESTS = REPO / "engine/spektra-core/src/main/cpp/tests"
GOLDEN_DIR = HERE / "goldens" / "small_preview_aa"


def main() -> None:
    # Non-square so the per-axis factor / sigma differ (catches an x/y swap):
    # H=200, W=384 -> scale 256/384 -> out 133x256, sigma_y != sigma_x.
    img = np.ascontiguousarray(make_test_image(384)[:200, :, :], dtype=np.float64)
    h, w = int(img.shape[0]), int(img.shape[1])
    assert max(h, w) > MAX_SIZE, "input long edge must exceed max_size"
    scale = MAX_SIZE / max(h, w)
    # The oracle's small_preview body, verbatim (anti_aliasing default -> True):
    out = rescale(img, scale, channel_axis=2, order=0)
    oh, ow = int(out.shape[0]), int(out.shape[1])

    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    # Input fixture: raw little-endian float64, row-major (H, W, 3), no header —
    # the same convention as tests/scan_portra_input_rgb.f64.
    (CPP_TESTS / "small_preview_aa_input_rgb.f64").write_bytes(
        np.ascontiguousarray(img, dtype="<f8").tobytes()
    )
    spkvec.write(GOLDEN_DIR / "preview_rgb.spkvec", out.astype(np.float32))

    manifest = {
        "case_id": "small_preview_aa",
        "generator": "gen_small_preview_golden.py",
        "stage_local": True,
        "skimage_version": __import__("skimage").__version__,
        "input": {"file": "tests/small_preview_aa_input_rgb.f64",
                  "dtype": "float64", "shape": [h, w, 3], "kind": "make_test_image(384)[:200]"},
        "call": "skimage.transform.rescale(image, %s/max(H,W), channel_axis=2, order=0)" % MAX_SIZE,
        "notes": (
            "ResizingService.small_preview(image, max_size=256). For a float "
            "downscale skimage defaults anti_aliasing=True and runs "
            "scipy.ndimage.gaussian_filter (sigma=max(0,(in/out-1)/2) per axis, "
            "mode='mirror') BEFORE the order=0 nearest resample. Gates the C++ "
            "small_preview AA prefilter (autoexposure.cpp) used by the metered EV."),
        "taps": {"preview_rgb": {"file": "preview_rgb.spkvec", "shape": [oh, ow, 3]}},
        "tolerance": {"max_abs": 1e-4, "rms": 1e-5},
    }
    (GOLDEN_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("input (%d,%d,3) -> preview (%d,%d,3) scale=%.6f" % (h, w, oh, ow, scale))


if __name__ == "__main__":
    main()
