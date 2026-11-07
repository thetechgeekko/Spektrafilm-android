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
    fun onlyUndoAndRedoAutoRepeat() {
        assertTrue(EditorShortcut.UNDO.repeats)
        assertTrue(EditorShortcut.REDO.repeats)
        for (s in EditorShortcut.entries - setOf(EditorShortcut.UNDO, EditorShortcut.REDO)) {
            assertFalse("$s must not auto-repeat", s.repeats)
        }
    }
}
