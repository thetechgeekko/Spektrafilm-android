/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Navigation 3 routes (#274). Each destination is a NavKey that carries its own arguments,
 * so the back stack alone says what is on screen and survives rotation and process death
 * through rememberNavBackStack. The editor is always the root entry and is never popped.
 */
package com.spectrafilm.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable internal data object EditorRoute : NavKey

@Serializable internal data object SettingsRoute : NavKey

@Serializable internal data object DiagnosticsRoute : NavKey

@Serializable internal data object AboutRoute : NavKey

@Serializable internal data object HowToRoute : NavKey

@Serializable internal data object LibraryRoute : NavKey

@Serializable internal data class FilmCurvesRoute(val profileId: String, val displayName: String) : NavKey

@Serializable internal data class PrintCurvesRoute(val profileId: String, val displayName: String) : NavKey

/** The [Screen] name a route publishes to the test probe. */
internal val NavKey.screen: Screen
    get() = when (this) {
        EditorRoute -> Screen.EDITOR
        SettingsRoute -> Screen.SETTINGS
        DiagnosticsRoute -> Screen.DIAGNOSTICS
        AboutRoute -> Screen.ABOUT
        HowToRoute -> Screen.HOWTO
        LibraryRoute -> Screen.LIBRARY
        is FilmCurvesRoute -> Screen.CURVES_FILM
        is PrintCurvesRoute -> Screen.CURVES_PRINT
        else -> error("unknown route $this")
    }
