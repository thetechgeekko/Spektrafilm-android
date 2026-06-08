/*
 * Spektrafilm for Android — output-space CCTF (transfer) decode/encode. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The 1-D per-output-space transfer functions the engine applies at the end of scanning, factored out
 * as a shared source of truth for the post-engine output ops (the mask compositor uses it; ColorGrade
 * still carries an equivalent private copy, to be de-duped onto this in a follow-up). A mirror of
 * engine model/color_output.cpp::output_cctf_encode + its inverse, gated by [cctf] (= io.outputCctfEncoding;
 * when false the engine emitted linear, so the round-trip is identity). Pure Kotlin, no engine touched.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace

object OutputCctf {

    /** Display-encoded [c] in [cs] → scene-linear. Identity when [cctf] is off. */
    fun decode(cs: ColorSpace, c: Float, cctf: Boolean): Float {
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

    /** Scene-linear [lIn] → display-encoded in [cs], clamped to [0,1]. Identity when [cctf] is off. */
    fun encode(cs: ColorSpace, lIn: Float, cctf: Boolean): Float {
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
