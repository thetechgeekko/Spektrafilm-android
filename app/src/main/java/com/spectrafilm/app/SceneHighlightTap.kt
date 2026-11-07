/*
 * Spektrafilm for Android — scene-referred highlight tap for the Ultra HDR gain map. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Scene-linear luminance of the export's source, sampled on the gain map's grid.
 *
 * Why: the film render is display-referred and, with the engine's default output handling
 * (LEGACY_CLIP), clipped to [0,1], and a print look's shoulder rolls highlights off below white
 * anyway. So the render alone almost never carries values above SDR white and the render-derived
 * gain map (#140) comes out flat: an "Ultra HDR" file with no HDR in it. The highlight detail the
 * film compressed is still in the SCENE (a RAW decode keeps it above 1.0). rawr's gain map taps
 * the scene the same way (exposure-matched scene light in the highlights); this is the same idea
 * on our pipeline, kept in the highlights only so the SDR film look is untouched below them.
 *
 * Geometry: the engine renders the source cut to its crop box (io.crop) and may resample it to
 * the output size, so map cell (mx, my) covers the same normalized area of that box. Each cell
 * averages a 2x2 grid of source samples, enough for a quarter-resolution, low-frequency map.
 */
internal class SceneLuma(val width: Int, val height: Int, val y: FloatArray)

internal object SceneHighlightTap {
    // Linear ProPhoto (ROMM, D50) RGB -> Y.
    private const val KR = 0.2880402f
    private const val KG = 0.7118741f
    private const val KB = 0.0000857f

    /**
     * Sample [src] (interleaved linear ProPhoto RGB, [srcW]x[srcH]) inside [box] onto a
     * [mapW]x[mapH] grid. [box] null means the whole frame.
     */
    fun capture(
        src: FloatBuffer,
        srcW: Int,
        srcH: Int,
        box: CropBox?,
        mapW: Int,
        mapH: Int,
    ): SceneLuma {
        require(srcW > 0 && srcH > 0 && mapW > 0 && mapH > 0) { "scene tap needs non-empty sizes" }
        val bx0 = box?.x0 ?: 0
        val by0 = box?.y0 ?: 0
        val bw = (box?.width ?: srcW).coerceIn(1, srcW - bx0)
        val bh = (box?.height ?: srcH).coerceIn(1, srcH - by0)
        val out = FloatArray(mapW * mapH)
        for (my in 0 until mapH) {
            for (mx in 0 until mapW) {
                var sum = 0f
                var n = 0
                for (sy in 0 until 2) {
                    val fy = (my + (sy + 0.5f) / 2f) / mapH
                    val py = by0 + min(bh - 1, (fy * bh).toInt())
                    for (sx in 0 until 2) {
                        val fx = (mx + (sx + 0.5f) / 2f) / mapW
                        val px = bx0 + min(bw - 1, (fx * bw).toInt())
                        val o = (py * srcW + px) * 3
                        val lum = KR * src.get(o) + KG * src.get(o + 1) + KB * src.get(o + 2)
                        if (lum.isFinite()) {
                            sum += max(0f, lum)
                            n++
                        }
                    }
                }
                out[my * mapW + mx] = if (n == 0) 0f else sum / n
            }
        }
        return SceneLuma(mapW, mapH, out)
    }

    /**
     * The film's halation glow applied to the scene tap (rawr's "glow factor").
     *
     * Why: the scene tap restores a bright source above SDR white, but the halo the film drew
     * around it is in the render only, at SDR level. On an HDR display the source then brightens
     * while its halo does not, so the glow -- the look's signature -- reads weaker than on SDR.
     * Running the scene through the same back-reflection model the engine uses (model/diffusion.cpp
     * step 3: a decaying sum of Gaussians at sigma*sqrt(k), added with strength `a`, then divided by
     * 1+a when renormalized) puts the halo into the scene light too, so it is lifted with its
     * source. On the low-resolution map grid and on luminance only: an approximation of the
     * render's halo, not a copy of it, which is all a low-frequency gain map can carry.
     *
     * [strength] is the luminance-weighted a_tot, [sigmaCells] the first-bounce sigma in map
     * cells. Returns [scene] unchanged when there is nothing to apply.
     */
    fun withHalationGlow(
        scene: SceneLuma,
        strength: Float,
        sigmaCells: Float,
        bounces: Int,
        decay: Float,
        renormalize: Boolean,
    ): SceneLuma {
        if (!(strength > 0f) || !(sigmaCells > 0f) || bounces < 1) return scene
        val w = scene.width
        val h = scene.height
        val weights = FloatArray(bounces) { k -> decay.toDouble().pow(k).toFloat() }
        val wsum = weights.sum()
        val glow = FloatArray(w * h)
        val tmp = FloatArray(w * h)
        val blurred = FloatArray(w * h)
        for (k in 0 until bounces) {
            val sigma = sigmaCells * sqrt((k + 1).toFloat())
            blurSeparable(scene.y, tmp, blurred, w, h, sigma)
            val wk = weights[k] / wsum
            for (i in glow.indices) glow[i] += wk * blurred[i]
        }
        val norm = if (renormalize) 1f / (1f + strength) else 1f
        val out = FloatArray(w * h) { i -> (scene.y[i] + strength * glow[i]) * norm }
        return SceneLuma(w, h, out)
    }

    /**
     * Clamp-to-edge Gaussian approximated by three successive box blurs per axis (the box sizes
     * from Kutskir's "boxes for gauss", matching sigma's variance), each a running sum: cost is
     * independent of sigma. Exactness does not matter here -- the result feeds a quarter-resolution,
     * 8-bit gain map -- but cost does: this runs over a ~3 Mcell map for a 50 MP export, once per
     * bounce, and a direct 3-sigma kernel took ~0.4 s for that on a desktop JVM.
     */
    private fun blurSeparable(src: FloatArray, tmp: FloatArray, dst: FloatArray, w: Int, h: Int, sigma: Float) {
        val boxes = boxesForGauss(sigma)
        val line = FloatArray(max(w, h))
        val out = FloatArray(max(w, h))
        // Horizontal: src -> tmp.
        for (y in 0 until h) {
            val row = y * w
            System.arraycopy(src, row, line, 0, w)
            for (r in boxes) { boxLine(line, out, w, r); System.arraycopy(out, 0, line, 0, w) }
            System.arraycopy(line, 0, tmp, row, w)
        }
        // Vertical: tmp -> dst, three passes ping-ponging between them. A running sum per column,
        // swept row by row so every access is sequential in memory.
        var a = tmp
        var b = dst
        val acc = FloatArray(w)
        for (r in boxes) {
            if (r <= 0) { System.arraycopy(a, 0, b, 0, w * h) } else {
                val inv = 1f / (2 * r + 1)
                java.util.Arrays.fill(acc, 0f)
                for (k in -r..r) {
                    val o = k.coerceIn(0, h - 1) * w
                    for (x in 0 until w) acc[x] += a[o + x]
                }
                for (y in 0 until h) {
                    val o = y * w
                    val add = min(y + r + 1, h - 1) * w
                    val sub = max(y - r, 0) * w
                    for (x in 0 until w) {
                        b[o + x] = acc[x] * inv
                        acc[x] += a[add + x] - a[sub + x]
                    }
                }
            }
            val t = a; a = b; b = t
        }
        // Three passes leave the result in tmp's buffer; dst must hold it.
        if (a !== dst) System.arraycopy(a, 0, dst, 0, w * h)
    }

    /** Half-widths of three boxes whose successive convolution has variance sigma^2. */
    private fun boxesForGauss(sigma: Float): IntArray {
        val n = 3
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1.0)
        var wl = wIdeal.toInt()
        if (wl % 2 == 0) wl--
        if (wl < 1) wl = 1
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)
        val m = Math.round(mIdeal).toInt()
        return IntArray(n) { i -> ((if (i < m) wl else wu) - 1) / 2 }
    }

    /** Mean over [i - r, i + r] with clamp-to-edge, by running sum. */
    private fun boxLine(src: FloatArray, dst: FloatArray, len: Int, r: Int) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, len); return }
        val inv = 1f / (2 * r + 1)
        var acc = 0f
        for (k in -r..r) acc += src[k.coerceIn(0, len - 1)]
        for (i in 0 until len) {
            dst[i] = acc * inv
            acc += src[min(i + r + 1, len - 1)] - src[max(i - r, 0)]
        }
    }
}
