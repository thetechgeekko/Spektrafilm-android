/*
 * SpectraFilm for Android — custom category / utility icons. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Hand-crafted ImageVector icons for the SpectraFilm bottom category bar and
 * toolbar. Each icon lives at 24 × 24 dp, drawn as stroke-only line art
 * at a consistent strokeWidth of 1.5f. Colour is left entirely to the caller,
 * so they tint naturally via the standard Compose Icon composable:
 *
 *   Icon(
 *       imageVector = SpectraIcons.Simulation,
 *       contentDescription = "Film simulation",
 *       tint = LocalContentColor.current   // or MaterialTheme.colorScheme.onSurface
 *   )
 *
 * Design motifs
 * -------------
 * Consistent with the adaptive launcher icon (35 mm film frame + spectral strip):
 *   • Pure line-art: stroke paths, no area fills.
 *   • StrokeCap.Round / StrokeJoin.Round throughout — slightly softens line ends
 *     without going pill-shaped, matching the launcher's rounded-rectangle film body.
 *   • All geometry fits within the 2–22 usable band of the 24 dp viewport.
 *   • Solid-dot accents (filled tiny circles / squares) for emphasis where needed.
 *
 * Public API — all members of the `SpectraIcons` object
 * -------------------------------------------------------
 *   SpectraIcons.Input          – arrow entering a rectangle (image source in)
 *   SpectraIcons.ImportRaw      – three-blade aperture iris with centre dot (RAW / WB)
 *   SpectraIcons.Simulation     – 35 mm film-frame outline + sprocket holes (film sim)
 *   SpectraIcons.Grain          – rectangle with 9 scattered grain dots (noise)
 *   SpectraIcons.Preflash       – lightning-bolt flash symbol (preflash)
 *   SpectraIcons.Halation       – concentric rings with filled centre (glow / halo)
 *   SpectraIcons.Couplers       – three offset rectangles (dye-coupler layers)
 *   SpectraIcons.Glare          – 4 long + 4 short rays with centre circle (lens flare)
 *   SpectraIcons.Experimental   – Erlenmeyer flask with bubbles (chemistry)
 *   SpectraIcons.Display        – monitor outline with spectral line (output colour)
 *   SpectraIcons.Presets        – three slider tracks with offset thumbs (preset snapshot)
 *   SpectraIcons.SourceImage    – picture frame with mountain + sun (photo thumbnail)
 *   SpectraIcons.Settings       – 8-tooth gear: outer toothed ring + inner hub circle
 *   SpectraIcons.Help           – circle containing "?" (help / about)
 *   SpectraIcons.Rotate         – ¾-arc with arrowhead (90° clockwise rotate)
 */

package com.spectrafilm.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Private builder helpers
// ---------------------------------------------------------------------------

private val Transparent = SolidColor(Color.Transparent)
private val Black = SolidColor(Color.Black)   // overridden by Icon tint at draw time
private const val SW = 1.5f

/**
 * Create a 24 × 24 tintable ImageVector.
 * The [block] receives an [ImageVector.Builder] and should call [addPath] to add paths.
 */
private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "SpectraIcons.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

/** Add a stroked (no fill) path parsed from an SVG path string. */
private fun ImageVector.Builder.strokePath(
    pathData: String,
    strokeWidth: Float = SW
): ImageVector.Builder = addPath(
    pathData = addPathNodes(pathData),
    fill = Transparent,
    fillAlpha = 0f,
    stroke = Black,
    strokeAlpha = 1f,
    strokeLineWidth = strokeWidth,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    strokeLineMiter = 4f,
    pathFillType = PathFillType.NonZero
)

/** Add a filled (no stroke) path parsed from an SVG path string. Used for small solid dots. */
private fun ImageVector.Builder.fillPath(pathData: String): ImageVector.Builder = addPath(
    pathData = addPathNodes(pathData),
    fill = Black,
    fillAlpha = 1f,
    stroke = null,
    strokeAlpha = 0f,
    strokeLineWidth = 0f,
    strokeLineCap = StrokeCap.Butt,
    strokeLineJoin = StrokeJoin.Miter,
    strokeLineMiter = 4f,
    pathFillType = PathFillType.NonZero
)

// ---------------------------------------------------------------------------
// Icon namespace
// ---------------------------------------------------------------------------

object SpectraIcons {

    /** Near-black editor canvas colour for the Lightroom-style preview background. */
    val nearBlackCanvas = Color(0xFF0B0B0D)


    // -----------------------------------------------------------------------
    // Input — image source / image-in
    // Arrow pointing right into a rectangle (inbound image source).
    // -----------------------------------------------------------------------
    val Input: ImageVector by lazy {
        icon("Input") {
            // Destination rectangle (right two-thirds of icon)
            strokePath("M 9 4 L 20 4 L 20 20 L 9 20 Z")
            // Arrow shaft
            strokePath("M 3 12 L 13 12")
            // Arrowhead chevron
            strokePath("M 10 9 L 13 12 L 10 15")
        }
    }

    // -----------------------------------------------------------------------
    // ImportRaw — RAW / aperture / white-balance
    // Circular lens barrel with three aperture blades (chords crossing centre)
    // and a small filled pupil dot — evokes an iris / RAW decoder.
    // -----------------------------------------------------------------------
    val ImportRaw: ImageVector by lazy {
        icon("ImportRaw") {
            // Outer lens ring
            strokePath("M 12 3 C 16.97 3 21 7.03 21 12 C 21 16.97 16.97 21 12 21 C 7.03 21 3 16.97 3 12 C 3 7.03 7.03 3 12 3 Z")
            // Blade 1 — NW→SE chord
            strokePath("M 5.5 8 L 18.5 16")
            // Blade 2 — NE→SW chord
            strokePath("M 18.5 8 L 5.5 16")
            // Blade 3 — horizontal chord
            strokePath("M 3 12 L 21 12")
            // Central pupil
            fillPath("M 13.2 12 C 13.2 12.66 12.66 13.2 12 13.2 C 11.34 13.2 10.8 12.66 10.8 12 C 10.8 11.34 11.34 10.8 12 10.8 C 12.66 10.8 13.2 11.34 13.2 12 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Simulation — 35 mm film frame
    // Matches the launcher icon: outer film body, inner gate window,
    // and four square sprocket holes (two top, two bottom).
    // -----------------------------------------------------------------------
    val Simulation: ImageVector by lazy {
        icon("Simulation") {
            // Film body
            strokePath("M 4 3 L 20 3 L 20 21 L 4 21 Z")
            // Exposed gate window
            strokePath("M 7 7 L 17 7 L 17 17 L 7 17 Z")
            // Sprocket — top-left
            fillPath("M 5 4 L 6.5 4 L 6.5 5.5 L 5 5.5 Z")
            // Sprocket — top-right
            fillPath("M 17.5 4 L 19 4 L 19 5.5 L 17.5 5.5 Z")
            // Sprocket — bottom-left
            fillPath("M 5 18.5 L 6.5 18.5 L 6.5 20 L 5 20 Z")
            // Sprocket — bottom-right
            fillPath("M 17.5 18.5 L 19 18.5 L 19 20 L 17.5 20 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Grain — film grain / noise texture
    // Rectangle bounding box with nine scattered tiny dot marks (3 × 3 grid,
    // positions slightly irregular to feel organic).
    // -----------------------------------------------------------------------
    val Grain: ImageVector by lazy {
        icon("Grain") {
            // Bounding rectangle
            strokePath("M 3 5 L 21 5 L 21 19 L 3 19 Z")
            // Row 1 dots
            fillPath("M 6.5 8.5 L 7.5 8.5 L 7.5 9.5 L 6.5 9.5 Z")
            fillPath("M 11.5 7.5 L 12.5 7.5 L 12.5 8.5 L 11.5 8.5 Z")
            fillPath("M 16 8 L 17 8 L 17 9 L 16 9 Z")
            // Row 2 dots
            fillPath("M 8 11.5 L 9 11.5 L 9 12.5 L 8 12.5 Z")
            fillPath("M 13 11 L 14 11 L 14 12 L 13 12 Z")
            fillPath("M 16.5 11.5 L 17.5 11.5 L 17.5 12.5 L 16.5 12.5 Z")
            // Row 3 dots
            fillPath("M 6 14.5 L 7 14.5 L 7 15.5 L 6 15.5 Z")
            fillPath("M 10.5 15 L 11.5 15 L 11.5 16 L 10.5 16 Z")
            fillPath("M 15 14.5 L 16 14.5 L 16 15.5 L 15 15.5 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Preflash — camera preflash / burst
    // Classic lightning-bolt / flash symbol: a downward-slanting zigzag
    // that reads instantly as "flash" in photography contexts.
    // -----------------------------------------------------------------------
    val Preflash: ImageVector by lazy {
        icon("Preflash") {
            strokePath("M 14 2 L 8 13 L 13 13 L 10 22 L 18 9 L 13 9 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Halation — glow / halo around a highlight
    // Two concentric rings (outer halo, inner bloom) with a filled bright-
    // source dot at the centre — the classic halation diagram.
    // -----------------------------------------------------------------------
    val Halation: ImageVector by lazy {
        icon("Halation") {
            // Outer halo ring (r≈9)
            strokePath("M 12 3 C 16.97 3 21 7.03 21 12 C 21 16.97 16.97 21 12 21 C 7.03 21 3 16.97 3 12 C 3 7.03 7.03 3 12 3 Z")
            // Inner bloom ring (r≈5.5)
            strokePath("M 12 6.5 C 14.76 6.5 17 9 17 12 C 17 15 14.76 17.5 12 17.5 C 9.24 17.5 7 15 7 12 C 7 9 9.24 6.5 12 6.5 Z")
            // Bright-source filled dot (r≈2)
            fillPath("M 14 12 C 14 13.1 13.1 14 12 14 C 10.9 14 10 13.1 10 12 C 10 10.9 10.9 10 12 10 C 13.1 10 14 10.9 14 12 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Couplers — DIR dye-coupler layers
    // Three offset overlapping rectangles suggesting the three colour-forming
    // emulsion layers (cyan / magenta / yellow).
    // -----------------------------------------------------------------------
    val Couplers: ImageVector by lazy {
        icon("Couplers") {
            // Bottom layer (southernmost)
            strokePath("M 5 10 L 16 10 L 16 18 L 5 18 Z")
            // Middle layer (offset +2, −3)
            strokePath("M 7 7 L 18 7 L 18 15 L 7 15 Z")
            // Top layer (offset +2, −3)
            strokePath("M 9 4 L 20 4 L 20 12 L 9 12 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Glare — lens flare / starburst
    // Centre circle with 4 long cardinal rays (N/S/E/W) and 4 shorter
    // diagonal rays (NE/NW/SE/SW) — the classic lens-flare starburst.
    // -----------------------------------------------------------------------
    val Glare: ImageVector by lazy {
        icon("Glare") {
            // Centre circle (r=2)
            strokePath("M 14 12 C 14 13.1 13.1 14 12 14 C 10.9 14 10 13.1 10 12 C 10 10.9 10.9 10 12 10 C 13.1 10 14 10.9 14 12 Z")
            // Cardinal rays
            strokePath("M 12 2 L 12 7")
            strokePath("M 12 17 L 12 22")
            strokePath("M 2 12 L 7 12")
            strokePath("M 17 12 L 22 12")
            // Diagonal rays (shorter)
            strokePath("M 5.64 5.64 L 8.46 8.46")
            strokePath("M 18.36 5.64 L 15.54 8.46")
            strokePath("M 5.64 18.36 L 8.46 15.54")
            strokePath("M 18.36 18.36 L 15.54 15.54")
        }
    }

    // -----------------------------------------------------------------------
    // Experimental — chemistry / Erlenmeyer flask
    // Narrow neck tapering to a wide triangular body, with a liquid-level
    // line and two small bubble circles above it.
    // -----------------------------------------------------------------------
    val Experimental: ImageVector by lazy {
        icon("Experimental") {
            // Flask outline: neck top → shoulders → wide base
            strokePath("M 9 3 L 15 3 L 15 9 L 20 20 L 4 20 L 9 9 Z")
            // Liquid level line inside the body
            strokePath("M 6.5 16 L 17.5 16")
            // Bubble left (r≈1)
            strokePath("M 10 13.5 C 10 14.33 9.33 15 8.5 15 C 7.67 15 7 14.33 7 13.5 C 7 12.67 7.67 12 8.5 12 C 9.33 12 10 12.67 10 13.5 Z")
            // Bubble right (r≈1)
            strokePath("M 15 12.5 C 15 13.33 14.33 14 13.5 14 C 12.67 14 12 13.33 12 12.5 C 12 11.67 12.67 11 13.5 11 C 14.33 11 15 11.67 15 12.5 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Display — monitor / output colour
    // Classic flat-screen monitor outline with a horizontal spectral-hint
    // line across the lower portion of the screen (echoing the launcher strip)
    // plus stand stem and foot.
    // -----------------------------------------------------------------------
    val Display: ImageVector by lazy {
        icon("Display") {
            // Monitor body (screen rectangle)
            strokePath("M 2 4 L 22 4 L 22 16 L 2 16 Z")
            // Spectral accent line across lower screen
            strokePath("M 4 13 L 20 13")
            // Stand stem
            strokePath("M 12 16 L 12 20")
            // Stand foot
            strokePath("M 8 20 L 16 20")
        }
    }

    // -----------------------------------------------------------------------
    // Presets — stacked sliders / parameter preset snapshot
    // Three horizontal slider tracks, each with a small filled square thumb
    // at a different position — visually suggests a saved set of parameters.
    // -----------------------------------------------------------------------
    val Presets: ImageVector by lazy {
        icon("Presets") {
            // Track 1
            strokePath("M 3 7 L 21 7")
            // Thumb 1 (≈1/3 from left)
            fillPath("M 8 5.5 L 10 5.5 L 10 8.5 L 8 8.5 Z")
            // Track 2
            strokePath("M 3 12 L 21 12")
            // Thumb 2 (near centre)
            fillPath("M 11 10.5 L 13 10.5 L 13 13.5 L 11 13.5 Z")
            // Track 3
            strokePath("M 3 17 L 21 17")
            // Thumb 3 (≈2/3 from left)
            fillPath("M 14 15.5 L 16 15.5 L 16 18.5 L 14 18.5 Z")
        }
    }

    // -----------------------------------------------------------------------
    // SourceImage — photo / picture source
    // Picture-frame rectangle containing a simple landscape: horizon line,
    // triangle mountain, and a small circle sun in the top-right corner.
    // -----------------------------------------------------------------------
    val SourceImage: ImageVector by lazy {
        icon("SourceImage") {
            // Picture frame
            strokePath("M 3 4 L 21 4 L 21 20 L 3 20 Z")
            // Horizon line
            strokePath("M 3 15 L 21 15")
            // Mountain triangle
            strokePath("M 7 15 L 12 9 L 17 15")
            // Sun circle (r≈1.5)
            strokePath("M 18 7.5 C 18 8.33 17.33 9 16.5 9 C 15.67 9 15 8.33 15 7.5 C 15 6.67 15.67 6 16.5 6 C 17.33 6 18 6.67 18 7.5 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Settings — gear
    // An 8-tooth gear built from two parts: an outer toothed ring (approximated
    // as an octagonal outline with rectangular protrusions) and an inner hub circle.
    // Simplified to stay sharp at 24 dp without trigonometry.
    // -----------------------------------------------------------------------
    val Settings: ImageVector by lazy {
        icon("Settings") {
            // Outer gear silhouette: 8 teeth at 45° intervals.
            // The path alternates between tooth-tips (r≈10) and valley corners (r≈7),
            // hand-computed for a 24×24 viewport (centre 12,12).
            //
            // Key points (tooth tips and valley corners, clockwise from top):
            //   Top tooth:    (10.5,2)→(13.5,2)
            //   NE valley:    (16.5,4.5)→(19.5,7.5)  [after rounding: 45° point ≈ (17,4)]
            //   Right tooth:  (22,10.5)→(22,13.5)
            //   SE valley:    (19.5,16.5)→(16.5,19.5)
            //   Bottom tooth: (13.5,22)→(10.5,22)
            //   SW valley:    (7.5,19.5)→(4.5,16.5)
            //   Left tooth:   (2,13.5)→(2,10.5)
            //   NW valley:    (4.5,7.5)→(7.5,4.5)
            strokePath(
                "M 10.5 2 L 13.5 2" +          // top tooth (top edge)
                " L 15.5 4 L 17 4 L 19.5 6.5" + // NE valley + slope
                " L 22 10.5 L 22 13.5" +        // right tooth
                " L 19.5 17.5 L 19.5 17.5" +    // SE slope
                " L 17 20 L 15.5 20" +           // SE valley
                " L 13.5 22 L 10.5 22" +         // bottom tooth
                " L 8.5 20 L 7 20" +             // SW valley
                " L 4.5 17.5" +                  // SW slope
                " L 2 13.5 L 2 10.5" +          // left tooth
                " L 4.5 6.5 L 7 4" +            // NW slope + valley
                " L 8.5 4 Z"                     // close back to top tooth
            )
            // Hub (inner circle, r≈3.5)
            strokePath("M 15.5 12 C 15.5 13.93 13.93 15.5 12 15.5 C 10.07 15.5 8.5 13.93 8.5 12 C 8.5 10.07 10.07 8.5 12 8.5 C 13.93 8.5 15.5 10.07 15.5 12 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Help — circle containing "?"
    // -----------------------------------------------------------------------
    val Help: ImageVector by lazy {
        icon("Help") {
            // Outer circle
            strokePath("M 12 3 C 16.97 3 21 7.03 21 12 C 21 16.97 16.97 21 12 21 C 7.03 21 3 16.97 3 12 C 3 7.03 7.03 3 12 3 Z")
            // Question-mark curve: arcs down from top to stem
            strokePath("M 9.5 9 C 9.5 7.07 11.07 7 12 7 C 13.1 7 14.5 7.67 14.5 9.5 C 14.5 11 12 11.5 12 13.5")
            // Dot below stem (r≈0.75)
            fillPath("M 12.75 16.5 C 12.75 16.91 12.41 17.25 12 17.25 C 11.59 17.25 11.25 16.91 11.25 16.5 C 11.25 16.09 11.59 15.75 12 15.75 C 12.41 15.75 12.75 16.09 12.75 16.5 Z")
        }
    }

    // -----------------------------------------------------------------------
    // Rotate — 90° clockwise rotate-arrow (preview rotate button)
    // Three-quarter open arc (clockwise from 3-o'clock around to 12-o'clock)
    // with a small arrowhead at the open end suggesting 90° CW rotation.
    // -----------------------------------------------------------------------
    val Rotate: ImageVector by lazy {
        icon("Rotate") {
            // ¾-arc (r=8, centre 12,12), clockwise: 3-o'clock → 6 → 9 → 12
            strokePath(
                "M 20 12" +
                " C 20 16.42 16.42 20 12 20" +   // 3-o'clock → 6-o'clock
                " C 7.58 20 4 16.42 4 12" +       // 6 → 9
                " C 4 7.58 7.58 4 12 4"           // 9 → 12 (arc ends at top, open)
            )
            // Arrowhead at the arc end (12,4), two short lines:
            // one pointing CW (→ right-upward direction)
            strokePath("M 12 4 L 15.5 6")
            strokePath("M 12 4 L 14 1")
        }
    }
}
