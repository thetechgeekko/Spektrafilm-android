/*
 * Spektrafilm for Android — unit tests for the skin segmenter's pre/post-processing. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Everything around the model call: the padded (never squashed) input, the softmax over the model's
 * logits, cutting the padding back off so the field covers exactly the photo, and the guided-filter
 * edge refinement.
 */
package com.spectrafilm.app.masks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinSegmentationTest {

    private val size = 8 // small model grid for the tests; the code takes it as a parameter

    @Test
    fun letterbox_keepsAspectAndCentres() {
        assertEquals(Letterbox(400, 200, 0, 64, 256, 128), Letterbox.fit(400, 200))
        assertEquals(Letterbox(200, 400, 64, 0, 128, 256), Letterbox.fit(200, 400))
        assertEquals(Letterbox(300, 300, 0, 0, 256, 256), Letterbox.fit(300, 300))
        val sliver = Letterbox.fit(10_000, 1)
        assertEquals(1, sliver.h)
        assertThrows(IllegalArgumentException::class.java) { Letterbox.fit(0, 10) }
    }

    @Test
    fun fillInput_normalizesContentAndPadsTheRest() {
        val red = IntArray(4 * 2) { 0xFFFF0000.toInt() }
        val box = Letterbox.fit(4, 2, size) // content rows 2..5
        val out = FloatArray(size * size * 3)
        SkinSegmentation.fillInput(red, box, out, size)
        val pad = 128 / 127.5f - 1f
        for (y in 0 until size) for (x in 0 until size) {
            val o = (y * size + x) * 3
            if (y in box.y until box.y + box.h) {
                assertEquals(1f, out[o], 1e-6f)
                assertEquals(-1f, out[o + 1], 1e-6f)
                assertEquals(-1f, out[o + 2], 1e-6f)
            } else {
                for (c in 0..2) assertEquals("pad at $x,$y", pad, out[o + c], 1e-6f)
            }
        }
    }

    @Test
    fun fillInput_keepsLeftOnTheLeft() {
        // Left half black, right half white: the model input must not be mirrored or transposed.
        val w = 16
        val h = 8
        val px = IntArray(w * h) { if (it % w < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val box = Letterbox.fit(w, h, size)
        val out = FloatArray(size * size * 3)
        SkinSegmentation.fillInput(px, box, out, size)
        val row = (box.y + 1) * size
        assertEquals(-1f, out[(row + 0) * 3], 1e-6f)
        assertEquals(1f, out[(row + size - 1) * 3], 1e-6f)
    }

    @Test
    fun fillInput_rejectsWrongBuffers() {
        val box = Letterbox.fit(4, 2, size)
        assertThrows(IllegalArgumentException::class.java) {
            SkinSegmentation.fillInput(IntArray(7), box, FloatArray(size * size * 3), size)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinSegmentation.fillInput(IntArray(8), box, FloatArray(10), size)
        }
    }

    /** Logits where face skin wins in the left half of the content box, background elsewhere. */
    private fun logits(box: Letterbox, faceLogit: Float = 10f): FloatArray {
        val k = SkinModelSpec.NUM_CLASSES
        val out = FloatArray(size * size * k)
        for (y in 0 until size) for (x in 0 until size) {
            val base = (y * size + x) * k
            val inLeftContent = y in box.y until box.y + box.h && x in box.x until box.x + box.w / 2
            if (inLeftContent) out[base + SkinModelSpec.CHANNEL_FACE_SKIN] = faceLogit
            else out[base] = 10f
        }
        return out
    }

    @Test
    fun toSkinField_coversOnlyThePhotoAndSoftmaxes() {
        val box = Letterbox.fit(4, 2, size)
        val field = SkinSegmentation.toSkinField(logits(box), box, size)
        assertEquals(box.w, field.width)
        assertEquals(box.h, field.height)
        val face = setOf(SemanticClass.FACE_SKIN)
        assertTrue(field.sample(face, 0.2f, 0.5f) > 0.99f)
        assertTrue(field.sample(face, 0.8f, 0.5f) < 0.01f)
        assertTrue(field.sample(setOf(SemanticClass.BODY_SKIN), 0.2f, 0.5f) < 0.01f)
        // The photo's top edge is the content box's top, not the padded square's.
        assertTrue(field.sample(face, 0.2f, 0.01f) > 0.99f)
    }

    @Test
    fun toSkinField_isStableForHugeLogitsAndZeroesNonFinite() {
        val box = Letterbox.fit(4, 4, size)
        val big = logits(box, faceLogit = 1e4f)
        val field = SkinSegmentation.toSkinField(big, box, size)
        assertEquals(1f, field.sample(setOf(SemanticClass.FACE_SKIN), 0.1f, 0.5f), 1e-5f)
        val bad = logits(box)
        for (i in bad.indices) bad[i] = Float.NaN
        val inert = SkinSegmentation.toSkinField(bad, box, size)
        assertEquals(0f, inert.sample(setOf(SemanticClass.FACE_SKIN, SemanticClass.BODY_SKIN), 0.1f, 0.5f), 0f)
    }

    @Test
    fun toSkinField_bindsLikeAnyField() {
        val box = Letterbox.fit(4, 2, size)
        val field = SkinSegmentation.toSkinField(logits(box), box, size)
        val skin = MaskComponent.Semantic(setOf(SemanticClass.FACE_SKIN, SemanticClass.BODY_SKIN))
        val mask = Mask(listOf(Mask.Component(BlendMode.ADD, skin)))
            .bindSemantic { BoundSemantic(it, field, FrameMap.IDENTITY) }
        assertTrue(mask.alphaAt(0.1f, 0.5f) > 0.99f)
        assertEquals(0f, mask.alphaAt(0.9f, 0.5f), 1e-4f)
    }

    @Test
    fun boxMean_matchesBruteForce() {
        val w = 7
        val h = 5
        val src = FloatArray(w * h) { ((it * 37) % 11) / 10f }
        val r = 2
        val fast = GuidedFilter.boxMean(src, w, h, r)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (yy in maxOf(0, y - r)..minOf(h - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(w - 1, x + r)) {
                s += src[yy * w + xx]; n++
            }
            assertEquals(s / n, fast[y * w + x], 1e-5f)
        }
    }

    @Test
    fun guidedFilter_keepsFlatInputAndSnapsToTheGuideEdge() {
        val w = 64
        val h = 4
        val flat = GuidedFilter.refine(FloatArray(w * h) { 0.7f }, FloatArray(w * h) { (it % w) / 64f }, w, h, 3, 0.01f)
        for (v in flat) assertEquals(0.7f, v, 1e-4f)

        // Guide: a hard edge at x = 32. Probability: a soft ramp over 24..40 (what upsampling gives).
        val guide = FloatArray(w * h) { if (it % w < 32) 0.1f else 0.9f }
        val ramp = FloatArray(w * h) { ((it % w - 24) / 16f).coerceIn(0f, 1f) }
        val q = GuidedFilter.refine(ramp, guide, w, h, 6, 0.01f)
        assertTrue("left of the edge drops (${q[28]} < ${ramp[28]})", q[28] < ramp[28])
        assertTrue("right of the edge rises (${q[36]} > ${ramp[36]})", q[36] > ramp[36])
        for (v in q) assertTrue(v in 0f..1f)
    }

    @Test
    fun refine_upsamplesEveryPlaneToTheGuide() {
        val box = Letterbox.fit(4, 2, size)
        val field = SkinSegmentation.toSkinField(logits(box), box, size)
        val gw = 32
        val gh = 16
        val guide = SkinSegmentation.luma(IntArray(gw * gh) { if (it % gw < gw / 2) 0xFFC08060.toInt() else 0xFF202020.toInt() })
        val fine = SkinSegmentation.refine(field, guide, gw, gh)
        assertEquals(gw, fine.width)
        assertEquals(gh, fine.height)
        assertTrue(fine.has(SemanticClass.FACE_SKIN) && fine.has(SemanticClass.BODY_SKIN))
        val face = setOf(SemanticClass.FACE_SKIN)
        assertTrue(fine.sample(face, 0.1f, 0.5f) > 0.95f)
        assertTrue(fine.sample(face, 0.9f, 0.5f) < 0.05f)
    }

    @Test
    fun luma_isRec709() {
        val l = SkinSegmentation.luma(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF00FF00.toInt()))
        assertEquals(1f, l[0], 1e-6f)
        assertEquals(0f, l[1], 0f)
        assertEquals(0.7152f, l[2], 1e-6f)
    }
}
