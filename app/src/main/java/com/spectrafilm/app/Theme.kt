/*
 * Spektrafilm for Android — app theme. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * One place decides colour for every screen. Before this the whole theme was a single inline call
 * in MainActivity — `MaterialTheme(colorScheme = if (dark) darkColorScheme() else
 * lightColorScheme())` — so the app shipped the Material 3 BASELINE palette: the purple every
 * unstyled Compose app has, on a device whose user has picked a wallpaper the system already
 * derives a palette from.
 *
 * This adds dynamic colour (Material You, API 31+) and gives the fallback schemes a name, so
 * choosing a brand identity later is a one-line change in one file.
 *
 * ## Why this is not Material 3 Expressive
 *
 * Expressive is the current Material 3 system, and its entry points — `MaterialExpressiveTheme`,
 * `MotionScheme.expressive()` — are the ones worth having, because every M3 component reads its
 * spring specs from the motion scheme and switching it changes how the whole app feels without
 * touching a single call site.
 *
 * They are **not usable here yet**. In `androidx.compose.material3:material3:1.4.0` — the latest
 * STABLE release — `MaterialExpressiveTheme`, `MotionScheme` and `ExperimentalMaterial3ExpressiveApi`
 * are all declared `internal`; referencing them fails to compile with "it is internal in file".
 * They become public in 1.5.0, which is at `1.5.0-alpha28` and has no beta, let alone a stable.
 *
 * This app's release workflow verifies six Gradle dependency locks and two SPDX documents and
 * signs in a protected environment. An alpha UI toolkit does not belong in that pipeline. When
 * material3 1.5.0 reaches stable, the change here is small and local: wrap with
 * `MaterialExpressiveTheme`, pass `motionScheme = MotionScheme.expressive()`, and swap the two
 * fallback schemes for `expressiveLightColorScheme()` / `expressiveDarkColorScheme()`.
 */
package com.spectrafilm.app

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** True when the platform can derive a palette from the user's wallpaper (Material You, API 31+). */
internal val dynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The fallback schemes, used when dynamic colour is unavailable or switched off. These are the
 * Material 3 baseline schemes; they are named rather than called inline so a brand identity is a
 * one-line change here and nowhere else.
 */
private fun spektraLightScheme(): ColorScheme = lightColorScheme()

private fun spektraDarkScheme(): ColorScheme = darkColorScheme()

/**
 * Wrap the whole app. [dark] comes from the user's [ThemeMode] resolved against the system setting;
 * [dynamicColor] is the user's Material You preference and is ignored below API 31.
 */
@Composable
internal fun SpektraTheme(
    dark: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        // Capability is checked separately from the preference, so a device that cannot do
        // Material You falls back instead of throwing, and the stored preference survives.
        dynamicColor && dynamicColorSupported ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> spektraDarkScheme()
        else -> spektraLightScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
