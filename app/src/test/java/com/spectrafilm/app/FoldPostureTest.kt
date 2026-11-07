/*
 * Spektrafilm for Android — unit tests for the foldable-posture layout rules. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldPostureTest {

    // An unfolded book-style foldable in landscape, half open with the hinge horizontal.
    private fun horizontalHinge(top: Float, halfOpened: Boolean = true) =
        HingeGeometry(horizontal = true, halfOpened = halfOpened, separating = halfOpened,
            left = 0f, top = top, right = 900f, bottom = top)

    private fun verticalHinge(x: Float, halfOpened: Boolean = true, separating: Boolean = halfOpened) =
        HingeGeometry(horizontal = false, halfOpened = halfOpened, separating = separating,
            left = x, top = 0f, right = x, bottom = 700f)

    @Test
    fun noHinge_isFlat() {
        assertEquals(FoldPosture.Flat, foldPosture(null, 412f, 800f))
    }

    @Test
    fun halfOpenHorizontalHinge_isTabletop() {
        assertEquals(FoldPosture.Tabletop(340f, 340f), foldPosture(horizontalHinge(340f), 900f, 700f))
    }

    @Test
    fun flatHorizontalFold_isJustATallScreen() {
        assertEquals(FoldPosture.Flat, foldPosture(horizontalHinge(340f, halfOpened = false), 900f, 700f))
    }

    @Test
    fun hingeOutsideTheEditorBox_isIgnored() {
        // The editor box starts under the app bar: a hinge above it does not split it.
        assertEquals(FoldPosture.Flat, foldPosture(horizontalHinge(-20f), 900f, 700f))
        assertEquals(FoldPosture.Flat, foldPosture(verticalHinge(950f), 900f, 700f))
    }

    @Test
    fun verticalHinge_isBook_whenHalfOpenOrSeparating() {
        assertEquals(FoldPosture.Book(420f, 420f), foldPosture(verticalHinge(420f), 840f, 700f))
        assertEquals(
            FoldPosture.Book(420f, 444f),
            foldPosture(
                HingeGeometry(false, halfOpened = false, separating = true, 420f, 0f, 444f, 700f),
                864f, 700f,
            ),
        )
        assertEquals(FoldPosture.Flat, foldPosture(verticalHinge(420f, halfOpened = false), 840f, 700f))
    }

    @Test
    fun tabletop_docksTheToolsBelowTheHinge_evenWhenWide() {
        assertEquals(EditorPaneMode.BOTTOM_SHEET, editorPaneMode(900f, 700f, FoldPosture.Tabletop(340f, 340f)))
        assertEquals(400f, tabletopPanelHeightDp(FoldPosture.Tabletop(300f, 300f), 700f)!!, 0f)
        assertNull(tabletopPanelHeightDp(FoldPosture.Flat, 700f))
        // A 24dp physical gap: photo ends at its top edge, panel starts at its bottom edge.
        val gapped = FoldPosture.Tabletop(300f, 324f)
        assertEquals(300f, tabletopPhotoHeightDp(gapped)!!, 0f)
        assertEquals(376f, tabletopPanelHeightDp(gapped, 700f)!!, 0f)
        assertNull(tabletopPhotoHeightDp(FoldPosture.Flat))
    }

    @Test
    fun book_givesTheToolsExactlyOneScreen() {
        val book = FoldPosture.Book(420f, 420f)
        // A medium-width unfolded foldable would be single-pane flat; book posture splits it.
        assertEquals(EditorPaneMode.SIDE_PANE, editorPaneMode(700f, 800f, FoldPosture.Book(350f, 350f)))
        assertEquals(420f, sidePaneWidthDp(840f, book, rtl = false), 0f)
        // A 24dp physical gap: the end screen is what is right of it.
        assertEquals(396f, sidePaneWidthDp(840f, FoldPosture.Book(420f, 444f), rtl = false), 0f)
        // Right-to-left puts the pane on the left screen.
        assertEquals(420f, sidePaneWidthDp(840f, FoldPosture.Book(420f, 444f), rtl = true), 0f)
    }

    @Test
    fun book_leavesThePhysicalGapEmpty() {
        // 864dp across two screens with a 24dp gap: pane 420 + gap 24 + photo 420.
        val gapped = FoldPosture.Book(420f, 444f)
        assertEquals(24f, bookHingeGapDp(gapped, 864f, rtl = false), 0f)
        assertEquals(864f - 24f - sidePaneWidthDp(864f, gapped, rtl = false), 420f, 0f)
        assertEquals(0f, bookHingeGapDp(FoldPosture.Book(420f, 420f), 840f, rtl = false), 0f)
        assertEquals(0f, bookHingeGapDp(FoldPosture.Flat, 840f, rtl = false), 0f)
        // Fallback width is not hinge-aligned, so no gap is reserved.
        assertEquals(0f, bookHingeGapDp(FoldPosture.Book(700f, 724f), 900f, rtl = false), 0f)
    }

    @Test
    fun book_isHingeAligned_onlyWhenTheToolsScreenFits() {
        assertTrue(isBookHingeAligned(FoldPosture.Book(420f, 420f), 840f, rtl = false))
        assertFalse(isBookHingeAligned(FoldPosture.Book(700f, 700f), 900f, rtl = false))
        assertTrue(isBookHingeAligned(FoldPosture.Book(700f, 700f), 900f, rtl = true))
        assertFalse(isBookHingeAligned(FoldPosture.Flat, 900f, rtl = false))
    }

    @Test
    fun book_fallsBackWhenTheEndScreenIsTooNarrow() {
        assertEquals(sidePaneWidthDp(900f), sidePaneWidthDp(900f, FoldPosture.Book(700f, 700f), rtl = false), 0f)
    }

    @Test
    fun flat_keepsTheSizeClassRules() {
        assertEquals(editorPaneMode(412f, 915f), editorPaneMode(412f, 915f, FoldPosture.Flat))
        assertEquals(sidePaneWidthDp(1000f), sidePaneWidthDp(1000f, FoldPosture.Flat, rtl = false), 0f)
    }
}
