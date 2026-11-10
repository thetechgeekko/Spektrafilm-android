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

import androidx.annotation.StringRes
import com.spectrafilm.engine.ColorSpace
import kotlin.math.roundToInt

/** Output-size choice in the export sheet (a post-render downscale, like Lightroom's "Dimensions"). */
enum class ExportSize(@StringRes val labelRes: Int, val longEdge: Int?) {
    FULL(R.string.tool_export_size_full, null),
    LARGE(R.string.tool_export_size_large, 4096),
    MEDIUM(R.string.tool_export_size_medium, 2048),
    SMALL(R.string.tool_export_size_small, 1024),
    CUSTOM(R.string.tool_export_size_custom, null),
}

/** The user's choices in the export sheet. Pure data; the UI binds to it and the pipeline reads it. */
data class ExportOptions(
    val format: ExportFormat,
    val jpegQuality: Int,
    val size: ExportSize,
    val customLongEdge: Int,
    val customName: String,
    /** Frame around the photo; grows the output. Bitmap formats only, see [effectiveBorder]. */
    val border: FilmBorder = FilmBorder.NONE,
    /** Output sharpening, applied after the resize (8-bit formats only). */
    val sharpen: OutputSharpen = OutputSharpen.NONE,
    /** Width, colour and aspect of the [border] frame (the Frame tab). */
    val frame: FrameLook = FrameLook(),
) {
    /**
     * The border this export actually gets. 16-bit and scene-linear formats write the engine's
     * float buffer directly and are for further editing, where a painted frame is unwanted, so
     * they always export the bare photo (the stored choice itself is left untouched, as with size).
     */
    fun effectiveBorder(): FilmBorder =
        if (format.isHighBitDepth() || format == ExportFormat.SCENE_LINEAR_TIFF) FilmBorder.NONE
        else border

    /** The whole frame this export paints: [effectiveBorder] with its [frame] look, or none. */
    fun effectiveFrame(): FrameSpec =
        if (format.isHighBitDepth() || format == ExportFormat.SCENE_LINEAR_TIFF) FrameSpec.NONE
        else FrameSpec(border, frame)

    /** The frame as chosen, whether or not this format can carry it (the Frame tab edits this). */
    val frameSpec: FrameSpec get() = FrameSpec(border, frame)

    fun withFrame(spec: FrameSpec): ExportOptions = copy(border = spec.style, frame = spec.look)

    /**
     * Target long edge in px for a post-render downscale, or null = full resolution. 16-bit formats
     * (TIFF / PNG16) always export full-res — their save path writes the float buffer directly, so a
     * Bitmap resize doesn't apply; the sheet hides the Size dropdown for them and this short-circuits
     * to null (the stored size choice itself is left untouched). An empty/unset custom field
     * (customLongEdge <= 0) also means full resolution — never a MIN_CUSTOM_EDGE thumbnail.
     */
    fun targetLongEdge(): Int? = when {
        format.isHighBitDepth() -> null
        size == ExportSize.CUSTOM ->
            if (customLongEdge <= 0) null
            else customLongEdge.coerceIn(MIN_CUSTOM_EDGE, MAX_CUSTOM_EDGE)
        else -> size.longEdge
    }

    /**
     * Resolve and validate the complete output contract before decode/render starts. The selected
     * format owns sample depth; the six oracle-backed engine transforms own transfer/CCTF pairing.
     */
    fun outputDescriptor(
        colorSpace: ColorSpace,
        outputCctfEncoding: Boolean,
        apiLevel: Int,
    ): OutputDescriptor {
        val descriptor = if (format == ExportFormat.SCENE_LINEAR_TIFF) {
            OutputDescriptor.sceneLinearTiff(OutputDescriptor.fixedBitDepth(format))
        } else {
            OutputDescriptor.rendered(
                format = format,
                colorSpace = colorSpace,
                outputCctfEncoding = outputCctfEncoding,
                bitDepth = OutputDescriptor.fixedBitDepth(format),
            )
        }
        return descriptor.requireExportable(apiLevel)
    }

    /** The sharpening this export actually gets: none for the high-bit-depth archive formats. */
    fun effectiveSharpen(): OutputSharpen =
        if (format.isHighBitDepth()) OutputSharpen.NONE else sharpen

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
fun exportBaseName(
    custom: String,
    timestampMs: Long,
    tokens: ExportNameTokens = ExportNameTokens(),
): String {
    val cleaned = expandExportNameTokens(custom, timestampMs, tokens).trim()
        .replace(Regex("[^A-Za-z0-9 _-]"), "")
        .replace(Regex("\\s+"), "_")
        .trim('_')
    return cleaned.ifEmpty { "Spektrafilm_$timestampMs" }
}

/** Values for the `{...}` placeholders a file name may contain; see [expandExportNameTokens]. */
data class ExportNameTokens(
    val sourceName: String = "",
    val film: String = "",
    val print: String = "",
)

/**
 * Replace the placeholders in a file-name [template]: `{name}` (the photo's file name without
 * extension), `{film}`, `{print}`, `{date}` (yyyyMMdd) and `{time}` (HHmmss), both in local time
 * at [timestampMs]. Unknown placeholders are left as typed, so the sanitiser strips their braces
 * and keeps the word. Matching is case-insensitive.
 */
fun expandExportNameTokens(template: String, timestampMs: Long, tokens: ExportNameTokens): String {
    if ('{' !in template) return template
    val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(timestampMs))
    val time = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date(timestampMs))
    return Regex("\\{(\\w+)\\}").replace(template) { m ->
        when (m.groupValues[1].lowercase(java.util.Locale.ROOT)) {
            "name" -> tokens.sourceName.substringBeforeLast('.')
            "film" -> tokens.film
            "print" -> tokens.print
            "date" -> date
            "time" -> time
            else -> m.value
        }
    }
}
