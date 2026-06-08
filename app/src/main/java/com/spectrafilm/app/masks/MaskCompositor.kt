/*
 * Spektrafilm for Android — local-adjustment compositor. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The seam that makes masks DO something: it blends a [LocalAdjustment]'s Tier-A delta into the engine
 * OUTPUT buffer where the mask is opaque. Applied in place on the same `SimResult.data` the global
 * grade uses (so preview + every export inherit it identically), it is a parity-safe Tier-2 op — no
 * engine/spektra-core/cpp is touched, and an empty/no-op stack is a strict no-op.
 *
 * v1 implements the canonical local adjustment, EXPOSURE (dodge & burn): decode the output CCTF →
 * scale linear by 2^EV → re-encode → blend `(1−α)·in + α·out`. Temp/tint/saturation/contrast Tier-A
 * ops land in the next increment (reusing the global ColorGrade/CreativeWhiteBalance math); the
 * [TierADelta] already carries those fields.
 */
package com.spectrafilm.app.masks

import com.spectrafilm.app.OutputCctf
import com.spectrafilm.engine.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MaskCompositor {

    /**
     * Apply every active [adjustments] entry in order, in place, to the output [data] (interleaved
     * float32 RGB, encoded in [cs] per [cctfEncoded]). Stacked masks compose (each blends onto the
     * previous result). No-op when nothing is active.
     */
    fun applyInPlace(
        data: ByteBuffer,
        w: Int,
        h: Int,
        cs: ColorSpace,
        cctfEncoded: Boolean,
        adjustments: List<LocalAdjustment>,
    ) {
        val active = adjustments.filter { exposureGain(it.delta.exposureEv) != 1f && MaskRaster.coverage(it.mask) > 1e-4f }
        if (active.isEmpty()) return
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (adj in active) {
            val gain = exposureGain(adj.delta.exposureEv)
            val alpha = MaskRaster.rasterize(adj.mask, w, h)
            var p = 0
            val n = w * h
            while (p < n) {
                val a = alpha[p]
                if (a > 0f) {
                    val k = p * 3
                    blendChannel(f, k, cs, cctfEncoded, gain, a)
                    blendChannel(f, k + 1, cs, cctfEncoded, gain, a)
                    blendChannel(f, k + 2, cs, cctfEncoded, gain, a)
                }
                p++
            }
        }
    }

    /** Exposure as a linear-light gain (2^EV); 0 EV → 1.0 (no-op). */
    private fun exposureGain(ev: Float): Float =
        if (ev == 0f) 1f else Math.pow(2.0, ev.toDouble()).toFloat()

    /** Decode → ×gain (linear) → encode, then blend the adjusted channel into the original by [a]. */
    private fun blendChannel(f: java.nio.FloatBuffer, k: Int, cs: ColorSpace, cctf: Boolean, gain: Float, a: Float) {
        val orig = f.get(k)
        val adjusted = OutputCctf.encode(cs, OutputCctf.decode(cs, orig, cctf) * gain, cctf)
        f.put(k, orig + a * (adjusted - orig))
    }
}
