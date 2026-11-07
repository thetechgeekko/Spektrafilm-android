/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Hardware keyboard shortcuts for the editor (tablets with a keyboard cover, Samsung DeX,
 * ChromeOS, Android desktop windows). Only the key -> shortcut mapping lives here so it is
 * testable on the plain JVM; MainActivity dispatches the result to the editor's EXISTING
 * actions (undo/redo, the Open picker, requestExport(), the compare toggle, and Back), so a
 * shortcut never has a code path of its own that the touch UI does not also use.
 */
package com.spectrafilm.app

import android.view.KeyEvent

internal enum class EditorShortcut {
    UNDO,
    REDO,
    OPEN,
    EXPORT,
    TOGGLE_COMPARE,

    /** Esc: the same as system Back, but only while something is open to close. */
    CLOSE,
    ;

    /** Undo/redo may auto-repeat while held; everything else fires once per press. */
    val repeats: Boolean get() = this == UNDO || this == REDO
}

/**
 * Map a key press to an editor shortcut, or null. [primary] is Ctrl, or Meta (the Cmd /
 * Search / Windows key), so Mac-layout and ChromeOS keyboards work the same way.
 */
internal fun editorShortcutFor(
    keyCode: Int,
    primary: Boolean,
    shift: Boolean,
    alt: Boolean,
): EditorShortcut? {
    if (alt) return null
    if (primary) {
        return when (keyCode) {
            KeyEvent.KEYCODE_Z -> if (shift) EditorShortcut.REDO else EditorShortcut.UNDO
            KeyEvent.KEYCODE_Y -> if (shift) null else EditorShortcut.REDO
            KeyEvent.KEYCODE_O -> if (shift) null else EditorShortcut.OPEN
            KeyEvent.KEYCODE_E -> if (shift) null else EditorShortcut.EXPORT
            else -> null
        }
    }
    if (shift) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> EditorShortcut.CLOSE
        // Lightroom's before/after key.
        KeyEvent.KEYCODE_BACKSLASH -> EditorShortcut.TOGGLE_COMPARE
        else -> null
    }
}

internal fun editorShortcutFor(event: KeyEvent): EditorShortcut? {
    val shortcut = editorShortcutFor(
        keyCode = event.keyCode,
        primary = event.isCtrlPressed || event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    ) ?: return null
    return if (event.repeatCount > 0 && !shortcut.repeats) null else shortcut
}
