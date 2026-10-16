package com.spectrafilm.app

import com.spectrafilm.libraw.DecodeStatus
import com.spectrafilm.libraw.RawDecodeException
import org.junit.Assert.*
import org.junit.Test

class RawDecodeMemoryFallbackTest {
    @Test fun nativeMemoryRejectionRetriesWithReducedOutput() {
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val result = decodeRawWithMemoryFallback(16384) { edge, half ->
            attempts += edge to half
            if (!half) throw RawDecodeException("geometry budget", DecodeStatus.NO_MEMORY.code, 0)
            "decoded RAW"
        }
        assertEquals("decoded RAW", result)
        assertEquals(listOf(16384 to false, 16384 to true), attempts)
    }

    @Test fun nativeRejectionStopsAfterOneReducedAttempt() {
        val error = RawDecodeException("raw store budget", DecodeStatus.NO_MEMORY.code, 0)
        var calls = 0
        try {
            decodeRawWithMemoryFallback<Unit>(16384) { _, _ -> calls++; throw error }
            fail("expected failure")
        } catch (actual: RawDecodeException) { assertSame(error, actual) }
        assertEquals(2, calls)
    }

    @Test fun previewDoesNotRetryAnAlreadyReducedNativeFailure() {
        var calls = 0
        try {
            decodeRawWithMemoryFallback<Unit>(2048) { _, half ->
                calls++; assertTrue(half)
                throw RawDecodeException("budget", DecodeStatus.NO_MEMORY.code, 0)
            }
            fail("expected failure")
        } catch (actual: RawDecodeException) { assertEquals(DecodeStatus.NO_MEMORY, actual.status) }
        assertEquals(1, calls)
    }

    @Test fun corruptUnsupportedAndCancelledInputsAreNotRetried() {
        for (status in listOf(DecodeStatus.INPUT, DecodeStatus.FILE_UNSUPPORTED, DecodeStatus.CANCELLED)) {
            val error = RawDecodeException("failure", status.code, 0)
            var calls = 0
            try {
                decodeRawWithMemoryFallback<Unit>(16384) { _, _ -> calls++; throw error }
                fail("expected failure")
            } catch (actual: RawDecodeException) { assertSame(error, actual) }
            assertEquals(1, calls)
        }
    }

    @Test fun managedAllocationFailureStillUsesReducedRetry() {
        val attempts = mutableListOf<Boolean>()
        val result = decodeRawWithMemoryFallback(16384) { _, half ->
            attempts += half
            if (!half) throw OutOfMemoryError("test allocation")
            42
        }
        assertEquals(42, result)
        assertEquals(listOf(false, true), attempts)
    }
}
