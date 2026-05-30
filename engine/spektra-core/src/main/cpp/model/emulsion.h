/*
 * Spektrafilm for Android — native engine: emulsion spectral contraction.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/model/emulsion.py::compute_density_spectral.
 */
#ifndef SPK_MODEL_EMULSION_H
#define SPK_MODEL_EMULSION_H

namespace spk {

// compute_density_spectral(channel_density, density_cmy, base_density):
//   density_spectral = contract('ijk, lk->ijl', density_cmy, channel_density)
//   if base_density is not None: density_spectral += base_density
//
// In tensor terms per pixel p and wavelength l:
//   spectral[p, l] = sum_k density_cmy[p, k] * channel_density[l, k]  (+ base[l])
//
// Buffers (row-major):
//   density_cmy      : (npix, 3)            CMY density per pixel.
//   channel_density  : (samples, 3)         per-wavelength CMY dye density,
//                                           channel_density[l*3 + k].
//   base_density     : (samples,) or null   per-wavelength base+fog density.
//   spectral_out     : (npix, samples)      output spectral density,
//                                           spectral_out[p*samples + l].
//
// `samples` is the spectral working size (kSpectralSamples == 81).
void compute_density_spectral(const float* density_cmy, int npix,
                              const float* channel_density, int samples,
                              const float* base_density,
                              float* spectral_out);

}  // namespace spk

#endif  // SPK_MODEL_EMULSION_H
