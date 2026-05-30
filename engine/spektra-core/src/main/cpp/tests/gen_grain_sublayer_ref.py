#!/usr/bin/env python3
# Spektrafilm for Android - generate a statistical reference for the SUBLAYER
# film-grain particle model (model/grain.py::apply_grain_to_density_layers +
# add_micro_structure), exercised through apply_grain with sublayers_active=True
# (the real GrainParams default).
#
# Like the non-sublayer reference (gen_grain_ref.py), grain is stochastic: the
# Python (numpy/Numba) RNG stream and the C++ std::mt19937 stream differ, so an
# element-wise byte match is NOT the goal. We dump (a) S grainy realisations and
# (b) per-channel summary statistics that the C++ host test (test_grain_sublayer)
# reproduces within a statistical tolerance: grain is (approximately) mean-
# preserving and the injected-noise std must match the oracle in magnitude.
#
# The sublayer path differs from the non-sublayer path in BOTH the particle model
# (per-sublayer density via density_curves_layers + agx_particle_scale_layers, with
# per-particle dye-cloud blur) AND the added lognormal micro-structure clumping, so
# its noise character differs from the non-sublayer path. The test checks that too.
#
# Usage (from repo root):
#   PYTHONPATH=/home/user/spektrafilm/src:/tmp/spkstubs \
#     python3 engine/spektra-core/src/main/cpp/tests/gen_grain_sublayer_ref.py
#
# Outputs (committed, small):
#   tests/grain_sublayer_ref_density.spkvec   (S, npix, 3)  S grainy realisations
#   tests/grain_sublayer_ref_stats.json       digested params + per-channel stats
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "..", "..", "..",
                                "tools", "parity"))
import spkvec  # noqa: E402

import spektrafilm as sf  # noqa: E402
from spektrafilm.runtime.params_builder import digest_params  # noqa: E402
from spektrafilm.model.grain import apply_grain  # noqa: E402

# Realistic full-resolution pixel size (µm/px). At the 64px parity image's huge
# pixel_size_um (~547µm) the dye-cloud and micro-structure blurs are sub-pixel and
# the sublayer/non-sublayer noise stats coincide; at a realistic ~10µm/px the
# sublayer path's per-sublayer particle accounting produces a MEASURABLY smaller
# noise std (different noise character), which the C++ test verifies.
CHAR_PIXEL_SIZE_UM = 10.0
CHAR_SIZE = 128
CHAR_DENSITY = 0.8  # uniform mid-density patch

GOLDEN = os.path.join(HERE, "..", "..", "..", "..", "..", "..", "tools", "parity",
                      "goldens", "scan_portra", "film_density_cmy.spkvec")
SIZE = 64
FILM_FORMAT_MM = 35.0
PIXEL_SIZE_UM = FILM_FORMAT_MM * 1000.0 / SIZE  # resize_service.pixel_size_um
SEEDS = [0, 1, 2, 3, 4]  # 5 realisations for a stable empirical std estimate


def digested():
    p = sf.init_params("kodak_portra_400", "kodak_portra_endura")
    p.io.scan_film = True
    p.debug.deactivate_stochastic_effects = False
    p.debug.deactivate_spatial_effects = False
    p.film_render.grain.active = True
    p.film_render.grain.sublayers_active = True  # the real default (sublayer path)
    p.camera.auto_exposure = False
    p.camera.exposure_compensation_ev = 0.0
    d = digest_params(p)
    dc = np.asarray(d.film.data.density_curves)
    ndc = dc - np.nanmin(dc, axis=0)  # normalized density curves (apply_grain arg)
    dcl = np.asarray(d.film.data.density_curves_layers)  # RAW (N,3,3)
    return d.film_render.grain, ndc, dcl, d.film.info.type


def run_one(density_no_grain, grain, ndc, dcl, profile_type, seed_offset):
    # apply_grain mutates intermediate arrays; pass a fresh copy each run.
    dens = density_no_grain.copy().astype(np.float64)
    np.random.seed(1000 + seed_offset)
    out = apply_grain(
        dens,
        PIXEL_SIZE_UM,
        grain,
        ndc,
        dcl,
        profile_type,
    )
    return np.asarray(out, dtype=np.float64)


def main():
    golden = spkvec.read(GOLDEN)  # (npix,3) or (size,size,3) float32
    arr = np.asarray(golden, dtype=np.float64)
    npix = arr.size // 3
    smooth = arr.reshape(npix, 3)

    grain, ndc, dcl, profile_type = digested()

    grainy = np.stack([
        run_one(smooth.reshape(SIZE, SIZE, 3), grain, ndc, dcl, profile_type, s)
        .reshape(npix, 3)
        for s in SEEDS
    ], axis=0)  # (S, npix, 3)

    smooth_mean = smooth.mean(axis=0)              # (3,)
    grainy_mean = grainy.mean(axis=(0, 1))         # (3,)
    noise = grainy - smooth[None, :, :]            # (S, npix, 3)
    noise_std = noise.std(axis=1).mean(axis=0)     # (3,)

    out_density = os.path.join(HERE, "grain_sublayer_ref_density.spkvec")
    spkvec.write(out_density, grainy.astype(np.float32))

    stats = {
        "description": "grain statistical reference (sublayer apply_grain_to_density_layers + micro_structure)",
        "size": SIZE,
        "npix": int(npix),
        "n_realisations": len(SEEDS),
        "pixel_size_um": PIXEL_SIZE_UM,
        "params": {
            "sublayers_active": True,
            "agx_particle_area_um2": grain.agx_particle_area_um2,
            "agx_particle_scale": list(grain.agx_particle_scale),
            "agx_particle_scale_layers": list(grain.agx_particle_scale_layers),
            "density_min": list(grain.density_min),
            "density_max_layers": [[float(x) for x in row]
                                   for row in np.nanmax(dcl, axis=0)],
            "uniformity": list(grain.uniformity),
            "blur": grain.blur,
            "blur_dye_clouds_um": grain.blur_dye_clouds_um,
            "micro_structure": list(grain.micro_structure),
        },
        "stats": {
            "smooth_mean": [float(x) for x in smooth_mean],
            "grainy_mean": [float(x) for x in grainy_mean],
            "noise_std": [float(x) for x in noise_std],
            "mean_abs_dev": [float(abs(grainy_mean[c] - smooth_mean[c]))
                             for c in range(3)],
        },
    }
    # --- "Noise character" reference: uniform mid-density patch at a realistic
    #     pixel size, sublayer vs non-sublayer noise std. ---
    patch = np.ones((CHAR_SIZE, CHAR_SIZE, 3)) * CHAR_DENSITY

    def char_run(sublayers, seed):
        grain.sublayers_active = sublayers
        np.random.seed(seed)
        return np.asarray(
            apply_grain(patch.copy(), CHAR_PIXEL_SIZE_UM, grain, ndc, dcl,
                        profile_type),
            dtype=np.float64,
        )

    char_seeds = [10, 11, 12, 13, 14]
    sub_std = np.mean([
        (char_run(True, s) - patch).reshape(-1, 3).std(axis=0) for s in char_seeds
    ], axis=0)
    non_std = np.mean([
        (char_run(False, s) - patch).reshape(-1, 3).std(axis=0) for s in char_seeds
    ], axis=0)
    grain.sublayers_active = True  # restore

    stats["noise_character"] = {
        "char_pixel_size_um": CHAR_PIXEL_SIZE_UM,
        "char_size": CHAR_SIZE,
        "char_density": CHAR_DENSITY,
        "sublayer_noise_std": [float(x) for x in sub_std],
        "nonsublayer_noise_std": [float(x) for x in non_std],
        # relative gap = |sub/non - 1|; the sublayer path is clearly distinct.
        "rel_gap": [float(abs(sub_std[c] / non_std[c] - 1.0)) for c in range(3)],
    }

    out_stats = os.path.join(HERE, "grain_sublayer_ref_stats.json")
    with open(out_stats, "w") as fh:
        json.dump(stats, fh, indent=2)

    print("wrote", out_density, "shape", grainy.shape)
    print("wrote", out_stats)
    print("smooth_mean", smooth_mean)
    print("grainy_mean", grainy_mean)
    print("noise_std  ", noise_std)
    print("|dmean|    ", np.abs(grainy_mean - smooth_mean))
    print("char sub_std", sub_std, "non_std", non_std,
          "rel_gap", np.abs(sub_std / non_std - 1.0))


if __name__ == "__main__":
    main()
