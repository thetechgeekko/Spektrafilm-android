/*
 * SpectraFilm for Android — native engine: crop / resize geometry stage.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. See crop_resize.h for the parity contract.
 */
#include "runtime/stages/crop_resize.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace spk {
namespace {

// numpy's np.round (banker's rounding / round-half-to-even). Used to match the
// crop_image rounding and skimage's output_shape = round(scale * shape).
double np_round(double x) {
    return std::nearbyint(x);  // honours the current rounding mode (default: to-nearest-even)
}

// ----------------------------------------------------------------------------
// Cubic B-spline prefilter (scipy.ndimage.spline_filter1d, order=3, mode=mirror)
// applied along one axis. Operates on a strided 1D line of length n.
// ----------------------------------------------------------------------------
void spline_prefilter_line(double* c, int n) {
    if (n <= 1) return;
    const double z = std::sqrt(3.0) - 2.0;  // the single pole for cubic splines
    const double lam = (1.0 - z) * (1.0 - 1.0 / z);
    for (int i = 0; i < n; ++i) c[i] *= lam;

    // Causal initialisation for the 'mirror' boundary. scipy truncates the
    // geometric sum at a tolerance-derived horizon; if the horizon reaches the
    // signal length it uses the exact mirror closed form. eps = 2^-52.
    const double tol = 2.220446049250313e-16;
    int horizon = n;
    {
        const double h = std::ceil(std::log(tol) / std::log(std::fabs(z)));
        if (h < static_cast<double>(n)) horizon = static_cast<int>(h);
    }
    if (horizon < n) {
        double zn = z, s = c[0];
        for (int i = 1; i < horizon; ++i) { s += zn * c[i]; zn *= z; }
        c[0] = s;
    } else {
        double zn = z, s = c[0];
        for (int i = 1; i < n; ++i) { s += zn * c[i]; zn *= z; }
        zn = std::pow(z, static_cast<double>(n));
        for (int i = n - 2; i > 0; --i) { s += zn * c[i]; zn *= z; }
        c[0] = s / (1.0 - std::pow(z, static_cast<double>(2 * (n - 1))));
    }
    // Causal recursion.
    for (int i = 1; i < n; ++i) c[i] += z * c[i - 1];
    // Anti-causal initialisation (mirror) + recursion.
    c[n - 1] = (z / (z * z - 1.0)) * (c[n - 1] + z * c[n - 2]);
    for (int i = n - 2; i >= 0; --i) c[i] = z * (c[i + 1] - c[i]);
}

// 'mirror' boundary index map (scipy NI_EXTEND_MIRROR): reflect about the end
// samples WITHOUT repeating them, period 2*(n-1).
int mirror_index(int i, int n) {
    if (n == 1) return 0;
    const int period = 2 * (n - 1);
    i %= period;
    if (i < 0) i += period;
    if (i >= n) i = period - i;
    return i;
}

// Cubic B-spline interpolation weights for fractional offset t in [0,1), giving
// the contributions of the four coefficients at indices floor-1..floor+2.
void cubic_weights(double t, double w[4]) {
    const double u = 1.0 - t;
    w[0] = (1.0 / 6.0) * u * u * u;
    w[1] = (2.0 / 3.0) - 0.5 * t * t * (2.0 - t);
    w[2] = (2.0 / 3.0) - 0.5 * u * u * (2.0 - u);
    w[3] = (1.0 / 6.0) * t * t * t;
}

}  // namespace

// scipy.ndimage.zoom(order=3, mode='mirror', grid_mode=True) for interleaved RGB,
// implemented as two separable 1D cubic-spline passes over the prefiltered
// coefficients, then a clip to the input [min, max] (skimage clip=True).
void rescale_cubic_rgb(const double* in, int w, int h, double factor,
                       std::vector<double>* out, int* out_w, int* out_h) {
    // skimage: output_shape = maximum(round(scale * shape), 1); channels kept.
    int oh = static_cast<int>(np_round(factor * static_cast<double>(h)));
    int ow = static_cast<int>(np_round(factor * static_cast<double>(w)));
    if (oh < 1) oh = 1;
    if (ow < 1) ow = 1;

    // Input value range for the final clip (over all channels, like skimage).
    double in_min = in[0], in_max = in[0];
    const size_t in_n = static_cast<size_t>(w) * h * 3;
    for (size_t i = 1; i < in_n; ++i) {
        if (in[i] < in_min) in_min = in[i];
        if (in[i] > in_max) in_max = in[i];
    }

    // 1) Prefilter the input into B-spline coefficients along both spatial axes.
    //    Channels are independent. Work buffer holds (h x w x 3) doubles.
    std::vector<double> coef(in, in + in_n);
    // Along rows (x / width axis): for each row y and channel c, filter w samples.
    {
        std::vector<double> line(w);
        for (int y = 0; y < h; ++y) {
            for (int c = 0; c < 3; ++c) {
                for (int x = 0; x < w; ++x)
                    line[x] = coef[(static_cast<size_t>(y) * w + x) * 3 + c];
                spline_prefilter_line(line.data(), w);
                for (int x = 0; x < w; ++x)
                    coef[(static_cast<size_t>(y) * w + x) * 3 + c] = line[x];
            }
        }
    }
    // Along columns (y / height axis): for each column x and channel c, filter h.
    {
        std::vector<double> line(h);
        for (int x = 0; x < w; ++x) {
            for (int c = 0; c < 3; ++c) {
                for (int y = 0; y < h; ++y)
                    line[y] = coef[(static_cast<size_t>(y) * w + x) * 3 + c];
                spline_prefilter_line(line.data(), h);
                for (int y = 0; y < h; ++y)
                    coef[(static_cast<size_t>(y) * w + x) * 3 + c] = line[y];
            }
        }
    }

    // 2) Interpolate along width (h x w x 3) -> (h x ow x 3).
    const double zoom_x = static_cast<double>(ow) / static_cast<double>(w);
    std::vector<double> tmp(static_cast<size_t>(h) * ow * 3);
    for (int ox = 0; ox < ow; ++ox) {
        const double xx = (static_cast<double>(ox) + 0.5) / zoom_x - 0.5;
        const int fl = static_cast<int>(std::floor(xx));
        double wt[4];
        cubic_weights(xx - fl, wt);
        int idx[4];
        for (int k = 0; k < 4; ++k) idx[k] = mirror_index(fl - 1 + k, w);
        for (int y = 0; y < h; ++y) {
            for (int c = 0; c < 3; ++c) {
                double acc = 0.0;
                for (int k = 0; k < 4; ++k)
                    acc += wt[k] * coef[(static_cast<size_t>(y) * w + idx[k]) * 3 + c];
                tmp[(static_cast<size_t>(y) * ow + ox) * 3 + c] = acc;
            }
        }
    }

    // 3) Interpolate along height (h x ow x 3) -> (oh x ow x 3).
    const double zoom_y = static_cast<double>(oh) / static_cast<double>(h);
    out->assign(static_cast<size_t>(oh) * ow * 3, 0.0);
    for (int oy = 0; oy < oh; ++oy) {
        const double yy = (static_cast<double>(oy) + 0.5) / zoom_y - 0.5;
        const int fl = static_cast<int>(std::floor(yy));
        double wt[4];
        cubic_weights(yy - fl, wt);
        int idx[4];
        for (int k = 0; k < 4; ++k) idx[k] = mirror_index(fl - 1 + k, h);
        for (int ox = 0; ox < ow; ++ox) {
            for (int c = 0; c < 3; ++c) {
                double acc = 0.0;
                for (int k = 0; k < 4; ++k)
                    acc += wt[k] * tmp[(static_cast<size_t>(idx[k]) * ow + ox) * 3 + c];
                double v = acc;
                if (v < in_min) v = in_min;  // skimage clip=True
                if (v > in_max) v = in_max;
                (*out)[(static_cast<size_t>(oy) * ow + ox) * 3 + c] = v;
            }
        }
    }

    *out_w = ow;
    *out_h = oh;
}

// crop_image(image, center, size) — utils/crop_resize.py. center is flipped to
// (y, x); size is a fraction of the LONG side, also flipped to (y, x).
//   cn  = round(shape * center_yx)
//   sz  = round(max(shape) * size_yx)            (int64)
//   x0  = round(cn - sz/2)                        (int64)
//   x0[x0<0] = 0
//   if x0[0]+sz[0] > H: x0[0] = H - sz[0]
//   if x0[1]+sz[1] > W: x0[1] = W - sz[1]
//   crop = image[x0[0]:x0[0]+sz[0], x0[1]:x0[1]+sz[1], :]
static void crop_image(const double* in, int h, int w,
                       const double center[2], const double size[2],
                       std::vector<double>* out, int* out_w, int* out_h) {
    const double shape0 = static_cast<double>(h);  // rows
    const double shape1 = static_cast<double>(w);  // cols
    const double max_shape = shape0 > shape1 ? shape0 : shape1;

    // center is (x, y); np.flip -> (y, x).
    const double cn0 = np_round(shape0 * center[1]);  // row center
    const double cn1 = np_round(shape1 * center[0]);  // col center
    // size is (x, y); np.flip -> (y, x). np.round then np.int64 (truncation).
    long long sz0 = static_cast<long long>(np_round(max_shape * size[1]));  // rows
    long long sz1 = static_cast<long long>(np_round(max_shape * size[0]));  // cols
    long long x00 = static_cast<long long>(np_round(cn0 - sz0 / 2.0));
    long long x01 = static_cast<long long>(np_round(cn1 - sz1 / 2.0));
    if (x00 < 0) x00 = 0;
    if (x01 < 0) x01 = 0;
    if (x00 + sz0 > h) x00 = h - sz0;
    if (x01 + sz1 > w) x01 = w - sz1;

    // NumPy slicing clamps a negative start to 0 and an oversize stop to the
    // dimension; a start past the end yields an empty axis. Mirror that so we
    // never read out of bounds while preserving the resulting extent.
    long long r0 = x00, r1 = x00 + sz0;
    long long c0 = x01, c1 = x01 + sz1;
    if (r0 < 0) r0 = 0; if (r0 > h) r0 = h;
    if (r1 < 0) r1 = 0; if (r1 > h) r1 = h;
    if (c0 < 0) c0 = 0; if (c0 > w) c0 = w;
    if (c1 < 0) c1 = 0; if (c1 > w) c1 = w;
    const int ch = static_cast<int>(r1 > r0 ? r1 - r0 : 0);
    const int cw = static_cast<int>(c1 > c0 ? c1 - c0 : 0);

    out->assign(static_cast<size_t>(ch) * cw * 3, 0.0);
    for (int y = 0; y < ch; ++y) {
        for (int x = 0; x < cw; ++x) {
            const size_t src = (static_cast<size_t>(r0 + y) * w + (c0 + x)) * 3;
            const size_t dst = (static_cast<size_t>(y) * cw + x) * 3;
            (*out)[dst + 0] = in[src + 0];
            (*out)[dst + 1] = in[src + 1];
            (*out)[dst + 2] = in[src + 2];
        }
    }
    *out_h = ch;
    *out_w = cw;
}

void crop_and_rescale(const double* in, int w, int h,
                      const CropResizeParams& params,
                      std::vector<double>* out, int* out_w, int* out_h,
                      double* pixel_size_um) {
    const double* cur = in;
    int cw = w, ch = h;
    std::vector<double> cropped;

    if (params.crop) {
        crop_image(cur, ch, cw, params.crop_center, params.crop_size,
                   &cropped, &cw, &ch);
        cur = cropped.data();
    }

    if (params.upscale_factor != 1.0) {
        if (pixel_size_um) *pixel_size_um /= params.upscale_factor;
        rescale_cubic_rgb(cur, cw, ch, params.upscale_factor, out, out_w, out_h);
        return;
    }

    // No rescale: emit the (possibly cropped) image as-is.
    out->assign(cur, cur + static_cast<size_t>(cw) * ch * 3);
    *out_w = cw;
    *out_h = ch;
}

}  // namespace spk
