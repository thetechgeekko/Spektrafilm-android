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
 * spektrafilm. Implements model/grain.h. Mirrors grain.py::layer_particle_model
 * (poisson_binomial) + apply_grain_to_density.
 *
 * Verification is statistical (see tests/test_grain.cpp): the model is
 * mean-preserving and injects noise whose per-channel std matches the Python
 * oracle in magnitude. Exact element-wise parity is impossible because the C++
 * std::mt19937 RNG stream differs from numpy/Numba — only the *distributions*
 * (Poisson, Binomial) and the algorithm structure are matched.
 */
#include "model/grain.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "kernels/gaussian.h"
#include "kernels/stats.h"

namespace spk {

void layer_particle_model(const float* density, int npix, int /*width*/,
                          int /*height*/, double density_max,
                          double n_particles_per_pixel, double grain_uniformity,
                          uint64_t seed, float* out) {
    const double od_particle = density_max / n_particles_per_pixel;
    StatsRng rng(seed);
    for (int i = 0; i < npix; ++i) {
        // probability_of_development = clip(density/density_max, 1e-6, 1-1e-6)
        double p = static_cast<double>(density[i]) / density_max;
        if (p < 1e-6) p = 1e-6;
        else if (p > 1.0 - 1e-6) p = 1.0 - 1e-6;

        double saturation = 1.0 - p * grain_uniformity * (1.0 - 1e-6);
        double lam = n_particles_per_pixel / saturation;
        int64_t seeds = fast_poisson_one(lam, rng);
        int64_t dev = fast_binomial_one(seeds, p, rng);
        out[i] = static_cast<float>(static_cast<double>(dev) * od_particle * saturation);
    }
}

void apply_grain_to_density(const float* density_cmy, int npix, int width,
                            int height, double pixel_size_um,
                            const GrainParams& grain, float* out) {
    if (!grain.active) {
        if (out != density_cmy)
            std::copy(density_cmy, density_cmy + static_cast<size_t>(npix) * 3, out);
        return;
    }

    const double pixel_area_um2 = pixel_size_um * pixel_size_um;
    const int n_sub = grain.n_sub_layers > 1 ? grain.n_sub_layers : 1;

    double density_max[3], n_ppp[3];
    for (int c = 0; c < 3; ++c) {
        density_max[c] = grain.density_max_curves[c] + grain.density_min[c];
        double particle_area = grain.agx_particle_area_um2 * grain.agx_particle_scale[c];
        n_ppp[c] = pixel_area_um2 / particle_area;
        if (n_sub > 1) n_ppp[c] /= n_sub;
    }

    // Accumulator per channel-interleaved pixel (the Python code does
    // density_cmy += density_min before sampling, then out -= density_min after).
    std::vector<double> acc(static_cast<size_t>(npix) * 3, 0.0);
    std::vector<float> shifted(static_cast<size_t>(npix));   // one channel plane
    std::vector<float> layer_out(static_cast<size_t>(npix));

    for (int c = 0; c < 3; ++c) {
        // density_cmy[:,c] + density_min[c]
        for (int i = 0; i < npix; ++i)
            shifted[i] = static_cast<float>(
                static_cast<double>(density_cmy[i * 3 + c]) + grain.density_min[c]);

        for (int sl = 0; sl < n_sub; ++sl) {
            uint64_t seed = static_cast<uint64_t>(grain.seed_base[c] + sl * 10 +
                                                  grain.seed_offset);
            layer_particle_model(shifted.data(), npix, width, height,
                                 density_max[c], n_ppp[c], grain.uniformity[c],
                                 seed, layer_out.data());
            for (int i = 0; i < npix; ++i)
                acc[static_cast<size_t>(i) * 3 + c] += layer_out[i];
        }
        // /= n_sub_layers, then -= density_min[c]
        for (int i = 0; i < npix; ++i) {
            double v = acc[static_cast<size_t>(i) * 3 + c] / n_sub - grain.density_min[c];
            out[i * 3 + c] = static_cast<float>(v);
        }
    }

    // Final per-channel Gaussian blur (sigma in pixels). Python threshold: > 0.4.
    if (grain.blur > 0.4) {
        gaussian_blur(out, width, height, 3, static_cast<float>(grain.blur));
    }
}

}  // namespace spk
