/*
 * Spektrafilm for Android — mask rasterization. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Turn a normalized [Mask] into a per-pixel alpha buffer at a concrete resolution, so the compositor
 * (a later increment, on the `simResultToBitmap` output seam) can blend a Tier-A adjustment by alpha.
 * Pure Kotlin; no engine touched. Because the mask geometry is normalized, the SAME mask rasterizes
 * correctly for the draft, the zoom ROI and the full-res export — only the [w]×[h] differs.
 */
package com.spectrafilm.app.masks

object MaskRaster {

    /**
     * Rasterize [mask] to a row-major [w]×[h] alpha buffer (values in [0,1]). Pixel centers map to
     * normalized coordinates `((x+0.5)/w, (y+0.5)/h)`. An empty (no-component) non-inverted mask is all
     * zero; the buffer is reusable across renders via a mask-hash + size cache in the compositor.
     */
    fun rasterize(mask: Mask, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        if (mask.components.isEmpty() && !mask.invert) return out  // selects nothing → all 0
        val invW = 1f / w
        val invH = 1f / h
        var i = 0
        var y = 0
        while (y < h) {
            val ny = (y + 0.5f) * invH
            var x = 0
            while (x < w) {
                val nx = (x + 0.5f) * invW
                out[i] = mask.alphaAt(nx, ny)
                x++; i++
            }
            y++
        }
        return out
    }

    /** Mean alpha of [mask] over a coarse [n]×[n] sample grid — a cheap "is anything selected?" probe. */
    fun coverage(mask: Mask, n: Int = 32): Float {
        var sum = 0f
        for (yy in 0 until n) {
            val ny = (yy + 0.5f) / n
            for (xx in 0 until n) {
                sum += mask.alphaAt((xx + 0.5f) / n, ny)
            }
        }
        return sum / (n * n)
    }
}
