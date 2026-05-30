# Spektrafilm for Android — golden-vector parity harness.
# Copyright (C) 2026 Spektrafilm Android contributors.
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See <https://www.gnu.org/licenses/>.
#
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
# spektrafilm.
"""Reader/writer for the portable ``.spkvec`` golden-vector container.

The on-disk format is specified in ``spkvec_format.md``. It stores exactly one
dense ``float32`` array, little-endian, C-contiguous. This module is the canonical
NumPy implementation used by ``gen_goldens.py`` and for ad-hoc inspection; the C++
counterpart lives in ``spkvec_io.h``.
"""

from __future__ import annotations

import struct
from pathlib import Path

import numpy as np

MAGIC = b"SPKVEC"
VERSION = 1

# dtype code -> NumPy dtype. Only float32 is defined today (see spkvec_format.md).
_DTYPE_CODES = {1: np.dtype("<f4")}
_DTYPE_TO_CODE = {np.dtype("float32"): 1}

_MAX_NDIM = 8


def write(path: str | Path, array: np.ndarray) -> Path:
    """Write ``array`` to ``path`` as a ``.spkvec`` file. Returns the path.

    The array is cast to little-endian ``float32`` and C-contiguous order before
    writing so output is deterministic and byte-stable for unchanged inputs.
    """
    arr = np.ascontiguousarray(np.asarray(array, dtype="<f4"))
    if arr.ndim < 1 or arr.ndim > _MAX_NDIM:
        raise ValueError(f"ndim must be in 1..{_MAX_NDIM}, got {arr.ndim}")

    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(MAGIC)
        fh.write(struct.pack("<H", VERSION))
        fh.write(struct.pack("<B", _DTYPE_TO_CODE[np.dtype("float32")]))
        fh.write(struct.pack("<B", arr.ndim))
        for dim in arr.shape:
            fh.write(struct.pack("<I", int(dim)))
        fh.write(arr.tobytes(order="C"))
    return path


def read(path: str | Path) -> np.ndarray:
    """Read a ``.spkvec`` file and return it as a ``float32`` NumPy array."""
    path = Path(path)
    with open(path, "rb") as fh:
        blob = fh.read()

    if blob[:6] != MAGIC:
        raise ValueError(f"{path}: not a spkvec file (bad magic)")
    off = 6
    (version,) = struct.unpack_from("<H", blob, off)
    off += 2
    if version != VERSION:
        raise ValueError(f"{path}: unsupported version {version}")
    (dtype_code,) = struct.unpack_from("<B", blob, off)
    off += 1
    if dtype_code not in _DTYPE_CODES:
        raise ValueError(f"{path}: unknown dtype code {dtype_code}")
    (ndim,) = struct.unpack_from("<B", blob, off)
    off += 1
    if ndim < 1 or ndim > _MAX_NDIM:
        raise ValueError(f"{path}: bad ndim {ndim}")

    shape = []
    for _ in range(ndim):
        (dim,) = struct.unpack_from("<I", blob, off)
        off += 4
        shape.append(dim)
    shape = tuple(shape)

    dtype = _DTYPE_CODES[dtype_code]
    count = int(np.prod(shape)) if shape else 0
    expected = count * dtype.itemsize
    payload = blob[off:]
    if len(payload) != expected:
        raise ValueError(
            f"{path}: payload size mismatch (got {len(payload)} bytes, "
            f"expected {expected} for shape {shape})"
        )
    return np.frombuffer(payload, dtype=dtype).reshape(shape).astype(np.float32)


def _cli() -> None:
    """Tiny CLI: ``python spkvec.py info FILE`` prints header + basic stats."""
    import argparse

    parser = argparse.ArgumentParser(description="Inspect a .spkvec file")
    parser.add_argument("cmd", choices=["info"])
    parser.add_argument("path")
    args = parser.parse_args()

    arr = read(args.path)
    finite = np.isfinite(arr)
    print(f"path  : {args.path}")
    print(f"shape : {arr.shape}")
    print(f"dtype : float32")
    print(f"count : {arr.size}")
    if finite.any():
        vals = arr[finite]
        print(f"min   : {vals.min():.6g}")
        print(f"max   : {vals.max():.6g}")
        print(f"mean  : {vals.mean():.6g}")
    if not finite.all():
        print(f"WARNING: {(~finite).sum()} non-finite values")


if __name__ == "__main__":
    _cli()
