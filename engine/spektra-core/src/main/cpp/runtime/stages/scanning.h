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

namespace spk {

// Scan parameters for the scan_film route. The scan_portra milestone uses the
// defaults below (sRGB output, CCTF on, no glare/blur/unsharp because spatial &
// stochastic effects are deactivated). Kept as fields so callers can later wire
// the full RuntimePhotoParams tree without changing the signature.
struct ScanningParams {
    bool scan_film = true;
    bool output_cctf_encoding = true;
    // Spatial effects (lens_blur, unsharp) and glare are no-ops here. The full
    // pipeline will add them; for the parity milestone they are disabled.
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
