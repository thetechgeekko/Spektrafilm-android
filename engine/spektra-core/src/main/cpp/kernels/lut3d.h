/*
 * Spektrafilm for Android — native engine: opt-in 3D LUT acceleration.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * Ports the OPT-IN LUT-acceleration path used by the scanner/enlarger
 * density->log_xyz transforms (spektrafilm.runtime.services.spectral_lut_compute
 * + spektrafilm.utils.lut + spektrafilm.utils.fast_interp_lut). The oracle builds
 * a per-channel uniform 3D LUT over [xmin, xmax] (compute_with_lut /
 * _create_lut_3d) and interpolates it with apply_lut_3d's DEFAULT method
 * 'pchip' (monotone cubic Hermite with precomputed per-axis slopes and per-cell
 * value clamping).
 *
 * IMPORTANT: this is an OPT-IN acceleration. The default engine path
 * (use_scanner_lut / use_enlarger_lut == false) does NOT touch this code and
 * stays bit-exact with the oracle. LUT interpolation is, by construction, NOT
 * bit-exact vs. the direct spectral evaluation — it trades a documented
 * interpolation tolerance for speed. The native PCHIP interpolator IS designed to
 * match the ORACLE's PCHIP output to ~float32 precision (same algorithm), which
 * is what tests/test_lut_accel.cpp gates.
 */
#ifndef SPEKTRA_KERNELS_LUT3D_H_
#define SPEKTRA_KERNELS_LUT3D_H_

#include <cstddef>
#include <vector>

namespace spk {

// A built 3D LUT: a (steps,steps,steps,3) row-major double array plus the
// per-channel domain bounds it was sampled over. lut index order is [r][g][b][c]
// (numpy indexing='ij' in _create_lut_3d, axis 0 = first input channel).
struct Lut3D {
    int steps = 0;
    double xmin[3] = {0.0, 0.0, 0.0};
    double xmax[3] = {1.0, 1.0, 1.0};
    std::vector<double> data;  // size steps^3 * 3

    inline size_t index(int i, int j, int k, int c) const {
        return (((static_cast<size_t>(i) * steps + j) * steps + k) * 3) +
               static_cast<size_t>(c);
    }
};

// Build a 3D LUT by sampling `fn` on the per-channel uniform grid over
// [xmin, xmax] with `steps` samples per axis (mirrors _create_lut_3d). `fn` maps
// one RGB-like triple (the grid density point) to a 3-vector (the transformed
// value); it is evaluated steps^3 times. xmin/xmax are length-3.
//
// Matches numpy np.linspace(..., endpoint=True): exact endpoints, interior via
// start + step*k with step = (stop-start)/(steps-1).
Lut3D build_lut_3d(const double xmin[3], const double xmax[3], int steps,
                   const std::vector<double>& fn_inputs_unused,
                   void (*fn)(const double in[3], double out[3], void* ctx),
                   void* ctx);

// Apply the LUT to an (h,w,3) image of RAW (un-normalized) data, mirroring
// compute_with_lut: data is normalized to [0,1] via (data - xmin)/(xmax - xmin)
// then interpolated with the PCHIP 3D path (apply_lut_3d default). Output is
// (h,w,3) row-major double, written into `out` (size h*w*3).
void apply_lut_3d_pchip(const Lut3D& lut, const double* data, int w, int h,
                        double* out);

// Apply the LUT to ALREADY-normalized [0,1] data (the path the golden test uses;
// equivalent to apply_lut_3d on normalized input). `norm` and `out` are h*w*3.
void apply_lut_3d_pchip_normalized(const Lut3D& lut, const double* norm, int w,
                                   int h, double* out);

}  // namespace spk

#endif  // SPEKTRA_KERNELS_LUT3D_H_
