/*
 * Spektrafilm for Android — the export joins or cancels the in-flight pre-render (#270). GPLv3.
 */
package com.spectrafilm.app

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrerenderFlightTest {
    private fun flight() = PrerenderFlight(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun exportOfTheSameEditJoinsTheFlightAndSeesItsResult() = runBlocking {
        val f = flight()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stored = AtomicBoolean(false)
        val render = f.launch("edit-a") {
            started.complete(Unit)
            release.await()
            stored.set(true)
            true
        }
        withTimeout(5_000) { started.await() }
        // UNDISPATCHED: settle runs until it suspends in join() before the render is released,
        // so the render cannot finish (and stop being tracked) before settle has seen it.
        val settling = async(start = CoroutineStart.UNDISPATCHED) { f.settle("edit-a") }
        assertFalse("settle must wait for the render", settling.isCompleted)
        release.complete(Unit)
        assertTrue("the same key is joined", withTimeout(5_000) { settling.await() })
        assertTrue("the payload was stored before settle returned", stored.get())
        assertFalse(render.isCancelled)
        assertNull("a finished flight is no longer tracked", f.inFlightKey())
    }

    @Test
    fun exportOfAnotherEditCancelsTheFlightAndWaitsItOut() = runBlocking {
        val f = flight()
        val started = CompletableDeferred<Unit>()
        val render = f.launch("edit-a") {
            started.complete(Unit)
            awaitCancellation()
        }
        withTimeout(5_000) { started.await() }
        assertFalse("a different key is not joined", withTimeout(5_000) { f.settle("edit-b") })
        assertTrue(render.isCancelled)
        assertTrue("the flight is over before the export renders", render.isCompleted)
    }

    @Test
    fun aNullKeyCancelsAnyFlight() = runBlocking {
        val f = flight()
        val started = CompletableDeferred<Unit>()
        val render = f.launch("edit-a") {
            started.complete(Unit)
            awaitCancellation()
        }
        withTimeout(5_000) { started.await() }
        assertFalse(withTimeout(5_000) { f.settle(null) })
        assertTrue(render.isCancelled)
    }

    @Test
    fun aNewFlightCancelsTheOldOne() = runBlocking {
        val f = flight()
        val started = CompletableDeferred<Unit>()
        val first = f.launch("edit-a") {
            started.complete(Unit)
            awaitCancellation()
        }
        withTimeout(5_000) { started.await() }
        val second = f.launch("edit-b") { true }
        assertTrue(withTimeout(5_000) { second.await() })
        withTimeout(5_000) { first.join() }
        assertTrue(first.isCancelled)
    }

    @Test
    fun aCancelledExportCancelsTheFlightItWasJoining() = runBlocking {
        val f = flight()
        val started = CompletableDeferred<Unit>()
        val render = f.launch("edit-a") {
            started.complete(Unit)
            awaitCancellation()
        }
        withTimeout(5_000) { started.await() }
        val export = launch(start = CoroutineStart.UNDISPATCHED) { f.settle("edit-a") }
        assertFalse(render.isCancelled)
        withTimeout(5_000) { export.cancelAndJoin() }
        assertTrue("the joined render is cancelled with the export", render.isCancelled)
        assertTrue("and has finished its cleanup", render.isCompleted)
    }

    @Test
    fun settleWithNothingInFlightReturnsAtOnce() = runBlocking {
        assertEquals(false, flight().settle("edit-a"))
    }
}
