/*
 * Spektrafilm for Android — per-mask local white balance (output-space chromatic adaptation). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The per-mask Temp/Tint op. Unlike the global CreativeWhiteBalance (which runs in the engine's linear
 * ProPhoto INPUT), a mask composites on the engine OUTPUT, in the user's chosen output space — so the
 * white balance must be a chromatic adaptation IN THAT SPACE to be colorimetrically accurate. This
 * builds a single linear-output-RGB 3x3 = XYZ→RGB · Bradford-CAT · RGB→XYZ (computed once per
 * adjustment, ~9 mults per pixel), reusing CreativeWhiteBalance's tested whitepoint locus + Bradford
 * CAT so the local and global WB share one color model. Temp adapts the space's native white toward a
 * warmer/cooler target (in mireds); Tint is a green-channel gain (magenta/green), as in the global WB.
 *
 * temp=0, tint=0 → exact identity (the CAT of a white onto itself cancels). Pure Kotlin, no engine
 * touched. RGB↔XYZ are the standard Lindbloom matrices at each space's adopted white (D65 for the sRGB
 * family, D50 for ProPhoto, ~D60 for ACES AP0).
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace

object LocalWhiteBalance {

    // Match CreativeWhiteBalance's creative scales so the local Temp/Tint feels like the global one.
    private const val TEMP_SPAN_MIRED = 110.0
    private const val TINT_SPAN = 0.18

    // Adopted-white CCT per space (the daylight-locus point the CAT adapts FROM; temp=0 → src=dst → id).
    private const val D65_CCT = 6504.0
    private const val D50_CCT = 5003.0
    private const val D60_CCT = 6000.0

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /**
     * Row-major float 3x3 to apply to LINEAR [cs] RGB for relative [temp]/[tint] in [-100,100].
     * temp>0 warms (adapts the native white toward a lower-CCT target); tint>0 adds magenta. Identity
     * when neutral.
     */
    fun matrix(cs: ColorSpace, temp: Float, tint: Float): FloatArray {
        if (CreativeWhiteBalance.isNeutral(temp, tint)) return IDENTITY.copyOf()
        val toXyz = rgbToXyz(cs)
        val fromXyz = xyzToRgb(cs)
        val srcCct = nativeCct(cs)
        val targetCct = 1.0e6 / (1.0e6 / srcCct + (temp / 100.0) * TEMP_SPAN_MIRED)
        val src = CreativeWhiteBalance.whitepointXyz(srcCct)
        val dst = CreativeWhiteBalance.whitepointXyz(targetCct)
        val cat = CreativeWhiteBalance.bradfordCat(src, dst)
        var m = CreativeWhiteBalance.mul3(fromXyz, CreativeWhiteBalance.mul3(cat, toXyz))
        // Tint = green-channel gain, folded in after the CAT (mirrors the global WB): diag(1,g,1)·M.
        val g = 1.0 - (tint / 100.0) * TINT_SPAN
        m = doubleArrayOf(m[0], m[1], m[2], m[3] * g, m[4] * g, m[5] * g, m[6], m[7], m[8])
        return FloatArray(9) { m[it].toFloat() }
    }

    private fun nativeCct(cs: ColorSpace): Double = when (cs) {
        ColorSpace.PROPHOTO -> D50_CCT
        ColorSpace.ACES2065_1 -> D60_CCT
        else -> D65_CCT  // SRGB, LINEAR_SRGB, ADOBE_RGB, REC2020
    }

    // Standard Lindbloom RGB→XYZ (row-major) at each space's adopted white.
    private fun rgbToXyz(cs: ColorSpace): DoubleArray = when (cs) {
        ColorSpace.ADOBE_RGB -> doubleArrayOf(
            0.5767309, 0.1855540, 0.1881852,
            0.2973769, 0.6273491, 0.0752741,
            0.0270343, 0.0706872, 0.9911085,
        )
        ColorSpace.REC2020 -> doubleArrayOf(
            0.6369580, 0.1446169, 0.1688810,
            0.2627002, 0.6779981, 0.0593017,
            0.0000000, 0.0280727, 1.0609851,
        )
        ColorSpace.PROPHOTO -> doubleArrayOf(
            0.7976749, 0.1351917, 0.0313534,
            0.2880402, 0.7118741, 0.0000857,
            0.0000000, 0.0000000, 0.8252100,
        )
        ColorSpace.ACES2065_1 -> doubleArrayOf(
            0.9525523959, 0.0000000000, 0.0000936786,
            0.3439664498, 0.7281660966, -0.0721325464,
            0.0000000000, 0.0000000000, 1.0088251844,
        )
        else -> doubleArrayOf(  // sRGB / linear-sRGB (D65)
            0.4124564, 0.3575761, 0.1804375,
            0.2126729, 0.7151522, 0.0721750,
            0.0193339, 0.1191920, 0.9503041,
        )
    }

    private fun xyzToRgb(cs: ColorSpace): DoubleArray = when (cs) {
        ColorSpace.ADOBE_RGB -> doubleArrayOf(
            2.0413690, -0.5649464, -0.3446944,
            -0.9692660, 1.8760108, 0.0415560,
            0.0134474, -0.1183897, 1.0154096,
        )
        ColorSpace.REC2020 -> doubleArrayOf(
            1.7166512, -0.3556708, -0.2533663,
            -0.6666844, 1.6164812, 0.0157685,
            0.0176399, -0.0427706, 0.9421031,
        )
        ColorSpace.PROPHOTO -> doubleArrayOf(
            1.3459433, -0.2556075, -0.0511118,
            -0.5445989, 1.5081673, 0.0205351,
            0.0000000, 0.0000000, 1.2118128,
        )
        ColorSpace.ACES2065_1 -> doubleArrayOf(
            1.0498110175, 0.0000000000, -0.0000974845,
            -0.4959030231, 1.3733130458, 0.0982400361,
            0.0000000000, 0.0000000000, 0.9912520182,
        )
        else -> doubleArrayOf(  // sRGB / linear-sRGB (D65)
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252,
        )
    }
}
