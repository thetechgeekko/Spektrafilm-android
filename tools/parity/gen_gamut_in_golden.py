#!/usr/bin/env python3
# Spektrafilm for Android — generate the INPUT-gamut-compression parity golden
# (model/gamut_compression.cpp::spectral_locus_xy / compress_pixel_xy and
# kernels/spectral_upsampling.cpp::remap_tc_lut_for_compression) from the upstream
# oracle utils/gamut_compression.py. GPLv3. Film modeling powered by spektrafilm.
#
# The oracle's radial xy gamut compression toward the visible spectral locus
# (utils/gamut_compression.py::compress_xy_radial, the production algorithm="xy")
# and its tc_lut remap-resample bake (remap_tc_lut_for_compression) ARE the spec for
# the native input-side port. These cases pin, captured directly from the oracle:
#   1. spectral_locus_xy()  — the closed CIE-1931-2deg xy polygon (66 vertices).
#   2. compress_xy_radial()  — xy in -> xy out across in/out-of-locus + at-white
#      points and several knee triples, around several reference whites.
#   3. remap_tc_lut_for_compression()  — the full LUT bake (quad2tri -> compress ->
#      tri2quad -> bilinear edge-clamp resample) on synthetic tc_luts.
#
# Binary format (little-endian):
#   --- Section 1: spectral locus ---
#   int32 nverts ; f64 locus_xy[nverts*2]               (x0,y0,x1,y1,...)
#   --- Section 2: compress_xy_radial cases ---
#   int32 num_xy_cases
#   per case: f64 white_xy[2] ; f64 threshold,limit,power ; int32 npix ;
#             f64 xy_in[npix*2] ; f64 xy_out[npix*2]
#   --- Section 3: remap_tc_lut_for_compression cases ---
#   int32 num_lut_cases
#   per case: f64 white_xy[2] ; f64 threshold,limit,power ; int32 H ; int32 W ;
#             f64 lut_in[H*W*3] ; f64 lut_out[H*W*3]
# Regenerate: python3 tools/parity/gen_gamut_in_golden.py
#
# Import note: gamut_compression.py imports `matplotlib.path` at module level (used
# only by the oklch path, NOT by the xy radial path / reinhard_knee). matplotlib is
# not installed in the parity env, but `colour` degrades gracefully without it, so we
# import colour FIRST (real), then shim a minimal matplotlib.path so the module's top
# level imports, and register it in sys.modules before exec (its frozen @dataclass
# under `from __future__ import annotations` introspects sys.modules). Separately,
# remap_tc_lut_for_compression lazily does `from spektrafilm.utils.spectral_upsampling
# import _quad2tri, _tri2quad`; the real spectral_upsampling.py pulls in the whole
# spektrafilm package (which fails here on missing lensfunpy), so we shim that single
# submodule with the EXACT oracle _tri2quad/_quad2tri source (verified byte-identical
# at spectral_upsampling.py:42-61) — the bake math is therefore real-oracle.
import importlib.util
import pathlib
import struct
import sys
import types

import numpy as np
import colour  # noqa: F401  — real import FIRST; degrades gracefully without matplotlib

# --- shim 1: matplotlib.path (gamut_compression.py top-level import) ---
_mpl = types.ModuleType("matplotlib")
_mpl.__path__ = []
_mplp = types.ModuleType("matplotlib.path")
_mplp.Path = object
_mpl.path = _mplp
sys.modules["matplotlib"] = _mpl
sys.modules["matplotlib.path"] = _mplp

# --- shim 2: spektrafilm.utils.spectral_upsampling with the EXACT oracle
#     _tri2quad/_quad2tri (verified byte-identical to spectral_upsampling.py:42-61),
#     so remap_tc_lut_for_compression's lazy import resolves without dragging in the
#     full spektrafilm package (lensfunpy etc.). ---
def _tri2quad(tc):
    tc = np.array(tc)
    tx = tc[..., 0]
    ty = tc[..., 1]
    y = ty / np.fmax(1.0 - tx, 1e-10)
    x = (1.0 - tx) * (1.0 - tx)
    x = np.clip(x, 0, 1)
    y = np.clip(y, 0, 1)
    return np.stack((x, y), axis=-1)


def _quad2tri(xy):
    x = xy[..., 0]
    y = xy[..., 1]
    tx = 1 - np.sqrt(x)
    ty = y * np.sqrt(x)
    return np.stack((tx, ty), axis=-1)


_sf = types.ModuleType("spektrafilm")
_sf.__path__ = []
_sfu = types.ModuleType("spektrafilm.utils")
_sfu.__path__ = []
_sfsu = types.ModuleType("spektrafilm.utils.spectral_upsampling")
_sfsu._tri2quad = _tri2quad
_sfsu._quad2tri = _quad2tri
_sfu.spectral_upsampling = _sfsu
_sf.utils = _sfu
sys.modules["spektrafilm"] = _sf
sys.modules["spektrafilm.utils"] = _sfu
sys.modules["spektrafilm.utils.spectral_upsampling"] = _sfsu

ORACLE = "/home/user/spektrafilm/src/spektrafilm/utils/gamut_compression.py"
_spec = importlib.util.spec_from_file_location("gamut_compression", ORACLE)
gc = importlib.util.module_from_spec(_spec)
sys.modules["gamut_compression"] = gc  # dataclass decorator needs it registered
_spec.loader.exec_module(gc)

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
OUT = REPO / "engine/spektra-core/src/main/cpp/tests/gamut_in_cases.bin"
rng = np.random.default_rng(20260624)


def whites():
    # Reference illuminant chromaticities the compression operates around. D55 is the
    # film reference illuminant the oracle uses; D65 and E (equal-energy) exercise the
    # function around other interior whites. All are inside the locus so every ray hits.
    ill = colour.CCS_ILLUMINANTS["CIE 1931 2 Degree Standard Observer"]
    yield np.asarray(ill["D55"], dtype=float)
    yield np.asarray(ill["D65"], dtype=float)
    yield np.array([1.0 / 3.0, 1.0 / 3.0], dtype=float)


def knees():
    yield (0.0, 1.0, 6.0)   # oracle production default (full-range soft roll-off)
    yield (0.2, 1.0, 4.0)   # alt threshold + power (leaves an identity region)
    yield (0.0, 1.2, 8.0)   # alt limit (asymptote past the locus boundary)


def xy_blocks(white_xy):
    # Hand-picked edge points: at-white (passthrough), near-white, in-locus, several
    # out-of-locus, and the sRGB primaries' chromaticities (well outside for D55).
    hand = np.array([
        [white_xy[0], white_xy[1]],          # exactly at white -> dist<1e-9 passthrough
        [white_xy[0] + 1e-12, white_xy[1]],  # sub-1e-9 from white -> still passthrough
        [0.45, 0.40], [0.30, 0.35], [0.25, 0.25],   # interior
        [0.64, 0.33], [0.30, 0.60], [0.15, 0.06],   # sRGB R/G/B primaries
        [0.70, 0.25], [0.10, 0.80], [0.05, 0.05], [0.08, 0.55],  # out-of-locus
        [0.6, 0.38], [0.2, 0.7], [0.5, 0.45],
    ], dtype=float)
    yield hand
    # Random chromaticities over a box spanning the locus + a margin outside it.
    for _ in range(4):
        x = rng.uniform(0.0, 0.75, size=64)
        y = rng.uniform(0.0, 0.85, size=64)
        yield np.stack([x, y], axis=-1)


def lut_blocks():
    # Synthetic tc_luts exercising the full bake (quad2tri -> compress -> tri2quad ->
    # bilinear edge-clamp). Values are arbitrary positive floats (the bake is a pure
    # coordinate remap + resample; magnitudes are irrelevant to the math).
    # (1) small random
    yield rng.uniform(0.0, 4.0, size=(16, 16, 3))
    # (2) smooth separable ramp (catches interpolation-weight / axis-order bugs)
    H = W = 24
    ii = np.linspace(0.0, 1.0, H)[:, None, None]
    jj = np.linspace(0.0, 1.0, W)[None, :, None]
    ch = np.array([1.0, 2.0, 3.0])[None, None, :]
    yield (ii * 0.7 + jj * 0.3 + 0.1) * ch
    # (3) tiny hand-tuned grid (asymmetric in i vs j to catch row/col swaps)
    g = np.zeros((4, 5, 3))
    for i in range(4):
        for j in range(5):
            g[i, j] = [i + 0.1 * j, 10.0 - i + j, 0.5 * i * j + 1.0]
    yield g
    # (4) a 32x32 random at a non-default knee handled by the case loop
    yield rng.uniform(0.0, 1.0, size=(32, 32, 3))


def main():
    # --- Section 1: spectral locus ---
    locus = np.asarray(gc.spectral_locus_xy(), dtype=float)
    assert locus.ndim == 2 and locus.shape[1] == 2, locus.shape
    nverts = locus.shape[0]

    # --- Section 2: compress_xy_radial cases ---
    xy_recs = []
    for white_xy in whites():
        for (t, l, p) in knees():
            for xy in xy_blocks(white_xy):
                xy = np.asarray(xy, dtype=float).reshape(-1, 2)
                out = np.asarray(
                    gc.compress_xy_radial(
                        xy, white_xy, threshold=t, limit=l, power=p),
                    dtype=float,
                ).reshape(-1, 2)
                xy_recs.append((white_xy, t, l, p, xy, out))

    # --- Section 3: remap_tc_lut_for_compression cases ---
    lut_recs = []
    d55 = next(whites())
    knee_list = list(knees())
    for bi, lut in enumerate(lut_blocks()):
        lut = np.asarray(lut, dtype=float)
        t, l, p = knee_list[bi % len(knee_list)]
        spec = gc.InputGamutCompressSpec(active=True, algorithm="xy", knee=(t, l, p))
        out = np.asarray(
            gc.remap_tc_lut_for_compression(lut, d55, spec),
            dtype=float,
        )
        assert out.shape == lut.shape, (out.shape, lut.shape)
        lut_recs.append((d55, t, l, p, lut, out))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "wb") as fh:
        # Section 1
        fh.write(struct.pack("<i", nverts))
        fh.write(locus.astype("<f8").tobytes())
        # Section 2
        fh.write(struct.pack("<i", len(xy_recs)))
        for white_xy, t, l, p, xy, out in xy_recs:
            fh.write(np.asarray(white_xy, dtype="<f8").tobytes())
            fh.write(struct.pack("<ddd", t, l, p))
            fh.write(struct.pack("<i", xy.shape[0]))
            fh.write(xy.astype("<f8").tobytes())
            fh.write(out.astype("<f8").tobytes())
        # Section 3
        fh.write(struct.pack("<i", len(lut_recs)))
        for white_xy, t, l, p, lin, lout in lut_recs:
            fh.write(np.asarray(white_xy, dtype="<f8").tobytes())
            fh.write(struct.pack("<ddd", t, l, p))
            fh.write(struct.pack("<ii", lin.shape[0], lin.shape[1]))
            fh.write(lin.astype("<f8").tobytes())
            fh.write(lout.astype("<f8").tobytes())

    n_xy = sum(r[4].shape[0] for r in xy_recs)
    print("wrote locus (%d verts) + %d xy-cases (%d points) + %d lut-cases -> %s (%d bytes)"
          % (nverts, len(xy_recs), n_xy, len(lut_recs), OUT.name, OUT.stat().st_size))
    # Re-emit the locus as a C++ constant block on stdout (the source of
    # model/gamut_compression.cpp's kSpectralLocusXy); pipe to a file to re-embed if
    # the colour-science CMF dataset ever changes. Kept off-disk so no dev artifact
    # lands in the repo (the constant lives in the .cpp; this is provenance only).
    if "--dump-locus" in sys.argv:
        print("static const double kSpectralLocusXy[%d][2] = {" % nverts)
        for (x, y) in locus:
            print("    {%s, %s}," % (repr(float(x)), repr(float(y))))
        print("};")
    # Echo a few values so the run log shows real numbers (sanity).
    print("locus[0]=%r locus[-1]=%r (closed=%s)"
          % (tuple(locus[0]), tuple(locus[-1]), bool(np.allclose(locus[0], locus[-1]))))
    print("D55=%r" % (tuple(d55),))


if __name__ == "__main__":
    main()
