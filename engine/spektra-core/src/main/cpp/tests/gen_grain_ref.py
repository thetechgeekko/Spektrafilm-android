#!/usr/bin/env python3
# SpectraFilm for Android - generate a statistical reference for the film-grain
# particle model (model/grain.py::apply_grain_to_density, the non-sublayer path).
#
# Grain is stochastic: even with a fixed seed the Python (numpy/Numba) RNG stream
# and the C++ std::mt19937 stream differ, so an element-wise byte match is NOT the
# goal. Instead we dump (a) one grainy density realisation per fixed seed, and (b)
# per-channel summary statistics that the C++ host test reproduces within a
# statistical tolerance: grain is mean-preserving, and the std-dev of the injected
# noise (grainy - smooth) must match in magnitude.
#
# The grain operates on the no-grain film_density_cmy (the committed golden
# goldens/scan_portra/film_density_cmy.spkvec) using the digested kodak_portra_400
# grain params with sublayers DISABLED (the path ported in C++). The sublayer +
# micro-structure path (apply_grain_to_density_layers) is intentionally deferred.
#
# Usage (from repo root):
#   PYTHONPATH=/home/user/spektrafilm/src:/tmp/spkstubs \
#     python3 engine/spektra-core/src/main/cpp/tests/gen_grain_ref.py
#
# Outputs (committed, small):
#   tests/grain_ref_density.spkvec   (S, npix, 3)  S grainy realisations
#   tests/grain_ref_stats.json       digested params + per-channel stats
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
from spektrafilm.model.grain import apply_grain_to_density  # noqa: E402

GOLDEN = os.path.join(HERE, "..", "..", "..", "..", "..", "..", "tools", "parity",
                      "goldens", "scan_portra", "film_density_cmy.spkvec")
SIZE = 64
FILM_FORMAT_MM = 35.0
PIXEL_SIZE_UM = FILM_FORMAT_MM * 1000.0 / SIZE  # resize_service.pixel_size_um
SEEDS = [0, 1, 2, 3, 4]  # 5 realisations for a stable empirical std estimate


def digested_grain():
    p = sf.init_params("kodak_portra_400", "kodak_portra_endura")
    p.io.scan_film = True
    p.debug.deactivate_stochastic_effects = False
    p.debug.deactivate_spatial_effects = False
    p.film_render.grain.active = True
    p.film_render.grain.sublayers_active = False  # non-sublayer path (ported in C++)
    p.camera.auto_exposure = False
    p.camera.exposure_compensation_ev = 0.0
    d = digest_params(p)
    dc = np.asarray(d.film.data.density_curves)
    ndc = dc - np.nanmin(dc, axis=0)
    density_max_curves = np.nanmax(ndc, axis=0)
    return d.film_render.grain, density_max_curves


def run_one(density_no_grain, grain, density_max_curves, seed_offset):
    # apply_grain_to_density mutates its input (density_cmy += density_min); pass a copy.
    dens = density_no_grain.copy().astype(np.float64)
    # The Python path uses a fixed per-channel seed list [0,1,2] (+ sublayer*10);
    # to obtain independent realisations we add a global offset before each run.
    np.random.seed(1000 + seed_offset)
    out = apply_grain_to_density(
        dens,
        pixel_size_um=PIXEL_SIZE_UM,
        agx_particle_area_um2=grain.agx_particle_area_um2,
        agx_particle_scale=list(grain.agx_particle_scale),
        density_min=list(grain.density_min),
        density_max_curves=list(density_max_curves),
        grain_uniformity=list(grain.uniformity),
        grain_blur=grain.blur,
        n_sub_layers=grain.n_sub_layers,
    )
    return out.astype(np.float64)


def main():
    golden = spkvec.read(GOLDEN)  # (npix, 3) or (size,size,3) float32
    arr = np.asarray(golden, dtype=np.float64)
    npix = arr.size // 3
    smooth = arr.reshape(npix, 3)

    grain, density_max_curves = digested_grain()

    grainy = np.stack([
        run_one(smooth.reshape(SIZE, SIZE, 3), grain, density_max_curves, s).reshape(npix, 3)
        for s in SEEDS
    ], axis=0)  # (S, npix, 3)

    # Per-channel statistics, averaged over the S realisations.
    smooth_mean = smooth.mean(axis=0)                       # (3,)
    grainy_mean = grainy.mean(axis=(0, 1))                  # (3,)
    # Noise std: std over space of (grainy - smooth), averaged over realisations.
    noise = grainy - smooth[None, :, :]                     # (S, npix, 3)
    noise_std = noise.std(axis=1).mean(axis=0)              # (3,)

    out_density = os.path.join(HERE, "grain_ref_density.spkvec")
    spkvec.write(out_density, grainy.astype(np.float32))

    stats = {
        "description": "grain statistical reference (non-sublayer apply_grain_to_density)",
        "size": SIZE,
        "npix": int(npix),
        "n_realisations": len(SEEDS),
        "pixel_size_um": PIXEL_SIZE_UM,
        "params": {
            "agx_particle_area_um2": grain.agx_particle_area_um2,
            "agx_particle_scale": list(grain.agx_particle_scale),
            "density_min": list(grain.density_min),
            "density_max_curves": [float(x) for x in density_max_curves],
            "uniformity": list(grain.uniformity),
            "blur": grain.blur,
            "n_sub_layers": grain.n_sub_layers,
        },
        "stats": {
            "smooth_mean": [float(x) for x in smooth_mean],
            "grainy_mean": [float(x) for x in grainy_mean],
            "noise_std": [float(x) for x in noise_std],
        },
    }
    out_stats = os.path.join(HERE, "grain_ref_stats.json")
    with open(out_stats, "w") as fh:
        json.dump(stats, fh, indent=2)

    print("wrote", out_density, "shape", grainy.shape)
    print("wrote", out_stats)
    print("smooth_mean", smooth_mean)
    print("grainy_mean", grainy_mean)
    print("noise_std  ", noise_std)


if __name__ == "__main__":
    main()
