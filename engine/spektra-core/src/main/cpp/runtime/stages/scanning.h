/*
 * SpectraFilm for Android — native engine: scanning stage.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/runtime/stages/scanning.py for the scan_film
 * route (negative film scanned directly, default params, spatial + stochastic
 * effects disabled): density_cmy -> spectral density -> light -> XYZ ->
 * output RGB -> sRGB CCTF encode + clip.
 */
#ifndef SPK_RUNTIME_STAGES_SCANNING_H
#define SPK_RUNTIME_STAGES_SCANNING_H

#include "profiles/profile.h"
#include "spektra.h"  // spk_color_space

namespace spk {

// Scan parameters for the scan_film route. The scan_portra milestone uses the
// defaults below (sRGB output, CCTF on, no glare/blur/unsharp because spatial &
// stochastic effects are deactivated). Kept as fields so callers can later wire
// the full RuntimePhotoParams tree without changing the signature.
struct ScanningParams {
    bool scan_film = true;
    bool output_cctf_encoding = true;
    // io.output_color_space: selects the XYZ->RGB matrix + per-space CCTF in
    // color_output. Defaults to sRGB. LINEAR_SRGB uses sRGB primaries with the
    // CCTF (and the near-identity RGB->RGB round-trip) skipped, matching
    // scanning.py, which only runs colour.RGB_to_RGB when output_cctf_encoding.
    spk_color_space output_color_space = SPK_CS_SRGB;
    // Scanner unsharp mask (scanner.unsharp_mask = (sigma, amount)). Applied in
    // the output (linear sRGB) space after XYZ->RGB and before the CAT02 round
    // trip + CCTF encode, matching scanning.py::_apply_blur_and_unsharp /
    // diffusion.apply_unsharp_mask: rgb += amount * (rgb - G(sigma) * rgb).
    // Both default 0 (spatial OFF); set to (0.7, 0.7) under scan_portra_spatial.
    // scanner.lens_blur is 0 under the goldens, so the gaussian-blur pass is a
    // no-op and is not modelled here.
    double unsharp_sigma = 0.0;
    double unsharp_amount = 0.0;
};

// scan(): run the scanning stage on an (h x w x 3) row-major density_cmy image.
//   in  : density_cmy, length w*h*3.
//   out : final RGB (sRGB, CCTF-encoded, clipped to [0,1]), length w*h*3.
// `film` supplies channel_density / base_density / viewing illuminant. The scan
// illuminant + XYZ->RGB matrix are the D50-derived constants in color_output.h
// (the film's viewing_illuminant is D50 for the bundled negatives).
//
// Mirrors scanning.py exactly for the negative scan_film route:
//   - black/white XYZ correction is skipped (negative film scan).
//   - glare is None (scan_film) and blur/unsharp are off (spatial deactivated).
void scan(const Profile& film, const ScanningParams& params,
          const float* density_cmy, int width, int height, float* rgb_out);

}  // namespace spk

#endif  // SPK_RUNTIME_STAGES_SCANNING_H
