/*
 * Spektrafilm for Android -- Library sort, filter and search rules. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {
    private fun e(name: String, modified: Long, size: Long = 1L, edited: Boolean = false, mime: String? = null) =
        LibraryEntry("content://tree/$name", name, mime, modified, size, edited)

    private val roll = listOf(
        e("DSCF0002.RAF", modified = 20, size = 30_000_000, edited = true),
        e("beach.jpg", modified = 30, size = 4_000_000, mime = "image/jpeg"),
        e("DSCF0001.RAF", modified = 10, size = 31_000_000),
        e("Beach crop.heic", modified = 30, size = -1),
        e("street.dng", modified = 5, size = 25_000_000, edited = true, mime = "image/x-adobe-dng"),
    )

    private fun names(q: LibraryQuery) = applyLibraryQuery(roll, q).map { it.name }

    @Test fun defaultIsNewestFirstWithNameBreakingTies() {
        assertEquals(
            listOf("beach.jpg", "Beach crop.heic", "DSCF0002.RAF", "DSCF0001.RAF", "street.dng").sortedWith(
                compareByDescending<String> { n -> roll.first { it.name == n }.modifiedMs }.thenBy { it.lowercase() },
            ),
            names(LibraryQuery()),
        )
        assertEquals("street.dng", names(LibraryQuery()).last())
    }

    @Test fun oldestFirstReversesDateButNotTheTieBreak() {
        assertEquals(
            listOf("street.dng", "DSCF0001.RAF", "DSCF0002.RAF", "Beach crop.heic", "beach.jpg"),
            names(LibraryQuery(descending = false)),
        )
    }

    @Test fun nameSortIgnoresCase() {
        assertEquals(
            listOf("beach.jpg", "Beach crop.heic", "DSCF0001.RAF", "DSCF0002.RAF", "street.dng").sortedBy { it.lowercase() },
            names(LibraryQuery(sort = LibrarySort.NAME, descending = false)),
        )
        assertEquals("street.dng", names(LibraryQuery(sort = LibrarySort.NAME, descending = true)).first())
    }

    @Test fun unknownSizeSortsLastBothWays() {
        assertEquals("Beach crop.heic", names(LibraryQuery(sort = LibrarySort.SIZE, descending = true)).last())
        assertEquals("Beach crop.heic", names(LibraryQuery(sort = LibrarySort.SIZE, descending = false)).last())
        assertEquals("DSCF0001.RAF", names(LibraryQuery(sort = LibrarySort.SIZE, descending = true)).first())
        assertEquals("beach.jpg", names(LibraryQuery(sort = LibrarySort.SIZE, descending = false)).first())
    }

    @Test fun filtersSplitRawFromPhotosAndEditedFromNot() {
        assertEquals(setOf("DSCF0002.RAF", "DSCF0001.RAF", "street.dng"), names(LibraryQuery(filter = LibraryFilter.RAW)).toSet())
        assertEquals(setOf("beach.jpg", "Beach crop.heic"), names(LibraryQuery(filter = LibraryFilter.PHOTO)).toSet())
        assertEquals(setOf("DSCF0002.RAF", "street.dng"), names(LibraryQuery(filter = LibraryFilter.EDITED)).toSet())
        assertEquals(3, names(LibraryQuery(filter = LibraryFilter.NOT_EDITED)).size)
    }

    @Test fun searchNeedsEveryWordAndIgnoresCase() {
        assertEquals(setOf("beach.jpg", "Beach crop.heic"), names(LibraryQuery(text = "BEACH")).toSet())
        assertEquals(listOf("Beach crop.heic"), names(LibraryQuery(text = " crop  beach ")))
        assertEquals(listOf("DSCF0002.RAF"), names(LibraryQuery(text = "dscf", filter = LibraryFilter.EDITED)))
        assertTrue(names(LibraryQuery(text = "nothing")).isEmpty())
        assertEquals(5, names(LibraryQuery(text = "   ")).size)
    }

    @Test fun storedFormKeepsSortAndFilterButNeverTheSearch() {
        val q = LibraryQuery(LibrarySort.SIZE, descending = false, filter = LibraryFilter.EDITED, text = "beach", view = LibraryView.SQUARE)
        assertEquals(q.copy(text = ""), LibraryQuery.decode(q.encode()))
        assertEquals(LibraryQuery(), LibraryQuery.decode(null))
        assertEquals(LibraryQuery(), LibraryQuery.decode("garbage"))
        assertEquals(LibraryQuery(sort = LibrarySort.NAME), LibraryQuery.decode("NAME:desc:NOPE"))
    }

    @Test fun onlyFilterAndSearchNarrow() {
        assertFalse(LibraryQuery(sort = LibrarySort.NAME, descending = false).narrows)
        assertTrue(LibraryQuery(filter = LibraryFilter.RAW).narrows)
        assertTrue(LibraryQuery(text = "x").narrows)
    }
}

class LibrarySelectionRangeTest {
    private fun e(n: String) = LibraryEntry("u:$n", n, null, 1L, 1L)
    private val shown = listOf("a", "b", "c", "d", "e").map { e(it) }

    @org.junit.Test fun rangeWorksInEitherDirectionOnTopOfTheBase() {
        org.junit.Assert.assertEquals(setOf("u:x", "u:b", "u:c", "u:d"), selectionRange(shown, "u:b", "u:d", setOf("u:x")))
        org.junit.Assert.assertEquals(setOf("u:b", "u:c", "u:d"), selectionRange(shown, "u:d", "u:b", emptySet()))
        org.junit.Assert.assertEquals(setOf("u:c"), selectionRange(shown, "u:c", "u:c", emptySet()))
    }

    @org.junit.Test fun draggingBackShrinksBecauseTheRangeStartsFromTheBaseAgain() {
        val wide = selectionRange(shown, "u:a", "u:e", emptySet())
        org.junit.Assert.assertEquals(5, wide.size)
        org.junit.Assert.assertEquals(setOf("u:a", "u:b"), selectionRange(shown, "u:a", "u:b", emptySet()))
    }

    @org.junit.Test fun hiddenEndsLeaveTheBase() {
        org.junit.Assert.assertEquals(setOf("u:a"), selectionRange(shown, "u:gone", "u:b", setOf("u:a")))
    }
}

class LibraryShareMimeTest {
    @org.junit.Test fun oneTypeFamilyOrAnything() {
        org.junit.Assert.assertEquals("image/jpeg", shareMimeType(listOf("image/jpeg", "IMAGE/JPEG")))
        org.junit.Assert.assertEquals("image/*", shareMimeType(listOf("image/jpeg", "image/x-adobe-dng")))
        org.junit.Assert.assertEquals("*/*", shareMimeType(listOf("image/jpeg", null)))
        org.junit.Assert.assertEquals("*/*", shareMimeType(listOf("image/jpeg", "application/octet-stream")))
        org.junit.Assert.assertEquals("*/*", shareMimeType(emptyList()))
    }
}
