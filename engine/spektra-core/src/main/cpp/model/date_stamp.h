/*
 * Spektrafilm for Android — native engine: date-back imprint (opt-in).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * NOT part of upstream spektrafilm (no oracle): an Android-only, OFF-by-default
 * stage. The idea of exposing the date ONTO THE NEGATIVE -- so it develops,
 * halates and grains with the picture instead of being pasted on afterwards --
 * comes from SpektraLab's "Date Back" (RFC-031,
 * https://github.com/JamesQiu2005/SpektraLab, GPL-3.0-or-later). This is an
 * independent implementation: procedural seven-segment LED digits, no fonts.
 *
 * Model: a camera date back is a small LED display behind the film that fires
 * while the shutter is open. Its light adds to the scene light reaching the
 * emulsion, so it is applied in filming.expose() to the scene-linear ProPhoto
 * RGB right before the tc lookup (after scene latitude, which belongs to the
 * scene, not the LED):
 *
 *   rgb += coverage(x, y) * light,   light = colour/Y(colour) * 0.184 * 2^ev / 2^ec
 *
 * `ev` is the LED's brightness in stops above mid-grey and `ec` the exposure
 * compensation, divided back out so the imprint's density does not follow the
 * scene's exposure slider (the LED's exposure is the LED's). Coordinates are
 * the engine's frame -- after crop/rescale -- so the imprint sits in the
 * corner of the picture the user framed.
 */
#ifndef SPK_MODEL_DATE_STAMP_H
#define SPK_MODEL_DATE_STAMP_H

#include <string>
#include <vector>

namespace spk {

struct DateStampParams {
    bool active = false;
    std::string text;              // e.g. "'26 11 07"; digits, space, ' - . : /
    int corner = 0;                // 0 bottom-right, 1 bottom-left, 2 top-right, 3 top-left
    double height = 0.045;         // digit height, fraction of the frame's short edge
    double margin = 0.05;          // inset from the corner, fraction of the short edge
    double exposure_ev = 2.5;      // LED brightness, stops above mid-grey
    double color[3] = {1.0, 0.18, 0.0};  // LED colour, linear ProPhoto (normalised to Y)
    // Band rendering (spk_simulate on a very large frame renders it as horizontal
    // bands): the full frame this band belongs to and the band's first frame row.
    // frame_width == 0 means the rendered image IS the frame.
    int frame_width = 0;
    int frame_height = 0;
    int frame_y0 = 0;

    bool engaged() const;
};

// The imprint rasterised for one frame: per-pixel coverage inside a bounding
// box, and the light (linear ProPhoto RGB) one unit of coverage adds.
struct DateStampMask {
    int x0 = 0, y0 = 0, w = 0, h = 0;  // bounding box in frame pixels
    std::vector<float> coverage;       // w*h, row-major, [0,1]
    double light[3] = {0.0, 0.0, 0.0};

    bool empty() const { return w <= 0 || h <= 0; }
    // Coverage at frame pixel (x, y); 0 outside the box.
    inline float at(int x, int y) const {
        const int lx = x - x0, ly = y - y0;
        if (lx < 0 || ly < 0 || lx >= w || ly >= h) return 0.0f;
        return coverage[static_cast<size_t>(ly) * w + lx];
    }
};

// Rasterise `p` for a `width` x `height` frame. `exposure_compensation_ev` is
// divided out of the light (see above). Returns an empty mask when the stage is
// not engaged, the text has no drawable glyph, or the frame is degenerate.
DateStampMask build_date_stamp_mask(const DateStampParams& p, int width, int height,
                                    double exposure_compensation_ev);

}  // namespace spk

#endif  // SPK_MODEL_DATE_STAMP_H
