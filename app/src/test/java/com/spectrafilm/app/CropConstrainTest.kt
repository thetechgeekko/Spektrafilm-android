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
}
