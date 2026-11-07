/*
 * Spektrafilm for Android — unit tests for the crop aspect-lock geometry. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * constrainToAspect re-fits a crop rect to a locked aspect while keeping the corner
 * OPPOSITE the dragged handle fixed. Pure geometry (compose-ui Rect), so it runs on the
 * plain JVM. Guards the fix for "TL/TR corner drags move the wrong edge".
 */
package com.spectrafilm.app

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropConstrainTest {

    // A square image, so the normalized aspect equals the pixel aspect.
    private val imgW = 100f
    private val imgH = 100f

    @Test
    fun brAnchor_keepsTopLeftFixed() {
        val r = Rect(0.2f, 0.2f, 0.6f, 0.9f)
        val out = constrainToAspect(r, aspect = 1f, imgW, imgH, anchor = Handle.BR)
        assertEquals("left pivot fixed", 0.2f, out.left, 1e-4f)
        assertEquals("top pivot fixed", 0.2f, out.top, 1e-4f)
        assertEquals("aspect 1 on a square image => square box",
            out.right - out.left, out.bottom - out.top, 1e-4f)
    }

    @Test
    fun tlAnchor_keepsBottomRightFixed() {
        // The regression: dragging the TOP-LEFT handle must pivot on the BOTTOM-RIGHT corner.
        val r = Rect(0.2f, 0.2f, 0.6f, 0.9f)
        val out = constrainToAspect(r, aspect = 1f, imgW, imgH, anchor = Handle.TL)
        assertEquals("right edge stays fixed", 0.6f, out.right, 1e-4f)
        assertEquals("bottom edge stays fixed", 0.9f, out.bottom, 1e-4f)
        assertEquals(out.right - out.left, out.bottom - out.top, 1e-4f)
    }

    @Test
    fun trAnchor_keepsBottomLeftFixed() {
        val r = Rect(0.2f, 0.3f, 0.7f, 0.9f)
        val out = constrainToAspect(r, aspect = 1f, imgW, imgH, anchor = Handle.TR)
        assertEquals("left edge stays fixed", 0.2f, out.left, 1e-4f)
        assertEquals("bottom edge stays fixed", 0.9f, out.bottom, 1e-4f)
    }

    @Test
    fun blAnchor_keepsTopRightFixed() {
        val r = Rect(0.2f, 0.2f, 0.7f, 0.7f)
        val out = constrainToAspect(r, aspect = 1f, imgW, imgH, anchor = Handle.BL)
        assertEquals("right edge stays fixed", 0.7f, out.right, 1e-4f)
        assertEquals("top edge stays fixed", 0.2f, out.top, 1e-4f)
    }

    @Test
    fun resultStaysInBounds() {
        val out = constrainToAspect(Rect(0.0f, 0.0f, 1.0f, 0.3f), aspect = 1f, imgW, imgH, anchor = Handle.BR)
        assertTrue(out.left >= 0f && out.top >= 0f && out.right <= 1f + 1e-4f && out.bottom <= 1f + 1e-4f)
    }

    @Test
    fun portrait2to3_onLandscapePhoto_fitsInsideTheFrame() {
        // A full-frame crop on a 3:2 landscape photo, locked to portrait 2:3: the box is far
        // too wide for the height available, so it must shrink its width, not overflow.
        val out = constrainToAspect(
            Rect(0f, 0f, 1f, 1f), aspect = 2f / 3f, imgW = 300f, imgH = 200f, anchor = Handle.BR,
        )
        val pixelAspect = ((out.right - out.left) * 300f) / ((out.bottom - out.top) * 200f)
        assertEquals("2:3 portrait in pixels", 2f / 3f, pixelAspect, 1e-3f)
        assertTrue("inside the image", out.left >= 0f && out.top >= 0f && out.right <= 1f && out.bottom <= 1f)
    }

    @Test
    fun ratioChip_givesLargestCentredBox() {
        // 3:2 landscape photo; a small off-centre box; pick 1:1.
        val w = 300f; val h = 200f
        val out = largestCenteredAspect(Rect(0.6f, 0.1f, 0.8f, 0.3f), 1f, w, h)
        val pxW = (out.right - out.left) * w
        val pxH = (out.bottom - out.top) * h
        assertEquals("square in pixels", pxW, pxH, 1e-3f)
        assertEquals("full photo height, the largest square", 1f, out.bottom - out.top, 1e-4f)
        assertTrue("inside the image", out.left >= 0f && out.right <= 1f + 1e-5f)
        // Centre x 0.7 asks for [0.367, 1.033]; it shifts left just enough to fit.
        assertEquals(1f, out.right, 1e-4f)
    }

    @Test
    fun ratioChip_doesNotShrinkFromThePreviousBox() {
        val w = 300f; val h = 200f
        val portrait = largestCenteredAspect(Rect(0f, 0f, 1f, 1f), 3f / 4f, w, h)
        val square = largestCenteredAspect(portrait, 1f, w, h)
        assertEquals("1:1 after 3:4 is still the largest square", 1f, square.bottom - square.top, 1e-4f)
        assertEquals("centred", 0.5f, (square.left + square.right) / 2f, 1e-4f)
    }
}
