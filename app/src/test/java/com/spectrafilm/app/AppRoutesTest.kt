package com.spectrafilm.app

import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRoutesTest {
    @Test fun everyScreenHasExactlyOneRoute() {
        val routes = listOf(
            EditorRoute,
            SettingsRoute,
            DiagnosticsRoute,
            AboutRoute,
            HowToRoute,
            LibraryRoute,
            FilmCurvesRoute("kodak_portra_400", "Portra 400"),
            PrintCurvesRoute("kodak_portra_endura", "Endura"),
        )
        assertEquals(Screen.entries.toSet(), routes.map { it.screen }.toSet())
        assertEquals(Screen.entries.size, routes.size)
    }

    /** rememberNavBackStack saves routes with their generated serializers. */
    @OptIn(ExperimentalSerializationApi::class)
    @Test fun routesWithArgumentsAreSerializable() {
        val film = FilmCurvesRoute.serializer().descriptor
        assertEquals(listOf("profileId", "displayName"), (0 until film.elementsCount).map(film::getElementName))
        val print = PrintCurvesRoute.serializer().descriptor
        assertEquals(listOf("profileId", "displayName"), (0 until print.elementsCount).map(print::getElementName))
        EditorRoute.serializer()
    }
}
