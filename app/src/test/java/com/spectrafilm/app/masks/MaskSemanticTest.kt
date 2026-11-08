/*
 * Spektrafilm for Android — unit tests for semantic (AI) mask components. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards the contract the segmenter and compositor will rely on: an unbound semantic component is
 * inert, a bound one samples the field in SOURCE-photo space through the crop map, softness/expand
 * shape the edge monotonically, and a bound component folds like any other shape.
 */
package com.spectrafilm.app.masks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskSemanticTest {

    private val skin = MaskComponent.Semantic(setOf(SemanticClass.FACE_SKIN, SemanticClass.BODY_SKIN))

    /** 4x2 field: face skin fills the left half of the photo, body skin the bottom-right cell only. */
    private val field = SemanticField(
        4, 2,
        mapOf(
            SemanticClass.FACE_SKIN to floatArrayOf(1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f),
            SemanticClass.BODY_SKIN to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
        ),
    )

    @Test
    fun unboundSemantic_selectsNothing() {
        val mask = Mask(listOf(Mask.Component(BlendMode.ADD, skin)))
        assertEquals(0f, mask.alphaAt(0.1f, 0.5f), 0f)
        assertEquals(0f, MaskRaster.coverage(mask), 0f)
        assertTrue(mask.hasSemantic)
    }

    @Test
    fun unboundSemantic_staysInertWhenInverted() {
        val radial = MaskComponent.Radial(0.5f, 0.5f, 0.3f, 0.3f)
        val cases = listOf(
            Mask(listOf(Mask.Component(BlendMode.ADD, skin)), invert = true),
            Mask(listOf(Mask.Component(BlendMode.ADD, skin, invert = true))),
            Mask(listOf(Mask.Component(BlendMode.ADD, radial), Mask.Component(BlendMode.SUBTRACT, skin))),
        )
        for (mask in cases) {
            assertEquals("$mask at centre", 0f, mask.alphaAt(0.5f, 0.5f), 0f)
            assertEquals("$mask coverage", 0f, MaskRaster.coverage(mask), 0f)
        }
        // Once bound, inversion behaves normally: inverted skin selects the background cell.
        val bound = cases[0].bindSemantic { BoundSemantic(it, field, FrameMap.IDENTITY) }
        assertEquals(1f, bound.alphaAt(0.6f, 0.25f), 1e-6f)
        assertEquals(0f, bound.alphaAt(0.1f, 0.25f), 1e-6f)
    }

    @Test
    fun coverage_keepsEndpointsAtEverySetting() {
        for (softness in listOf(0f, 0.5f, 1f)) for (expand in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val c = skin.copy(softness = softness, expand = expand)
            assertEquals("p=0 s=$softness e=$expand", 0f, c.coverage(0f), 0f)
            assertEquals("p=1 s=$softness e=$expand", 1f, c.coverage(1f), 0f)
        }
        // Max expand + max softness: a field with no skin (body plane missing entirely) must not leak a
        // faint global edit.
        val empty = SemanticField(2, 2, mapOf(SemanticClass.FACE_SKIN to FloatArray(4)))
        val bound = BoundSemantic(skin.copy(softness = 1f, expand = 1f), empty, FrameMap.IDENTITY)
        assertEquals(0f, bound.alphaAt(0.5f, 0.5f), 0f)
        assertEquals(0f, MaskRaster.coverage(Mask(listOf(Mask.Component(BlendMode.ADD, bound)))), 0f)
    }

    @Test
    fun boundSemantic_followsTheField() {
        val bound = BoundSemantic(skin, field, FrameMap.IDENTITY)
        assertEquals(1f, bound.alphaAt(0.1f, 0.25f), 1e-6f)  // face cell
        assertEquals(0f, bound.alphaAt(0.6f, 0.25f), 1e-6f)  // background cell
        assertEquals(1f, bound.alphaAt(0.95f, 0.95f), 1e-6f) // body cell
    }

    @Test
    fun classSelection_isRespected() {
        val faceOnly = MaskComponent.Semantic(setOf(SemanticClass.FACE_SKIN))
        val bound = BoundSemantic(faceOnly, field, FrameMap.IDENTITY)
        assertEquals(1f, bound.alphaAt(0.1f, 0.25f), 1e-6f)
        assertEquals("body skin not selected", 0f, bound.alphaAt(0.95f, 0.95f), 1e-6f)
    }

    @Test
    fun outsideThePhoto_isZero() {
        assertEquals(0f, field.sample(skin.classes, -0.01f, 0.5f), 0f)
        assertEquals(0f, field.sample(skin.classes, 0.5f, 1.01f), 0f)
        assertEquals(0f, field.sample(skin.classes, Float.NaN, 0.5f), 0f)
    }

    @Test
    fun cropMap_keepsTheMaskOnTheContent() {
        // Output frame = the right half of an 800x400 photo. Its left edge is the photo's centre.
        val frame = FrameMap.forCrop(800, 400, 400, 0, 400, 400)
        assertEquals(0.5f, frame.sourceX(0f), 1e-6f)
        assertEquals(1f, frame.sourceX(1f), 1e-6f)
        val bound = BoundSemantic(skin, field, frame)
        // Output (0.1, 0.25) is source (0.55, 0.25): background, although (0.1, 0.25) is face in the full frame.
        assertEquals(0f, bound.alphaAt(0.1f, 0.25f), 1e-6f)
        // Output (0.9, 0.9) is source (0.95, 0.9): body skin.
        assertEquals(1f, bound.alphaAt(0.9f, 0.9f), 1e-6f)
    }

    @Test
    fun composedMap_matchesTwoStepMapping() {
        val crop = FrameMap.forCrop(1000, 500, 100, 50, 600, 300)
        val roi = FrameMap(offsetX = 0.25f, offsetY = 0.5f, scaleX = 0.5f, scaleY = 0.25f)
        val both = crop.compose(roi)
        for (n in listOf(0f, 0.3f, 1f)) {
            assertEquals(crop.sourceX(roi.sourceX(n)), both.sourceX(n), 1e-6f)
            assertEquals(crop.sourceY(roi.sourceY(n)), both.sourceY(n), 1e-6f)
        }
    }

    @Test
    fun coverageCurve_isMonotonicAndCentred() {
        var prev = -1f
        for (i in 0..100) {
            val c = skin.coverage(i / 100f)
            assertTrue("monotonic at $i", c >= prev)
            prev = c
        }
        assertEquals(0.5f, skin.coverage(0.5f), 1e-6f)
        assertEquals(0f, skin.coverage(0f), 0f)
        assertEquals(1f, skin.coverage(1f), 0f)
        assertEquals(0f, skin.coverage(Float.NaN), 0f)
    }

    @Test
    fun expand_growsAndContractShrinksTheSelection() {
        val p = 0.4f
        val grown = skin.copy(expand = 0.5f).coverage(p)
        val base = skin.coverage(p)
        val shrunk = skin.copy(expand = -0.5f).coverage(p)
        assertTrue("expand selects more ($grown > $base)", grown > base)
        assertTrue("contract selects less ($shrunk < $base)", shrunk < base)
    }

    @Test
    fun softness_widensTheEdge() {
        val hard = skin.copy(softness = 0f)
        val soft = skin.copy(softness = 1f)
        assertEquals(0f, hard.coverage(0.45f), 1e-6f)
        assertTrue(soft.coverage(0.45f) > 0.3f)
    }

    @Test
    fun bindSemantic_onlyTouchesSemanticComponents() {
        val radial = MaskComponent.Radial(0.25f, 0.25f, 0.3f, 0.3f)
        val mask = Mask(
            listOf(
                Mask.Component(BlendMode.ADD, skin),
                Mask.Component(BlendMode.INTERSECT, radial),
            ),
        )
        val bound = mask.bindSemantic { BoundSemantic(it, field, FrameMap.IDENTITY) }
        assertTrue(bound.components[0].shape is BoundSemantic)
        assertSame(radial, bound.components[1].shape)
        // Skin INTERSECT radial: face inside the radial is selected, face outside it is not.
        assertTrue(bound.alphaAt(0.2f, 0.25f) > 0.9f)
        assertEquals(0f, bound.alphaAt(0.95f, 0.95f), 1e-6f)
    }

    @Test
    fun bindSemantic_nullLeavesItInert_andPlainMasksAreReturnedAsIs() {
        val mask = Mask(listOf(Mask.Component(BlendMode.ADD, skin)))
        assertEquals(mask, mask.bindSemantic { null })
        val plain = Mask(listOf(Mask.Component(BlendMode.ADD, MaskComponent.Radial(0.5f, 0.5f, 0.2f, 0.2f))))
        assertSame(plain, plain.bindSemantic { error("must not be called") })
        assertFalse(plain.hasSemantic)
    }

    @Test
    fun adjustmentListBinding_withoutFieldIsANoOp() {
        val list = listOf(LocalAdjustment(Mask(listOf(Mask.Component(BlendMode.ADD, skin))), TierADelta(exposureEv = 1f)))
        assertSame(list, list.bindSemantic(null, FrameMap.IDENTITY))
        val bound = list.bindSemantic(field, FrameMap.IDENTITY)
        assertTrue(bound[0].mask.components[0].shape is BoundSemantic)
        assertEquals(TierADelta(exposureEv = 1f), bound[0].delta)
    }

    @Test
    fun field_rejectsMismatchedPlanes() {
        assertThrows(IllegalArgumentException::class.java) {
            SemanticField(2, 2, mapOf(SemanticClass.FACE_SKIN to FloatArray(3)))
        }
        assertThrows(IllegalArgumentException::class.java) { SemanticField(0, 2, emptyMap()) }
    }

    @Test
    fun gestures_ignoreSemanticComponents() {
        assertEquals(MaskGesture.Handle.NONE, MaskGesture.pick(skin, 10f, 10f, 100, 100))
        assertSame(skin, MaskGesture.applyDrag(skin, MaskGesture.Handle.MOVE, 0.1f, 0.1f))
    }

    @Test
    fun peakIsTheHighestSummedProbability() {
        assertEquals(1f, field.peak(setOf(SemanticClass.FACE_SKIN)), 0f)
        assertEquals(1f, field.peak(setOf(SemanticClass.BODY_SKIN)), 0f)
        val faint = SemanticField(2, 1, mapOf(SemanticClass.FACE_SKIN to floatArrayOf(0.1f, 0.3f)))
        assertEquals(0.3f, faint.peak(setOf(SemanticClass.FACE_SKIN, SemanticClass.BODY_SKIN)), 1e-6f)
        assertEquals(0f, faint.peak(setOf(SemanticClass.BODY_SKIN)), 0f)
    }

    @Test
    fun fingerprintFollowsTheContent() {
        val same = SemanticField(4, 2, mapOf(
            SemanticClass.FACE_SKIN to floatArrayOf(1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f),
            SemanticClass.BODY_SKIN to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
        ))
        val other = SemanticField(4, 2, mapOf(
            SemanticClass.FACE_SKIN to floatArrayOf(1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f),
            SemanticClass.BODY_SKIN to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f),
        ))
        assertEquals(field.fingerprint(), same.fingerprint())
        assertFalse(field.fingerprint() == other.fingerprint())
    }
}
