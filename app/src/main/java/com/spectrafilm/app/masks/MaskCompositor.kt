/*
 * Spektrafilm for Android — local-adjustment compositor. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The seam that makes masks DO something: it blends a [LocalAdjustment]'s Tier-A delta into the engine
 * OUTPUT buffer where the mask is opaque. Applied in place on the same `SimResult.data` the global
 * grade uses (so preview + every export inherit it identically), it is a parity-safe Tier-2 op — no
 * engine/spektra-core/cpp is touched, and an empty/no-op stack is a strict no-op.
 *
 * Per pixel where the mask is opaque: decode the output CCTF → apply the Tier-A ops in linear/encoded
 * domain → blend `(1−α)·in + α·out`. Wired ops (Class-P, pointwise, parity-safe): **Exposure** (2^EV
 * linear, dodge & burn), **Saturation** (Oklab chroma scale, hue-neutral-gray), **Contrast** (the
 * hue-neutral master S-curve, per channel on the encoded value — same curve as the global control).
 * Temp/tint/whites/blacks/hue land next (temp/tint need an output-space CAT decision); [TierADelta]
 * already carries them.
 */
package com.spectrafilm.app.masks

import com.spectrafilm.app.ContrastCurve
import com.spectrafilm.app.Oklab
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
        val active = adjustments.filter { hasOp(it.delta) && MaskRaster.coverage(it.mask) > 1e-4f }
        if (active.isEmpty()) return
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val rgb = FloatArray(3)  // reused per pixel for the linear-domain ops (no per-pixel alloc)
        for (adj in active) {
            val d = adj.delta
            val gain = exposureGain(d.exposureEv)
            val sat = d.saturation / 100f
            val hue = d.hue
            val contrast = d.contrast
            // whites/blacks = a linear levels remap of the encoded value: move the display white/black
            // points, then rescale. (0,0) → bp=0, wp=1 → identity. ±100 moves an endpoint by 0.25.
            val levels = d.whites != 0f || d.blacks != 0f
            val bp = -(d.blacks / 100f) * 0.25f               // blacks + → bp<0 (lift); − → bp>0 (crush)
            val wp = 1f - (d.whites / 100f) * 0.25f            // whites + → wp<1 (brighten); − → wp>1 (recover)
            val invSpan = if (levels) 1f / (wp - bp) else 1f   // wp−bp ∈ [0.5,1.5] over the slider ranges
            val lumRange = adj.mask.luminanceRange?.takeIf { it.isActive }
            val colorRange = adj.mask.colorRange?.takeIf { it.isActive }
            val alpha = MaskRaster.rasterize(adj.mask, w, h)
            var p = 0
            val n = w * h
            while (p < n) {
                var a = alpha[p]
                if (a > 0f) {
                    val k = p * 3
                    val or = f.get(k); val og = f.get(k + 1); val ob = f.get(k + 2)
                    // luminance-range refinement: gate coverage by the output luma (Rec-709 on display).
                    if (lumRange != null) a *= lumRange.gate(0.2126f * or + 0.7152f * og + 0.0722f * ob)
                    // color-range refinement: gate coverage by chroma-plane distance to the target color.
                    if (a > 0f && colorRange != null) a *= colorRange.gate(or, og, ob)
                    if (a > 0f) {
                        // decode → linear, exposure gain
                        rgb[0] = OutputCctf.decode(cs, or, cctfEncoded) * gain
                        rgb[1] = OutputCctf.decode(cs, og, cctfEncoded) * gain
                        rgb[2] = OutputCctf.decode(cs, ob, cctfEncoded) * gain
                        // saturation (linear Oklab; hue + lightness preserved)
                        if (sat != 0f) Oklab.scaleChromaLinear(rgb, sat, 0f)
                        // hue (linear Oklab; rotate chroma, lightness preserved)
                        if (hue != 0f) Oklab.rotateHueLinear(rgb, hue)
                        // encode → display
                        var er = OutputCctf.encode(cs, rgb[0], cctfEncoded)
                        var eg = OutputCctf.encode(cs, rgb[1], cctfEncoded)
                        var eb = OutputCctf.encode(cs, rgb[2], cctfEncoded)
                        // contrast (encoded, hue-neutral per-channel S-curve)
                        if (contrast != 0f) {
                            er = ContrastCurve.curveAt(er, contrast)
                            eg = ContrastCurve.curveAt(eg, contrast)
                            eb = ContrastCurve.curveAt(eb, contrast)
                        }
                        // whites/blacks (encoded levels remap; anchors the opposite end for a single slider)
                        if (levels) {
                            er = ((er - bp) * invSpan).coerceIn(0f, 1f)
                            eg = ((eg - bp) * invSpan).coerceIn(0f, 1f)
                            eb = ((eb - bp) * invSpan).coerceIn(0f, 1f)
                        }
                        // blend by alpha: (1−a)·in + a·out
                        f.put(k, or + a * (er - or))
                        f.put(k + 1, og + a * (eg - og))
                        f.put(k + 2, ob + a * (eb - ob))
                    }
                }
                p++
            }
        }
    }

    /** True when [d] has a Tier-A op the compositor wires today (exposure / sat / hue / contrast / levels). */
    private fun hasOp(d: TierADelta): Boolean =
        d.exposureEv != 0f || d.saturation != 0f || d.hue != 0f || d.contrast != 0f ||
            d.whites != 0f || d.blacks != 0f

    /** Exposure as a linear-light gain (2^EV); 0 EV → 1.0 (no-op). */
    private fun exposureGain(ev: Float): Float =
        if (ev == 0f) 1f else Math.pow(2.0, ev.toDouble()).toFloat()
}
