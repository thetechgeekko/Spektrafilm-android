/*
 * Spektrafilm for Android — native engine: auto-exposure metering stage.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. See autoexposure.h for the parity contract.
 */
#include "runtime/stages/autoexposure.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <vector>

#include "runtime/stages/crop_resize.h"  // build_gaussian_kernel (shared AA kernel)

namespace spk {
namespace {

// Luminance Y for one RGB pixel = the Y (index-1) row of
// colour.RGB_to_XYZ(cs, apply_cctf_decoding=False). For ProPhoto/sRGB the
// transform with illuminant=None applies NO chromatic adaptation (the source
// and target whitepoint are equal), so it reduces to a single dot product with
// the matrix's Y row. Values baked from colour-science 0.4.x
// (cs.matrix_RGB_to_XYZ[1]).
//
// ProPhoto RGB (D50):  [0.288, 0.7119, 0.0001]
// sRGB (D65, BT.709):  [0.21263900587151036, 0.7151686787677559,
//                       0.07219231536073371]
struct YRow { double r, g, b; };

YRow y_row(AeColorSpace cs) {
    switch (cs) {
        case AeColorSpace::kProPhotoRGB:
            return {0.288, 0.7119, 0.0001};
        case AeColorSpace::kSRGB:
        default:
            return {0.21263900587151036, 0.7151686787677559,
                    0.07219231536073371};
    }
}

// colour.cctf_decoding inverse EOTF (only used when apply_cctf_decoding=True,
// which the engine never sets by default). sRGB uses the IEC 61966-2-1 piecewise
// curve; ProPhoto RGB (ROMM) uses its 1.8 gamma with a linear toe.
double cctf_decode(AeColorSpace cs, double v) {
    if (cs == AeColorSpace::kSRGB) {
        return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
    }
    // ProPhoto RGB (ROMM RGB): Et = 16 * Es is the break (Es = 1/512).
    const double Et = 16.0 / 512.0;
    return v < Et ? v / 16.0 : std::pow(v, 1.8);
}

// Per-pixel luminance buffer Y (length w*h, row-major).
std::vector<double> luminance_y(const double* image, int w, int h,
                                AeColorSpace cs, bool apply_cctf_decoding) {
    const YRow yr = y_row(cs);
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<double> Y(n);
    for (size_t i = 0; i < n; ++i) {
        double r = image[i * 3 + 0];
        double g = image[i * 3 + 1];
        double b = image[i * 3 + 2];
        if (apply_cctf_decoding) {
            r = cctf_decode(cs, r);
            g = cctf_decode(cs, g);
            b = cctf_decode(cs, b);
        }
        Y[i] = yr.r * r + yr.g * g + yr.b * b;
    }
    return Y;
}

// _normalized_coords: x over width, y over height; the LONG edge spans
// [-0.5, 0.5]. norm_shape = shape[0:2] / max(shape[0:2]); note shape is (H, W)
// so norm_shape[0] scales y (rows) and norm_shape[1] scales x (cols).
void normalized_coords(int w, int h, std::vector<double>* x,
                       std::vector<double>* y) {
    const double m = static_cast<double>(std::max(h, w));
    const double ny = static_cast<double>(h) / m;  // norm_shape[0]
    const double nx = static_cast<double>(w) / m;  // norm_shape[1]
    x->resize(w);
    y->resize(h);
    for (int i = 0; i < w; ++i)
        (*x)[i] = (static_cast<double>(i) / static_cast<double>(w) - 0.5) * nx;
    for (int j = 0; j < h; ++j)
        (*y)[j] = (static_cast<double>(j) / static_cast<double>(h) - 0.5) * ny;
}

const double kMidgray = 0.184;
const double kPi = 3.141592653589793;

// np.median of a copied buffer: sort, average the two middle elements for even
// length (numpy's exact midpoint rule), middle element for odd.
double np_median(std::vector<double> v) {
    const size_t n = v.size();
    if (n == 0) return 0.0;
    std::sort(v.begin(), v.end());
    if (n & 1) return v[n / 2];
    return 0.5 * (v[n / 2 - 1] + v[n / 2]);
}

}  // namespace

bool ae_method_from_string(const char* s, AeMethod* out) {
    if (!s) return false;
    struct M { const char* name; AeMethod v; };
    static const M table[] = {
        {"center_weighted", AeMethod::kCenterWeighted},
        {"average", AeMethod::kAverage},
        {"median", AeMethod::kMedian},
        {"partial", AeMethod::kPartial},
        {"matrix", AeMethod::kMatrix},
        {"multi_zone", AeMethod::kMultiZone},
        {"highlight_weighted", AeMethod::kHighlightWeighted},
    };
    for (const M& m : table) {
        if (std::strcmp(s, m.name) == 0) { *out = m.v; return true; }
    }
    return false;
}

double measure_autoexposure_ev(const double* image, int w, int h,
                               AeColorSpace cs, bool apply_cctf_decoding,
                               AeMethod method, bool known_method) {
    std::vector<double> Y = luminance_y(image, w, h, cs, apply_cctf_decoding);
    const size_t n = Y.size();

    double exposure;

    if (!known_method) {
        // The Python `else` branch: exposure = 1.0 (-> ev = 0).
        exposure = 1.0;
    } else if (method == AeMethod::kAverage) {
        double s = 0.0;
        for (double v : Y) s += v;
        exposure = (s / static_cast<double>(n)) / kMidgray;
    } else if (method == AeMethod::kMedian) {
        exposure = np_median(Y) / kMidgray;
    } else if (method == AeMethod::kCenterWeighted) {
        std::vector<double> x, y;
        normalized_coords(w, h, &x, &y);
        const double sigma = 0.2;
        const double inv2s2 = 1.0 / (2.0 * sigma * sigma);
        // mask = exp(-(x^2 + y^2)/(2 sigma^2)); mask /= sum(mask);
        // exposure = sum(Y * mask) / 0.184.
        std::vector<double> mask(n);
        double mask_sum = 0.0;
        for (int j = 0; j < h; ++j) {
            const double yy = y[j] * y[j];
            for (int i = 0; i < w; ++i) {
                double mval = std::exp(-(x[i] * x[i] + yy) * inv2s2);
                mask[static_cast<size_t>(j) * w + i] = mval;
                mask_sum += mval;
            }
        }
        double acc = 0.0;
        for (size_t i = 0; i < n; ++i) acc += Y[i] * (mask[i] / mask_sum);
        exposure = acc / kMidgray;
    } else if (method == AeMethod::kPartial) {
        std::vector<double> x, y;
        normalized_coords(w, h, &x, &y);
        double s = 0.0;
        size_t cnt = 0;
        for (int j = 0; j < h; ++j) {
            const double yy = y[j] * y[j];
            for (int i = 0; i < w; ++i) {
                double radius = std::sqrt(x[i] * x[i] + yy);
                if (radius < 0.15) { s += Y[static_cast<size_t>(j) * w + i]; ++cnt; }
            }
        }
        if (cnt == 0) {  // mask.sum()==0 -> mask = all True
            s = 0.0;
            for (double v : Y) s += v;
            cnt = n;
        }
        exposure = (s / static_cast<double>(cnt)) / kMidgray;
    } else if (method == AeMethod::kMatrix) {
        const int n_rows = 5, n_cols = 5;
        const int cell_h = h / n_rows;
        const int cell_w = w / n_cols;
        std::vector<double> zone_means, zone_weights;
        for (int r = 0; r < n_rows; ++r) {
            for (int c = 0; c < n_cols; ++c) {
                const int ys = r * cell_h, ye = (r + 1) * cell_h;
                const int xs = c * cell_w, xe = (c + 1) * cell_w;
                if ((ye - ys) <= 0 || (xe - xs) <= 0) continue;  // cell.size==0
                double s = 0.0;
                size_t cnt = 0;
                for (int jj = ys; jj < ye; ++jj)
                    for (int ii = xs; ii < xe; ++ii) {
                        s += Y[static_cast<size_t>(jj) * w + ii];
                        ++cnt;
                    }
                zone_means.push_back(s / static_cast<double>(cnt));
                double dy = (static_cast<double>(r) - (n_rows - 1) / 2.0) /
                            ((n_rows - 1) / 2.0);
                double dx = (static_cast<double>(c) - (n_cols - 1) / 2.0) /
                            ((n_cols - 1) / 2.0);
                double dist = std::sqrt(dx * dx + dy * dy) / std::sqrt(2.0);
                zone_weights.push_back(0.5 * (1.0 + std::cos(kPi * dist)));
            }
        }
        double wsum = 0.0;
        for (double wv : zone_weights) wsum += wv;
        double dot = 0.0;
        for (size_t i = 0; i < zone_weights.size(); ++i)
            dot += (zone_weights[i] / wsum) * zone_means[i];
        exposure = dot / kMidgray;
    } else if (method == AeMethod::kMultiZone) {
        std::vector<double> x, y;
        normalized_coords(w, h, &x, &y);
        const double ring_bounds[3][2] = {{0.00, 0.05}, {0.05, 0.25},
                                          {0.25, 0.50}};
        const double ring_w[3] = {0.50, 0.30, 0.20};
        double weighted_sum = 0.0, weight_total = 0.0;
        for (int rb = 0; rb < 3; ++rb) {
            double s = 0.0;
            size_t cnt = 0;
            for (int j = 0; j < h; ++j) {
                const double yy = y[j] * y[j];
                for (int i = 0; i < w; ++i) {
                    double radius = std::sqrt(x[i] * x[i] + yy);
                    if (radius >= ring_bounds[rb][0] &&
                        radius < ring_bounds[rb][1]) {
                        s += Y[static_cast<size_t>(j) * w + i];
                        ++cnt;
                    }
                }
            }
            if (cnt == 0) continue;
            weighted_sum += ring_w[rb] * (s / static_cast<double>(cnt));
            weight_total += ring_w[rb];
        }
        double e;
        if (weight_total > 0.0) {
            e = weighted_sum / weight_total;
        } else {
            double s = 0.0;
            for (double v : Y) s += v;
            e = s / static_cast<double>(n);
        }
        exposure = e / kMidgray;
    } else {  // kHighlightWeighted
        // weights = Y^2; if sum < 1e-12 -> weights = ones. exposure =
        // sum(Y * weights) / sum(weights) / 0.184.
        double total = 0.0;
        for (double v : Y) total += v * v;
        double acc;
        if (total < 1e-12) {
            double s = 0.0;
            for (double v : Y) s += v;  // sum(Y * 1)
            acc = s / static_cast<double>(n);  // total = n
        } else {
            double s = 0.0;
            for (double v : Y) s += v * (v * v);
            acc = s / total;
        }
        exposure = acc / kMidgray;
    }

    double ev = -std::log2(exposure);
    if (std::isinf(ev)) ev = 0.0;  // np.isinf guard (matches the oracle)
    return ev;
}

void small_preview(const double* in, int w, int h, int max_size,
                   std::vector<double>* out, int* out_w, int* out_h) {
    const int longest = std::max(w, h);
    if (longest <= max_size) {
        out->assign(in, in + static_cast<size_t>(w) * h * 3);
        *out_w = w;
        *out_h = h;
        return;
    }
    // skimage.transform.rescale(image, scale, channel_axis=2, order=0):
    // scipy.ndimage.zoom(order=0, mode='mirror', grid_mode=True). output_shape =
    // round(scale * shape) (round-half-to-even); the input coordinate for output
    // index o is (o + 0.5)/zoom - 0.5 with zoom = out/in, rounded to nearest
    // (mirror boundary). For nearest, the value is the source sample at the
    // rounded coordinate.
    //
    // ANTI-ALIASING. The image is float and an output dimension shrinks, so
    // skimage leaves anti_aliasing at its default (None) which resolves to True
    // (skimage 0.26): scipy.ndimage.gaussian_filter (sigma = max(0,(in/out-1)/2)
    // per spatial axis, mode='mirror', truncate=4.0) runs BEFORE the nearest
    // resample. Without it the metered EV diverges from the oracle on every real
    // (>256px) import. We evaluate that separable gaussian FUSED at each sampled
    // source location, reusing build_gaussian_kernel (the exact kernel the
    // order=3 crop_resize stage uses), rather than materialising a full blurred
    // copy — so a full-resolution export does not allocate a second w*h*3 buffer.
    // Gated by tests/test_small_preview_aa.cpp against a skimage golden.
    const double scale = static_cast<double>(max_size) / static_cast<double>(longest);
    int ow = static_cast<int>(std::nearbyint(scale * static_cast<double>(w)));
    int oh = static_cast<int>(std::nearbyint(scale * static_cast<double>(h)));
    ow = std::max(ow, 1);
    oh = std::max(oh, 1);
    out->assign(static_cast<size_t>(ow) * oh * 3, 0.0);
    const double zoom_x = static_cast<double>(ow) / static_cast<double>(w);
    const double zoom_y = static_cast<double>(oh) / static_cast<double>(h);
    auto mirror_index = [](long long idx, int n) -> int {
        if (n == 1) return 0;
        const long long period = 2 * (static_cast<long long>(n) - 1);
        long long m = idx % period;
        if (m < 0) m += period;
        if (m >= n) m = period - m;
        return static_cast<int>(m);
    };
    // Per-axis anti-aliasing gaussian kernels. sigma = max(0,(in/out - 1)/2);
    // build_gaussian_kernel returns radius 0 + empty for sigma <= 0, which we
    // treat as the identity kernel {1.0} so an axis that does not shrink is a
    // strict passthrough (matching the crop_resize "rad==0 -> skip" behaviour).
    std::vector<double> ker_x, ker_y;
    int rad_x = build_gaussian_kernel(
        std::max(0.0, (static_cast<double>(w) / static_cast<double>(ow) - 1.0) / 2.0), &ker_x);
    int rad_y = build_gaussian_kernel(
        std::max(0.0, (static_cast<double>(h) / static_cast<double>(oh) - 1.0) / 2.0), &ker_y);
    if (rad_x == 0) ker_x.assign(1, 1.0);
    if (rad_y == 0) ker_y.assign(1, 1.0);
    for (int oy = 0; oy < oh; ++oy) {
        double yy = (static_cast<double>(oy) + 0.5) / zoom_y - 0.5;
        int sy = mirror_index(static_cast<long long>(std::nearbyint(yy)), h);
        for (int ox = 0; ox < ow; ++ox) {
            double xx = (static_cast<double>(ox) + 0.5) / zoom_x - 0.5;
            int sx = mirror_index(static_cast<long long>(std::nearbyint(xx)), w);
            // Separable gaussian at (sy, sx): inner x-pass per kernel row, then
            // the y-pass — the same accumulation order as the order=3 stage's
            // full-image prefilter, so the result tracks the oracle's
            // gaussian_filter(...)-then-resize to fp round-off.
            double acc0 = 0.0, acc1 = 0.0, acc2 = 0.0;
            for (int ky = -rad_y; ky <= rad_y; ++ky) {
                const int syy = mirror_index(static_cast<long long>(sy) + ky, h);
                double in0 = 0.0, in1 = 0.0, in2 = 0.0;
                for (int kx = -rad_x; kx <= rad_x; ++kx) {
                    const int sxx = mirror_index(static_cast<long long>(sx) + kx, w);
                    const double wx = ker_x[static_cast<size_t>(kx + rad_x)];
                    const size_t si = (static_cast<size_t>(syy) * w + sxx) * 3;
                    in0 += wx * in[si + 0];
                    in1 += wx * in[si + 1];
                    in2 += wx * in[si + 2];
                }
                const double wy = ker_y[static_cast<size_t>(ky + rad_y)];
                acc0 += wy * in0;
                acc1 += wy * in1;
                acc2 += wy * in2;
            }
            const size_t di = (static_cast<size_t>(oy) * ow + ox) * 3;
            (*out)[di + 0] = acc0;
            (*out)[di + 1] = acc1;
            (*out)[di + 2] = acc2;
        }
    }
    *out_w = ow;
    *out_h = oh;
}

double apply_auto_exposure(double* image, int w, int h, AeColorSpace cs,
                           bool apply_cctf_decoding, AeMethod method,
                           bool known_method, int preview_max_size) {
    std::vector<double> prev;
    int pw = 0, ph = 0;
    small_preview(image, w, h, preview_max_size, &prev, &pw, &ph);
    double ev = measure_autoexposure_ev(prev.data(), pw, ph, cs,
                                        apply_cctf_decoding, method,
                                        known_method);
    const double gain = std::pow(2.0, ev);
    const size_t n = static_cast<size_t>(w) * h * 3;
    for (size_t i = 0; i < n; ++i) image[i] *= gain;
    return ev;
}

}  // namespace spk
