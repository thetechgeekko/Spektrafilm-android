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

object ColorGrade {

    /** True when [saturation] or [vibrance] (each [-100,100]) would change the image. */
    fun isActive(saturation: Float, vibrance: Float): Boolean = saturation != 0f || vibrance != 0f

    /**
     * Apply gamut compression + Saturation/Vibrance in place to the engine output [data] (interleaved
     * float32 RGB, the SimResult buffer), encoded in [cs] per [cctfEncoded]. Both share one CCTF
     * round-trip: decode → (gamut compress) → (Oklab chroma scale) → encode. No-op (zero per-pixel
     * cost) when [gamutCompress] is 0 and [saturation]/[vibrance] are both 0.
     */
    fun applyInPlace(
        data: ByteBuffer,
        w: Int,
        h: Int,
        cs: ColorSpace,
        cctfEncoded: Boolean,
        saturation: Float,
        vibrance: Float,
        gamutCompress: Float = 0f,
    ) {
        val chroma = isActive(saturation, vibrance)
        val gamut = GamutCompress.isActive(gamutCompress)
        if (!chroma && !gamut) return
        val sat = saturation / 100f
        val vib = vibrance / 100f
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val rgb = FloatArray(3)  // reused per pixel for the gamut-compress triple (no per-pixel alloc)
        val n = w * h
        var i = 0
        while (i < n) {
            val k = i * 3
            // display → linear (in the output space's primaries)
            var rl = OutputCctf.decode(cs, f.get(k), cctfEncoded)
            var gl = OutputCctf.decode(cs, f.get(k + 1), cctfEncoded)
            var bl = OutputCctf.decode(cs, f.get(k + 2), cctfEncoded)

            // Gamut compression first: pull the most-saturated colors toward neutral (softens clip).
            if (gamut) {
                rgb[0] = rl; rgb[1] = gl; rgb[2] = bl
                GamutCompress.apply(rgb, gamutCompress)
                rl = rgb[0]; gl = rgb[1]; bl = rgb[2]
            }

            var r2 = rl; var g2 = gl; var b2 = bl
            if (chroma) {
                // Oklab chroma grade (shared with the per-mask Saturation op): scale C by
                // (1+sat)·(1+vib·exp(-C/C0)), preserving lightness + hue. Neutral → a=b=0 (rows sum to 1).
                rgb[0] = rl; rgb[1] = gl; rgb[2] = bl
                Oklab.scaleChromaLinear(rgb, sat, vib)
                r2 = rgb[0]; g2 = rgb[1]; b2 = rgb[2]
            }

            // linear → display, clamp back into the encoded [0,1] range
            f.put(k, OutputCctf.encode(cs, r2, cctfEncoded))
            f.put(k + 1, OutputCctf.encode(cs, g2, cctfEncoded))
            f.put(k + 2, OutputCctf.encode(cs, b2, cctfEncoded))
            i++
        }
    }
}
