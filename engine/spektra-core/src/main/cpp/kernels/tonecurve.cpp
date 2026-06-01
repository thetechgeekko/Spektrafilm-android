/*
 * Spektrafilm for Android — native engine: 1D tone curve implementation. GPLv3.
 * Film modeling powered by spektrafilm.
 */
#include "kernels/tonecurve.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace spk {

namespace {

inline float clamp01(float v) {
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

}  // namespace

float ToneCurve1D::eval(float x) const {
    if (identity) return x;
    if (std::isnan(x)) return x;          // preserve NaN (matches scan()'s clip)
    float xc = clamp01(x);
    float pos = xc * static_cast<float>(kLutSize - 1);
    int i = static_cast<int>(pos);
    if (i >= kLutSize - 1) return lut[kLutSize - 1];
    if (i < 0) return lut[0];
    float frac = pos - static_cast<float>(i);
    return lut[i] + (lut[i + 1] - lut[i]) * frac;
}

float ToneCurveSet::apply(int c, float v) const {
    if (!active) return v;
    return master.eval(rgb[c].eval(v));
}

ToneCurve1D build_tone_curve_1d(const float* xs, const float* ys, int n) {
    ToneCurve1D curve;
    if (xs == nullptr || ys == nullptr || n < 2) {
        curve.identity = true;            // strict no-op
        return curve;
    }
    // If every control point lies on y = x the intended curve IS the identity, so
    // keep it a strict no-op (bypassing the LUT) rather than introducing ~1e-7 LUT
    // sampling error — an active identity curve then stays byte-identical to "off".
    bool is_identity = true;
    for (int i = 0; i < n; ++i) {
        if (xs[i] != ys[i]) { is_identity = false; break; }
    }
    if (is_identity) { curve.identity = true; return curve; }
    curve.identity = false;

    // Fritsch–Carlson monotone-cubic tangents over the control points.
    // Secants d[i] on [x[i], x[i+1]].
    const int segs = n - 1;
    std::vector<float> d(segs);
    for (int i = 0; i < segs; ++i) {
        float dx = xs[i + 1] - xs[i];
        d[i] = (dx > 0.0f) ? (ys[i + 1] - ys[i]) / dx : 0.0f;
    }
    std::vector<float> m(n);
    m[0] = d[0];
    m[n - 1] = d[segs - 1];
    for (int i = 1; i < n - 1; ++i) m[i] = 0.5f * (d[i - 1] + d[i]);
    // Enforce monotonicity (Fritsch–Carlson): zero a tangent where a secant is flat,
    // otherwise project (alpha,beta) back into the circle of radius 3.
    for (int i = 0; i < segs; ++i) {
        if (d[i] == 0.0f) {
            m[i] = 0.0f;
            m[i + 1] = 0.0f;
        } else {
            float a = m[i] / d[i];
            float b = m[i + 1] / d[i];
            float s = a * a + b * b;
            if (s > 9.0f) {
                float t = 3.0f / std::sqrt(s);
                m[i] = t * a * d[i];
                m[i + 1] = t * b * d[i];
            }
        }
    }

    // Bake the LUT: for each sample x in [0,1], find its segment and Hermite-eval.
    int seg = 0;
    for (int k = 0; k < ToneCurve1D::kLutSize; ++k) {
        float x = static_cast<float>(k) / static_cast<float>(ToneCurve1D::kLutSize - 1);
        if (x <= xs[0]) { curve.lut[k] = clamp01(ys[0]); continue; }
        if (x >= xs[n - 1]) { curve.lut[k] = clamp01(ys[n - 1]); continue; }
        while (seg < segs - 1 && x > xs[seg + 1]) ++seg;
        float h = xs[seg + 1] - xs[seg];
        float t = (x - xs[seg]) / h;
        float t2 = t * t, t3 = t2 * t;
        // Hermite basis.
        float h00 = 2.0f * t3 - 3.0f * t2 + 1.0f;
        float h10 = t3 - 2.0f * t2 + t;
        float h01 = -2.0f * t3 + 3.0f * t2;
        float h11 = t3 - t2;
        float y = h00 * ys[seg] + h10 * h * m[seg] +
                  h01 * ys[seg + 1] + h11 * h * m[seg + 1];
        curve.lut[k] = clamp01(y);
    }
    return curve;
}

}  // namespace spk
