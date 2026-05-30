# Spektrafilm for Android — oracle reference generator for scanning output
# color spaces.
# Copyright (C) 2026 Spektrafilm Android contributors.
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See <https://www.gnu.org/licenses/>.
#
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
# spektrafilm.
#
# Reproduces spektrafilm.runtime.stages.scanning for the scan_film route on the
# committed scan_portra golden input (film_density_cmy.spkvec), once per
# io.output_color_space, and writes tests/scan_portra_ref_<space>.spkvec.
#
# These are the references test_output_spaces.cpp compares the C++ scan() output
# against (tol max_abs <= 1e-4, rms <= 1e-5). Adobe RGB intentionally carries
# NaN at gamut excursions (gamma encode of negative linear RGB), preserved by
# np.clip — the C++ matches this exactly.
#
# Usage (oracle env):
#   PYTHONPATH=/home/user/spektrafilm/src:/tmp/spkstubs:\
#       /home/user/Spectrafilmandroid/tools/parity \
#     python3 engine/spektra-core/src/main/cpp/tests/gen_output_spaces_ref.py
from __future__ import annotations

import json
from pathlib import Path

import colour
import numpy as np
from opt_einsum import contract

import spkvec  # tools/parity/spkvec.py

from spektrafilm.config import STANDARD_OBSERVER_CMFS
from spektrafilm.model.emulsion import compute_density_spectral
from spektrafilm.model.illuminants import standard_illuminant
from spektrafilm.utils.conversions import density_to_light

HERE = Path(__file__).resolve().parent
GOLDEN = Path("/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra")
PROFILE = Path(
    "/home/user/spektrafilm/src/spektrafilm/data/profiles/kodak_portra_400.json"
)

# (colour colourspace name, output_cctf_encoding, reference label / spk enum).
SPACES = [
    ("sRGB", True, "srgb"),               # SPK_CS_SRGB
    ("Adobe RGB (1998)", True, "adobe_rgb"),  # SPK_CS_ADOBE_RGB
    ("ProPhoto RGB", True, "prophoto"),   # SPK_CS_PROPHOTO
    ("ITU-R BT.2020", True, "rec2020"),   # SPK_CS_REC2020
    ("ACES2065-1", True, "aces2065_1"),   # SPK_CS_ACES2065_1
    ("sRGB", False, "linear_srgb"),       # SPK_CS_LINEAR_SRGB (sRGB primaries, CCTF off)
]


def main() -> int:
    dcmy = np.ascontiguousarray(
        spkvec.read(GOLDEN / "film_density_cmy.spkvec"), dtype=np.float32
    )
    prof = json.loads(PROFILE.read_text())
    channel_density = np.asarray(prof["data"]["channel_density"], dtype=np.float64)
    base_density = np.asarray(prof["data"]["base_density"], dtype=np.float64)
    viewing = prof["info"]["viewing_illuminant"]

    scan_illuminant = standard_illuminant(viewing)
    normalization = np.sum(scan_illuminant * STANDARD_OBSERVER_CMFS[:, 1], axis=0)

    # _density_to_rgb up to XYZ (scan_film: no black/white correction, glare None).
    density_spectral = compute_density_spectral(channel_density, dcmy, base_density)
    light = density_to_light(density_spectral, scan_illuminant)
    xyz = contract("ijk,kl->ijl", light, STANDARD_OBSERVER_CMFS[:]) / normalization
    xyz = 10 ** np.log10(np.fmax(xyz, 0.0) + 1e-10)

    illuminant_xyz = (
        contract("k,kl->l", scan_illuminant, STANDARD_OBSERVER_CMFS[:]) / normalization
    )
    illuminant_xy = colour.XYZ_to_xy(illuminant_xyz)

    print(f"profile viewing_illuminant={viewing} illuminant_xy={illuminant_xy.tolist()}")
    for name, cctf, label in SPACES:
        rgb = colour.XYZ_to_RGB(
            xyz, colourspace=name, apply_cctf_encoding=False, illuminant=illuminant_xy
        )
        if cctf:
            rgb = colour.RGB_to_RGB(
                rgb, name, name, apply_cctf_decoding=False, apply_cctf_encoding=True
            )
        rgb = np.clip(rgb, 0, 1)
        out = np.ascontiguousarray(rgb.astype(np.float32))
        spkvec.write(HERE / f"scan_portra_ref_{label}.spkvec", out)
        finite = out[np.isfinite(out)]
        print(
            f"  {label:12s} cctf={cctf!s:5s} nans={int(np.isnan(out).sum()):4d} "
            f"min={finite.min():.6f} max={finite.max():.6f}"
        )
    print("DONE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
