/*
 * SpectraFilm for Android — native engine: scan color output (XYZ->RGB + CCTF).
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 */
#include "color_output.h"

#include <cmath>

namespace spk {

// standard_illuminant("D50"), SpectralShape(380,780,5), normalised to mean 1.
// Extracted from colour-science via the parity oracle.
const float kIlluminantD50[81] = {
    2.850090653e-01f, 3.163288707e-01f, 3.476603148e-01f, 4.607654314e-01f, 5.738821868e-01f,
    6.158048695e-01f, 6.577391909e-01f, 6.782233445e-01f, 6.987191369e-01f, 6.858234311e-01f,
    6.729277253e-01f, 7.718917926e-01f, 8.708674987e-01f, 9.431556115e-01f, 1.015443724e+00f,
    1.035031696e+00f, 1.054608029e+00f, 1.059007467e+00f, 1.063406904e+00f, 1.085171318e+00f,
    1.106947370e+00f, 1.088639657e+00f, 1.070331945e+00f, 1.092212746e+00f, 1.114105185e+00f,
    1.119284417e+00f, 1.124452010e+00f, 1.127454801e+00f, 1.130457592e+00f, 1.159379820e+00f,
    1.188302048e+00f, 1.180480826e+00f, 1.172659604e+00f, 1.181749447e+00f, 1.190839290e+00f,
    1.177361648e+00f, 1.163872367e+00f, 1.150697332e+00f, 1.137510658e+00f, 1.144400782e+00f,
    1.151279268e+00f, 1.119738327e+00f, 1.088209025e+00f, 1.112580512e+00f, 1.136963638e+00f,
    1.146158230e+00f, 1.155364460e+00f, 1.154037646e+00f, 1.152722470e+00f, 1.133402189e+00f,
    1.114081907e+00f, 1.132331426e+00f, 1.150569306e+00f, 1.132005542e+00f, 1.113441778e+00f,
    1.128129847e+00f, 1.142806277e+00f, 1.170820685e+00f, 1.198823454e+00f, 1.176302524e+00f,
    1.153781594e+00f, 1.085392453e+00f, 1.017003313e+00f, 1.041572659e+00f, 1.066153643e+00f,
    1.073625704e+00f, 1.081109403e+00f, 9.878017555e-01f, 8.944824691e-01f, 9.506858657e-01f,
    1.006877624e+00f, 1.042201150e+00f, 1.077513038e+00f, 9.940051952e-01f, 9.104973528e-01f,
    7.909792995e-01f, 6.714612461e-01f, 8.182837452e-01f, 9.651178830e-01f, 9.380694892e-01f,
    9.110094567e-01f,
};

// sum(illuminant * ybar) over the working shape (the XYZ denominator).
const double kNormD50 = 24.451263183234168;

// colour.XYZ_to_RGB(eye, 'sRGB', illuminant=D50_xy) effective matrix, row-major.
// rgb = M . xyz. Bradford CAT D50->D65 + sRGB primaries baked in.
const float kXYZ_to_sRGB_D50[9] = {
     3.142560531830132e+00f, -1.632613796187014e+00f, -4.818555764518727e-01f,
    -9.697409102368959e-01f,  1.902220529621061e+00f,  3.987257349273618e-02f,
     5.923885096274457e-02f, -2.007043145336758e-01f,  1.386049599349437e+00f,
};

// matrix_RGB_to_RGB(sRGB, sRGB, "CAT02"), row-major. Extracted from
// colour-science via the parity oracle (near-identity, ~1e-5 off-diagonal).
const double kSRGB_to_sRGB_CAT02[9] = {
     0.99999174000000013,    1.1823931167498358e-16, 2.3159999999960313e-05,
     2.1670000000040608e-05, 1.0000403199999999,    -7.9399999999762392e-06,
     3.8000000000042523e-07, 1.1920000000013188e-05, 1.00000355,
};

float srgb_cctf_encode(float linear) {
    // colour: spow(L, 1/2.4) == sign(L)*pow(|L|, 1/2.4); inputs here are >= 0.
    if (linear <= 0.0031308f) return 12.92f * linear;
    return 1.055f * std::pow(linear, 1.0f / 2.4f) - 0.055f;
}

}  // namespace spk
