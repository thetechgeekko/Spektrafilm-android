/*
 * Spektrafilm for Android — ACES-style Reference Gamut Compression. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Forum pain #4: saturated highlights show a hard cyan/odd fringe because the engine's only gamut
 * handling is a per-channel hard clip at the end of scanning. This is the ACES 1.3 Reference Gamut
 * Compression shaper, applied as a parity-safe Tier-2 post-op on the output buffer (via ColorGrade's
 * shared CCTF seam): it pulls the MOST-saturated colors (distance-from-achromatic past a threshold)
 * toward neutral, softening the harsh transition into the clipped region.
 *
 * Honest scope: because it runs AFTER the engine's hard clip, it *softens* the edge rather than fully
 * curing it (the true pre-clip cure is an engine-gated change, deferred — §2 P3). Default amount 0 →
 * identity → byte-identical, so no engine/parity impact. Constants are the published ACES values
 * (AP1-tuned); shipped fixed for v1, the design notes them as adjustable later.
 *
 * Per channel c (linear), with achromatic ach = max(r,g,b): distance d = (ach−c)/ach (0 at the bright
 * channel, →1 as c darkens), compressed by [compress] past per-channel threshold, then c' = ach·(1−d').
 */
package com.spectrafilm.app

import kotlin.math.max
import kotlin.math.pow

object GamutCompress {

    // ACES 1.3 reference gamut compression parameters (per-channel threshold + limit, scalar power).
    private val THR = floatArrayOf(0.815f, 0.803f, 0.880f)
    private val LIM = floatArrayOf(1.147f, 1.264f, 1.312f)
    private const val PWR = 1.2f

    /** True when [amount] (0..100) would change the image. */
    fun isActive(amount: Float): Boolean = amount > 0f

    /**
     * The ACES distance compressor for channel [ch]: identity below the threshold, asymptoting so a
     * color at distance LIM lands exactly on the gamut boundary (distance 1). Monotonic in [dist].
     */
    fun compress(dist: Float, ch: Int): Float {
        val thr = THR[ch]; val lim = LIM[ch]
        if (dist < thr) return dist
        val d = dist.toDouble(); val t = thr.toDouble(); val l = lim.toDouble(); val p = PWR.toDouble()
        val scl = (l - t) / (((1.0 - t) / (l - t)).pow(-p) - 1.0).pow(1.0 / p)
        return (t + (d - t) / (1.0 + ((d - t) / scl).pow(p)).pow(1.0 / p)).toFloat()
    }

    /**
     * Apply gamut compression in place to a single linear RGB triple [rgb] (3 elements), lerped by
     * [amount]/100 (0 = identity). Neutral (v,v,v) is unchanged (all distances 0); the brightest
     * channel (= achromatic) is always preserved.
     */
    fun apply(rgb: FloatArray, amount: Float) {
        val a = (amount / 100f).coerceIn(0f, 1f)
        if (a == 0f) return
        val ach = max(rgb[0], max(rgb[1], rgb[2]))
        if (ach <= 0f) return
        for (i in 0..2) {
            val dist = (ach - rgb[i]) / ach
            val compressed = ach * (1f - compress(dist, i))
            rgb[i] += a * (compressed - rgb[i])  // lerp toward the compressed value
        }
    }
}
