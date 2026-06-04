#!/usr/bin/env bash
# Reproducible environment for generating spektrafilm golden vectors.
#
# spektrafilm's full install pulls GUI/RAW/IO deps (napari, rawpy, OpenImageIO, exiv2,
# lensfunpy) that the *math* path never touches. For golden generation we only need the
# numeric stack + a few stubs so `import spektrafilm` succeeds without those heavy libs.
#
# Usage:
#   git clone https://github.com/andreavolpato/spektrafilm /path/to/spektrafilm
#   git -C /path/to/spektrafilm checkout "$SPEKTRAFILM_ORACLE_SHA"   # see below
#   export SPEKTRAFILM_SRC=/path/to/spektrafilm/src      # the package source root
#   source tools/parity/setup_env.sh
#   python tools/parity/gen_goldens.py --case scan_portra
#
# This is what produced the committed goldens under tools/parity/goldens/.
#
# ORACLE PROVENANCE — the committed goldens are REPRODUCIBLE ONLY at this exact
# upstream spektrafilm commit. Regenerating them at this SHA reproduces every
# committed gen_goldens case bit-exactly (max_abs=0, rms=0). Upstream's HEAD has
# since drifted (see SPEKTRAFILM_ORACLE_DRIFT below), so you MUST check this SHA
# out before generating, or the goldens will not match.
#
#   SPEKTRAFILM_ORACLE_REPO = https://github.com/andreavolpato/spektrafilm
#   SPEKTRAFILM_ORACLE_SHA  = c1d0e44b962d80a51ea096d33faea346e4f3836c
#     ("docs: update high-res banner", 2026-05-23) — last commit at which the
#     committed goldens reproduce bit-exactly.
#   SPEKTRAFILM_ORACLE_DRIFT = a9bccd6bf58764811eeb69883cdc9d86ab64ee18
#     ("feat: tap inject/collect system for runtime", 2026-05-23) — the immediate
#     child of the pinned SHA. It changed the filming raw-scaling semantics
#     (film_log_raw max_abs jumps to ~4.44 vs the committed golden); every commit
#     from here to upstream HEAD diverges. This is why the SHA must be pinned.
set -euo pipefail

# The pinned oracle commit the committed goldens were generated from. Exported so
# callers / tooling can `git checkout "$SPEKTRAFILM_ORACLE_SHA"` reproducibly.
export SPEKTRAFILM_ORACLE_REPO="https://github.com/andreavolpato/spektrafilm"
export SPEKTRAFILM_ORACLE_SHA="c1d0e44b962d80a51ea096d33faea346e4f3836c"

: "${SPEKTRAFILM_SRC:=/home/user/spektrafilm/src}"

# If SPEKTRAFILM_SRC is a git working tree, verify it is on the pinned oracle SHA
# (or offer the exact checkout command). A mismatch silently produces goldens that
# will NOT match the committed ones, so warn loudly rather than fail.
_spk_repo_root="$(git -C "${SPEKTRAFILM_SRC}" rev-parse --show-toplevel 2>/dev/null || true)"
if [ -n "${_spk_repo_root}" ]; then
  _spk_head="$(git -C "${_spk_repo_root}" rev-parse HEAD 2>/dev/null || true)"
  if [ "${_spk_head}" != "${SPEKTRAFILM_ORACLE_SHA}" ]; then
    echo "WARNING: spektrafilm checkout is NOT on the pinned oracle SHA." >&2
    echo "  HEAD   = ${_spk_head:-<unknown>}" >&2
    echo "  pinned = ${SPEKTRAFILM_ORACLE_SHA}" >&2
    echo "  Goldens will only reproduce after:" >&2
    echo "    git -C ${_spk_repo_root} checkout ${SPEKTRAFILM_ORACLE_SHA}" >&2
  fi
fi

# 1) Real numeric dependencies the engine actually uses.
python3 -m pip install --quiet numba opt_einsum scikit-image exiv2 || {
  echo "pip install failed (offline?). Install: numba opt_einsum scikit-image exiv2" >&2
}

# 2) Stub the I/O/GUI deps that are imported but unused on the math path.
#    Fixed path so the same PYTHONPATH works across separate shell invocations.
STUBS="${SPK_STUBS:-/tmp/spkstubs}"
mkdir -p "$STUBS"
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
