package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipping overlay's per-pixel classification, and the viewer state that shows it.
 *
 * The thresholds must be the histogram's clipped bins (exactly 0 and 255): a pixel at 254 is
 * printable detail, and an overlay that lit there would disagree with the wedges beside it.
 */
class ClippingOverlayTest {

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun classify(vararg px: Int): IntArray {
        val out = IntArray(px.size)
        classifyClipping(px, out)
        return out
    }

    @Test
    fun midtonesAndNearEndsAreClear() {
        val out = classify(rgb(128, 128, 128), rgb(254, 254, 254), rgb(1, 1, 1))
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun allChannelsBlownIsTheStrongHighlightColour() {
        assertEquals(CLIP_HIGH_ALL, classify(rgb(255, 255, 255))[0])
    }

    @Test
    fun oneChannelBlownIsThePartialHighlightColour() {
        assertEquals(CLIP_HIGH_SOME, classify(rgb(255, 200, 120))[0])
    }

    @Test
    fun allChannelsCrushedIsTheStrongShadowColour() {
        assertEquals(CLIP_LOW_ALL, classify(rgb(0, 0, 0))[0])
    }

    @Test
    fun oneChannelCrushedIsThePartialShadowColour() {
        assertEquals(CLIP_LOW_SOME, classify(rgb(0, 40, 80))[0])
    }

    @Test
    fun aBlownChannelOutranksACrushedOne() {
        // Saturated pure red: R at the top, G and B at zero. The blown channel wins.
        assertEquals(CLIP_HIGH_SOME, classify(rgb(255, 0, 0))[0])
    }

    @Test
    fun alphaIsIgnored() {
        val translucentWhite = (0x40 shl 24) or 0xFFFFFF
        assertEquals(CLIP_HIGH_ALL, classify(translucentWhite)[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun aShortOutputIsRejected() {
        classifyClipping(IntArray(4), IntArray(3))
    }

    @Test
    fun latchedToggleAndFingerPeekBothShowClipping() {
        var s = EditorViewerUiState()
        assertFalse(s.showClipping)

        s = EditorViewerViewModel.reduce(s, EditorViewerAction.SetClipPeek(true))
        assertTrue(s.showClipping)
        s = EditorViewerViewModel.reduce(s, EditorViewerAction.SetClipPeek(false))
        assertFalse(s.showClipping)

        s = EditorViewerViewModel.reduce(s, EditorViewerAction.ToggleClipWarnings)
        assertTrue(s.showClipping)
        // Lifting a peek finger must not turn off the latched toggle.
        s = EditorViewerViewModel.reduce(s, EditorViewerAction.SetClipPeek(true))
        s = EditorViewerViewModel.reduce(s, EditorViewerAction.SetClipPeek(false))
        assertTrue(s.showClipping)
        s = EditorViewerViewModel.reduce(s, EditorViewerAction.ToggleClipWarnings)
        assertFalse(s.showClipping)
    }
}
