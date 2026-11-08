/*
 * Spektrafilm for Android — pre- and post-processing for the on-device skin segmenter. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pure Kotlin around the model, so everything except the inference call runs (and is tested) on the
 * plain JVM:
 *   photo pixels --[Letterbox + fillInput]--> 256x256x3 float input in [-1, 1]
 *   model logits --[toSkinField]--> per-class probabilities for the PHOTO region only (padding cut off)
 *   probabilities --[GuidedFilter.refine]--> edges snapped to the photo at a higher resolution
 *
 * The model is Google's MediaPipe "Selfie Multiclass 256x256" (Apache 2.0): NHWC float input,
 * normalized to [-1, 1]; output [1, 256, 256, 6] LOGITS for background, hair, body skin, face skin,
 * clothes, others. See docs/MASKING_SPEC.md §5 for why this model and the phone measurements.
 *
 * The photo is padded to a square (centred, mid grey) instead of squashed: squashing distorts faces
 * and measurably hurt the masks in our evaluation.
 */
package com.spectrafilm.app.masks

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fixed facts about the model the segmenter loads. Changing the model means changing these. */
object SkinModelSpec {
    const val INPUT_SIZE = 256
    const val NUM_CLASSES = 6
    const val CHANNEL_BODY_SKIN = 2
    const val CHANNEL_FACE_SKIN = 3

    /** Pad value before normalization (mid grey). */
    const val PAD_VALUE = 128
}

/**
 * Where a [srcW]x[srcH] photo sits inside the square model input: scaled to fit (aspect kept) and
 * centred, with the rest padded. [x], [y], [w], [h] are the content box in input pixels.
 */
data class Letterbox(val srcW: Int, val srcH: Int, val x: Int, val y: Int, val w: Int, val h: Int) {
    companion object {
        fun fit(srcW: Int, srcH: Int, size: Int = SkinModelSpec.INPUT_SIZE): Letterbox {
            require(srcW > 0 && srcH > 0 && size > 0) { "letterbox dimensions must be positive" }
            val w: Int
            val h: Int
            if (srcW >= srcH) {
                w = size
                h = (size.toLong() * srcH / srcW.toDouble()).roundToInt().coerceIn(1, size)
            } else {
                h = size
                w = (size.toLong() * srcW / srcH.toDouble()).roundToInt().coerceIn(1, size)
            }
            return Letterbox(srcW, srcH, (size - w) / 2, (size - h) / 2, w, h)
        }
    }
}

object SkinSegmentation {

    /**
     * Fill [out] (NHWC, `size*size*3`) from [argb] (row-major `srcW*srcH` packed ARGB, e.g.
     * `Bitmap.getPixels`) through [box]: bilinear resample into the content box, [SkinModelSpec.PAD_VALUE]
     * elsewhere, every channel mapped from 0..255 to [-1, 1].
     */
    fun fillInput(argb: IntArray, box: Letterbox, out: FloatArray, size: Int = SkinModelSpec.INPUT_SIZE) {
        val srcW = box.srcW
        val srcH = box.srcH
        require(argb.size == srcW * srcH) { "pixel buffer is ${argb.size}, expected ${srcW * srcH}" }
        require(out.size == size * size * 3) { "input buffer is ${out.size}, expected ${size * size * 3}" }
        val pad = norm(SkinModelSpec.PAD_VALUE)
        out.fill(pad)
        val sx = srcW.toFloat() / box.w
        val sy = srcH.toFloat() / box.h
        for (oy in 0 until box.h) {
            val fy = ((oy + 0.5f) * sy - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, srcH - 1)
            val ty = fy - y0
            var o = ((box.y + oy) * size + box.x) * 3
            for (ox in 0 until box.w) {
                val fx = ((ox + 0.5f) * sx - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, srcW - 1)
                val tx = fx - x0
                val p00 = argb[y0 * srcW + x0]
                val p01 = argb[y0 * srcW + x1]
                val p10 = argb[y1 * srcW + x0]
                val p11 = argb[y1 * srcW + x1]
                out[o++] = lerp2(p00, p01, p10, p11, 16, tx, ty) / 127.5f - 1f // R
                out[o++] = lerp2(p00, p01, p10, p11, 8, tx, ty) / 127.5f - 1f  // G
                out[o++] = lerp2(p00, p01, p10, p11, 0, tx, ty) / 127.5f - 1f  // B
            }
        }
    }

    /**
     * Turn the model's [logits] (NHWC, `size*size*NUM_CLASSES`) into a [SemanticField] covering only
     * the photo region of [box], with face- and body-skin probabilities from a per-pixel softmax.
     * A non-finite logit makes that cell's probabilities 0 (unselected), never NaN.
     */
    fun toSkinField(logits: FloatArray, box: Letterbox, size: Int = SkinModelSpec.INPUT_SIZE): SemanticField {
        val k = SkinModelSpec.NUM_CLASSES
        require(logits.size == size * size * k) { "logits buffer is ${logits.size}, expected ${size * size * k}" }
        val n = box.w * box.h
        val face = FloatArray(n)
        val body = FloatArray(n)
        var i = 0
        for (y in box.y until box.y + box.h) {
            for (x in box.x until box.x + box.w) {
                val base = (y * size + x) * k
                var maxLogit = Float.NEGATIVE_INFINITY
                var finite = true
                for (c in 0 until k) {
                    val v = logits[base + c]
                    if (!v.isFinite()) finite = false
                    if (v > maxLogit) maxLogit = v
                }
                if (finite) {
                    var sum = 0f
                    for (c in 0 until k) sum += exp(logits[base + c] - maxLogit)
                    face[i] = exp(logits[base + SkinModelSpec.CHANNEL_FACE_SKIN] - maxLogit) / sum
                    body[i] = exp(logits[base + SkinModelSpec.CHANNEL_BODY_SKIN] - maxLogit) / sum
                }
                i++
            }
        }
        return SemanticField(
            box.w, box.h,
            mapOf(SemanticClass.FACE_SKIN to face, SemanticClass.BODY_SKIN to body),
        )
    }

    /**
     * Upsample every plane of [field] to the [guideW]x[guideH] grid of [guide] (luma 0..1, covering
     * the same photo) and snap its edges to the photo with [GuidedFilter]. The model sees the photo at
     * 256 px; this is what keeps a skin edge on the jawline instead of a soft blob at full size.
     */
    fun refine(field: SemanticField, guide: FloatArray, guideW: Int, guideH: Int): SemanticField {
        require(guide.size == guideW * guideH) { "guide is ${guide.size}, expected ${guideW * guideH}" }
        val radius = GuidedFilter.radiusFor(guideW, guideH)
        val planes = SemanticClass.entries.filter { field.has(it) }.associateWith { cls ->
            val up = FloatArray(guideW * guideH)
            val one = setOf(cls)
            for (y in 0 until guideH) {
                val sy = (y + 0.5f) / guideH
                for (x in 0 until guideW) up[y * guideW + x] = field.sample(one, (x + 0.5f) / guideW, sy)
            }
            GuidedFilter.refine(up, guide, guideW, guideH, radius, GuidedFilter.DEFAULT_EPS)
        }
        return SemanticField(guideW, guideH, planes)
    }

    /** Rec. 709 luma (0..1) of packed ARGB pixels, the guide for [refine]. */
    fun luma(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val p = argb[i]
        (0.2126f * ch(p, 16) + 0.7152f * ch(p, 8) + 0.0722f * ch(p, 0)) / 255f
    }

    private fun lerp2(p00: Int, p01: Int, p10: Int, p11: Int, shift: Int, tx: Float, ty: Float): Float {
        val top = ch(p00, shift) * (1f - tx) + ch(p01, shift) * tx
        val bottom = ch(p10, shift) * (1f - tx) + ch(p11, shift) * tx
        return top * (1f - ty) + bottom * ty
    }

    private fun ch(p: Int, shift: Int): Float = ((p ushr shift) and 0xFF).toFloat()
    private fun norm(v: Int): Float = v / 127.5f - 1f
}

/**
 * He et al.'s guided filter (grey guide): an edge-preserving smoother that makes the output a local
 * linear function of the guide, so a coarse probability map takes on the photo's edges. Box means come
 * from summed-area tables, so the cost is O(pixels) whatever the radius.
 */
object GuidedFilter {
    /** Regularization for a 0..1 guide; larger smooths more across weak edges. */
    const val DEFAULT_EPS = 0.01f

    /** About two model pixels at the guide's scale, at least 2. */
    fun radiusFor(w: Int, h: Int): Int = max(2, (2f * max(w, h) / SkinModelSpec.INPUT_SIZE).roundToInt())

    fun refine(p: FloatArray, guide: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = w * h
        require(w > 0 && h > 0 && p.size == n && guide.size == n) { "guided filter inputs must be ${w}x$h" }
        require(radius >= 1 && eps > 0f) { "guided filter needs radius >= 1 and eps > 0" }
        val ip = FloatArray(n) { guide[it] * p[it] }
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val meanI = boxMean(guide, w, h, radius)
        val meanP = boxMean(p, w, h, radius)
        val corrIp = boxMean(ip, w, h, radius)
        val corrI = boxMean(ii, w, h, radius)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val covIp = corrIp[i] - meanI[i] * meanP[i]
            a[i] = covIp / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxMean(a, w, h, radius)
        val meanB = boxMean(b, w, h, radius)
        return FloatArray(n) { i ->
            val q = meanA[i] * guide[i] + meanB[i]
            if (q.isFinite()) q.coerceIn(0f, 1f) else 0f
        }
    }

    /** Mean over the (2r+1)^2 window clipped to the image, via a double-precision summed-area table. */
    internal fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val sat = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w, x + r + 1)
                val s = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
                out[y * w + x] = (s / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        return out
    }
}

/**
 * The on-device skin finder. Implementations run off the main thread and honour cancellation;
 * null means no field could be produced (model unavailable, inference failed), which leaves every
 * skin mask inert rather than wrong.
 */
fun interface SkinSegmenter {
    /** [argb] is the photo (after EXIF orientation, before crop), `width*height` packed ARGB. */
    suspend fun segment(argb: IntArray, width: Int, height: Int): SemanticField?
}
