/*
 * Spektrafilm for Android — brand fallback colour scheme contrast (#468). GPLv3.
 */
package com.spectrafilm.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The film-amber fallback schemes are generated, not hand-picked; this pins that every text /
 * icon role stays readable on the surface it is drawn on (WCAG AA, 4.5:1), so a hand edit of a
 * single role that breaks the tonal relationship fails here instead of on a user's screen.
 */
class BrandSchemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun pairs(s: ColorScheme) = listOf(
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondary/secondary" to (s.onSecondary to s.secondary),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onError/error" to (s.onError to s.error),
        "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
        "onBackground/background" to (s.onBackground to s.background),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
        "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
        "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
        "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
        "primary/surface" to (s.primary to s.surface),
        "primary/surfaceContainerHighest" to (s.primary to s.surfaceContainerHighest),
    )

    private fun assertAa(name: String, scheme: ColorScheme) {
        val failures = pairs(scheme).mapNotNull { (label, p) ->
            val c = contrast(p.first, p.second)
            if (c < 4.5) "$label = ${"%.2f".format(c)}" else null
        }
        assertTrue("$name scheme below 4.5:1: $failures", failures.isEmpty())
    }

    @Test
    fun lightBrandSchemeMeetsAa() = assertAa("light", spektraLightScheme())

    @Test
    fun darkBrandSchemeMeetsAa() = assertAa("dark", spektraDarkScheme())

    /** Chrome beside a colour-critical photo stays grey: amber is for accents only. */
    @Test
    fun brandSurfacesAreNeutralGrey() {
        for ((name, s) in listOf("light" to spektraLightScheme(), "dark" to spektraDarkScheme())) {
            val surfaces = listOf(
                s.background, s.surface, s.surfaceVariant, s.surfaceDim, s.surfaceBright,
                s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest, s.outline, s.outlineVariant,
            )
            for (c in surfaces) {
                assertTrue("$name surface $c is tinted", c.red == c.green && c.green == c.blue)
            }
        }
    }

    @Test
    fun brandSchemesAreNotTheMaterialBaseline() {
        // The whole point of #468: the fallback is not Material's default purple (#6750A4).
        assertTrue(spektraLightScheme().primary != Color(0xFF6750A4))
        assertTrue(spektraDarkScheme().primary != Color(0xFFD0BCFF))
    }
}
