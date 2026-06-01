/*
 * Spektrafilm for Android — unit tests for the .mcraw container parser. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Builds synthetic .mcraw byte layouts (little-endian, matching the reverse-
 * engineered CONTAINER_VERSION 3 format in docs/RESEARCH_MCRAW.md) and asserts the
 * pure-Kotlin pre-flight parser reads the header, footer index, and frame timestamps,
 * and rejects malformed input.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class McrawContainerTest {

    /**
     * Build a minimal valid container:
     *   [0,8)   Header (ident "MOTION " + version 3)
     *   [8,16)  camera METADATA Item (type=3, size=0) — skipped by the parser
     *   [16,16+16*N)  BufferOffset[] index data        — indexDataOffset = 16
     *   footer  Item{type=BUFFER_INDEX(0), size=16} + BufferIndex{magic, N, 16}
     */
    private fun buildContainer(frames: List<McrawFrame>): ByteArray {
        val n = frames.size
        val headerSize = 8
        val metaSize = 8
        val indexDataOffset = (headerSize + metaSize).toLong()
        val indexBytes = n * 16
        val total = headerSize + metaSize + indexBytes + 8 /*Item*/ + 16 /*BufferIndex*/
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        bb.put(byteArrayOf(0x4D, 0x4F, 0x54, 0x49, 0x4F, 0x4E, 0x20)) // "MOTION "
        bb.put(3.toByte())
        // camera METADATA item (type=3 METADATA, size=0)
        bb.putInt(3); bb.putInt(0)
        // index data: BufferOffset { int64 offset; int64 timestamp }
        for (f in frames) { bb.putLong(f.offset); bb.putLong(f.timestampNs) }
        // footer Item { type=BUFFER_INDEX(0), size=16 }
        bb.putInt(0); bb.putInt(16)
        // BufferIndex { magic, numOffsets, indexDataOffset }
        bb.putInt(0x8A905612.toInt()); bb.putInt(n); bb.putLong(indexDataOffset)
        return bb.array()
    }

    @Test fun parsesFrameIndex() {
        val expected = listOf(McrawFrame(1000L, 16L), McrawFrame(2000L, 99L))
        val frames = McrawContainer.parse(buildContainer(expected))
        assertEquals(2, frames.size)
        assertEquals(1000L, frames[0].timestampNs)
        assertEquals(16L, frames[0].offset)
        assertEquals(2000L, frames[1].timestampNs)
    }

    @Test fun isMcrawAcceptsValid() {
        assertTrue(McrawContainer.isMcraw(buildContainer(listOf(McrawFrame(1L, 16L)))))
    }

    @Test fun rejectsBadMagic() {
        val data = buildContainer(listOf(McrawFrame(1L, 16L)))
        data[0] = 'X'.code.toByte()
        assertFalse(McrawContainer.isMcraw(data))
        assertThrows(McrawParseException::class.java) { McrawContainer.parse(data) }
    }

    @Test fun rejectsBadVersion() {
        val data = buildContainer(listOf(McrawFrame(1L, 16L)))
        data[7] = 99 // version byte
        assertThrows(McrawParseException::class.java) { McrawContainer.parse(data) }
    }

    @Test fun rejectsTruncated() {
        assertThrows(McrawParseException::class.java) { McrawContainer.parse(ByteArray(4)) }
    }

    @Test fun extensionCheck() {
        assertTrue(McrawContainer.isMcrawFileName("clip.mcraw"))
        assertTrue(McrawContainer.isMcrawFileName("CLIP.MCRAW"))
        assertFalse(McrawContainer.isMcrawFileName("photo.dng"))
    }
}
