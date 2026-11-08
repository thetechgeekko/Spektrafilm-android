/*
 * Spektrafilm for Android -- Library date sections and row packing. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LibraryLayoutTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L

    private fun e(name: String, modified: Long = 1L) = LibraryEntry("content://t/$name", name, null, modified, 1L)

    @Test fun dateSortGroupsRunsOfTheSameLocalDay() {
        val list = listOf(e("a", 3 * day + 10), e("b", 3 * day + 5), e("c", 2 * day + 1), e("d", 0), e("f", 1 * day))
        val sections = librarySections(list, LibrarySort.DATE, utc)
        assertEquals(listOf(3L, 2L, null, 1L), sections.map { it.epochDay })
        assertEquals(listOf("a", "b"), sections[0].entries.map { it.name })
        assertTrue(sections[2].undated)
    }

    @Test fun dayFollowsTheZoneNotUtc() {
        // 23:30 UTC on day 10 is already day 11 in UTC+2.
        val late = 10 * day + 23 * 3_600_000L + 30 * 60_000L
        assertEquals(10L, localEpochDay(late, utc))
        assertEquals(11L, localEpochDay(late, TimeZone.getTimeZone("GMT+02:00")))
        assertEquals(-1L, localEpochDay(-1L, utc))
    }

    @Test fun otherSortsAreOneHeaderlessSection() {
        val sections = librarySections(listOf(e("a", day), e("b", 5 * day)), LibrarySort.NAME, utc)
        assertEquals(1, sections.size)
        assertNull(sections[0].epochDay)
        assertTrue(librarySections(emptyList(), LibrarySort.DATE, utc).isEmpty())
    }

    @Test fun justifiedRowsFillTheWidthExactlyExceptTheLast() {
        val frames = (0 until 7).map { e("f$it") }
        val aspects = listOf(1.5f, 0.67f, 1.5f, 1f, 1.78f, 0.75f, 1.5f)
        val rows = justifyRows(frames, { aspects[frames.indexOf(it)] }, width = 360f, targetHeight = 120f, gap = 3f)
        assertEquals(7, rows.sumOf { it.entries.size })
        rows.dropLast(1).forEach { row ->
            assertEquals(360f, row.widths.sum() + 3f * (row.entries.size - 1), 0.01f)
            assertTrue(row.height <= 120f)
        }
        assertTrue(rows.last().height <= 120f)
        rows.forEach { row -> row.entries.forEachIndexed { i, f -> assertEquals(aspects[frames.indexOf(f)] * row.height, row.widths[i], 0.01f) } }
    }

    @Test fun extremeAndUnknownAspectsAreTamed() {
        val frames = listOf(e("pano"), e("sliver"), e("nan"))
        val rows = justifyRows(frames, { mapOf("pano" to 9f, "sliver" to 0.1f, "nan" to Float.NaN)[it.name]!! }, 1000f, 100f, 0f)
        val all = rows.flatMap { r -> r.widths.map { it / r.height } }
        assertEquals(listOf(MAX_ROW_ASPECT, MIN_ROW_ASPECT, DEFAULT_FRAME_ASPECT), all.map { (it * 100).toInt() / 100f })
    }

    @Test fun squareRowsUseColumnsThatFit() {
        assertEquals(3, squareColumns(width = 360f, minCell = 104f, gap = 3f))
        assertEquals(1, squareColumns(width = 50f, minCell = 104f, gap = 3f))
        val rows = squareRows((0 until 7).map { e("s$it") }, 3, 360f, 3f)
        assertEquals(listOf(3, 3, 1), rows.map { it.entries.size })
        assertEquals(118f, rows[0].height, 0.01f)
        assertTrue(justifyRows(emptyList(), { 1f }, 100f, 10f, 0f).isEmpty())
    }
}
