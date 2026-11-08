package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure album rules ([AlbumOps]) and the albums document ([AlbumCodec]). */
class AlbumsTest {
    private fun item(n: Int, tree: String? = "content://tree/a", modified: Long = n.toLong()) =
        AlbumItem("content://doc/$n", "f$n.jpg", "image/jpeg", modified, 10L, tree)

    private fun albums(vararg items: List<AlbumItem>) =
        items.mapIndexed { i, list -> Album("id$i", "Album ${i + 1}", 0L, list) }

    @Test fun namesAreTrimmedSingleLineAndBounded() {
        assertEquals("Summer trip", AlbumOps.cleanName("  Summer \n\t trip  "))
        assertEquals("", AlbumOps.cleanName("   "))
        assertEquals(AlbumOps.MAX_NAME_CHARS, AlbumOps.cleanName("x".repeat(200)).length)
    }

    @Test fun renameRefusesABlankName() {
        val before = albums(emptyList())
        assertSame(before, AlbumOps.rename(before, "id0", "  "))
        assertEquals("Roll", AlbumOps.rename(before, "id0", " Roll ")[0].name)
    }

    @Test fun addSkipsFramesAlreadyThereAndKeepsOrder() {
        val before = albums(listOf(item(1), item(2)))
        val after = AlbumOps.add(before, "id0", listOf(item(2), item(3), item(3), item(4)))
        assertEquals(listOf(1, 2, 3, 4).map { "content://doc/$it" }, after[0].items.map { it.uri })
    }

    @Test fun addStopsAtTheTotalBudget() {
        val big = (0 until AlbumOps.MAX_TOTAL_ITEMS - 1).map { item(it) }
        val before = albums(big.take(AlbumOps.MAX_ITEMS), big.drop(AlbumOps.MAX_ITEMS), emptyList())
        val after = AlbumOps.add(before, "id2", listOf(item(-1), item(-2), item(-3)))
        assertEquals(1, after[2].items.size)
        assertEquals(0, AlbumOps.spareItems(after))
    }

    @Test fun removingTheCoverFallsBackToTheNewestFrame() {
        val before = AlbumOps.setCover(albums(listOf(item(1), item(5), item(3))), "id0", "content://doc/1")
        assertEquals("content://doc/1", before[0].cover?.uri)
        val after = AlbumOps.remove(before, "id0", setOf("content://doc/1"))
        assertNull(after[0].coverUri)
        assertEquals("content://doc/5", after[0].cover?.uri)
    }

    @Test fun coverMustBeInTheAlbum() {
        val before = albums(listOf(item(1)))
        assertNull(AlbumOps.setCover(before, "id0", "content://doc/9")[0].coverUri)
    }

    @Test fun undoPutsADeletedAlbumBackInPlace() {
        val before = albums(emptyList(), listOf(item(1)), emptyList())
        val deleted = before[1]
        val after = AlbumOps.restore(AlbumOps.delete(before, "id1"), deleted, 1)
        assertEquals(before, after)
        assertSame(after, AlbumOps.restore(after, deleted, 1))
    }

    @Test fun retainedGrantsAreEachItemsFolderOrTheItemItself() {
        val list = albums(listOf(item(1), item(2, tree = null)), listOf(item(3, tree = "content://tree/b")))
        assertEquals(
            setOf("content://tree/a", "content://doc/2", "content://tree/b"),
            AlbumOps.retainedUris(list),
        )
    }

    @Test fun defaultNameTakesTheFirstFreeNumber() {
        val list = listOf(Album("a", "Album 2", 0L), Album("b", "Album 3", 0L))
        assertEquals("Album 4", AlbumOps.defaultName(list, "Album"))
        assertEquals("Album 1", AlbumOps.defaultName(emptyList(), "Album"))
    }

    @Test fun ensureCreatesTheImportedAlbumOnce() {
        val once = AlbumOps.ensure(emptyList(), AlbumOps.IMPORTED_ID, "Imported", 5L)
        assertEquals(listOf(Album(AlbumOps.IMPORTED_ID, "Imported", 5L)), once)
        assertSame(once, AlbumOps.ensure(once, AlbumOps.IMPORTED_ID, "Other", 6L))
    }

    @Test fun codecRoundTrips() {
        val list = listOf(
            Album("a", "Trip", 42L, listOf(item(1), item(2, tree = null)), coverUri = "content://doc/2"),
            Album("b", "Empty", 43L),
        )
        assertEquals(list, AlbumCodec.decode(AlbumCodec.encode(list)))
    }

    @Test fun codecSkipsBadItemsAndDuplicateAlbums() {
        val text = """
            {"version":1,"albums":[
              {"id":"a","name":"  A  ","created":1,"items":[
                {"uri":"file:///sdcard/x.jpg","name":"x"},
                {"uri":"content://doc/1","name":"one","modified":5,"size":7},
                {"uri":"content://doc/1","name":"dup"}
              ]},
              {"id":"a","name":"again"},
              {"name":"no id"}
            ]}
        """.trimIndent()
        val list = AlbumCodec.decode(text)
        assertEquals(1, list.size)
        assertEquals("A", list[0].name)
        assertEquals(listOf("content://doc/1"), list[0].items.map { it.uri })
        assertNull(list[0].items[0].mimeType)
        assertNull(list[0].items[0].treeUri)
    }

    @Test fun codecRejectsAnUnknownVersion() {
        val failure = runCatching { AlbumCodec.decode("""{"version":2,"albums":[]}""") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun aFullBudgetDocumentFitsTheReadLimits() {
        val long = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera/document/primary%3ADCIM%2FCamera%2F"
        val items = (0 until AlbumOps.MAX_TOTAL_ITEMS).map {
            AlbumItem("$long$it.dng", "PXL_20261108_${it}_RAW-01.MP.COVER.dng", "image/x-adobe-dng", 1L, 30_000_000L, long.substringBefore("/document"))
        }
        val list = items.chunked(AlbumOps.MAX_ITEMS).mapIndexed { i, chunk -> Album("id$i", "A$i", 0L, chunk) }
        val text = AlbumCodec.encode(list)
        assertTrue(text.length < AlbumCodec.MAX_BYTES)
        assertEquals(AlbumOps.MAX_TOTAL_ITEMS, AlbumCodec.decode(text).sumOf { it.items.size })
    }
}
