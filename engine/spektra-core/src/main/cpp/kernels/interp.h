/*
 * SpectraFilm for Android — native engine: 1D linear interpolation kernel.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/utils/fast_interp.py (fast_interp).
 */
#ifndef SPK_KERNELS_INTERP_H
#define SPK_KERNELS_INTERP_H

namespace spk {

// Scalar 1D linear interpolation on a single monotonically increasing axis.
//
// Port of spektrafilm/utils/fast_interp.py::fast_interp (single-channel core).
// Matches numpy.interp / fast_interp endpoint-clamping semantics:
//   - x <= xp[0]      -> fp[0]
//   - x >= xp[n-1]    -> fp[n-1]
//   - otherwise       -> linear interpolation between bracketing samples,
//                        with right-biased exact-match (searchsorted side='right').
//
// `xp` must be sorted ascending and have `n` entries; `fp` has `n` entries.
float interp1d_scalar(float x, const float* xp, const float* fp, int n);

// Vectorised form: interpolate `count` query values from `x` into `out`,
// sharing one (xp, fp) table. This is the "clamped" variant — values outside
// [xp[0], xp[n-1]] clamp to the endpoint fp values, exactly like fast_interp.
void interp1d(const float* x, int count,
              const float* xp, const float* fp, int n,
              float* out);

// Per-channel interpolation over an (..., 3) buffer flattened to `npix` rows of
// 3 contiguous floats, mirroring fast_interp's image path.
//
// `xp` layout selects fast_interp's two modes:
//   - common_axis == true : `xp` is one shared axis of length `n` (1D case).
//   - common_axis == false: `xp` is (n, 3) row-major; column c is channel c's
//                           axis (the 2D `x_axis` case).
// `fp` is always (n, 3) row-major: fp[k*3 + c] is channel c's reference value.
// `img` and `out` are (npix, 3) row-major; in-place (out == img) is allowed.
void interp1d_planar3(const float* img, int npix,
                      const float* xp, bool common_axis,
                      const float* fp, int n,
                      float* out);

}  // namespace spk

#endif  // SPK_KERNELS_INTERP_H
