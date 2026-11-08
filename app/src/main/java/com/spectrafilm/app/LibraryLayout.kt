/*
 * Spektrafilm for Android -- how the Library lays its frames out: date sections, justified
 * rows and the square grid. GPLv3. Film modeling powered by spektrafilm.
 *
 * Pure functions with no Android types, so the packing is tested on the plain JVM. The screen
 * turns their output into one LazyColumn of sticky date headers and rows of frames.
 */
package com.spectrafilm.app

import java.util.TimeZone

/** How the Library draws its frames. */
internal enum class LibraryView { JUSTIFIED, SQUARE }

/**
 * A run of frames under one header. [epochDay] is the local calendar day of the frames'
 * dates (days since 1970-01-01 in the device's zone), or null when the grid is not sorted by
 * date (one section, no header) or the provider reported no date ([undated]).
 */
internal data class LibrarySection(
    val epochDay: Long?,
    val entries: List<LibraryEntry>,
    val undated: Boolean = false,
)

private const val DAY_MS = 86_400_000L

/** The local calendar day of [epochMs] in [zone], as days since 1970-01-01. */
internal fun localEpochDay(epochMs: Long, zone: TimeZone): Long =
    Math.floorDiv(epochMs + zone.getOffset(epochMs), DAY_MS)

/**
 * Splits [entries] (already in grid order) into date sections when sorted by date: each run of
 * frames from the same local day gets one header. Frames with no date (0 or less) share one
 * "no date" section wherever they fall in the order. Any other sort is a single headerless
 * section, since a day header over an alphabetical run would mean nothing.
 */
internal fun librarySections(
    entries: List<LibraryEntry>,
    sort: LibrarySort,
    zone: TimeZone,
): List<LibrarySection> {
    if (entries.isEmpty()) return emptyList()
    if (sort != LibrarySort.DATE) return listOf(LibrarySection(null, entries))
    val out = ArrayList<LibrarySection>()
    var day: Long? = null
    var undated = false
    var run = ArrayList<LibraryEntry>()
    fun close() {
        if (run.isNotEmpty()) out += LibrarySection(day, run, undated)
        run = ArrayList()
    }
    for (entry in entries) {
        val isUndated = entry.modifiedMs <= 0L
        val entryDay = if (isUndated) null else localEpochDay(entry.modifiedMs, zone)
        if (run.isNotEmpty() && (entryDay != day || isUndated != undated)) close()
        day = entryDay
        undated = isUndated
        run += entry
    }
    close()
    return out
}

/** One row of frames: each frame's width, and the row's shared height, in the caller's unit. */
internal data class LibraryRow(
    val entries: List<LibraryEntry>,
    val widths: List<Float>,
    val height: Float,
) {
    /** Stable LazyColumn key: a row is named by its first frame. */
    val key: String get() = "row:" + entries.first().documentUri
}

/** Aspect ratios outside this range are clamped, so a panorama or a sliver cannot take a row. */
internal const val MIN_ROW_ASPECT = 0.5f
internal const val MAX_ROW_ASPECT = 2.5f

/** Used until a frame's real shape is known: the 3:2 of most camera sensors. */
internal const val DEFAULT_FRAME_ASPECT = 1.5f

/**
 * Packs frames into rows of equal height that fill [width], like a photo book: frames are
 * added to a row until its height would drop to [targetHeight] or below, then the row is
 * scaled to fill the width exactly. The last row keeps [targetHeight] instead of stretching
 * a few frames across the screen. [aspectOf] is width / height of each frame as shown.
 */
internal fun justifyRows(
    entries: List<LibraryEntry>,
    aspectOf: (LibraryEntry) -> Float,
    width: Float,
    targetHeight: Float,
    gap: Float,
): List<LibraryRow> {
    if (entries.isEmpty() || width <= 0f || targetHeight <= 0f) return emptyList()
    val rows = ArrayList<LibraryRow>()
    var row = ArrayList<LibraryEntry>()
    var aspects = ArrayList<Float>()
    for (entry in entries) {
        val raw = aspectOf(entry)
        val aspect = if (raw.isFinite() && raw > 0f) raw.coerceIn(MIN_ROW_ASPECT, MAX_ROW_ASPECT) else DEFAULT_FRAME_ASPECT
        row += entry
        aspects += aspect
        val height = (width - gap * (row.size - 1)) / aspects.sum()
        if (height <= targetHeight) {
            rows += LibraryRow(row, aspects.map { it * height }, height)
            row = ArrayList()
            aspects = ArrayList()
        }
    }
    if (row.isNotEmpty()) {
        val fill = (width - gap * (row.size - 1)) / aspects.sum()
        val height = minOf(targetHeight, fill)
        rows += LibraryRow(row, aspects.map { it * height }, height)
    }
    return rows
}

/** Square cells, [columns] to a row, filling [width]. A short last row stays left-aligned. */
internal fun squareRows(entries: List<LibraryEntry>, columns: Int, width: Float, gap: Float): List<LibraryRow> {
    if (entries.isEmpty() || width <= 0f) return emptyList()
    val n = columns.coerceAtLeast(1)
    val side = (width - gap * (n - 1)) / n
    return entries.chunked(n).map { chunk -> LibraryRow(chunk, List(chunk.size) { side }, side) }
}

/** How many square columns fit [width] with cells at least [minCell] wide. */
internal fun squareColumns(width: Float, minCell: Float, gap: Float): Int =
    if (minCell <= 0f) 1 else ((width + gap) / (minCell + gap)).toInt().coerceAtLeast(1)
