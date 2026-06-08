/*
 * Spektrafilm for Android — pixel sampling helpers (the eyedropper core). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pure, JVM-testable math behind the "pick a color from the photo" eyedropper: map a normalized tap to
 * a bitmap pixel index, and decode an ARGB int to 0..1 floats. The preview bitmap is the engine OUTPUT
 * tagged in the output space, so its 8-bit values live in the SAME encoded domain the color-range gate
 * reads (MaskCompositor uses or,og,ob directly) — so a sampled (r,g,b) drops straight into
 * ColorRange.target. (The Android Bitmap.getPixel call itself lives in the composable, which needs a
 * real bitmap; only this index/decode math is unit tested.)
 */
package com.spectrafilm.app

object PixelSample {

    /** Normalized ([nx],[ny]) in 0..1 → a clamped pixel (x,y) for a [w]×[h] bitmap. */
    fun pixel(nx: Float, ny: Float, w: Int, h: Int): Pair<Int, Int> {
        val x = (nx * w).toInt().coerceIn(0, (w - 1).coerceAtLeast(0))
        val y = (ny * h).toInt().coerceIn(0, (h - 1).coerceAtLeast(0))
        return x to y
    }

    /** An ARGB int (e.g. from Bitmap.getPixel) → (r,g,b) in 0..1, alpha ignored. */
    fun rgb01(argb: Int): Triple<Float, Float, Float> {
        val r = (argb ushr 16 and 0xFF) / 255f
        val g = (argb ushr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return Triple(r, g, b)
    }
}
