/*
 * Spektrafilm for Android — the in-flight idle pre-render, joinable by an export (#270). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The idle pre-render (#179) used to run inside the editor's LaunchedEffect, which an export
 * start cancels so the two never compete for the same cores. But a pre-render that is already
 * most of the way through rendering THIS edit is exactly the work the export is about to
 * redo: an SM-S948W trace showed the export tap throwing a nearly finished 12 MP render away
 * and starting the decode and engine from scratch.
 *
 * So the render now runs as a job this object owns, detached from the effect. The effect
 * cancels it on an ordinary key change (an edit, a new photo), as before. When an export starts
 * it leaves the job alone, and the export settles it: the same edit is JOINED, and the export
 * then reads the stored payload; any other edit is cancelled, as before, so the two still never
 * render at the same time.
 */
package com.spectrafilm.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

internal class PrerenderFlight(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ProcessDispatchers.default),
) {
    private class Flight(val key: String, val deferred: Deferred<Boolean>)

    private val lock = Any()
    private var current: Flight? = null

    /**
     * Starts [render] for [key] as the one tracked flight, cancelling any earlier one. The
     * result is true when the payload was stored. The caller awaits it, and on its own
     * cancellation decides whether the flight lives on for an export to join.
     */
    fun launch(key: String, render: suspend () -> Boolean): Deferred<Boolean> {
        val deferred = scope.async(start = CoroutineStart.LAZY) { render() }
        val previous = synchronized(lock) {
            val old = current
            current = Flight(key, deferred)
            old
        }
        previous?.deferred?.cancel()
        deferred.invokeOnCompletion {
            synchronized(lock) { if (current?.deferred === deferred) current = null }
        }
        deferred.start()
        return deferred
    }

    /**
     * Called by an export before it reads the payload store. A flight for [key] is joined, so
     * the payload it is writing is there to read; any other flight (or every flight, for a null
     * key) is cancelled and waited out, so the export never renders alongside it. Returns true
     * when a flight for [key] was joined. A cancelled export cancels the flight it was joining.
     */
    suspend fun settle(key: String?): Boolean {
        val flight = synchronized(lock) { current } ?: return false
        val joining = key != null && flight.key == key
        if (!joining) flight.deferred.cancel()
        try {
            flight.deferred.join()
        } catch (cancelled: CancellationException) {
            // The export was cancelled while joining. The editor effect has already left this
            // flight to it, so nothing else would stop the render: cancel it here and wait for
            // its cleanup, so a cancelled export leaves no full-resolution render behind.
            flight.deferred.cancel()
            withContext(NonCancellable) { flight.deferred.join() }
            throw cancelled
        }
        return joining
    }

    /** The key in flight, for tests and diagnostics. */
    fun inFlightKey(): String? = synchronized(lock) { current?.key }
}
