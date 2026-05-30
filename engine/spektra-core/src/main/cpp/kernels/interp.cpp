/*
 * Spektrafilm for Android — native engine: 1D linear interpolation kernel.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Ports spektrafilm/utils/fast_interp.py (fast_interp).
 */
#include "interp.h"

namespace spk {

namespace {

// searchsorted(xa, x, side='right') over a sorted ascending array `xa[0..n)`.
// Returns the first index i such that x < xa[i] (i.e. count of xa[k] <= x).
// Matches numpy.searchsorted's right-biased semantics used by fast_interp.
inline int searchsorted_right(const float* xa, int n, float x) {
    int lo = 0;
    int hi = n;
    while (lo < hi) {
        int mid = lo + ((hi - lo) >> 1);
        if (x < xa[mid]) {
            hi = mid;
        } else {
            lo = mid + 1;
        }
    }
    return lo;
}

// Core linear interpolation with fast_interp's endpoint clamping. `xa`/`fp`
// have `n` entries; `xa` sorted ascending. Repeated x values follow the
// right-biased exact-match rule (idx = searchsorted side='right', low = idx-1).
inline float interp_core(float x, const float* xa, const float* fp, int n) {
    // fast_interp.py:
    //   if x <= xa[0]:   result = y_vals[0]
    //   elif x >= xa[K-1]: result = y_vals[K-1]
    //   else: idx = searchsorted(xa, x, 'right'); low = idx-1
    //         t = (x - xa[low]) / (xa[low+1] - xa[low])
    //         result = y_vals[low] + t*(y_vals[low+1] - y_vals[low])
    if (n <= 0) return 0.0f;
    if (n == 1) return fp[0];
    if (x <= xa[0]) return fp[0];
    if (x >= xa[n - 1]) return fp[n - 1];
    int idx = searchsorted_right(xa, n, x);
    int low = idx - 1;
    if (low < 0) low = 0;
    if (low > n - 2) low = n - 2;
    float dx = xa[low + 1] - xa[low];
    // fast_interp precomputes inv_dx and uses 0.0 when dx == 0 (repeated x).
    float t = (dx != 0.0f) ? (x - xa[low]) / dx : 0.0f;
    return fp[low] + t * (fp[low + 1] - fp[low]);
}

}  // namespace

float interp1d_scalar(float x, const float* xp, const float* fp, int n) {
    return interp_core(x, xp, fp, n);
}

void interp1d(const float* x, int count,
              const float* xp, const float* fp, int n,
              float* out) {
    for (int i = 0; i < count; ++i) {
        out[i] = interp_core(x[i], xp, fp, n);
    }
}

void interp1d_planar3(const float* img, int npix,
                      const float* xp, bool common_axis,
                      const float* fp, int n,
                      float* out) {
    // fp is (n,3) row-major: fp[k*3 + c]. Build a small stride-3 gather inline.
    if (common_axis) {
        for (int i = 0; i < npix; ++i) {
            const float* px = img + (long)i * 3;
            float* po = out + (long)i * 3;
            for (int c = 0; c < 3; ++c) {
                float x = px[c];
                // Inline interp over shared axis xp with channel-c column of fp.
                if (n <= 0) { po[c] = 0.0f; continue; }
                if (n == 1 || x <= xp[0]) { po[c] = fp[c]; continue; }
                if (x >= xp[n - 1]) { po[c] = fp[(n - 1) * 3 + c]; continue; }
                int idx = searchsorted_right(xp, n, x);
                int low = idx - 1;
                if (low < 0) low = 0;
                if (low > n - 2) low = n - 2;
                float dx = xp[low + 1] - xp[low];
                float t = (dx != 0.0f) ? (x - xp[low]) / dx : 0.0f;
                float y0 = fp[low * 3 + c];
                float y1 = fp[(low + 1) * 3 + c];
                po[c] = y0 + t * (y1 - y0);
            }
        }
    } else {
        // Channel-specific axis: xp is (n,3) row-major; column c is the axis.
        for (int i = 0; i < npix; ++i) {
            const float* px = img + (long)i * 3;
            float* po = out + (long)i * 3;
            for (int c = 0; c < 3; ++c) {
                float x = px[c];
                if (n <= 0) { po[c] = 0.0f; continue; }
                float xa0 = xp[c];
                float xaN = xp[(n - 1) * 3 + c];
                if (n == 1 || x <= xa0) { po[c] = fp[c]; continue; }
                if (x >= xaN) { po[c] = fp[(n - 1) * 3 + c]; continue; }
                // Binary search on the strided column.
                int lo = 0, hi = n;
                while (lo < hi) {
                    int mid = lo + ((hi - lo) >> 1);
                    if (x < xp[mid * 3 + c]) hi = mid; else lo = mid + 1;
                }
                int low = lo - 1;
                if (low < 0) low = 0;
                if (low > n - 2) low = n - 2;
                float xl = xp[low * 3 + c];
                float xh = xp[(low + 1) * 3 + c];
                float dx = xh - xl;
                float t = (dx != 0.0f) ? (x - xl) / dx : 0.0f;
                float y0 = fp[low * 3 + c];
                float y1 = fp[(low + 1) * 3 + c];
                po[c] = y0 + t * (y1 - y0);
            }
        }
    }
}

}  // namespace spk
