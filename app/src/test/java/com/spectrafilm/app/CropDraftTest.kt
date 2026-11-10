package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Crop tab's draft: what leaving the tab writes to the recipe, and when it writes nothing.
 * An untouched draft must stay untouched, or every visit to Crop would push an undo step.
 */
class CropDraftTest {

    private fun draft(
        crop: Boolean = false,
        center: Pair<Float, Float> = 0.5f to 0.5f,
        size: Pair<Float, Float> = 0.1f to 0.1f,
        straighten: Float = 0f,
    ) = CropDraft(1200f, 800f, crop, center, size, straighten)

    @Test
    fun seededDraftIsUntouchedAndCommitsTheSameCrop() {
        val d = draft(crop = true, center = 0.4f to 0.55f, size = 0.5f to 0.3f)
        assertFalse(d.touched)
        val c = d.commit()
        assertTrue(c.crop)
        assertEquals(0.4f, c.center.first, 1e-5f)
        assertEquals(0.55f, c.center.second, 1e-5f)
        assertEquals(0.5f, c.size.first, 1e-5f)
        assertEquals(0.3f, c.size.second, 1e-5f)
    }

    @Test
    fun fullFrameTurnsTheCropOff() {
        val d = draft()
        assertTrue(d.isReset)
        assertFalse(d.commit().crop)
    }

    @Test
    fun squareRatioIsATouchAndCommitsASquareBox() {
        val d = draft()
        d.pickAspect(CropAspect.SQUARE)
        assertTrue(d.touched)
        val c = d.commit()
        assertTrue(c.crop)
        // Size is in fractions of the long side, so a square box has equal components.
        assertEquals(c.size.first, c.size.second, 1e-5f)
        assertEquals(800f / 1200f, c.size.first, 1e-5f)
    }

    @Test
    fun freeRatioOnAFullFrameChangesNothing() {
        val d = draft()
        d.pickAspect(CropAspect.FREE)
        assertFalse(d.touched)
    }

    @Test
    fun straightenToTheSameAngleIsNotATouch() {
        val d = draft(straighten = 3f)
        d.straightenTo(3f)
        assertFalse(d.touched)
        d.straightenTo(4.5f)
        assertTrue(d.touched)
        assertEquals(4.5f, d.commit().straighten, 0f)
    }

    @Test
    fun resetLevelsAndUncropsAndIsANoOpWhenAlreadyReset() {
        val clean = draft()
        clean.reset()
        assertFalse("reset of a full, level frame must not record an edit", clean.touched)

        val d = draft(crop = true, center = 0.5f to 0.5f, size = 0.4f to 0.4f, straighten = -2f)
        d.reset()
        assertTrue(d.touched)
        assertTrue(d.isReset)
        val c = d.commit()
        assertFalse(c.crop)
        assertEquals(0f, c.straighten, 0f)
    }

    @Test
    fun dragMovesTheBoxInsideTheImage() {
        val d = draft(crop = true, center = 0.5f to 0.5f, size = 0.5f to 0.25f)
        d.drag(Handle.MOVE, 1f, 0f)
        assertTrue(d.touched)
        assertEquals(1f, d.rect.right, 1e-5f)
    }
}
