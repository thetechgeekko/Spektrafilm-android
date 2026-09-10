#!/usr/bin/env python3
"""Halide AOT generators for diffusion + grain, f32, Vulkan compute (#217).

Spektrafilm for Android — GPLv3. Film modeling powered by spektrafilm.

Owner request (2026-09-10): "put diffusion, grain in halide fragment in f32."
Halide emits COMPUTE SPIR-V rather than fragment shaders; for a whole-image
pipeline that is the same thing and the better one, so that is what this targets.

WHY f32 IS SAFE HERE, measured rather than assumed. f75f95c ran the engine's own
templated FFT convolution at f64 and f32 on the real Black Pro-Mist PSF:
max_abs 1.2e-06 against a scene peaking at 3.8 (inside the 1e-4 parity band) and
0.000 8-bit display codes. The same run showed f32 buys ~1.00x on the CPU, which
is why the answer is a GPU schedule and not a precision change.

TWO GENERATORS, AND THEY ARE NOT EQUALLY GOOD FITS -- stated up front because the
difference decides what is worth shipping:

  grain      An excellent fit. Pointwise per (pixel, sublayer, channel) with a
             counter-based RNG, so every output is a pure function of its
             coordinates: no scan, no reduction, no cross-pixel dependency.
             Schedules to a flat gpu_tile.

  diffusion  A DIRECT convolution, and that is the catch. The engine's CPU path
             uses FFT because the Black Pro-Mist kernel is ks = 273 at the 640 px
             preview and ks = 1725 at a 12.5 MP export -- the direct form is
             2.97e6 taps per pixel at export, i.e. ~1.1e14 MACs for the frame,
             which no schedule rescues. Halide has no FFT primitive. So this
             generator is useful for SMALL kernels and the crossover has to be
             measured, not hoped for; gpu/fft_convolve.comp remains the answer at
             export scale.

USAGE
    ~/halidenv/bin/python3 tools/halide/gen_gpu_f32.py <outdir> [target ...]
    default targets: host, arm-64-android-vulkan
"""
import os
import sys
from typing import Any, Callable, List, Tuple

import halide as hl

Built = Tuple[hl.Func, List[Any], Tuple[hl.Var, hl.Var, hl.Var]]


# --------------------------------------------------------------------- grain --
def build_grain() -> Built:
    """AgX particle grain: Normal-approx Poisson -> Binomial, counter-based RNG.

    Mirrors gpu/grain.comp, which is itself model/grain.cpp's sampler restricted
    to the branches the real workload lands in (99.4% at 1080p, measured by
    tools/gpu_probe/census_grain_branches.cpp).
    """
    density = hl.ImageParam(hl.Float(32), 3, "density")   # (x, y, cell)
    cells = hl.ImageParam(hl.Float(32), 2, "cells")       # (param, cell)
    ncells = hl.Param(hl.Int(32), "ncells")
    seed_off = hl.Param(hl.UInt(32), "seed_offset")

    x, y, c, k = hl.Var("x"), hl.Var("y"), hl.Var("c"), hl.Var("k")

    def pcg(v: hl.Expr) -> hl.Expr:
        # PCG-RXS-M-XS, 32-bit. Same constants as gpu/grain.comp so the two can
        # be checked against each other.
        state = v * hl.u32(747796405) + hl.u32(2891336453)
        word = ((state >> ((state >> hl.u32(28)) + hl.u32(4))) ^ state) * hl.u32(277803737)
        return (word >> hl.u32(22)) ^ word

    def mix3(a: hl.Expr, b: hl.Expr, cc: hl.Expr) -> hl.Expr:
        return pcg(pcg(a) ^ pcg(b + hl.u32(0x9e3779b9)) ^ pcg(cc + hl.u32(0x85ebca6b)))

    # Per-cell constants, laid out as gpu/grain.comp's cell block.
    dmin = cells[0, k]
    dmax = cells[1, k]
    n_ppp = cells[2, k]
    uni = cells[3, k]
    od = cells[4, k]
    seed_base = hl.cast(hl.UInt(32), cells[6, k])

    gp = hl.cast(hl.UInt(32), y) * hl.u32(65536) + hl.cast(hl.UInt(32), x)
    h0 = mix3(seed_base, gp, seed_off ^ (hl.cast(hl.UInt(32), k) * hl.u32(0x27d4eb2f)))
    h1 = pcg(h0 ^ hl.u32(0xa5a5a5a5))

    u0 = (hl.cast(hl.Float(32), h0 >> hl.u32(8)) + 1.0) * (1.0 / 16777216.0)
    u1 = hl.cast(hl.Float(32), h1 >> hl.u32(8)) * (1.0 / 16777216.0)
    rad = hl.sqrt(-2.0 * hl.log(u0))
    ang = hl.f32(6.28318530717958647692) * u1
    z0 = rad * hl.cos(ang)
    z1 = rad * hl.sin(ang)

    d = density[x, y, k] + dmin
    pr = hl.clamp(d / dmax, hl.f32(1e-6), hl.f32(1.0 - 1e-6))
    sat = 1.0 - pr * uni * hl.f32(1.0 - 1e-6)
    lam = n_ppp / sat
    seeds = hl.max(hl.round(lam + hl.sqrt(lam) * z0), hl.f32(0.0))
    var = seeds * pr * (1.0 - pr)
    dev = hl.clamp(hl.round(seeds * pr + hl.sqrt(var) * z1), hl.f32(0.0), seeds)

    contrib = hl.Func("contrib")
    contrib[x, y, k] = dev * od * sat

    # Accumulate the cells that target channel c. cells[5,k] is the channel index.
    r = hl.RDom([(0, ncells)], "r")
    out = hl.Func("grain_out")
    out[x, y, c] = hl.f32(0.0)
    out[x, y, c] += hl.select(hl.cast(hl.Int(32), cells[5, r.x]) == c,
                              contrib[x, y, r.x], hl.f32(0.0))
    return out, [density, cells, ncells, seed_off], (x, y, c)


# ----------------------------------------------------------------- diffusion --
def build_diffusion() -> Built:
    """Direct 'same' PSF convolution in f32 -- see the module docstring on scale."""
    padded = hl.ImageParam(hl.Float(32), 3, "padded")   # (x, y, channel)
    kern = hl.ImageParam(hl.Float(32), 2, "kern")       # (ks, ks)
    ks = hl.Param(hl.Int(32), "ks")

    x, y, c = hl.Var("x"), hl.Var("y"), hl.Var("c")
    r = hl.RDom([(0, ks), (0, ks)], "r")

    out = hl.Func("diffusion_out")
    # Correlation with the flipped kernel == convolution, matching
    # model/diffusion.cpp's 'same' form on a caller-padded plane.
    out[x, y, c] = hl.f32(0.0)
    out[x, y, c] += padded[x + r.x, y + r.y, c] * kern[ks - 1 - r.x, ks - 1 - r.y]
    return out, [padded, kern, ks], (x, y, c)


def schedule_gpu(func: hl.Func,
                 xyz: Tuple[hl.Var, hl.Var, hl.Var],
                 target: hl.Target) -> hl.Func:
    x, y, c = xyz
    if target.has_gpu_feature():
        xi, yi = hl.Var("xi"), hl.Var("yi")
        # 16x16 tiles: a plain, defensible starting point. Tuning comes after a
        # measurement, not before one.
        func.gpu_tile(x, y, xi, yi, 16, 16)
        for i in range(func.num_update_definitions()):
            func.update(i).gpu_tile(x, y, xi, yi, 16, 16)
    else:
        func.parallel(y).vectorize(x, 8)
        for i in range(func.num_update_definitions()):
            func.update(i).parallel(y).vectorize(x, 8)
    return func


def emit(name: str, builder: Callable[[], Built], outdir: str,
         target_str: str) -> str:
    target = hl.Target(target_str)
    func, inputs, xyz = builder()
    schedule_gpu(func, xyz, target)
    base = os.path.join(outdir, f"{name}_{target_str.replace('-', '_')}")
    func.compile_to(
        {
            hl.OutputFileType.static_library: base + ".a",
            hl.OutputFileType.c_header: base + ".h",
            hl.OutputFileType.stmt_html: base + ".html",
        },
        inputs, name, target)
    return base + ".a"


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    outdir = sys.argv[1]
    targets = sys.argv[2:] or ["host", "arm-64-android-vulkan"]
    os.makedirs(outdir, exist_ok=True)
    for t in targets:
        for name, builder in (("spk_grain_f32", build_grain),
                              ("spk_diffusion_f32", build_diffusion)):
            try:
                path = emit(name, builder, outdir, t)
                # SPIR-V presence is the only honest proof a GPU target actually
                # produced GPU code rather than a CPU fallback: scan for the
                # magic number 0x07230203.
                blob = open(path, "rb").read()
                magic = blob.count((0x07230203).to_bytes(4, "little"))
                print(f"{t:26s} {name:20s} OK  {len(blob):9d} bytes  spirv_magic={magic}")
            except Exception as e:
                print(f"{t:26s} {name:20s} FAILED  {type(e).__name__}: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
