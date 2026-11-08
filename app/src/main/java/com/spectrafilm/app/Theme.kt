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
 * The brand lives in the near-black editor canvas, the spectral palette, the launcher mark and,
 * when dynamic colour is off, a film-amber tonal scheme (#468; it replaced the M3 baseline the
 * design system first kept, see [spektraLightScheme]). Dark is the shipped default. The
 * theme-independent brand surfaces live in [SpektraBrand] below, at exactly the values
 * `tokens/colors.css` records.
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

    /**
     * White-balance gradient bars (Instruments.kt): cool to warm, and green to magenta.
     * Fixed, not themed, because the bar shows the colour shift being applied to the photo.
     */
    val warmthStops = listOf(Color(0xFF4F86D9), Color(0xFFD8D2C8), Color(0xFFF0A443))
    val tintStops = listOf(Color(0xFF4CAF6A), Color(0xFFCFC8D0), Color(0xFFC04FB0))
    /** Scan level meters: black to white in every theme, since the ramp stands for tones. */
    val levelRamp = listOf(Color(0xFF000000), Color(0xFFFFFFFF))
    /** EQ bands for per-channel controls: red, green, blue, muted to sit on any surface. */
    /**
     * Print filter wheel (Instruments.kt Pad2D): a sweep starting at 3 o'clock, clockwise on
     * screen, so magenta sits right and yellow on top, each with its complement opposite
     * (green left, blue below). It names the filter axes, not the print's resulting cast.
     */
    val filterWheel = arrayOf(
        0f to Color(0xFFC04FB0), 0.25f to Color(0xFF4F6AD9), 0.375f to Color(0xFF4FB0C0),
        0.5f to Color(0xFF4CAF6A), 0.75f to Color(0xFFE0C040), 0.875f to Color(0xFFD9534F),
        1f to Color(0xFFC04FB0),
    )
    val channelColors = listOf(Color(0xFFD9534F), Color(0xFF4CAF6A), Color(0xFF4F86D9))

    /** Export darkroom (ExportDevelopTray.kt): the safelit room, dark in every theme. */
    val darkroomScrim = Color(0xF5120A08)

    /** Text on the export darkroom scrim. */
    val onDarkroom = Color(0xFFF2E6DF)

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

    /**
     * The intro's illustrated sample photo (Welcome.kt): a dusk lake scene drawn in code so
     * the first launch needs no bundled image. Fixed colours: it stands in for a photograph,
     * so it must not follow the theme.
     */
    val introSkyTop = Color(0xFF7E93A6)
    val introSkyMid = Color(0xFFC9A993)
    val introSkyLow = Color(0xFFDCC3A8)
    val introSun = Color(0xFFFFF6E4)
    val introHillFar = Color(0xFF76707A)
    val introHillNear = Color(0xFF4F4A55)
    val introLakeTop = Color(0xFFB79E8C)
    val introLakeLow = Color(0xFF3C4250)
    val introGlint = Color(0x80FFF5DC)
    val introTrees = Color(0xFF23262E)

    /** Intro overlays drawn on that photo: the compare divider and the demo fingertip. */
    val introDivider = Color(0xFFFFFFFF)
    val introTouch = Color(0xE6FFFFFF)
    val introTouchHalo = Color(0x33FFFFFF)
    val introChipScrim = Color(0xB30F0E10)
    val introChipText = Color(0xFFECE6EE)
    val introChipAccent = Color(0xFFFFB95C)

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
 * The fallback schemes, used when dynamic colour is unavailable (API < 31) or switched off:
 * the Spektrafilm brand scheme (#468, audit V1). Generated with the M3 tonal system
 * (Material Color Utilities, Tonal Spot, 2021 spec, standard contrast) from one seed, film
 * amber #C8892B, so the chrome reads as film stock rather than Material's default purple.
 * The neutral and neutral-variant palettes are generated at chroma 0 instead of Tonal Spot's
 * warm 6 / 8: backgrounds, panels and outlines are true greys, so no tinted chrome sits beside
 * a photo being judged for colour. Amber lives only in the accent roles (primary, secondary,
 * their containers). Every on-/container pair clears WCAG AA 4.5:1 (lowest: light primary on
 * surfaceContainerHighest 5.0, dark onSurfaceVariant on surfaceVariant 5.4).
 *
 * The photo is never judged against these: the editor canvas and everything drawn on it use
 * the theme-independent [SpektraBrand] neutrals. To regenerate, rerun Tonal Spot on the seed
 * with neutral chroma 0 (Material Color Utilities `DynamicScheme(..., neutral_palette =
 * TonalPalette.from_hue_and_chroma(seedHue, 0), neutral_variant_palette = same)`, spec 2021);
 * do not hand-tune single roles, or the tonal relationships (and the contrast) break.
 */
internal fun spektraLightScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF815512),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB6),
    onPrimaryContainer = Color(0xFF643F00),
    inversePrimary = Color(0xFFF6BC70),
    secondary = Color(0xFF705B40),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBDEBC),
    onSecondaryContainer = Color(0xFF57432B),
    tertiary = Color(0xFF53643E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6E9BA),
    onTertiaryContainer = Color(0xFF3C4C29),
    background = Color(0xFFF9F9F9),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFF9F9F9),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE2E2E2),
    onSurfaceVariant = Color(0xFF474747),
    surfaceTint = Color(0xFF815512),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF1F1F1),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFC6C6C6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFEEEEEE),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE2E2E2),
    surfaceContainerLow = Color(0xFFF3F3F3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDADADA),
)

internal fun spektraDarkScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFF6BC70),
    onPrimary = Color(0xFF462B00),
    primaryContainer = Color(0xFF643F00),
    onPrimaryContainer = Color(0xFFFFDDB6),
    inversePrimary = Color(0xFF815512),
    secondary = Color(0xFFDEC2A2),
    onSecondary = Color(0xFF3E2D16),
    secondaryContainer = Color(0xFF57432B),
    onSecondaryContainer = Color(0xFFFBDEBC),
    tertiary = Color(0xFFBACD9F),
    onTertiary = Color(0xFF263514),
    tertiaryContainer = Color(0xFF3C4C29),
    onTertiaryContainer = Color(0xFFD6E9BA),
    background = Color(0xFF131313),
    onBackground = Color(0xFFE2E2E2),
    surface = Color(0xFF131313),
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF474747),
    onSurfaceVariant = Color(0xFFC6C6C6),
    surfaceTint = Color(0xFFF6BC70),
    inverseSurface = Color(0xFFE2E2E2),
    inverseOnSurface = Color(0xFF303030),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF919191),
    outlineVariant = Color(0xFF474747),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF393939),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF353535),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceDim = Color(0xFF131313),
)

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
