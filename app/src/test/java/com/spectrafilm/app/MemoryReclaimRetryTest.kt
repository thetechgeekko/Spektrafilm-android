package com.spectrafilm.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** #269: one reclaim and one retry for an out-of-memory render, never more, never otherwise. */
class MemoryReclaimRetryTest {
    private var reclaims = 0
    private val logs = mutableListOf<String>()

    private fun <T> run(block: suspend () -> T): T = runBlocking {
        retryOnceAfterMemoryReclaim(
            reason = "export render",
            reclaim = { reclaims++ },
            log = { logs += it },
            snapshot = { "{\"limit_bytes\":1}" },
            block = block,
        )
    }

    @Test
    fun `success runs once and never reclaims`() {
        var calls = 0
        assertEquals(7, run { calls++; 7 })
        assertEquals(1, calls)
        assertEquals(0, reclaims)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `native allocation failure reclaims then retries once`() {
        var calls = 0
        val result = run {
            calls++
            if (calls == 1) throw RuntimeException("spektra: native allocation failed")
            42
        }
        assertEquals(42, result)
        assertEquals(2, calls)
        assertEquals(1, reclaims)
        assertTrue(logs.single().contains("budget={\"limit_bytes\":1}"))
    }

    @Test
    fun `second out of memory is thrown with the first suppressed`() {
        val first = OutOfMemoryError("first")
        val second = OutOfMemoryError("second")
        var calls = 0
        val thrown = assertThrows(OutOfMemoryError::class.java) {
            run<Int> { calls++; throw if (calls == 1) first else second }
        }
        assertSame(second, thrown)
        assertSame(first, thrown.suppressed.single())
        assertEquals(2, calls)
        assertEquals(1, reclaims)
        assertEquals(2, logs.size)
    }

    @Test
    fun `other failures and cancellation are not retried`() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            run<Int> { calls++; error("bad params") }
        }
        assertThrows(CancellationException::class.java) {
            run<Int> { calls++; throw CancellationException("user") }
        }
        assertEquals(2, calls)
        assertEquals(0, reclaims)
    }

    @Test
    fun `memory exhaustion classification`() {
        assertTrue(isMemoryExhaustion(OutOfMemoryError()))
        assertTrue(isMemoryExhaustion(RuntimeException("spektra: native allocation failed")))
        assertTrue(!isMemoryExhaustion(RuntimeException("decode failed")))
        assertTrue(!isMemoryExhaustion(IllegalStateException()))
    }
}
