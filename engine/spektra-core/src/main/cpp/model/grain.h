/*
 * Spektrafilm for Android — native engine: film grain (AgX particle model).
 * Copyright (C) 2026 Spektrafilm Android contributors.
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
 * Scope: BOTH the non-sublayer path (grain.sublayers_active == False) AND the
 * sublayer + micro-structure path (grain.sublayers_active == True, the schema
 * default). The sublayer path ports apply_grain_to_density_layers (per-sublayer
 * particle model driven by density_curves_layers / interp_density_cmy_layers and
 * agx_particle_scale_layers, with per-particle dye-cloud blur) followed by
 * add_micro_structure (per-pixel lognormal clumping).
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
    bool sublayers_active = true;    // schema default: use the per-sublayer path.
    double agx_particle_area_um2 = 0.2;
    double agx_particle_scale[3] = {0.8, 1.0, 2.0};
    // Per-SUBLAYER particle-area scale (sublayers_active path only). Schema default
    // GrainParams.agx_particle_scale_layers = (2.5, 1.0, 0.5).
    double agx_particle_scale_layers[3] = {2.5, 1.0, 0.5};
    double density_min[3] = {0.07, 0.08, 0.12};
    // density_max_curves[c] = nanmax(normalized_density_curves[:,c]). Filled from
    // the film profile at apply time (NOT a schema constant). Non-sublayer path.
    double density_max_curves[3] = {2.2, 2.2, 2.2};
    double uniformity[3] = {0.97, 0.97, 0.99};
    double blur = 0.65;              // final Gaussian sigma in pixels
    // Per-particle dye-cloud blur (µm), sublayer path only. Schema default 1.0.
    double blur_dye_clouds_um = 1.0;
    // Micro-structure clumping (sublayer path): (blur_um, sigma_nm). Schema default
    // (0.2, 30). add_micro_structure: blur_pixel = micro_structure[0]/pixel_size_um,
    // sigma = micro_structure[1]*0.001/pixel_size_um.
    double micro_structure[2] = {0.2, 30.0};
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

// Overload with the per-particle dye-cloud blur (sublayer path). When
// blur_particle > 0, after sampling the grain plane is Gaussian-blurred with
// sigma = blur_particle * sqrt(od_particle), od_particle = density_max /
// n_particles_per_pixel — mirroring grain.py::layer_particle_model's
// `if blur_particle>0: grain = fast_gaussian_filter(grain, blur_particle*sqrt(od_particle))`.
// (width, height) drive that 2D blur; npix == width*height.
void layer_particle_model(const float* density, int npix, int width, int height,
                          double density_max, double n_particles_per_pixel,
                          double grain_uniformity, uint64_t seed,
                          double blur_particle, float* out);

// add_micro_structure(density_cmy_out, micro_structure, pixel_size_um):
//
//   blur_pixel = micro_structure[0] / pixel_size_um
//   sigma      = micro_structure[1] * 0.001 / pixel_size_um   (nm -> µm -> px)
//   if sigma > 0.05:
//       clumping = fast_lognormal_from_mean_std(ones, sigma)   (per pixel*channel)
//       if blur_pixel > 0.4: clumping = gaussian_filter(clumping, blur_pixel)
//       density_cmy_out *= clumping
//
// `inout` is (npix, 3) row-major (channel-interleaved); mutated in place.
// `seed` seeds the deterministic lognormal RNG stream (statistical parity only).
void add_micro_structure(float* inout, int npix, int width, int height,
                         const double micro_structure[2], double pixel_size_um,
                         uint64_t seed);

// apply_grain_to_density_layers(density_cmy_layers, ...): the sublayer grain path
// (grain.sublayers_active == True). Mirrors grain.py::apply_grain_to_density_layers
// followed by add_micro_structure.
//
//   density_max_total[c]      = sum_sl density_max_layers[sl,c]
//   density_max_fractions[sl,c] = density_max_layers[sl,c] / density_max_total[c]
//   density_min_layers[sl,c]  = density_max_fractions[sl,c] * density_min[c]
//   density_max_layers[sl,c] += density_min_layers[sl,c]
//   particle_area[sl,c]       = agx_particle_area_um2 * agx_particle_scale[c]
//                               * agx_particle_scale_layers[sl]
//   n_ppp[sl,c] = pixel_area_um2 * density_max_fractions[sl,c] / particle_area[sl,c]
//   density_cmy_layers[:,sl,c] += density_min_layers[sl,c]
//   for c, sl: out[:,c] += layer_particle_model(density_cmy_layers[:,sl,c],
//                          density_max_layers[sl,c], n_ppp[sl,c], uniformity[c],
//                          seed = seed_base[c] + sl*10 + seed_offset,
//                          blur_particle = blur_dye_clouds_um)
//   out = add_micro_structure(out, micro_structure, pixel_size_um)
//   out -= density_min
//   if blur > 0: out = gaussian_filter(out, sigma=blur)      (NOTE: > 0, not > 0.4)
//
// `density_cmy_layers` is (npix, 3layer, 3ch) row-major (index pix*9 + sl*3 + c),
// matching interp_density_cmy_layers' output. `density_max_layers` is (3layer, 3ch)
// row-major (index sl*3 + c). It is NOT mutated. `out` is (npix, 3) row-major.
void apply_grain_to_density_layers(const float* density_cmy_layers, int npix,
                                   int width, int height,
                                   const double* density_max_layers,
                                   double pixel_size_um, const GrainParams& grain,
                                   float* out);

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
