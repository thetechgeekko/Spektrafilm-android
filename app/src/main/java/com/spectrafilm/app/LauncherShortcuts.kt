/*
 * Spektrafilm for Android — static launcher shortcuts (#467). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * res/xml/shortcuts.xml declares two shortcuts on the launcher icon. Each starts MainActivity
 * with one of the actions below and no data, so a shortcut only ever chooses a destination:
 * "Open photo" opens the editor's photo picker, "Library" opens the Library.
 */
package com.spectrafilm.app

internal enum class LauncherShortcut(val action: String) {
    OPEN_PHOTO("com.spectrafilm.app.action.SHORTCUT_OPEN_PHOTO"),
    LIBRARY("com.spectrafilm.app.action.SHORTCUT_LIBRARY"),
    ;

    companion object {
        /** The shortcut an intent action asks for, or null for every other action. */
        fun fromAction(action: String?): LauncherShortcut? = entries.firstOrNull { it.action == action }
    }
}
