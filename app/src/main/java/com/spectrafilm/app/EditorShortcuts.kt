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

import android.content.res.Resources
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo

internal enum class EditorShortcut {
    UNDO,
    REDO,
    OPEN,
    EXPORT,
    TOGGLE_COMPARE,
    ZOOM_IN,
    ZOOM_OUT,
    ZOOM_FIT,

    /** Ctrl+Shift+V: apply the previous photo's edit, the Presets panel's "Apply previous edit". */
    APPLY_PREVIOUS,

    /** Ctrl+I: the Photo info sheet, the overflow menu's first item. */
    PHOTO_INFO,

    /** Esc: the same as system Back, but only while something is open to close. */
    CLOSE,
    ;

    /** Undo/redo and zoom in/out may auto-repeat while held; the rest fire once per press. */
    val repeats: Boolean get() = this == UNDO || this == REDO || this == ZOOM_IN || this == ZOOM_OUT
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
            KeyEvent.KEYCODE_I -> if (shift) null else EditorShortcut.PHOTO_INFO
            // Ctrl+V alone is left free: it is the system paste chord inside text fields.
            KeyEvent.KEYCODE_V -> if (shift) EditorShortcut.APPLY_PREVIOUS else null
            // '+' is Shift+'=' on most layouts, so Ctrl+Shift+= is zoom in too.
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> EditorShortcut.ZOOM_IN
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> if (shift) null else EditorShortcut.ZOOM_OUT
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> if (shift) null else EditorShortcut.ZOOM_FIT
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

/**
 * What the system keyboard-shortcuts list (Meta+/ or Search+/) shows for the editor, so the
 * shortcuts are discoverable the platform way rather than through an in-app cheat sheet.
 * Must stay in step with [editorShortcutFor]; EditorShortcutsTest checks every listed chord
 * maps to the shortcut it is labelled with.
 */
internal data class ShortcutListing(val labelRes: Int, val keyCode: Int, val metaState: Int, val shortcut: EditorShortcut)

internal val editorShortcutListings: List<ShortcutListing> = listOf(
    ShortcutListing(R.string.shortcut_undo, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON, EditorShortcut.UNDO),
    ShortcutListing(R.string.shortcut_redo, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, EditorShortcut.REDO),
    ShortcutListing(R.string.shortcut_open, KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON, EditorShortcut.OPEN),
    ShortcutListing(R.string.shortcut_export, KeyEvent.KEYCODE_E, KeyEvent.META_CTRL_ON, EditorShortcut.EXPORT),
    ShortcutListing(R.string.shortcut_apply_previous, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, EditorShortcut.APPLY_PREVIOUS),
    ShortcutListing(R.string.shortcut_photo_info, KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, EditorShortcut.PHOTO_INFO),
    ShortcutListing(R.string.shortcut_compare, KeyEvent.KEYCODE_BACKSLASH, 0, EditorShortcut.TOGGLE_COMPARE),
    ShortcutListing(R.string.shortcut_zoom_in, KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON, EditorShortcut.ZOOM_IN),
    ShortcutListing(R.string.shortcut_zoom_out, KeyEvent.KEYCODE_MINUS, KeyEvent.META_CTRL_ON, EditorShortcut.ZOOM_OUT),
    ShortcutListing(R.string.shortcut_zoom_fit, KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON, EditorShortcut.ZOOM_FIT),
    ShortcutListing(R.string.shortcut_close, KeyEvent.KEYCODE_ESCAPE, 0, EditorShortcut.CLOSE),
)

internal fun editorKeyboardShortcutGroup(res: Resources): KeyboardShortcutGroup =
    KeyboardShortcutGroup(
        res.getString(R.string.shortcut_group_editor),
        editorShortcutListings.map { KeyboardShortcutInfo(res.getString(it.labelRes), it.keyCode, it.metaState) },
    )
