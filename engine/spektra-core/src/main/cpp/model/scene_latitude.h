/*
 * Spektrafilm for Android — native engine: scene latitude mapping (opt-in).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * NOT part of upstream spektrafilm, so there is no oracle for it: it is an
 * Android-only, OFF-by-default stage. The idea -- place a scene wider than the
 * film can hold onto the film BEFORE exposure, instead of bending the film's own
 * curves -- comes from SpektraLab's "Scene Latitude Mapping" (RFC-023,
 * https://github.com/JamesQiu2005/SpektraLab, GPL-3.0-or-later). This is an
 * independent implementation of that idea, not a copy of its kernel.
 *
 * What it does, per pixel, on the scene-linear ProPhoto RGB that the film is
 * about to be exposed with (after auto-exposure, before exposure compensation):
 *
 *   Y  = luminance of max(RGB, 0)                       (ProPhoto D50 Y row)
 *   E  = log2(Y / 0.184)                                stops from mid-grey
 *   above the highlight knee K_h:  D = E - K_h
 *        E' = K_h + D * H_h / (H_h^m + D^m)^(1/m)       -> K_h + H_h as E -> inf
 *   below the shadow knee K_s:     D = K_s - E
 *        E' = K_s - D * H_s / (H_s^m + D^m)^(1/m)       -> K_s - H_s as E -> -inf
 *   d  = E' - E  (a positive shadow lift is softly bounded by L: d L / sqrt(L^2+d^2))
 *   k  = 2^d
 *   shadows (k >= 1):     RGB' = k * RGB
 *   highlights (k < 1):   RGB' = k * (Y + k * (RGB - Y))
 *
 * Shadows get one scalar gain, so their chromaticity (and the Hanatos tc
 * lookup) is untouched. Compressed highlights ALSO lose saturation in step with
 * the compression (the chroma RGB - Y, Y being the neutral (Y,Y,Y) in ProPhoto
 * D50, is scaled by k once more): the film's own shoulder would have
 * desaturated those pixels, and pulling them down into the film's mid-tones
 * without this exposed the clipped highlight's raw tint as a flat coloured
 * patch with a coloured fringe (phone test, 2026-11-07). Luminance is still
 * exactly k*Y. Between the knees nothing changes. A room H of 0 switches that
 * side off exactly. A non-finite or non-positive luminance passes through.
 */
#ifndef SPK_MODEL_SCENE_LATITUDE_H
#define SPK_MODEL_SCENE_LATITUDE_H

namespace spk {

struct SceneLatitudeParams {
    bool active = false;
    double highlight_knee = 1.0;   // K_h, stops above mid-grey
    double highlight_room = 2.5;   // H_h, stops; 0 = highlight side off
    double shadow_knee = -2.0;     // K_s, stops relative to mid-grey
    double shadow_room = 0.0;      // H_s, stops; 0 = shadow side off
    double rolloff = 2.0;          // m, the knee's order (1 = soft .. 4 = hard)
    double max_lift = 4.0;         // L, smooth bound on the shadow lift, stops

    // True when the stage changes anything. A stage that is not engaged is
    // never run, so every pre-existing render is byte-identical.
    bool engaged() const;
};

// The gain 2^d for one scene-linear ProPhoto RGB triple. Pure function of the
// triple and the params; 1.0 inside the core and for degenerate pixels.
double scene_latitude_gain(const SceneLatitudeParams& p, const double rgb[3]);

// Applies the mapping to one triple in place (gain, plus the highlight
// desaturation described above). What the filming stage runs.
void scene_latitude_apply(const SceneLatitudeParams& p, double rgb[3]);

// The stop mapping alone (E -> E'), for tests and the UI's curve preview.
double scene_latitude_map_stops(const SceneLatitudeParams& p, double stops);

}  // namespace spk

#endif  // SPK_MODEL_SCENE_LATITUDE_H
