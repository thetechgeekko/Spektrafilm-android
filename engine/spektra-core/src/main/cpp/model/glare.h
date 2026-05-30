/*
 * Spektrafilm for Android — native engine: lognormal viewing glare.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/glare.py (add_glare,
 * compute_random_glare_amount) and the lognormal sampler from
 * spektrafilm/utils/fast_stats.py (fast_lognormal_from_mean_std / fast_lognormal).
 */
#ifndef SPK_MODEL_GLARE_H
#define SPK_MODEL_GLARE_H

#include <cstdint>

namespace spk {

// compute_random_glare_amount(amount, roughness, blur, shape):
//   random = fast_lognormal_from_mean_std(amount, roughness*amount)  (per pixel)
//   random = fast_gaussian_filter(random, blur)                      (sigma px)
//   random /= 100
//
// Writes a single-channel (h*w, row-major) glare amount plane into `out`.
// `seed` makes the RNG deterministic (std::mt19937). `blur` is the Gaussian
// sigma in pixels (glare.py passes glare.blur directly to the filter).
void compute_random_glare_amount(float amount, float roughness, float blur,
                                 int w, int h, uint64_t seed, float* out);

// add_glare(xyz, illuminant_xyz, glare): if active and percent > 0,
//   xyz += glare_amount[:,:,None] * illuminant_xyz[None,None,:]
//
// `xyz` is (h*w*3) row-major interleaved XYZ (modified in place).
// `illuminant_xyz` is a 3-vector. No-op when percent <= 0.
void add_glare(float* xyz, int w, int h, const float illuminant_xyz[3],
               float percent, float roughness, float blur, uint64_t seed);

}  // namespace spk

#endif  // SPK_MODEL_GLARE_H
