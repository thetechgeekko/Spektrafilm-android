// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
//
// How wrong is the separable Gaussian-mixture diffusion PSF, really? (#213)
//
// The exact diffusion filter is a sum of 2D isotropic EXPONENTIALS. That is not
// separable, so the engine convolves it with an FFT -- and it is the largest stage of
// a real export (13559 ms of 25319 on a 12.5 MP frame) with no GPU path at all.
//
// A sum of GAUSSIANS is separable, which is what makes a GPU port cheap and, once the
// wide components run at reduced resolution, roughly independent of the filter radius.
// The cost is accuracy, and the cost is not uniform: an exponential has a CUSP at
// r = 0 and every Gaussian is flat there, so the error concentrates exactly on the
// bright core of the bloom, which is the part a viewer looks at.
//
// This is a BENCHMARK, not a gate. It prints numbers so the tier decision in #209 can
// be made against measurements instead of adjectives. Nothing asserts, because the
// right threshold is a product decision that has not been made yet.
#include <cstdio>
#include <string>
#include <vector>

#include "model/diffusion.h"

namespace {

struct Cell {
    const char* name;
    spk::DiffusionFamily family;
    double strength;
    double spatial_scale;
};

const char* family_name(spk::DiffusionFamily f) {
    switch (f) {
        case spk::DiffusionFamily::kBlackProMist: return "black_pro_mist";
        case spk::DiffusionFamily::kProMist:      return "pro_mist";
        default:                                  return "other";
    }
}

void row(const Cell& c, double pixel_size_um, int w, int h, int terms) {
    double max_abs = 0.0, rms = 0.0;
    if (!spk::diffusion_mixture_psf_error(
            [&] {
                spk::DiffusionFilterParams p;
                p.active = true;
                p.family = c.family;
                p.strength = c.strength;
                p.spatial_scale = c.spatial_scale;
                return p;
            }(),
            pixel_size_um, w, h, terms, &max_abs, &rms)) {
        std::printf("  %-16s str=%-4.2f scale=%-4.1f terms=%d   (no-op)\n", c.name,
                    c.strength, c.spatial_scale, terms);
        return;
    }
    std::vector<spk::DiffusionGaussian> comps;
    double p_s = 0.0;
    int radius = 0;
    spk::DiffusionFilterParams p;
    p.active = true;
    p.family = c.family;
    p.strength = c.strength;
    p.spatial_scale = c.spatial_scale;
    spk::build_diffusion_mixture(p, pixel_size_um, w, h, terms, &comps, &p_s, &radius);

    // The PSF is unit-sum, so an absolute error is directly "fraction of the kernel's
    // total energy landing in the wrong place at one tap".
    std::printf("  %-16s str=%-4.2f scale=%-4.1f terms=%d  comps=%-3zu radius=%-5d "
                "p_s=%.4f  max_abs=%.3e  rms=%.3e\n",
                c.name, c.strength, c.spatial_scale, terms, comps.size(), radius, p_s,
                max_abs, rms);
}

}  // namespace

int main(int argc, char** argv) {
    // 12.5 MP at 35 mm is the geometry the export measurement came from; 1080p is the
    // realtime target. pixel_size_um = film_format_mm * 1000 / longest_edge.
    const int w = (argc > 1) ? std::atoi(argv[1]) : 4080;
    const int h = (argc > 2) ? std::atoi(argv[2]) : 3060;
    const double pixel_size_um = 36.0 * 1000.0 / static_cast<double>(w > h ? w : h);

    std::printf("diffusion mixture vs exact PSF\n");
    std::printf("frame %dx%d, pixel_size_um=%.4f\n\n", w, h, pixel_size_um);

    const Cell cells[] = {
        {"BlackProMist", spk::DiffusionFamily::kBlackProMist, 0.25, 1.0},
        {"BlackProMist", spk::DiffusionFamily::kBlackProMist, 0.50, 1.0},
        {"BlackProMist", spk::DiffusionFamily::kBlackProMist, 1.00, 1.0},
        {"BlackProMist", spk::DiffusionFamily::kBlackProMist, 0.50, 0.5},
        {"BlackProMist", spk::DiffusionFamily::kBlackProMist, 0.50, 2.0},
        {"ProMist",      spk::DiffusionFamily::kProMist,      0.50, 1.0},
        {"ProMist",      spk::DiffusionFamily::kProMist,      1.00, 1.0},
    };

    for (int terms : {3, 5, 7}) {
        std::printf("-- %d Gaussians per exponential%s --\n", terms,
                    terms == 3 ? "  (the upstream OFX fit)" : "");
        for (const Cell& c : cells) row(c, pixel_size_um, w, h, terms);
        std::printf("\n");
    }

    std::printf("Reference points: the engine's oracle tolerance is max_abs 1e-4;\n"
                "the shipping GPU halation pass measures ~2.1e-7 against its CPU pass.\n");
    (void)family_name;
    return 0;
}
