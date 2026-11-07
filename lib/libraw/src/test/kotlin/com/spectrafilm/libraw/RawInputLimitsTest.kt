package com.spectrafilm.libraw

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawInputLimitsTest {

    @Test
    fun `bounded stream accepts exactly the configured byte ceiling`() {
        val input = ByteArray(8) { it.toByte() }

        val actual = RawInputLimits.readBounded(ByteArrayInputStream(input), 8)

        assertArrayEquals(input, actual)
    }

    @Test
    fun `bounded stream observes one extra byte then rejects`() {
        val error = assertThrows(RawDecodeException::class.java) {
            RawInputLimits.readBounded(ByteArrayInputStream(ByteArray(9)), 8)
        }

        assertEquals(DecodeStatus.INPUT, error.status)
        assertEquals(0, error.librawCode)
    }

    @Test
    fun `declared byte container is rejected before native allocation`() {
        val error = assertThrows(RawDecodeException::class.java) {
            RawInputLimits.requireWithinLimit(9, 8)
        }

        assertEquals(DecodeStatus.INPUT, error.status)
    }

    @Test
    fun `bounded stream observes cancellation between reads with typed status`() {
        var checks = 0

        val error = assertThrows(RawDecodeException::class.java) {
            RawInputLimits.readBounded(
                ByteArrayInputStream(ByteArray(128 * 1024)),
                isCancelled = { ++checks >= 2 },
            )
        }

        assertEquals(DecodeStatus.CANCELLED, error.status)
        assertEquals(0, error.librawCode)
    }

    @Test
    fun `only unknown or over-ceiling sources take the on-disk snapshot`() {
        val limit = RawInputLimits.MAX_ENCODED_BYTES.toLong()
        assertEquals(true, RawInputLimits.needsInputSnapshot(-1L))
        assertEquals(false, RawInputLimits.needsInputSnapshot(0L))
        assertEquals(false, RawInputLimits.needsInputSnapshot(limit))
        assertEquals(true, RawInputLimits.needsInputSnapshot(limit + 1L))
        assertEquals(true, RawInputLimits.needsInputSnapshot(600L * 1024L * 1024L))
    }
}
