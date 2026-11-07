/*
 * Spektrafilm for Android — hardware keyboard shortcut mapping tests. GPLv3.
 */
package com.spectrafilm.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorShortcutsTest {

    private fun map(key: Int, primary: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        editorShortcutFor(key, primary = primary, shift = shift, alt = alt)

    @Test
    fun ctrlOrMetaShortcuts() {
        assertEquals(EditorShortcut.UNDO, map(KeyEvent.KEYCODE_Z, primary = true))
        assertEquals(EditorShortcut.REDO, map(KeyEvent.KEYCODE_Z, primary = true, shift = true))
        assertEquals(EditorShortcut.REDO, map(KeyEvent.KEYCODE_Y, primary = true))
        assertEquals(EditorShortcut.OPEN, map(KeyEvent.KEYCODE_O, primary = true))
        assertEquals(EditorShortcut.EXPORT, map(KeyEvent.KEYCODE_E, primary = true))
        assertEquals(EditorShortcut.APPLY_PREVIOUS, map(KeyEvent.KEYCODE_V, primary = true, shift = true))
        assertEquals(EditorShortcut.PHOTO_INFO, map(KeyEvent.KEYCODE_I, primary = true))
        assertNull(map(KeyEvent.KEYCODE_I, primary = true, shift = true))
        assertNull(map(KeyEvent.KEYCODE_I))
    }

    @Test
    fun ctrlVAloneStaysTheSystemPaste() {
        assertNull(map(KeyEvent.KEYCODE_V, primary = true))
        assertNull(map(KeyEvent.KEYCODE_V))
        assertNull(map(KeyEvent.KEYCODE_V, shift = true))
    }

    @Test
    fun plainKeys() {
        assertEquals(EditorShortcut.CLOSE, map(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(EditorShortcut.TOGGLE_COMPARE, map(KeyEvent.KEYCODE_BACKSLASH))
    }

    @Test
    fun lettersWithoutCtrlAndAltChordsAreNotShortcuts() {
        // Plain letters must stay free for typing (preset names, date-back text).
        for (key in listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_E)) {
            assertNull(map(key))
            assertNull(map(key, shift = true))
        }
        assertNull(map(KeyEvent.KEYCODE_Z, primary = true, alt = true))
        assertNull(map(KeyEvent.KEYCODE_ESCAPE, alt = true))
        assertNull(map(KeyEvent.KEYCODE_BACKSLASH, shift = true)) // '|'
        assertNull(map(KeyEvent.KEYCODE_O, primary = true, shift = true))
        assertNull(map(KeyEvent.KEYCODE_A, primary = true))
    }

    @Test
    fun zoomKeys() {
        assertEquals(EditorShortcut.ZOOM_IN, map(KeyEvent.KEYCODE_EQUALS, primary = true))
        assertEquals(EditorShortcut.ZOOM_IN, map(KeyEvent.KEYCODE_EQUALS, primary = true, shift = true)) // Ctrl+'+'
        assertEquals(EditorShortcut.ZOOM_IN, map(KeyEvent.KEYCODE_NUMPAD_ADD, primary = true))
        assertEquals(EditorShortcut.ZOOM_OUT, map(KeyEvent.KEYCODE_MINUS, primary = true))
        assertEquals(EditorShortcut.ZOOM_OUT, map(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, primary = true))
        assertEquals(EditorShortcut.ZOOM_FIT, map(KeyEvent.KEYCODE_0, primary = true))
        assertNull(map(KeyEvent.KEYCODE_0))
        assertNull(map(KeyEvent.KEYCODE_MINUS))
    }

    @Test
    fun onlyUndoRedoAndStepZoomAutoRepeat() {
        val repeating = setOf(EditorShortcut.UNDO, EditorShortcut.REDO, EditorShortcut.ZOOM_IN, EditorShortcut.ZOOM_OUT)
        for (s in repeating) assertTrue("$s should auto-repeat", s.repeats)
        for (s in EditorShortcut.entries - repeating) {
            assertFalse("$s must not auto-repeat", s.repeats)
        }
    }

    @Test
    fun theSystemShortcutListMatchesWhatTheKeysDo() {
        for (listing in editorShortcutListings) {
            val primary = listing.metaState and KeyEvent.META_CTRL_ON != 0
            val shift = listing.metaState and KeyEvent.META_SHIFT_ON != 0
            assertEquals(listing.toString(), listing.shortcut, map(listing.keyCode, primary = primary, shift = shift))
        }
        // Every shortcut the editor handles is discoverable.
        assertEquals(EditorShortcut.entries.toSet(), editorShortcutListings.map { it.shortcut }.toSet())
    }
}
