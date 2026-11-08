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
        assertEquals("7-244070.jpg", SharedSourceCopies.copyFileName(7, "244070", "jpg"))
        assertEquals("7-IMG_1.DNG", SharedSourceCopies.copyFileName(7, "IMG_1.DNG", "dng"))
        assertEquals("7-IMG_1.ARW", SharedSourceCopies.copyFileName(7, "IMG_1.ARW", null))
    }

    @Test fun nameCannotEscapeTheDirectory() {
        val n = SharedSourceCopies.copyFileName(7, "../../shared_prefs/x.xml", null)
        assertFalse(n, n.contains('/') || n.contains(".."))
        assertEquals("7-shared", SharedSourceCopies.copyFileName(7, "...", null))
        assertEquals("7-a_b.jpg", SharedSourceCopies.copyFileName(7, "a b", "JPG"))
        assertEquals("7-x", SharedSourceCopies.copyFileName(7, "x", "not an ext!"))
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

    @Test fun pruneKeepsOnlyTheCurrentSource() {
        val dir = File(tmp.root, SharedSourceCopies.DIR).apply { mkdirs() }
        listOf("1-a.jpg", "2-b.jpg", "3-c.jpg.partial").forEach { File(dir, it).writeText("x") }
        SharedSourceCopies.pruneDir(dir, "2-b.jpg")
        assertEquals(listOf("2-b.jpg"), dir.list()!!.toList())
        SharedSourceCopies.pruneDir(dir, null)
        assertTrue(dir.list()!!.isEmpty())
    }
}
