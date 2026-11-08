/*
 * Spektrafilm for Android — shared-source working copies (#508). GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class SharedSourceCopiesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun nameKeepsTheSendersNameAndAddsTheMimeExtension() {
        assertEquals("7-244070.jpg", SharedSourceCopies.copyFileName("7", "244070", "jpg"))
        assertEquals("7-IMG_1.DNG", SharedSourceCopies.copyFileName("7", "IMG_1.DNG", "dng"))
        assertEquals("7-IMG_1.ARW", SharedSourceCopies.copyFileName("7", "IMG_1.ARW", null))
    }

    /** Codex on #512: sharing the same photo again must reach the same copy, and its recipe. */
    @Test fun copyNameIsStablePerOriginal() {
        val a = SharedSourceCopies.identityKey("content://media/external/images/media/244070")
        assertEquals(a, SharedSourceCopies.identityKey("content://media/external/images/media/244070"))
        assertFalse(a == SharedSourceCopies.identityKey("content://media/external/images/media/244071"))
        assertTrue(a.matches(Regex("[0-9a-f]{16}")))
    }

    @Test fun nameCannotEscapeTheDirectory() {
        val n = SharedSourceCopies.copyFileName("7", "../../shared_prefs/x.xml", null)
        assertFalse(n, n.contains('/') || n.contains(".."))
        assertEquals("7-shared", SharedSourceCopies.copyFileName("7", "...", null))
        assertEquals("7-a_b.jpg", SharedSourceCopies.copyFileName("7", "a b", "JPG"))
        assertEquals("7-x", SharedSourceCopies.copyFileName("7", "x", "not an ext!"))
    }

    @Test fun copyLandsWholeOrNotAtAll() {
        val dir = File(tmp.root, SharedSourceCopies.DIR)
        val ok = SharedSourceCopies.writeCopy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dir, File(dir, "a.jpg"))
        assertEquals(3L, ok.length())

        val broken = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 10) 1 else throw IOException("grant revoked")
        }
        runCatching { SharedSourceCopies.writeCopy(broken, dir, File(dir, "b.jpg")) }
        assertEquals(listOf("a.jpg"), dir.list()!!.sorted())
    }

    @Test fun pruneKeepsTheSourceAndCopiesInFlight() {
        val dir = File(tmp.root, SharedSourceCopies.DIR).apply { mkdirs() }
        listOf("1-a.jpg", "2-b.jpg", "3-c.jpg.partial-123", "4-d.jpg").forEach { File(dir, it).writeText("x") }
        // 2-b is the source, 3-c is still being written, 4-d finished but is not adopted yet.
        SharedSourceCopies.pruneDir(dir, setOf("2-b.jpg", "3-c.jpg", "4-d.jpg"))
        assertEquals(listOf("2-b.jpg", "3-c.jpg.partial-123", "4-d.jpg"), dir.list()!!.sorted())
        SharedSourceCopies.pruneDir(dir, emptySet())
        assertTrue(dir.list()!!.isEmpty())
    }

    /** Codex on #512: a Library frame is readable through its folder's tree grant, keep it. */
    @Test fun libraryFramesAndPersistedGrantsAreNotCopied() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
        val grants = setOf(tree, "content://media/picker/0/x/media/1")
        assertTrue(SharedSourceCopies.alreadyDurable("$tree/document/primary%3ADCIM%2Fa.dng", grants))
        assertTrue(SharedSourceCopies.alreadyDurable("content://media/picker/0/x/media/1", grants))
        assertFalse(SharedSourceCopies.alreadyDurable("content://media/external/images/media/244070", grants))
    }

    /** Codex on #512: a sender's name containing ".partial-" is not a writer's partial. */
    @Test fun aSendersPartialLikeNameIsStillKept() {
        val dir = File(tmp.root, SharedSourceCopies.DIR).apply { mkdirs() }
        listOf("k-photo.partial-1.jpg", "k-photo.partial-1.jpg.partial-99", "old.jpg.partial-5")
            .forEach { File(dir, it).writeText("x") }
        SharedSourceCopies.pruneDir(dir, setOf("k-photo.partial-1.jpg"))
        assertEquals(listOf("k-photo.partial-1.jpg", "k-photo.partial-1.jpg.partial-99"), dir.list()!!.sorted())
    }
}
