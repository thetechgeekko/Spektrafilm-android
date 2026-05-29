/*
 * SpectraFilm for Android — native engine: film grain (AgX particle model).
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports the Poisson-Binomial AgX particle grain model of
 * spektrafilm/model/grain.py:
 *   - layer_particle_model (method='poisson_binomial')
 *   - apply_grain_to_density (the non-sublayer path)
 *
 * Scope: the non-sublayer path (grain.sublayers_active == False). The sublayer +
 * micro-structure path (apply_grain_to_density_layers / add_micro_structure) is
 * NOT ported in this milestone — it requires the per-layer density curves
 * (density_curves_layers, interp_density_cmy_layers) and the lognormal clumping
 * sampler. See grain.cpp and the milestone report for the explicit deferral.
 */
#ifndef SPK_MODEL_GRAIN_H
#define SPK_MODEL_GRAIN_H

#include <cstdint>

namespace spk {

// Digested grain parameters. Field names mirror
// spektrafilm.runtime.params_schema.GrainParams. Defaults are the schema
// defaults; the only stock-specific override under the parity toggles is that
// blur stays at 0.65 when deactivate_spatial_effects is False (the grain-ON
// statistical case). Under deactivate_spatial_effects the Python digest forces
// blur/blur_dye_clouds_um to 0, but grain itself is then also deactivated by
// deactivate_stochastic_effects, so the C++ grain path is only ever taken with
// the stochastic effects on.
struct GrainParams {
    bool active = false;             // gates the whole pass (identity when false)
    double agx_particle_area_um2 = 0.2;
    double agx_particle_scale[3] = {0.8, 1.0, 2.0};
    double density_min[3] = {0.07, 0.08, 0.12};
    // density_max_curves[c] = nanmax(normalized_density_curves[:,c]). Filled from
    // the film profile at apply time (NOT a schema constant).
    double density_max_curves[3] = {2.2, 2.2, 2.2};
    double uniformity[3] = {0.97, 0.97, 0.99};
    double blur = 0.65;              // final Gaussian sigma in pixels
    int n_sub_layers = 1;
    // Per-channel deterministic RNG seeds (Python uses [0,1,2] + sublayer*10).
    // A single global offset is added so independent realisations can be drawn.
    int seed_base[3] = {0, 1, 2};
    int seed_offset = 0;
};

// layer_particle_model(density, density_max, n_particles_per_pixel,
//                      grain_uniformity, seed, method='poisson_binomial',
//                      blur_particle=0):
//
//   p   = clip(density / density_max, 1e-6, 1-1e-6)              (per pixel)
//   od_particle = density_max / n_particles_per_pixel
//   saturation  = 1 - p * grain_uniformity * (1 - 1e-6)
//   seeds = Poisson(n_particles_per_pixel / saturation)         (per pixel)
//   grain = Binomial(seeds, p)                                  (per pixel)
//   grain = grain * od_particle * saturation
//
// `density` and `out` are length-`npix` planes (single channel). `out` may alias
// `density`. blur_particle (the per-particle dye-cloud blur) is 0 in the
// non-sublayer path and is not applied here.
void layer_particle_model(const float* density, int npix, int width, int height,
                          double density_max, double n_particles_per_pixel,
                          double grain_uniformity, uint64_t seed, float* out);

// apply_grain_to_density(density_cmy, ...): the non-sublayer grain path.
//
//   density_min = grain.density_min
//   density_max = grain.density_max_curves + density_min
//   n_particles_per_pixel[c] = pixel_size_um^2 / (agx_particle_area_um2 *
//                              agx_particle_scale[c])   (/ n_sub_layers)
//   density_cmy += density_min
//   for c in 0..2, sl in 0..n_sub_layers-1:
//       out[:,c] += layer_particle_model(density_cmy[:,c], density_max[c],
//                                        n_particles_per_pixel[c], uniformity[c],
//                                        seed = seed_base[c] + sl*10 + seed_offset)
//   out /= n_sub_layers
//   out -= density_min
//   if blur > 0.4: out = gaussian_filter(out, sigma=blur)   (per channel)
//
// `density_cmy` is (npix, 3) row-major (channel-interleaved); it is NOT mutated.
// `out` is (npix, 3) row-major and may alias density_cmy. (width, height) drive
// the 2D Gaussian blur; npix == width * height.
void apply_grain_to_density(const float* density_cmy, int npix, int width,
                            int height, double pixel_size_um,
                            const GrainParams& grain, float* out);

}  // namespace spk

#endif  // SPK_MODEL_GRAIN_H
