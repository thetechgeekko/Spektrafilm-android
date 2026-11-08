/*
 * Spektrafilm for Android — unit tests for the editor window-size layout rules. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorLayoutTest {

    @Test
    fun phonePortrait_usesBottomSheet() {
        assertEquals(EditorPaneMode.BOTTOM_SHEET, editorPaneMode(412f, 915f))
        assertEquals(EditorPaneMode.BOTTOM_SHEET, editorPaneMode(320f, 640f))
    }

    @Test
    fun phoneLandscape_usesSidePane() {
        assertEquals(EditorPaneMode.SIDE_PANE, editorPaneMode(915f, 412f))
        assertEquals(EditorPaneMode.SIDE_PANE, editorPaneMode(640f, 360f))
    }

    @Test
    fun mediumPortrait_keepsBottomSheet() {
        // Tablet portrait / unfolded foldable: M3 keeps medium windows single-pane.
        assertEquals(EditorPaneMode.BOTTOM_SHEET, editorPaneMode(700f, 1000f))
        assertEquals(EditorPaneMode.BOTTOM_SHEET, editorPaneMode(800f, 600f))
    }

    @Test
    fun expandedAndLarger_useSidePane() {
        assertEquals(EditorPaneMode.SIDE_PANE, editorPaneMode(840f, 1200f))
        assertEquals(EditorPaneMode.SIDE_PANE, editorPaneMode(1280f, 800f))
    }

    @Test
    fun paneWidth_followsM3FixedPane() {
        assertEquals(360f, sidePaneWidthDp(840f), 0f)
        assertEquals(360f, sidePaneWidthDp(1199f), 0f)
        assertEquals(412f, sidePaneWidthDp(1200f), 0f)
    }

    @Test
    fun paneWidth_landscapePhone_isAThirdClamped() {
        assertEquals(830f * 0.34f, sidePaneWidthDp(830f), 0.01f)
        assertEquals(280f, sidePaneWidthDp(800f), 0f) // 272 -> min
        assertEquals(280f, sidePaneWidthDp(640f), 0f)
        // A 915dp-wide landscape phone is already an expanded window: fixed 360dp pane.
        assertEquals(360f, sidePaneWidthDp(915f), 0f)
    }

    @Test
    fun breakpoints_comeFromWindowSizeClass_atTheM3Edges() {
        // #457: the edges are WindowManager's, and they land where the hand-rolled numbers did.
        assertEquals(EditorPaneMode.BOTTOM_SHEET, editorPaneMode(839f, 1000f))
        assertEquals(EditorPaneMode.SIDE_PANE, editorPaneMode(840f, 1000f))
        assertEquals(EditorPaneMode.SIDE_PANE, editorPaneMode(700f, 479f)) // compact height, landscape
        assertEquals(EditorPaneMode.BOTTOM_SHEET, editorPaneMode(700f, 480f)) // medium height
        assertEquals(360f, sidePaneWidthDp(1199.5f), 0f)
        assertEquals(false, settingsTwoPane(839f))
        assertEquals(true, settingsTwoPane(840f))
        // The Library's open album goes list-detail at the same edge as Settings.
        assertEquals(false, listDetailTwoPane(839f))
        assertEquals(true, listDetailTwoPane(840f))
    }
}
