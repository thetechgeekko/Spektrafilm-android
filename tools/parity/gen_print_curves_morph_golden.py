#!/usr/bin/env python3
# Spektrafilm for Android — generate the print-curve morph (s023) parity golden.
# Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
#
# Loads the REAL upstream utils/morph_curves.py::apply_print_curves_morph (identical
# at oracle 27bd085 and merged main 3bb2c2d) and runs it on a bundled print-paper
# profile's density_curves_model with a NON-identity morph (coupled gamma across
# band/channel + developer exhaustion, which exercises the Gumbel blend and the
# brentq D(0)-preserving center offset). The result pins what the C++
# apply_print_curves_morph (model/morph_curves.cpp) must reproduce.
#
# Stage-local: depends only on morph_curves.py + numpy/scipy, not the full pipeline,
# so it does not require the heavy spektrafilm package import (lensfunpy etc.).
# Regenerate: python3 gen_print_curves_morph_golden.py
import importlib.util
import json
import pathlib
import sys
import types
from dataclasses import dataclass, field

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
ORACLE_MORPH = "/home/user/spektrafilm/src/spektrafilm/utils/morph_curves.py"
PROFILE = REPO / "engine/spektra-core/src/main/assets/spektra/profiles/kodak_portra_endura.json"
GOLDEN_DIR = HERE / "goldens" / "print_curves_morph"

# Non-identity morph params used for the golden (also hardcoded in the C++ test).
MORPH = dict(
    active=True,
    gamma_factor=1.15,
    gamma_factor_fast=0.90,
    gamma_factor_slow=1.05,
    gamma_factor_red=1.08,
    gamma_factor_green=0.97,
    gamma_factor_blue=1.03,
    developer_exhaustion=0.35,
)


@dataclass
class DensityCurvesModel:
    """Minimal stand-in for spektrafilm.profiles.io.DensityCurvesModel."""
    model_type: str = "cdfs"
    centers: np.ndarray = field(default_factory=lambda: np.empty((0, 0)))
    amplitudes: np.ndarray = field(default_factory=lambda: np.empty((0, 0)))
    sigmas: np.ndarray = field(default_factory=lambda: np.empty((0, 0)))

    def __post_init__(self):
        self.centers = np.asarray(self.centers, dtype=float)
        self.amplitudes = np.asarray(self.amplitudes, dtype=float)
        self.sigmas = np.asarray(self.sigmas, dtype=float)

    @property
    def n_channels(self):
        return self.centers.shape[0] if self.centers.ndim == 2 else 0

    @property
    def n_layers(self):
        return self.centers.shape[1] if self.centers.ndim == 2 else 0


def _load_oracle_morph():
    # Inject stub package modules so morph_curves.py's
    # `from spektrafilm.profiles.io import DensityCurvesModel` resolves to our stub
    # without triggering the heavy real spektrafilm package __init__.
    pkg = types.ModuleType("spektrafilm"); pkg.__path__ = []
    prof = types.ModuleType("spektrafilm.profiles"); prof.__path__ = []
    io = types.ModuleType("spektrafilm.profiles.io")
    io.DensityCurvesModel = DensityCurvesModel
    sys.modules.setdefault("spektrafilm", pkg)
    sys.modules.setdefault("spektrafilm.profiles", prof)
    sys.modules.setdefault("spektrafilm.profiles.io", io)
    spec = importlib.util.spec_from_file_location("oracle_morph_curves", ORACLE_MORPH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    mc = _load_oracle_morph()
    prof = json.load(open(PROFILE))
    ptype = prof["info"]["type"]                     # "negative" | "positive"
    m = prof["data"]["density_curves_model"]
    model = DensityCurvesModel(
        model_type=m.get("model_type", "cdfs"),
        centers=np.asarray(m["centers"], dtype=float),
        amplitudes=np.asarray(m["amplitudes"], dtype=float),
        sigmas=np.asarray(m["sigmas"], dtype=float),
    )
    # The C++ engine stores log_exposure as float32; cast so the axis matches.
    log_exposure = np.asarray(prof["data"]["log_exposure"], dtype=np.float32).astype(np.float64)

    params = mc.PrintCurvesMorphParams(**MORPH)
    morphed = mc.apply_print_curves_morph(
        log_exposure, model, params, profile_type=ptype
    )  # (N, n_channels)

    n, nch = morphed.shape
    sys.path.insert(0, str(HERE))
    import spkvec  # noqa: E402  (local module, see spkvec_format.md)
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    spkvec.write(GOLDEN_DIR / "print_density.spkvec", morphed.astype(np.float32))
    manifest = {
        "case_id": "print_curves_morph",
        "generator": "gen_print_curves_morph_golden.py",
        "stage_local": True,
        "profile": "kodak_portra_endura",
        "profile_type": ptype,
        "n_layers": int(model.n_layers),
        "morph_params": MORPH,
        "tap": {"file": "print_density.spkvec", "shape": [int(n), int(nch)]},
        "notes": ("apply_print_curves_morph (s023 coupled-gamma print-curve morph) on "
                  "kodak_portra_endura density_curves_model with a non-identity morph. "
                  "Gates the C++ model/morph_curves.cpp opt-in port. Default-off keeps "
                  "the print parity goldens byte-identical."),
        "tolerance": {"max_abs": 1e-4, "rms": 1e-5},
    }
    (GOLDEN_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("type=%s n_layers=%d -> morphed (%d,%d)  range[%.4f,%.4f]" % (
        ptype, model.n_layers, n, nch, float(morphed.min()), float(morphed.max())))


if __name__ == "__main__":
    main()
