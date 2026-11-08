/*
 * Spektrafilm for Android — on-disk preview proxy cache (#288). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import com.spectrafilm.libraw.WhiteBalance
import java.io.File
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyDiskCacheTest {

    private fun request(
        temperature: Float = 5500f,
        rotation: Int = 0,
        maxEdge: Int = 1024,
        film: String = "",
    ) = DecodedSourceCache.Request(
        uri = "content://media/1",
        kind = "RAW",
        authorizationRequired = false,
        whiteBalance = WhiteBalance.values().first(),
        temperature = temperature,
        tint = 0f,
        creativeTemp = 0f,
        creativeTint = 0f,
        filmBalance = film,
        rotationDegrees = rotation,
        maxEdge = maxEdge,
    )

    private val source = "size=1;mtime=2;sample=ab"
    private val app = "app=13@100"

    @Test
    fun sameInputsGiveTheSameKey() {
        assertEquals(
            ProxyDiskCache.cacheKey(request(), source, app),
            ProxyDiskCache.cacheKey(request(), source, app),
        )
    }

    @Test
    fun everyDecodeAffectingInputChangesTheKey() {
        val base = ProxyDiskCache.cacheKey(request(), source, app)
        assertNotEquals(base, ProxyDiskCache.cacheKey(request(temperature = 5501f), source, app))
        assertNotEquals(base, ProxyDiskCache.cacheKey(request(rotation = 90), source, app))
        assertNotEquals(base, ProxyDiskCache.cacheKey(request(maxEdge = 640), source, app))
        assertNotEquals(base, ProxyDiskCache.cacheKey(request(film = "kodak_portra_400"), source, app))
        // Same request, different source bytes or a reinstalled app: never the old decode.
        assertNotEquals(base, ProxyDiskCache.cacheKey(request(), "size=1;mtime=3;sample=ab", app))
        assertNotEquals(base, ProxyDiskCache.cacheKey(request(), source, "app=13@101"))
        // -0f and 0f are different raw bits; the key must not merge distinct float inputs.
        assertNotEquals(
            ProxyDiskCache.cacheKey(request(temperature = 0f), source, app),
            ProxyDiskCache.cacheKey(request(temperature = -0f), source, app),
        )
    }

    @Test
    fun straightenChangesTheKeyOnlyWhenNonZero() {
        val base = ProxyDiskCache.cacheKey(request(), source, app)
        assertEquals(base, ProxyDiskCache.cacheKey(request().copy(straightenDegrees = 0f), source, app))
        val tilted = ProxyDiskCache.cacheKey(request().copy(straightenDegrees = 2.5f), source, app)
        assertNotEquals(base, tilted)
        assertNotEquals(tilted, ProxyDiskCache.cacheKey(request().copy(straightenDegrees = -2.5f), source, app))
    }

    @Test
    fun rawCleanupChangesTheKeyOnlyWhenOn() {
        val base = ProxyDiskCache.cacheKey(request(), source, app)
        // Off is the default spelling: a pre-#259 proxy keeps its key and stays valid.
        assertEquals(
            base,
            ProxyDiskCache.cacheKey(request().copy(hotPixels = false, chromaNoise = 0f), source, app),
        )
        val hot = ProxyDiskCache.cacheKey(request().copy(hotPixels = true), source, app)
        val cnr = ProxyDiskCache.cacheKey(request().copy(chromaNoise = 40f), source, app)
        assertNotEquals(base, hot)
        assertNotEquals(base, cnr)
        assertNotEquals(hot, cnr)
        assertNotEquals(cnr, ProxyDiskCache.cacheKey(request().copy(chromaNoise = 41f), source, app))
    }

    @Test
    fun headerRoundTripsAndPayloadSizeFollowsDimensions() {
        val header = ProxyHeader(640, 480, "ACES2065-1", usedPlatformFallback = true)
        val decoded = ProxyHeader.decode(header.encode())
        assertEquals(header, decoded)
        assertEquals(640L * 480 * 3 * 4, header.payloadBytes())
        val fixed = header.encode()
        fixed.limit(ProxyHeader.FIXED_BYTES)
        assertEquals("ACES2065-1".length, ProxyHeader.colorSpaceLength(fixed))
    }

    @Test
    fun foreignOrDamagedHeadersAreRejected() {
        fun good() = ProxyHeader(4, 4, "ProPhoto RGB", false).encode()
        // Wrong magic.
        val badMagic = good().also { it.put(0, 'X'.code.toByte()) }
        assertNull(ProxyHeader.decode(badMagic))
        // Future format version.
        val future = good().order(ByteOrder.LITTLE_ENDIAN)
        future.putInt(8, ProxyDiskCache.FORMAT_VERSION + 1)
        assertNull(ProxyHeader.decode(future))
        // Truncated colour-space name.
        val truncated = good().also { it.limit(it.limit() - 1) }
        assertNull(ProxyHeader.decode(truncated))
        // Zero width.
        val zero = good().order(ByteOrder.LITTLE_ENDIAN)
        zero.putInt(12, 0)
        assertNull(ProxyHeader.decode(zero))
    }

    @Test
    fun trimDropsLeastRecentlyUsedUntilItFits() {
        val dir = Files.createTempDirectory("proxy-cache").toFile()
        try {
            val now = System.currentTimeMillis()
            fun entry(name: String, bytes: Int, age: Long): File =
                File(dir, "$name.bin").apply {
                    writeBytes(ByteArray(bytes))
                    setLastModified(now - age)
                }
            val oldest = entry("a", 400, 30_000)
            val middle = entry("b", 400, 20_000)
            val newest = entry("c", 400, 10_000)
            val staleTemp = File(dir, "d.1.1.part").apply {
                writeBytes(ByteArray(10))
                setLastModified(now - 120_000)
            }
            ProxyDiskCache.trim(dir, maxBytes = 800)
            assertFalse(oldest.exists())
            assertTrue(middle.exists())
            assertTrue(newest.exists())
            assertFalse(staleTemp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
