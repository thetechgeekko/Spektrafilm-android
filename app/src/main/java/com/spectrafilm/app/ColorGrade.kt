/*
 * Spektrafilm for Android — Saturation / Vibrance (Oklab post-engine grade). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A creative chroma grade applied to the engine's OUTPUT buffer (display-referred RGB), in place,
 * once, right after simulate — so it shows live in preview and on export identically. Forum pain #3
 * ("too punchy; I want less saturation") needs a plain Saturation/Vibrance control; the couplers'
 * 3×3 inter-layer matrix is the wrong UI for it. This is a Tier-2 post-op: no engine/spektra-core/cpp
 * is touched, so the 26-test parity suite is unaffected, and default (0,0) is a strict no-op.
 *
 * Method (per the design in docs/USER_DRIVEN_SOLUTIONS.md §3.2): decode the output CCTF → linear →
 * Oklab (Ottosson); scale chroma C while preserving lightness L and hue; back to linear → re-encode.
 *   • Saturation: uniform   C' = C·(1+sat)         (sat ∈ [-1,1]; -1 = grayscale, +1 = 2×).
 *   • Vibrance:   low-chroma-weighted  C' = C·(1+vib·exp(-C/C0)), C0≈0.12 (boosts muted colors, keeps
 *     already-saturated ones from clipping). The two compose: f = (1+sat)·(1+vib·exp(-C/C0)).
 *
 * Correctness across output spaces: Ottosson's linear-RGB→LMS matrix rows each sum to 1, so a neutral
 * (v,v,v) maps to a=b=0 in Oklab for ANY RGB primaries — grays stay neutral without per-space color
 * matrices. The chroma axis is perceptually exact only for the sRGB family (the default + dominant
 * output); for wide spaces it's a faithful, hue-preserving creative control (not colorimetrically
 * exact — which the design accepts). [cctfEncoded] mirrors io.outputCctfEncoding: when false the
 * engine emitted linear already, so the CCTF round-trip is skipped.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.hypot

object ColorGrade {

    /** Oklab chroma scale at which vibrance is half-weighted; tuned so muted colors get most boost. */
    private const val VIBRANCE_C0 = 0.12f

    /** True when [saturation] or [vibrance] (each [-100,100]) would change the image. */
    fun isActive(saturation: Float, vibrance: Float): Boolean = saturation != 0f || vibrance != 0f

    /**
     * Apply Saturation/Vibrance in place to the engine output [data] (interleaved float32 RGB, the
     * SimResult buffer), encoded in [cs] per [cctfEncoded]. No-op when both are 0 (zero per-pixel cost).
     */
    fun applyInPlace(
        data: ByteBuffer,
        w: Int,
        h: Int,
        cs: ColorSpace,
        cctfEncoded: Boolean,
        saturation: Float,
        vibrance: Float,
    ) {
        if (!isActive(saturation, vibrance)) return
        val sat = saturation / 100f
        val vib = vibrance / 100f
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = w * h
        var i = 0
        while (i < n) {
            val k = i * 3
            // display → linear (in the output space's primaries)
            val rl = decode(cs, f.get(k), cctfEncoded)
            val gl = decode(cs, f.get(k + 1), cctfEncoded)
            val bl = decode(cs, f.get(k + 2), cctfEncoded)

            // linear → Oklab (Ottosson). Neutral (v,v,v) → a=b=0 for any primaries (rows sum to 1).
            val l = 0.4122214708f * rl + 0.5363325363f * gl + 0.0514459929f * bl
            val m = 0.2119034982f * rl + 0.6806995451f * gl + 0.1073969566f * bl
            val s = 0.0883024619f * rl + 0.2817188376f * gl + 0.6299787005f * bl
            val l_ = cbrt(l); val m_ = cbrt(m); val s_ = cbrt(s)
            val okL = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
            var okA = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
            var okB = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_

            // scale chroma, preserve L + hue
            val c = hypot(okA, okB)
            val scale = (1f + sat) * (1f + vib * exp(-c / VIBRANCE_C0))
            okA *= scale
            okB *= scale

            // Oklab → linear
            val l2 = okL + 0.3963377774f * okA + 0.2158037573f * okB
            val m2 = okL - 0.1055613458f * okA - 0.0638541728f * okB
            val s2 = okL - 0.0894841775f * okA - 1.2914855480f * okB
            val lc = l2 * l2 * l2; val mc = m2 * m2 * m2; val sc = s2 * s2 * s2
            val r2 = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
            val g2 = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
            val b2 = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc

            // linear → display, clamp back into the encoded [0,1] range
            f.put(k, encode(cs, r2, cctfEncoded))
            f.put(k + 1, encode(cs, g2, cctfEncoded))
            f.put(k + 2, encode(cs, b2, cctfEncoded))
            i++
        }
    }

    private fun cbrt(x: Float): Float = Math.cbrt(x.toDouble()).toFloat()

    /** Inverse of [encode]: display-encoded [c] in [cs] → scene-linear. Identity when [cctf] is off. */
    private fun decode(cs: ColorSpace, c: Float, cctf: Boolean): Float {
        if (!cctf) return c
        return when (cs) {
            ColorSpace.ACES2065_1 -> c
            ColorSpace.ADOBE_RGB -> fpow(c, 2.19921875f)
            ColorSpace.PROPHOTO -> if (c < 0.03125f) c / 16f else fpow(c, 1.8f)
            ColorSpace.REC2020 -> if (c < 0.081f) c / 4.5f else fpow((c + 0.099f) / 1.099f, 1f / 0.45f)
            // SRGB + LINEAR_SRGB share the sRGB CCTF in output_cctf_encode.
            else -> if (c <= 0.04045f) c / 12.92f else fpow((c + 0.055f) / 1.055f, 2.4f)
        }
    }

    /** Mirror of engine model/color_output.cpp::output_cctf_encode. Result clamped to [0,1]. */
    private fun encode(cs: ColorSpace, lIn: Float, cctf: Boolean): Float {
        val l = lIn.coerceAtLeast(0f)  // gamut excursions can push slightly negative; clamp before pow
        val e = if (!cctf) l else when (cs) {
            ColorSpace.ACES2065_1 -> l
            ColorSpace.ADOBE_RGB -> fpow(l, 0.4547069271758437f)
            ColorSpace.PROPHOTO -> if (0.001953125f > l) l * 16f else fpow(l, 1f / 1.8f)
            ColorSpace.REC2020 -> if (0.018f > l) l * 4.5f else 1.099f * fpow(l, 0.45f) - 0.099f
            else -> if (l <= 0.0031308f) 12.92f * l else 1.055f * fpow(l, 1f / 2.4f) - 0.055f
        }
        return e.coerceIn(0f, 1f)
    }

    private fun fpow(x: Float, e: Float): Float = Math.pow(x.toDouble(), e.toDouble()).toFloat()
}
