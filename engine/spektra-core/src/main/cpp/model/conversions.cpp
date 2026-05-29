/*
 * SpectraFilm for Android — native engine: density<->light & color matrices.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/utils/conversions.py.
 */
#include "conversions.h"

#include <cmath>

namespace spk {

// conversions.py::density_to_light:
//   transmitted = 10**(-density); transmitted *= light; nan -> 0
float density_to_light_scalar(float density, float light) {
    float t = std::pow(10.0f, -density) * light;
    if (std::isnan(t)) t = 0.0f;
    return t;
}

void density_to_light(const float* density, const float* light, int count,
                      float* out) {
    for (int i = 0; i < count; ++i) {
        out[i] = density_to_light_scalar(density[i], light[i]);
    }
}

float light_to_density_scalar(float light, float floor) {
    float l = light;
    if (!(l > floor)) l = floor;  // also catches NaN
    return -std::log10(l);
}

void light_to_density(const float* light, int count, float* out, float floor) {
    for (int i = 0; i < count; ++i) {
        out[i] = light_to_density_scalar(light[i], floor);
    }
}

// --- Embedded color matrices (colour-science 0.4.7) --------------------------
const float kSRGB_to_XYZ[9] = {
    0.4124f, 0.3576f, 0.1805f,
    0.2126f, 0.7152f, 0.0722f,
    0.0193f, 0.1192f, 0.9505f,
};
const float kXYZ_to_SRGB[9] = {
     3.2406f, -1.5372f, -0.4986f,
    -0.9689f,  1.8758f,  0.0415f,
     0.0557f, -0.2040f,  1.0570f,
};
const float kACES_to_XYZ[9] = {
    9.5255239590e-01f, 0.0000000000e+00f, 9.36786000e-05f,
    3.4396644980e-01f, 7.2816609660e-01f, -7.21325464e-02f,
    0.0000000000e+00f, 0.0000000000e+00f, 1.0088251844e+00f,
};
const float kXYZ_to_ACES[9] = {
     1.0498110175e+00f, 0.0000000000e+00f, -9.74845000e-05f,
    -4.9590302310e-01f, 1.3733130458e+00f,  9.82400361e-02f,
     0.0000000000e+00f, 0.0000000000e+00f,  9.9125201820e-01f,
};

void matvec3(const float* m, const float* v, float* out3) {
    float v0 = v[0], v1 = v[1], v2 = v[2];
    out3[0] = m[0] * v0 + m[1] * v1 + m[2] * v2;
    out3[1] = m[3] * v0 + m[4] * v1 + m[5] * v2;
    out3[2] = m[6] * v0 + m[7] * v1 + m[8] * v2;
}

void apply_matrix3(const float* in, int npix, const float* m, float* out) {
    // contract('ijk,lk->ijl', image, M): per-pixel out = M . in.
    for (int i = 0; i < npix; ++i) {
        const float* p = in + (long)i * 3;
        float v0 = p[0], v1 = p[1], v2 = p[2];
        float* o = out + (long)i * 3;
        o[0] = m[0] * v0 + m[1] * v1 + m[2] * v2;
        o[1] = m[3] * v0 + m[4] * v1 + m[5] * v2;
        o[2] = m[6] * v0 + m[7] * v1 + m[8] * v2;
    }
}

}  // namespace spk
