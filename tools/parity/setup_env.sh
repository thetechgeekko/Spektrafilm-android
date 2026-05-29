#!/usr/bin/env bash
# Reproducible environment for generating spektrafilm golden vectors.
#
# spektrafilm's full install pulls GUI/RAW/IO deps (napari, rawpy, OpenImageIO, exiv2,
# lensfunpy) that the *math* path never touches. For golden generation we only need the
# numeric stack + a few stubs so `import spektrafilm` succeeds without those heavy libs.
#
# Usage:
#   export SPEKTRAFILM_SRC=/path/to/spektrafilm/src      # the package source root
#   source tools/parity/setup_env.sh
#   python tools/parity/gen_goldens.py --case scan_portra
#
# This is what produced the committed goldens under tools/parity/goldens/.
set -euo pipefail

: "${SPEKTRAFILM_SRC:=/home/user/spektrafilm/src}"

# 1) Real numeric dependencies the engine actually uses.
python3 -m pip install --quiet numba opt_einsum scikit-image exiv2 || {
  echo "pip install failed (offline?). Install: numba opt_einsum scikit-image exiv2" >&2
}

# 2) Stub the I/O/GUI deps that are imported but unused on the math path.
STUBS="$(mktemp -d /tmp/spkstubs.XXXXXX)"
cat > "$STUBS/lensfunpy.py" <<'PY'
# unused on the math path
PY
cat > "$STUBS/rawpy.py" <<'PY'
# minimal rawpy stub: RAW decode not exercised (we feed numpy arrays to simulate()).
class ColorSpace:
    raw=0; sRGB=1; Adobe=2; Wide=3; ProPhoto=4; XYZ=5; ACES=6; P3D65=7; Rec2020=8
def imread(*a, **k):
    raise RuntimeError("rawpy stub: RAW decode not available in parity env")
PY
cat > "$STUBS/OpenImageIO.py" <<'PY'
# OpenImageIO stub: colour-science + spektrafilm.utils.io import names here at load but
# only use them for file I/O, which the parity path never does. Return harmless dummies.
from unittest.mock import MagicMock as _MM
def __getattr__(name):
    return _MM(name=f"OpenImageIO.{name}")
PY

export PYTHONPATH="${SPEKTRAFILM_SRC}:${STUBS}${PYTHONPATH:+:${PYTHONPATH}}"
echo "Parity env ready."
echo "  SPEKTRAFILM_SRC = ${SPEKTRAFILM_SRC}"
echo "  stubs           = ${STUBS}"
echo "  PYTHONPATH      = ${PYTHONPATH}"
