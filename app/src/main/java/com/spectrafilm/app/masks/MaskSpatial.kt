/*
 * Spektrafilm for Android — spatial (Class-S) mask primitives. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The neighborhood pass the edge-aware local adjustments need (Lightroom's Clarity / Sharpness /
 * Texture / Highlights / Shadows are spatial — a per-pixel curve can't produce them). Pure Kotlin,
 * JVM-testable, applied on the engine OUTPUT luma in the Tier-2 compositor — ZERO engine/parity impact.
 *
 * Primitive: a separable 3-pass box blur (≈ Gaussian, O(n) regardless of radius, edge-clamped) of a
 * single-channel buffer. Unsharp-mask ops then add back a weighted (luma − blur) detail at different
 * radii: small = Sharpness (high-freq), mid = Texture, large + midtone-weighted = Clarity (local
 * contrast). Radii scale with the image's long edge so the look is resolution-independent (one mask
 * drives the 640px draft and the full-res export identically).
 */
package com.spectrafilm.app.masks

object MaskSpatial {

    /**
     * Separable box blur (3 passes ≈ Gaussian) of single-channel [src] (length ≥ [w]*[h]), with an
     * integer radius derived from [radiusPx]. Edge-clamped; returns a NEW array (input untouched).
     * radius < 1 → a copy (no blur).
     */
    fun blur(src: FloatArray, w: Int, h: Int, radiusPx: Float): FloatArray {
        val r = radiusPx.toInt()
        if (r < 1 || w < 2 || h < 2 || src.size < w * h) return src.copyOf()
        var cur = src.copyOf()
        val tmp = FloatArray(w * h)
        repeat(3) {
            boxH(cur, tmp, w, h, r)   // cur -> tmp (horizontal)
            boxV(tmp, cur, w, h, r)   // tmp -> cur (vertical)
        }
        return cur
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            dst[row] = sum * norm
            for (x in 1 until w) {
                sum += src[row + (x + r).coerceIn(0, w - 1)] - src[row + (x - r - 1).coerceIn(0, w - 1)]
                dst[row + x] = sum * norm
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += src[k.coerceIn(0, h - 1) * w + x]
            dst[x] = sum * norm
            for (y in 1 until h) {
                sum += src[(y + r).coerceIn(0, h - 1) * w + x] - src[(y - r - 1).coerceIn(0, h - 1) * w + x]
                dst[y * w + x] = sum * norm
            }
        }
    }

    /**
     * Midtone weight in [0,1]: 1 at luma=0.5, falling to 0 at black/white. Lets Clarity boost local
     * contrast in the midtones while sparing the extremes (the standard way to avoid halo/clipping).
     */
    fun midtoneWeight(luma: Float): Float {
        val x = 2f * luma.coerceIn(0f, 1f) - 1f   // -1..1, 0 at midtone
        val a = 1f - x * x                          // 1 at mid, 0 at the extremes
        return a * a                                // sharpen the peak
    }

    // Blur radii as a fraction of the image long edge (resolution-independent look). [RECON] tunables.
    const val RADIUS_FRAC_SHARP = 0.0015f
    const val RADIUS_FRAC_TEXTURE = 0.006f
    const val RADIUS_FRAC_CLARITY = 0.03f
    const val RADIUS_FRAC_REGION = 0.05f
}
