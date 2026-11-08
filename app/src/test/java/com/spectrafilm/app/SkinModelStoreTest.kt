/*
 * Spektrafilm for Android — unit tests for the skin model download. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The pinned digest is the integrity gate: only the exact bytes are ever kept, nothing partial or
 * wrong survives under the final name, redirects are not followed, and a verified file is reused
 * without touching the network.
 */
package com.spectrafilm.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinModelStoreTest {

    private val bytes = ByteArray(1000) { (it * 7).toByte() }
    private val dir: File = Files.createTempDirectory("skinmodel").toFile()
    private val pin = SkinModelStore.Pin(
        url = "https://example.invalid/m/1/model.tflite",
        fileName = "model.tflite",
        sizeBytes = bytes.size.toLong(),
        sha256 = File.createTempFile("skinmodel", ".bin").let { f ->
            f.writeBytes(bytes)
            SkinModelStore.sha256(f).also { f.delete() }
        },
    )
    private val warnings = mutableListOf<String>()
    private val requested = mutableListOf<URI>()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun store(code: Int = 200, body: () -> ByteArray = { bytes }, length: Long = -1L) =
        SkinModelStore(dir, Dispatchers.IO, pin, { warnings += it }) { uri ->
            requested += uri
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = code
                override fun getContentLengthLong() = length
                override fun getHeaderField(name: String?): String? =
                    if (name == "Location") "https://evil.invalid/model.tflite" else null
                override fun getInputStream(): InputStream = ByteArrayInputStream(body())
            }
        }

    private fun leftovers() = dir.listFiles()?.map { it.name }.orEmpty()

    @Test
    fun downloadsVerifiesAndReusesWithoutTheNetwork() = runBlocking {
        val s = store()
        assertNull(s.cached())
        assertNotNull(s.ensure())
        val file = s.ensure()!!
        assertTrue(bytes.contentEquals(file.readBytes()))
        assertEquals(listOf(URI(pin.url)), requested)
        assertEquals(listOf("model.tflite"), leftovers())
        // A fresh store (next app start) verifies the file on disk instead of downloading again.
        assertNotNull(store().cached())
        assertEquals(1, requested.size)
    }

    @Test
    fun wrongBytesAreNeverKept() = runBlocking {
        val tampered = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(store(body = { tampered }).ensure())
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(listOf("skin model digest mismatch"), warnings)
    }

    @Test
    fun wrongLengthIsRejected() = runBlocking {
        assertNull(store(body = { bytes + 1 }).ensure())
        assertNull(store(body = { bytes.copyOf(999) }).ensure())
        assertNull(store(length = 5).ensure())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        assertNull(store(code = 302).ensure())
        assertEquals(listOf(URI(pin.url)), requested)
    }

    @Test
    fun aDamagedFileOnDiskIsReplaced() = runBlocking {
        dir.mkdirs()
        File(dir, pin.fileName).writeBytes(ByteArray(bytes.size))
        val s = store()
        assertNull(s.cached())
        assertEquals(emptyList<String>(), leftovers())
        assertTrue(bytes.contentEquals(s.ensure()!!.readBytes()))
    }

    @Test
    fun clearRemovesTheModel() = runBlocking {
        val s = store()
        assertNotNull(s.ensure())
        s.clear()
        assertNull(s.cached())
        assertEquals(emptyList<String>(), leftovers())
    }
}
