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
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "kernels/fft_convolve.h"
#include "kernels/gaussian.h"
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


// ---------------------------------------------------------------------------
// The PSF table above measures the FIT. It is not the number that decides anything,
// and reading it as though it were is how this route got mis-diagnosed once already.
//
// The fit is the difference between the exact exponential PSF and the mixture PSF.
// The ROUTE also differs from the exact path in how it APPLIES that mixture: not as
// one sampled kernel through an exact convolution, but as N independent separable
// Gaussian blurs, which for every sigma >= kSmallSigmaMax means Young-van Vliet IIR.
//
// Measured below, the two halves behave in opposite ways as `terms` grows. The FIT
// converges (max_rel 6.5e-2 -> 4.6e-3 from 3 to 12 terms). The APPLIED result does
// not move (2.2e-1 -> 2.0e-1). So the route's error is the blur PRIMITIVE on
// high-dynamic-range content, and adding components cannot reach it.
//
// Why the primitive: YvV is an impulse-response approximation, and a specular
// highlight is nearly an impulse. On a smooth field it is accurate (rel_rms <= 7e-3
// at sigma 80); on 1e3 speculars over a 0.02 field one blur is already 2-6% rel_rms
// and up to 13% of peak, and the mixture stacks 27-99 of them.

// Bright speculars on a dim gradient -- the content a diffusion filter is bought for,
// and the content that separates the two halves. Same scene as
// tools/gpu_probe/probe_diffusion_main.cpp so the numbers are comparable to the
// device measurement that produced the 18-37% figure in runtime/params.h.
void fill_scene(std::vector<double>& img, int w, int h) {
    img.assign(static_cast<size_t>(w) * h * 3u, 0.0);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const size_t i = (static_cast<size_t>(y) * w + x) * 3u;
            const double base = 0.02 + 0.08 * (static_cast<double>(x) / w);
            img[i] = base;
            img[i + 1] = base * 0.9;
            img[i + 2] = base * 0.8;
        }
    const int pts[][2] = {{1, 3}, {2, 2}, {3, 4}, {5, 5}, {7, 2}};
    for (const auto& q : pts) {
        const int cx = w * q[0] / 8, cy = h * q[1] / 8;
        for (int dy = -2; dy <= 2; ++dy)
            for (int dx = -2; dx <= 2; ++dx) {
                const int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                const size_t i = (static_cast<size_t>(y) * w + x) * 3u;
                img[i] = 1.0e3;
                img[i + 1] = 9.0e2;
                img[i + 2] = 7.5e2;
            }
    }
}

// numpy 'reflect', the pad apply_diffusion_filter_um uses.
int reflect_idx(int p, int n) {
    if (n == 1) return 0;
    int period = 2 * (n - 1);
    int m = p % period;
    if (m < 0) m += period;
    return m < n ? m : period - m;
}

struct Err {
    double max_rel, rel_rms;
};

Err compare_img(const std::vector<double>& ref, const std::vector<double>& got) {
    double worst = 0.0, sq = 0.0, refsq = 0.0;
    for (size_t i = 0; i < ref.size(); ++i) {
        const double d = std::fabs(ref[i] - got[i]);
        sq += d * d;
        refsq += ref[i] * ref[i];
        const double den = std::fabs(ref[i]) > 1e-9 ? std::fabs(ref[i]) : 1e-9;
        const double rel = d / den;
        if (rel > worst) worst = rel;
    }
    Err e;
    e.max_rel = worst;
    e.rel_rms = refsq > 0.0 ? std::sqrt(sq / refsq) : 0.0;
    return e;
}

void fit_vs_apply(const Cell& c, int w, int h, double pixel_size_um) {
    spk::DiffusionFilterParams p;
    p.active = true;
    p.family = c.family;
    p.strength = c.strength;
    p.spatial_scale = c.spatial_scale;

    std::vector<double> scene;
    fill_scene(scene, w, h);
    std::vector<double> exact(scene);
    spk::apply_diffusion_filter_um(exact.data(), w, h, p, pixel_size_um, false);

    std::printf("  %-16s str=%-4.2f scale=%-4.1f\n", c.name, c.strength, c.spatial_scale);
    std::printf("    %-5s %-6s | %-21s | %-21s\n", "terms", "comps",
                "FIT  (same operator)", "APPLY (the route)");
    std::printf("    %-5s %-6s | %-10s %-10s | %-10s %-10s\n", "", "",
                "max_rel", "rel_rms", "max_rel", "rel_rms");

    for (int terms : {3, 5, 7, 9, 12}) {
        std::vector<spk::DiffusionGaussian> comps;
        double p_s = 0.0;
        int radius = 0;
        if (!spk::build_diffusion_mixture(p, pixel_size_um, w, h, terms, &comps, &p_s,
                                          &radius))
            return;
        const int ks = 2 * radius + 1;

        // FIT: the mixture sampled on the exact path's own truncated grid, jointly
        // sum-normalised there, pushed through the exact path's own FFT. Only the
        // exp->Gauss fit differs from `exact`.
        const int cc = ks / 2;
        std::vector<std::vector<double>> mix(
            3, std::vector<double>(static_cast<size_t>(ks) * ks, 0.0));
        double msum[3] = {0.0, 0.0, 0.0};
        for (int yy = 0; yy < ks; ++yy)
            for (int xx = 0; xx < ks; ++xx) {
                const double dx = xx - cc, dy = yy - cc;
                const double r2 = dx * dx + dy * dy;
                const size_t idx = static_cast<size_t>(yy) * ks + xx;
                for (const spk::DiffusionGaussian& g : comps) {
                    const double s2 = g.sigma_px * g.sigma_px;
                    const double e = std::exp(-r2 / (2.0 * s2)) / (2.0 * M_PI * s2);
                    for (int ch = 0; ch < 3; ++ch) mix[ch][idx] += g.weight[ch] * e;
                }
                for (int ch = 0; ch < 3; ++ch) msum[ch] += mix[ch][idx];
            }
        for (int ch = 0; ch < 3; ++ch)
            if (msum[ch] > 0.0)
                for (double& v : mix[ch]) v /= msum[ch];

        const int pw = w + 2 * radius, ph = h + 2 * radius;
        std::vector<double> padded(static_cast<size_t>(pw) * ph);
        std::vector<double> fit_out(scene.size());
        for (int ch = 0; ch < 3; ++ch) {
            for (int yy = 0; yy < ph; ++yy) {
                const int sy = reflect_idx(yy - radius, h);
                for (int xx = 0; xx < pw; ++xx)
                    padded[static_cast<size_t>(yy) * pw + xx] =
                        scene[(static_cast<size_t>(sy) * w +
                               reflect_idx(xx - radius, w)) * 3 + ch];
            }
            spk::fft_convolve_same<double>(padded.data(), pw, ph, mix[ch].data(), ks,
                                           w, h, fit_out.data(), 3, ch);
        }
        for (size_t i = 0; i < fit_out.size(); ++i)
            fit_out[i] = (1.0 - p_s) * scene[i] + p_s * fit_out[i];

        // APPLY: the route's own arithmetic -- one independent separable f32 Gaussian
        // per component, FIR below kSmallSigmaMax and Young-van Vliet IIR above.
        const size_t npix = static_cast<size_t>(w) * h;
        std::vector<double> app_out(scene.size(), 0.0);
        std::vector<float> plane(npix), acc(npix);
        for (int ch = 0; ch < 3; ++ch) {
            std::fill(acc.begin(), acc.end(), 0.0f);
            for (const spk::DiffusionGaussian& g : comps) {
                if (g.weight[ch] == 0.0) continue;
                for (size_t i = 0; i < npix; ++i)
                    plane[i] = static_cast<float>(scene[i * 3 + ch]);
                spk::gaussian_blur_plane(plane.data(), w, h,
                                         static_cast<float>(g.sigma_px));
                const float wt = static_cast<float>(g.weight[ch]);
                for (size_t i = 0; i < npix; ++i) acc[i] += wt * plane[i];
            }
            for (size_t i = 0; i < npix; ++i)
                app_out[i * 3 + ch] = (1.0 - p_s) * scene[i * 3 + ch] +
                                      p_s * static_cast<double>(acc[i]);
        }

        const Err f = compare_img(exact, fit_out);
        const Err a = compare_img(exact, app_out);
        std::printf("    %-5d %-6zu | %-10.4f %-10.2e | %-10.4f %-10.2e\n", terms,
                    comps.size(), f.max_rel, f.rel_rms, a.max_rel, a.rel_rms);
        std::fflush(stdout);
    }
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

    // The half that decides the route. Run at a size where the exact path is
    // affordable to run five more times; the split does not depend on resolution.
    std::printf("-- fit vs apply, on speculars (1920x1080) --\n");
    const double ps_hd = 36.0 * 1000.0 / 1920.0;
    fit_vs_apply(cells[2], 1920, 1080, ps_hd);
    std::printf("\n");

    std::printf("Reference points: the engine's oracle tolerance is max_abs 1e-4;\n"
                "the shipping GPU halation pass measures ~2.1e-7 against its CPU pass.\n");
    (void)family_name;
    return 0;
}
