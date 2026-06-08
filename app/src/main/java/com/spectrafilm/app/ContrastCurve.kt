/*
 * Spektrafilm for Android — Contrast as a tone-curve master S-curve. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A discoverable Contrast slider that drives the engine's already-wired, parity-gated master tone
 * curve (kernels/tonecurve.cpp, applied in scanning on display-encoded [0,1] RGB). Mapping a single
 * [-100,100] slider to a pivoted S-curve gives users the "mute the punchy look / add punch" control
 * the forum asks for (problem #3) WITHOUT a color trap: the master channel is applied to all three
 * channels equally, so contrast stays hue-neutral (unlike film_gamma, which scales the three CMY
 * density curves differently and shifts color).
 *
 * Pure Kotlin; no engine/spektra-core/cpp touched → the 26-test parity suite is unaffected (Tier 0).
 * It reuses the same ToneCurveParams path the manual point editor uses, so it shows live in preview
 * and on export exactly like a hand-drawn curve.
 *
 * Curve: a power S-curve pivoted at [PIVOT] (display ~18% gray, so mid-gray is fixed), with matched
 * slope g = 2^(contrast/100) at the pivot. g>1 (contrast>0) deepens shadows + lifts highlights (an
 * S); g<1 (contrast<0) is the mute direction users want. contrast=0 → g=1 → identity → no points.
 */
package com.spectrafilm.app

import kotlin.math.pow

object ContrastCurve {

    /**
     * Tone-curve pivot. The master curve operates on display-ENCODED [0,1] RGB (scanning.cpp), where
     * sRGB-encoded 18% mid-gray ≈ 0.46; pivoting here keeps mid-gray fixed as contrast changes.
     */
    const val PIVOT = 0.46f

    /** |contrast| = 100 maps to pivot slope 2.0 (max punch) or 0.5 (max mute). Geometric in log. */
    private const val GAMMA_MAX = 2.0f

    /** Fixed control-point x grid (includes [PIVOT] so mid-gray is pinned exactly). 7 ≤ 16-pt cap. */
    private val GRID = floatArrayOf(0f, 0.15f, 0.30f, PIVOT, 0.64f, 0.82f, 1f)

    /** LUT resolution used to evaluate a user-drawn master curve when composing under contrast. */
    private const val LUT_N = 256

    /** Pivot slope g = 2^(contrast/100): 1 at 0 (identity), 2 at +100, 0.5 at -100. */
    fun gamma(contrast: Float): Float = GAMMA_MAX.toDouble().pow((contrast / 100f).toDouble()).toFloat()

    /**
     * Evaluate the contrast S-curve at [x] for [contrast]. Passes through (0,0), ([PIVOT],[PIVOT]),
     * (1,1) with matched slope [gamma] at the pivot; monotonic for all contrasts.
     */
    fun curveAt(x: Float, contrast: Float): Float {
        val xc = x.coerceIn(0f, 1f)
        val g = gamma(contrast).toDouble()
        val p = PIVOT
        val y = if (xc < p) {
            p * (xc / p).toDouble().pow(g)
        } else {
            1.0 - (1f - p) * ((1f - xc) / (1f - p)).toDouble().pow(g)
        }
        return y.toFloat().coerceIn(0f, 1f)
    }

    /** The contrast curve as tone-curve control points, or empty (strict no-op) when [contrast] is 0. */
    fun points(contrast: Float): List<Pair<Float, Float>> =
        if (contrast == 0f) emptyList() else GRID.map { it to curveAt(it, contrast) }

    /**
     * Compose contrast UNDER a user-drawn [userMaster]: the engine applies master as out = curve(in),
     * and we want out = userMaster(contrast(in)) so contrast is the inner remap and the user's drawn
     * curve still has the final say. Returns:
     *   • [userMaster] unchanged when [contrast] == 0 (no-op),
     *   • the plain contrast [points] when the user curve is identity/empty,
     *   • else the composite sampled at [GRID]: (x, userMaster(contrastCurve(x))).
     */
    fun composeMaster(userMaster: List<Pair<Float, Float>>, contrast: Float): List<Pair<Float, Float>> {
        if (contrast == 0f) return userMaster
        if (ToneCurveMath.isIdentity(userMaster)) return points(contrast)
        val lut = ToneCurveMath.sample(userMaster, LUT_N)
        return GRID.map { x -> x to lutLookup(lut, curveAt(x, contrast)) }
    }

    /** Linear-interpolated lookup of an evenly-spaced [lut] (x in [0,1]). */
    private fun lutLookup(lut: FloatArray, x: Float): Float {
        val fx = x.coerceIn(0f, 1f) * (lut.size - 1)
        val i = fx.toInt()
        if (i >= lut.size - 1) return lut[lut.size - 1]
        val frac = fx - i
        return lut[i] * (1f - frac) + lut[i + 1] * frac
    }
}
