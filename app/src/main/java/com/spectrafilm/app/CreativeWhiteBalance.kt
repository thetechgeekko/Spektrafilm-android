/*
 * Spektrafilm for Android — creative white balance. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A parity-free, all-sources warm/cool + green/magenta grade applied to the LINEAR ProPhoto input
 * BEFORE the spectral engine. The engine's working space is linear ProPhoto (D50) — identical to
 * Lightroom's internal "Lightroom RGB" (RE'd: docs/RESEARCH_LIGHTROOM_IMPLEMENTATION.md §B) — so a
 * Bradford chromatic-adaptation transform here is the correct model. This is DISTINCT from the
 * RAW-decode white balance (lib/libraw, which corrects the camera's as-shot illuminant to 6504 K and
 * exists only for RAW): creative WB is a deliberate look that also works on JPEG/HEIC, and it never
 * touches engine/spektra-core/cpp so the parity suite is unaffected.
 *
 * UI is a relative push in [-100, 100]; 0/0 = identity (no-op, skipped). temp>0 warms; tint>0 = magenta.
 */
package com.spectrafilm.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object CreativeWhiteBalance {

    // ProPhoto's adopted white is D50; the neutral slider position adapts D50 -> D50 = identity.
    private const val D50_CCT = 5003.0
    private val D50_MIRED = 1.0e6 / D50_CCT
    // ±100 spans ±110 mired (a strong, Lightroom-ish warm/cool range) and ±0.18 green gain for tint.
    private const val TEMP_SPAN_MIRED = 110.0
    private const val TINT_SPAN = 0.18
    private const val EPS = 1e-3f

    // ProPhoto(D50) RGB <-> XYZ (Lindbloom), row-major 3x3.
    private val PROPHOTO_TO_XYZ = doubleArrayOf(
        0.7976749, 0.1351917, 0.0313534,
        0.2880402, 0.7118741, 0.0000857,
        0.0000000, 0.0000000, 0.8252100,
    )
    private val XYZ_TO_PROPHOTO = doubleArrayOf(
        1.3459433, -0.2556075, -0.0511118,
        -0.5445989, 1.5081673, 0.0205351,
        0.0000000, 0.0000000, 1.2118128,
    )
    // Bradford cone-response matrix and its inverse (the ICC/colour-science default CAT).
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    private val BRADFORD_INV = doubleArrayOf(
        0.9869929, -0.1470543, 0.1599627,
        0.4323053, 0.5183603, 0.0492912,
        -0.0085287, 0.0400428, 0.9684867,
    )

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** True when both sliders are ~0 → matrix is identity and the pass can be skipped entirely. */
    fun isNeutral(temp: Float, tint: Float): Boolean = abs(temp) < EPS && abs(tint) < EPS

    /**
     * CCT (kelvin) -> XYZ whitepoint (Y=1): CIE daylight locus for >=4000 K, Kang 2002 Planckian
     * below — the same locus the RAW decoder uses (raw_decoder.cpp::whitepointXyzFromTemperature),
     * so creative and corrective WB stay on one consistent color model.
     */
    fun whitepointXyz(cct: Double): DoubleArray {
        val x: Double
        val y: Double
        if (cct >= 4000.0) {
            val t1 = 1.0e3 / cct; val t2 = 1.0e6 / (cct * cct); val t3 = 1.0e9 / (cct * cct * cct)
            x = if (cct <= 7000.0) 0.244063 + 0.09911 * t1 + 2.9678 * t2 - 4.6070 * t3
            else 0.237040 + 0.24748 * t1 + 1.9018 * t2 - 2.0064 * t3
            y = -3.000 * x * x + 2.870 * x - 0.275
        } else {
            val t2 = cct * cct; val t3 = t2 * cct
            x = if (cct <= 4000.0) -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / cct + 0.179910
            else -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / cct + 0.240390
            val x2 = x * x; val x3 = x2 * x
            y = when {
                cct <= 2222.0 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
                cct <= 4000.0 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
            }
        }
        return doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
    }

    /**
     * Row-major float 3x3 to apply to linear ProPhoto RGB for relative [temp]/[tint] in [-100,100].
     * temp>0 warms the image (Bradford-adapts the D50 reference toward a warmer target white);
     * tint>0 adds magenta (green-channel gain < 1). Returns identity when neutral.
     */
    fun matrix(temp: Float, tint: Float): FloatArray {
        if (isNeutral(temp, tint)) return IDENTITY.copyOf()
        // Warmer image = warmer (lower-CCT) target white; map the slider in perceptually-linear mireds.
        val targetCct = 1.0e6 / (D50_MIRED + (temp / 100.0) * TEMP_SPAN_MIRED)
        val src = whitepointXyz(D50_CCT)
        val dst = whitepointXyz(targetCct)
        val cat = bradfordCat(src, dst)
        var m = mul3(XYZ_TO_PROPHOTO, mul3(cat, PROPHOTO_TO_XYZ))
        // Tint as a green-channel multiplier (mirrors spektrafilm's _apply_tint_adjustment), folded
        // in after the CAT: diag(1, g, 1) . M.
        val g = 1.0 - (tint / 100.0) * TINT_SPAN
        m = doubleArrayOf(m[0], m[1], m[2], m[3] * g, m[4] * g, m[5] * g, m[6], m[7], m[8])
        return FloatArray(9) { m[it].toFloat() }
    }

    /**
     * Apply row-major 3x3 [m] in place to a direct float32 RGB buffer of [pixelCount] interleaved
     * pixels (native byte order, matching the engine's JNI view). No clamping — the spectral
     * upsampler handles the small out-of-[0,1] excursions, as the RAW-WB path does.
     */
    fun applyInPlace(data: ByteBuffer, pixelCount: Int, m: FloatArray) {
        val dup = data.duplicate()
        dup.clear()
        dup.order(ByteOrder.nativeOrder())
        val f = dup.asFloatBuffer()
        val m0 = m[0]; val m1 = m[1]; val m2 = m[2]
        val m3 = m[3]; val m4 = m[4]; val m5 = m[5]
        val m6 = m[6]; val m7 = m[7]; val m8 = m[8]
        var i = 0
        repeat(pixelCount) {
            val r = f.get(i); val gv = f.get(i + 1); val b = f.get(i + 2)
            f.put(i, m0 * r + m1 * gv + m2 * b)
            f.put(i + 1, m3 * r + m4 * gv + m5 * b)
            f.put(i + 2, m6 * r + m7 * gv + m8 * b)
            i += 3
        }
    }

    /**
     * Bradford chromatic-adaptation 3x3 (XYZ→XYZ) mapping a color seen under white [wSrc] to its
     * appearance under [wDst] (CAT·wSrc = wDst). Shared with the per-mask local WB (LocalWhiteBalance).
     */
    internal fun bradfordCat(wSrc: DoubleArray, wDst: DoubleArray): DoubleArray {
        val cs = mulVec(BRADFORD, wSrc)
        val cd = mulVec(BRADFORD, wDst)
        val diag = doubleArrayOf(cd[0] / cs[0], 0.0, 0.0, 0.0, cd[1] / cs[1], 0.0, 0.0, 0.0, cd[2] / cs[2])
        return mul3(BRADFORD_INV, mul3(diag, BRADFORD))
    }

    /** Row-major 3x3 · 3x3 (shared with LocalWhiteBalance). */
    internal fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[r * 3 + k] * b[k * 3 + c]
            o[r * 3 + c] = s
        }
        return o
    }

    private fun mulVec(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )
}
