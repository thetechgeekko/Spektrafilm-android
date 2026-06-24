/*
 * Spektrafilm for Android — native engine: output gamut compression.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports utils/gamut_compression.py::reinhard_knee and
 * compress_rgb_aces_rgc. All math is in double precision to match the oracle
 * (NumPy float64); the knee uses std::pow, the same transcendental NumPy calls.
 */
#include "model/gamut_compression.h"

#include <cmath>
#include <limits>

namespace spk {

double reinhard_knee(double d, double threshold, double limit, double power) {
    // gamut_compression.py::reinhard_knee: identity at/below threshold (the oracle's
    // `mask = d > threshold` is strict, so d == threshold returns d unchanged), and a
    // smooth Reinhard roll-off above it that asymptotes to `limit`.
    if (!(d > threshold)) return d;  // (!(>) also leaves NaN untouched, as np.where would)
    const double scale = limit - threshold;
    const double x = (d - threshold) / scale;
    const double y = x / std::pow(1.0 + std::pow(x, power), 1.0 / power);
    return threshold + scale * y;
}

void compress_pixel_aces_rgc(const double rgb[3], double threshold, double limit,
                             double power, double out[3]) {
    // gamut_compression.py::compress_rgb_aces_rgc, per pixel.
    //   ach = max(R,G,B)
    //   safe_ach = ach if ach > 1e-12 else 1.0
    //   d = (ach - c) / safe_ach   (per channel; d >= 0, and d > 1 iff c < 0)
    //   c' = ach * (1 - reinhard_knee(d))
    //   pixels with ach <= 1e-12 keep their original (near-black) values.
    const double ach = std::fmax(rgb[0], std::fmax(rgb[1], rgb[2]));
    if (!(ach > 1e-12)) {  // near-black (or non-finite ach) -> identity, matching np.where
        out[0] = rgb[0];
        out[1] = rgb[1];
        out[2] = rgb[2];
        return;
    }
    for (int c = 0; c < 3; ++c) {
        const double d = (ach - rgb[c]) / ach;  // safe_ach == ach here (ach > 1e-12)
        const double dc = reinhard_knee(d, threshold, limit, power);
        out[c] = ach * (1.0 - dc);
    }
}

void compress_rgb_aces_rgc(double* rgb, int npix, double threshold, double limit,
                           double power) {
    for (int p = 0; p < npix; ++p) {
        double* px = rgb + static_cast<long>(p) * 3;
        const double in[3] = {px[0], px[1], px[2]};
        compress_pixel_aces_rgc(in, threshold, limit, power, px);
    }
}

// ---------------------------------------------------------------------------
// Input-side: radial xy compression toward the visible spectral locus.
// ---------------------------------------------------------------------------
namespace {

// CIE 1931 2 deg spectral locus, 380..700 nm @ 5 nm, first vertex repeated (closed).
// Captured from colour-science via gamut_compression.py::spectral_locus_xy()
// (tools/parity/gen_gamut_in_golden.py); 66 vertices / 65 edges. Embedded as a
// constant so the radial compression reproduces the oracle bit-for-bit without
// bundling a CMF table — tests/test_gamut_in_xy.cpp re-verifies it against the oracle.
constexpr int kSpectralLocusVerts = 66;
constexpr double kSpectralLocusXy[kSpectralLocusVerts][2] = {
    {0.1741122344263416, 0.00496372598145272},
    {0.17400791751588918, 0.004980548622995036},
    {0.17380077262082788, 0.004915411905373405},
    {0.1735599065272137, 0.004923202577307893},
    {0.17333686548078078, 0.0047967434472668885},
    {0.17302096545549497, 0.004775050361859285},
    {0.17257655084880216, 0.004799301919720766},
    {0.1720866307552483, 0.0048325242180399484},
    {0.17140743386310872, 0.005102170973749332},
    {0.17030098877973637, 0.005788504996470994},
    {0.16887752067098927, 0.00690024388793052},
    {0.16689529035208048, 0.00855560636081898},
    {0.16441175637527494, 0.01085755827676388},
    {0.16110457958027466, 0.013793358821732412},
    {0.15664093257730702, 0.01770480499089134},
    {0.15098540837597124, 0.022740193291642986},
    {0.14396039603960398, 0.029702970297029722},
    {0.13550267119961157, 0.0398791214721278},
    {0.12411847672778563, 0.05780251337374045},
    {0.10959432361561011, 0.08684251118309427},
    {0.0912935070022711, 0.13270204248699013},
    {0.06870592129105556, 0.20072321772810214},
    {0.045390734674777715, 0.29497596460628756},
    {0.02345994254707948, 0.4127034790935206},
    {0.008168028004667443, 0.5384230705117518},
    {0.0038585209003215433, 0.6548231511254019},
    {0.013870246085011192, 0.750186428038777},
    {0.03885180240320428, 0.8120160213618158},
    {0.07430242477337495, 0.833803091340228},
    {0.11416071960667964, 0.8262069597811889},
    {0.15472206121571344, 0.8058635454256492},
    {0.1928760978777212, 0.781629216363077},
    {0.22961967264964017, 0.7543290899027438},
    {0.2657750849711837, 0.7243239249298064},
    {0.3016037993957512, 0.6923077623715741},
    {0.3373633328508564, 0.6588482901396886},
    {0.37310154386845756, 0.624450859796661},
    {0.40873625570642336, 0.5896068688595312},
    {0.44406246358233303, 0.5547139028085305},
    {0.47877479115758376, 0.5202023072114564},
    {0.5124863667817968, 0.48659078806085704},
    {0.5447865055948337, 0.45443411456883603},
    {0.5751513113651648, 0.42423223492490464},
    {0.6029327855757162, 0.3964966335729773},
    {0.6270365997638726, 0.37249114521841786},
    {0.6482331060136394, 0.35139491630502157},
    {0.6657635762380971, 0.33401065115476053},
    {0.680078849721707, 0.31974721706864556},
    {0.6915039729617021, 0.30834226055665565},
    {0.7006060606060607, 0.29930069930069925},
    {0.7079177916216642, 0.2920271089348396},
    {0.7140315971169937, 0.2859288735456499},
    {0.7190329416297438, 0.280934951518654},
    {0.7230316025730948, 0.27694835774834164},
    {0.7259923175416133, 0.2740076824583867},
    {0.7282717282717283, 0.27172827172827163},
    {0.7299690128375388, 0.27003098716246127},
    {0.7310893955845097, 0.2689106044154904},
    {0.7319932998324957, 0.26800670016750433},
    {0.7327188940092165, 0.2672811059907835},
    {0.7334169672259683, 0.2665830327740317},
    {0.7340473003123604, 0.2659526996876395},
    {0.7343901649951473, 0.2656098350048527},
    {0.7345916616426285, 0.2654083383573716},
    {0.7346900232582807, 0.2653099767417192},
    {0.1741122344263416, 0.00496372598145272},
};

// gamut_compression.py::_ray_polygon_distance: distance from `origin` along `dir` to
// the first intersection with the closed locus polygon, via parametric segment
// intersection. denom = dir.x*ey - dir.y*ex; valid iff |denom| > 1e-12; accept the
// hit iff t > 1e-9 and 0 <= s <= 1. Returns +inf on a miss (numpy t_min init = inf).
double ray_polygon_distance(const double origin[2], const double dir[2]) {
    double t_min = std::numeric_limits<double>::infinity();
    for (int k = 0; k < kSpectralLocusVerts - 1; ++k) {
        const double ax = kSpectralLocusXy[k][0], ay = kSpectralLocusXy[k][1];
        const double ex = kSpectralLocusXy[k + 1][0] - ax;
        const double ey = kSpectralLocusXy[k + 1][1] - ay;
        const double denom = dir[0] * ey - dir[1] * ex;
        if (std::fabs(denom) > 1e-12) {
            const double ox = origin[0] - ax, oy = origin[1] - ay;
            const double t = (-ox * ey + oy * ex) / denom;
            const double s = (-ox * dir[1] + oy * dir[0]) / denom;
            if (t > 1e-9 && s >= 0.0 && s <= 1.0 && t < t_min) t_min = t;
        }
    }
    return t_min;
}

}  // namespace

int spectral_locus_xy(const double** out_xy) {
    *out_xy = &kSpectralLocusXy[0][0];
    return kSpectralLocusVerts;
}

void compress_pixel_xy(const double xy[2], const double white_xy[2], double threshold,
                       double limit, double power, double out[2]) {
    // gamut_compression.py::compress_xy_radial, per point.
    //   delta = xy - white; dist = |delta|; dir = delta / fmax(dist, 1e-12)
    //   boundary = ray_polygon_distance(white, dir); d_norm = dist / fmax(boundary, 1e-12)
    //   new_xy = white + dir * (reinhard_knee(d_norm) * boundary)
    //   return where(dist < 1e-9, xy, new_xy)   (at-white passthrough)
    const double dx = xy[0] - white_xy[0];
    const double dy = xy[1] - white_xy[1];
    const double dist = std::sqrt(dx * dx + dy * dy);
    const double safe_dist = std::fmax(dist, 1e-12);
    const double dir[2] = {dx / safe_dist, dy / safe_dist};
    const double boundary = ray_polygon_distance(white_xy, dir);
    const double d_norm = dist / std::fmax(boundary, 1e-12);
    const double d_comp = reinhard_knee(d_norm, threshold, limit, power);
    // 0 * inf == NaN on a genuine ray miss (boundary == inf), as numpy propagates;
    // for an interior white_xy every ray hits, so boundary is finite in practice.
    const double scaled = d_comp * boundary;
    const double nx = white_xy[0] + dir[0] * scaled;
    const double ny = white_xy[1] + dir[1] * scaled;
    if (dist < 1e-9) {  // np.where((dist < 1e-9), xy, new_xy): at-white passthrough
        out[0] = xy[0];
        out[1] = xy[1];
    } else {
        out[0] = nx;
        out[1] = ny;
    }
}

void compress_xy_radial(double* xy, int npix, const double white_xy[2],
                        double threshold, double limit, double power) {
    for (int p = 0; p < npix; ++p) {
        double* q = xy + static_cast<long>(p) * 2;
        const double in[2] = {q[0], q[1]};
        compress_pixel_xy(in, white_xy, threshold, limit, power, q);
    }
}

}  // namespace spk
