/*
 * Spektrafilm for Android -- Library folder listing rules. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun row(name: String, mime: String? = null, modified: Long = 0L) =
        LibraryEntry("content://tree/$name", name, mime, modified, 1L)

    @Test fun keepsStillsAndCameraRawOnly() {
        val kept = libraryEntriesOf(
            listOf(
                row("a.dng", "image/x-adobe-dng"),
                row("b.CR3"),
                row("c.jpg", "image/jpeg"),
                row("d.heic"),
                row("e.mp4", "video/mp4"),
                row("f.mcraw"),
                row("g.xmp", "application/rdf+xml"),
                row("notes.txt", "text/plain"),
                row("Subfolder", "vnd.android.document/directory"),
                row(".thumbnails"),
            ),
        ).map { it.name }.toSet()
        assertEquals(setOf("a.dng", "b.CR3", "c.jpg", "d.heic"), kept)
    }

    @Test fun newestFirstThenByName() {
        val order = libraryEntriesOf(
            listOf(
                row("old.dng", modified = 10),
                row("B.jpg", modified = 20),
                row("a.jpg", modified = 20),
                row("new.arw", modified = 30),
            ),
        ).map { it.name }
        assertEquals(listOf("new.arw", "a.jpg", "B.jpg", "old.dng"), order)
    }

    @Test fun rawBadgeFollowsTheEditorsRouting() {
        assertTrue(row("x.nef").isRaw)
        assertTrue(row("x.dng", "image/tiff").isRaw)
        assertFalse(row("x.jpg", "image/jpeg").isRaw)
    }
}
