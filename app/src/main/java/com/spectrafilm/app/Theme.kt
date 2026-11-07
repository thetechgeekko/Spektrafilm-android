/*
 * Spektrafilm for Android — app theme (Material 3 Expressive). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * One place decides colour, motion and shape for every screen. Before this the whole theme was a
 * single inline call in MainActivity, so the app shipped the Material 3 BASELINE palette and the
 * baseline motion scheme.
 *
 * ## Material 3 Expressive
 *
 * `MaterialExpressiveTheme` + `MotionScheme.expressive()`, on `material3:1.5.0-rc01` pinned
 * above the Compose BOM (owner decision, 2026-09-10, first on 1.5.0-alpha28; #292, #384 moved it
 * to the release candidate). These are `internal` in 1.4.0 and only become public in 1.5.0.
 *
 * The motion scheme is the highest-leverage line here: every M3 component reads its spring specs
 * from it, so setting it once changes how the whole app feels without touching a call site. The
 * Spektrafilm design system already observed that the adjustment panel's entrance
 * (`DampingRatioMediumBouncy` / `StiffnessLow`) is Expressive-shaped by hand; this makes that the
 * system default rather than one hand-tuned exception.
 *
 * ## Colour
 *
 * The design system is explicit that Spektrafilm does NOT override the M3 baseline — the brand
 * lives in the near-black editor canvas, the spectral palette and the launcher mark, and dark is
 * the shipped default. So the scheme stays baseline (now the *expressive* baseline), and the brand
 * surfaces are lifted out of scattered Kotlin literals into [SpektraBrand] below, at exactly the
 * values `tokens/colors.css` records.
 *
 * Dynamic colour (Material You, API 31+) is offered as an opt-in rather than imposed, because
 * wallpaper-derived chrome around a colour-critical image canvas is a matter of taste, not of
 * correctness. It defaults ON to match platform behaviour; a user who wants the documented
 * identity turns it off.
 */
package com.spectrafilm.app

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.remember
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** True when the platform can derive a palette from the user's wallpaper (Material You, API 31+). */
internal val dynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Brand surfaces that are deliberately theme-INDEPENDENT: they are the ground a photograph is
 * judged against, so they must not move when the colour scheme does. Values are the ones recorded
 * in the design system's `tokens/colors.css`, which read them out of the source in the first place.
 *
 * These exist so the literals stop being duplicated across Viewer/ToneCurve/Welcome/SpectraIcons.
 */
internal object SpektraBrand {
    /** Editor preview canvas + category bar. */
    val canvasNearBlack = Color(0xFF0B0B0D)

    /** Tone-curve graph canvas. */
    val canvasCurve = Color(0xFF101014)

    /** 100 % crop magnifier well. */
    val canvasMagnifier = Color(0xFF050505)

    /** Onboarding gradient end stop. */
    val canvasOnboarding = Color(0xFF0A0A12)

    // --- Content on the always-dark editor surfaces (preview, crop, mask, sampler, top bar) ---
    // These sit on the photo or on a near-black canvas in every theme, so they are fixed rather
    // than taken from the colour scheme: a light theme must not turn the photo chrome dark.

    /** Icons, labels and outlines drawn on the editor canvas or over the photo. */
    val onCanvas = Color.White

    /** Scrims and translucent backings behind canvas content (always used with an alpha). */
    val scrim = Color.Black

    /** Text on a selected (white) segment of the crop's connected button group. */
    val onCanvasSelected = Color(0xFF0B0B0D)

    /** Histogram fills over the preview: luma, then R/G/B. */
    val histLuma = Color(0x33FFFFFF)
    val histRed = Color(0x88FF4040)
    val histGreen = Color(0x8840FF40)
    val histBlue = Color(0x884070FF)

    /** Histogram clipping wedges: shadows (cool) and highlights (warm). */
    val clipShadows = Color(0xCC6FA8FF)
    val clipHighlights = Color(0xFFFF5A4D)

    /** Tone-curve lines per channel, tuned for the dark curve canvas. */
    val curveRed = Color(0xFFFF5A5A)
    val curveGreen = Color(0xFF5AD46A)
    val curveBlue = Color(0xFF5A93FF)
    val curveLuma = Color(0xFFE6E6E6)

    /** Film/paper characteristic-curve channels on the profile-curves screen. */
    val profileRed = Color(0xFFE53935)
    val profileGreen = Color(0xFF43A047)
    val profileBlue = Color(0xFF1E88E5)

    /** Draggable mask-geometry handles: a light accent readable on any photo. */
    val maskHandle = Color(0xFF7FD1FF)

    /** Onboarding backdrop / film-strip emblem, violet -> red. */
    val spectrum = listOf(
        Color(0xFF7B2FF7),
        Color(0xFF2F6BFF),
        Color(0xFF14C7C7),
        Color(0xFF34C759),
        Color(0xFFFFCC00),
        Color(0xFFFF8A00),
        Color(0xFFFF3B30),
    )
}

/**
 * The fallback schemes, used when dynamic colour is unavailable or switched off. Baseline by
 * design, per the note above; named rather than called inline so a brand scheme is a one-line
 * change here and nowhere else.
 */
private fun spektraLightScheme(): ColorScheme = lightColorScheme()

private fun spektraDarkScheme(): ColorScheme = darkColorScheme()

/**
 * Wrap the whole app. [dark] comes from the user's [ThemeMode] resolved against the system setting;
 * [dynamicColor] is the user's Material You preference and is ignored below API 31.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpektraTheme(
    dark: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = when {
        // Capability is checked separately from the preference, so a device that cannot do
        // Material You falls back instead of throwing, and the stored preference survives.
        dynamicColor && dynamicColorSupported ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> spektraDarkScheme()
        else -> spektraLightScheme()
    }
    // Reduced motion (Developer options / Accessibility "Remove animations" sets the animator
    // scale to 0): slides, morphs and bounces become short fades (Pen board, "Reduced motion").
    val reduceMotion = remember(context) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = if (reduceMotion) ReducedMotionScheme else MotionScheme.expressive(),
        content = content,
    )
}

/** Spatial motion snaps; effects (colour, alpha) keep a short fade. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object ReducedMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(150)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(100)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(200)
}
