/*
 * Spektrafilm for Android — Oklab chroma scaling (shared). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The hue-preserving chroma scale used by the global Saturation/Vibrance grade and by per-mask local
 * Saturation. Ottosson Oklab (linear RGB → LMS → Lab), scale chroma, back to linear — neutral (v,v,v)
 * stays neutral for ANY RGB primaries (the LMS matrix rows each sum to 1), so it is correct on the
 * sRGB family and a faithful creative control on wide spaces. Pure Kotlin; no engine touched.
 *
 * (ColorGrade still carries an equivalent inline copy; both should de-dup onto this in a follow-up,
 * together with OutputCctf.)
 */
package com.spectrafilm.app

import kotlin.math.exp
import kotlin.math.hypot

object Oklab {

    /** Oklab chroma scale at which vibrance is half-weighted (muted colors get most of the boost). */
    private const val VIBRANCE_C0 = 0.12f

    /**
     * Scale the chroma of a single LINEAR RGB triple [rgb] (mutated in place), preserving lightness and
     * hue. [sat]/[vib] are NORMALIZED (e.g. saturation/100 ∈ [-1,1]): saturation is uniform `1+sat`,
     * vibrance is low-chroma-weighted `1+vib·exp(-C/C0)`. (0,0) is a no-op (within float round-trip).
     */
    fun scaleChromaLinear(rgb: FloatArray, sat: Float, vib: Float) {
        val rl = rgb[0]; val gl = rgb[1]; val bl = rgb[2]
        val l = 0.4122214708f * rl + 0.5363325363f * gl + 0.0514459929f * bl
        val m = 0.2119034982f * rl + 0.6806995451f * gl + 0.1073969566f * bl
        val s = 0.0883024619f * rl + 0.2817188376f * gl + 0.6299787005f * bl
        val l_ = cbrt(l); val m_ = cbrt(m); val s_ = cbrt(s)
        val okL = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
        var okA = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
        var okB = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_

        val c = hypot(okA, okB)
        val scale = (1f + sat) * (1f + vib * exp(-c / VIBRANCE_C0))
        okA *= scale
        okB *= scale

        val l2 = okL + 0.3963377774f * okA + 0.2158037573f * okB
        val m2 = okL - 0.1055613458f * okA - 0.0638541728f * okB
        val s2 = okL - 0.0894841775f * okA - 1.2914855480f * okB
        val lc = l2 * l2 * l2; val mc = m2 * m2 * m2; val sc = s2 * s2 * s2
        rgb[0] = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
        rgb[1] = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
        rgb[2] = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc
    }

    private fun cbrt(x: Float): Float = Math.cbrt(x.toDouble()).toFloat()
}
