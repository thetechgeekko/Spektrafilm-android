/*
 * Spektrafilm for Android — export options model (Lightroom-style export sheet §6a/§6b). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Lightroom's export is a format-aware settings sheet (format → format-specific options → size →
 * naming → metadata) rather than a single global setting. This is the pure, JVM-testable core of
 * our version: the size/quality/name choices and the maths behind them. The Compose sheet binds to
 * it; the export pipeline reads it. UI/post-engine only — no engine params, no parity impact.
 */
package com.spectrafilm.app

import kotlin.math.roundToInt

/** Output-size choice in the export sheet (a post-render downscale, like Lightroom's "Dimensions"). */
enum class ExportSize(val label: String, val longEdge: Int?) {
    FULL("Full resolution", null),
    LARGE("Large — 4096 px", 4096),
    MEDIUM("Medium — 2048 px", 2048),
    SMALL("Small — 1024 px", 1024),
    CUSTOM("Custom long edge", null),
}

/** The user's choices in the export sheet. Pure data; the UI binds to it and the pipeline reads it. */
data class ExportOptions(
    val format: ExportFormat,
    val jpegQuality: Int,
    val size: ExportSize,
    val customLongEdge: Int,
    val customName: String,
) {
    /**
     * Target long edge in px for a post-render downscale, or null = full resolution. 16-bit formats
     * (TIFF / PNG16) always export full-res — their save path writes the float buffer directly, so a
     * Bitmap resize doesn't apply; the sheet pins Size to Full for them.
     */
    fun targetLongEdge(): Int? = when {
        format.is16Bit() -> null
        size == ExportSize.CUSTOM -> customLongEdge.coerceIn(MIN_CUSTOM_EDGE, MAX_CUSTOM_EDGE)
        else -> size.longEdge
    }

    companion object {
        const val MIN_CUSTOM_EDGE = 256
        const val MAX_CUSTOM_EDGE = 16384
    }
}

/**
 * Width/height after fitting [longEdge] to the longer side, preserving aspect ratio and never
 * enlarging (Lightroom's "Don't Enlarge"). Returns the input unchanged when it already fits.
 */
fun scaledDimensions(w: Int, h: Int, longEdge: Int): Pair<Int, Int> {
    val maxSide = maxOf(w, h)
    if (longEdge <= 0 || maxSide <= longEdge) return w to h
    val s = longEdge.toDouble() / maxSide
    return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
}

/**
 * A safe export base filename (no extension): the sanitised [custom] name, or a timestamped default
 * when it's blank or sanitises to nothing. Keeps letters/digits/space/underscore/hyphen; spaces
 * collapse to underscores so the result is a valid, portable filename.
 */
fun exportBaseName(custom: String, timestampMs: Long): String {
    val cleaned = custom.trim()
        .replace(Regex("[^A-Za-z0-9 _-]"), "")
        .replace(Regex("\\s+"), "_")
        .trim('_')
    return cleaned.ifEmpty { "Spektrafilm_$timestampMs" }
}
