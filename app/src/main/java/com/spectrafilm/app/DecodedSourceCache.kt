/*
 * Spektrafilm for Android — decoded-source cache. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Owned-resource helpers, SingleFlight and the in-memory decoded-source cache. Split out of
 * EngineHelpers.kt (#273).
 */
package com.spectrafilm.app

import com.spectrafilm.engine.LinearImage
import com.spectrafilm.libraw.WhiteBalance
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Dispose an already-created owned value if any later construction/fill step throws. */
internal inline fun <T, R> disposeOnFailure(
    owned: T,
    dispose: (T) -> Unit,
    block: (T) -> R,
): R = try {
    block(owned)
} catch (failure: Throwable) {
    try {
        dispose(owned)
    } catch (disposeFailure: Throwable) {
        runCatching { failure.addSuppressed(disposeFailure) }
    }
    throw failure
}

/**
 * Run a resource-producing block on [context] without leaking its result in
 * [withContext]'s prompt-cancellation return window.
 *
 * A dispatcher switch can finish [block], then observe caller cancellation before the value is
 * delivered. Ordinary `withContext` discards that value. This helper keeps the produced value in
 * an atomic handoff slot until delivery succeeds; any exceptional/cancelled exit consumes the slot
 * through [dispose]. After a normal return, ownership belongs exclusively to the caller.
 */
internal suspend fun <T : Any> withOwnedContext(
    context: CoroutineContext,
    dispose: (T) -> Unit,
    block: suspend () -> T,
): T {
    val pending = AtomicReference<T?>()
    try {
        val result = withContext(context) {
            block().also { produced ->
                check(pending.compareAndSet(null, produced)) {
                    "owned context produced more than one resource"
                }
            }
        }
        check(pending.compareAndSet(result, null)) {
            "owned context resource handoff was already consumed"
        }
        return result
    } finally {
        pending.getAndSet(null)?.let(dispose)
    }
}

/**
 * Coalesces concurrent or rapidly-cancelled decodes of the SAME source key into ONE in-flight
 * decode, run in a caller-supplied STABLE [CoroutineScope]. The zoom ROI render + 100% magnifier
 * re-fire on every gesture settle and are cancelled+restarted, but a native LibRaw decode does NOT
 * stop on coroutine cancellation — so without this, one pinch could spawn several overlapping
 * full-resolution decodes (the measured battery drain). Callers await the shared [Deferred]: a
 * cancelled caller drops only its await; the decode keeps running in the stable scope and the next
 * caller awaits the same one (or, by then, hits the now-warm cache). Not for different keys — those
 * just start their own flight (a source/WB/rotation change, never a per-gesture event).
 */
internal class SingleFlight<T> {
    private val lock = Any()
    private var key: Any? = null
    private var inflight: Deferred<Result<T>>? = null

    suspend fun run(key: Any, scope: CoroutineScope, block: suspend () -> T): T {
        var superseded: Deferred<Result<T>>? = null
        var created: Deferred<Result<T>>? = null
        val deferred = synchronized(lock) {
            val cur = inflight
            if (this.key == key && cur != null && cur.isActive) {
                cur
            } else {
                if (cur?.isActive == true) superseded = cur
                // The result is captured so a FAILURE reaches the AWAITERS and nothing else.
                // `scope` is the editor's rememberCoroutineScope, whose Job is a plain Job,
                // not a SupervisorJob: an exception thrown out of async propagates into that
                // shared Job and cancels every other coroutine it owns. A RAW file the
                // decoder legitimately refuses therefore took down the whole editor scope,
                // and because the awaiting effect was cancelled rather than resumed with the
                // error, nothing was ever reported — the preview just never arrived.
                // Cancellation is deliberately NOT captured: a superseded flight must stay
                // cancelled so its waiters observe cancellation rather than a failure.
                scope.async {
                    try {
                        Result.success(block())
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }
                }.also {
                    this.key = key
                    inflight = it
                    created = it
                }
            }
        }
        // Cleanup belongs to the stable-scope operation, not to an arbitrary waiter. A caller can
        // be cancelled while the detached decode legitimately continues; its finally block then
        // runs before completion and cannot clear the retained Deferred. invokeOnCompletion also
        // runs immediately when an Unconfined/fast block completed before registration.
        created?.let { owned ->
            owned.invokeOnCompletion {
                synchronized(lock) {
                    if (inflight === owned) {
                        inflight = null
                        this.key = null
                    }
                }
            }
        }
        // A different request cannot publish useful work. Cancellation is cooperative: a native
        // decoder that is already inside a non-cancellable call may finish, but its stale ticket is
        // rejected by DecodedSourceCache.publish and the result is closed there.
        superseded?.cancel(CancellationException("single-flight request superseded"))
        return try {
            deferred.await().getOrThrow()
        } finally {
            // A completed Deferred retains its coroutine closure and captured request graph. Drop
            // both it and the key promptly; same-key waiters already hold their own local reference.
            synchronized(lock) {
                if (inflight === deferred && deferred.isCompleted) {
                    inflight = null
                    this.key = null
                }
            }
        }
    }

    /** Cancel and forget the current generation (source replacement / editor teardown). */
    fun invalidate() {
        val previous = synchronized(lock) {
            key = null
            inflight.also { inflight = null }
        }
        previous?.cancel(CancellationException("single-flight invalidated"))
    }

    internal fun isIdle(): Boolean = synchronized(lock) {
        key == null && inflight == null
    }
}

/**
 * In-memory cache of the decoded *proxy-resolution source* [LinearImage], so that interactive
 * slider/param edits don't re-decode the RAW/photo (LibRaw decode or bitmap decode + sRGB→
 * ProPhoto linearization + EXIF/rotation) on every render. Only the look/film params change
 * between most renders, and the decoded source does NOT depend on any of them — it depends
 * only on the DECODE-affecting inputs captured in [Request].
 *
 * READ-ONLY / DEFENSIVE-COPY DECISION: verified against the engine + JNI that the input image
 * buffer is treated as strictly const, so the SAME cached [LinearImage] can be re-fed to
 * `simulatePreview` across edits with no defensive copy:
 *   - `spk_simulate` / `spk_simulate_preview` take `const spk_image* in` (spektra.h).
 *   - `preprocess_geometry` (the shared entry for both scan_film and print routes) copies
 *     `in->data` into a fresh `std::vector<double> src` and operates only on that copy
 *     (spektra.cpp ~L265-267); the input is never written.
 *   - `spk_simulate_preview` downscales `in->data` into a fresh `std::vector<float> small`
 *     (read-only read; spektra.cpp ~L901) before simulating.
 *   - The JNI obtains `in_data` via `GetDirectBufferAddress` and only reads it (spektra_jni.cpp).
 * Therefore re-using the cached buffer cannot corrupt it. (If the engine ever started writing
 * into the input, this class would have to hand out a copy instead — see [acquire].)
 *
 * Scope: exactly ONE cached entry (the current source at the current proxy resolution). Publishing
 * a new entry retires the previous [LinearImage], closing it now or after its last active lease.
 *
 * Thread-safety: all access happens from the preview render coroutine (Dispatchers.Default /
 * .IO sequentially per render); methods are `@Synchronized` as cheap insurance since
 * [invalidate] may be called from a different scope.
 */
internal interface DecodedSourceCacheConstructionHooks {
    fun beforeTicketConstruction() = Unit
    fun beforeEntryConstruction() = Unit
    fun beforeLeaseConstruction() = Unit

    companion object {
        val NONE: DecodedSourceCacheConstructionHooks = object : DecodedSourceCacheConstructionHooks {}
    }
}

internal class DecodedSourceCache(
    private val constructionHooks: DecodedSourceCacheConstructionHooks =
        DecodedSourceCacheConstructionHooks.NONE,
) {
    /** Everything that affects the DECODE of the proxy source — and nothing that doesn't. */
    data class Request(
        val uri: String?,
        val kind: String,
        val authorizationRequired: Boolean,
        val whiteBalance: WhiteBalance,
        val temperature: Float,
        val tint: Float,
        // Creative WB is baked into the decoded buffer by loadSource (a pre-engine CAT), so it is a
        // decode-affecting input and belongs in the key — a change re-decodes, like raw temp/tint.
        val creativeTemp: Float,
        val creativeTint: Float,
        // "Balance to film stock" (85-filter) is likewise baked into the decoded buffer and depends on
        // the film profile's reference illuminant — so the key carries the profile id when the toggle is
        // ON and "" when OFF. That way switching film with the toggle off does NOT re-decode (the input
        // is film-independent then), while toggling it or changing film with it on does.
        val filmBalance: String,
        val rotationDegrees: Int,
        val maxEdge: Int,
        // Opt-in RAW clean-up (#259) is applied inside the native decode, so it is part of
        // the decoded bytes and of the key. Defaults keep pre-#259 keys identical.
        val hotPixels: Boolean = false,
        val chromaNoise: Float = 0f,
        val straightenDegrees: Float = 0f,
    )

    /** Publication authority for one immutable logical decode request. */
    data class Ticket(
        internal val request: Request,
        internal val generation: Long,
    )

    private class Entry(
        val request: Request,
        val image: LinearImage,
        private val constructionHooks: DecodedSourceCacheConstructionHooks,
    ) {
        private var leases = 0
        private var retired = false

        @Synchronized
        fun acquire(): Lease? {
            if (retired) return null
            leases++
            return try {
                constructionHooks.beforeLeaseConstruction()
                Lease(image, ::release)
            } catch (failure: Throwable) {
                // Lease construction owns the increment. Roll it back before the exception can
                // escape, otherwise retirement permanently believes a phantom reader is active.
                release()
                throw failure
            }
        }

        @Synchronized
        fun retire() {
            if (retired) return
            retired = true
            if (leases == 0) image.close()
        }

        @Synchronized
        private fun release() {
            check(leases > 0) { "decoded-source lease underflow" }
            leases--
            if (retired && leases == 0) image.close()
        }
    }

    /** Pins one cached frame until [close], deferring eviction/invalidation cleanup. */
    class Lease internal constructor(
        val image: LinearImage,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private var entry: Entry? = null
    private var requested: Request? = null
    private var generation = 0L

    /**
     * Begin or join the newest decode generation. A different immutable request invalidates old
     * publication authority before its detached decode can complete.
     */
    fun beginRequest(request: Request): Ticket {
        var stale: Entry? = null
        val ticket = synchronized(this) {
            val changed = requested != request
            val ticketGeneration = if (changed) generation + 1L else generation
            // Construct authority before mutating/detaching current state. OOME here leaves the
            // previous request and entry fully intact instead of creating a half-switched cache.
            constructionHooks.beforeTicketConstruction()
            val created = Ticket(request, ticketGeneration)
            if (changed) {
                requested = request
                generation = ticketGeneration
                stale = entry
                entry = null
            }
            created
        }
        // Never strand the previous source while a replacement decode is pending or fails. Active
        // leases still pin it; Entry.retire closes only after the final reader exits.
        stale?.retire()
        return ticket
    }

    /**
     * Return the cached decoded source if its key matches the supplied decode inputs, else
     * null. A null result means the caller must decode the immutable request and then [publish] it.
     */
    fun acquire(ticket: Ticket): Lease? {
        val candidate = synchronized(this) {
            entry?.takeIf {
                ticket.generation == generation &&
                    ticket.request == requested &&
                    it.request == ticket.request
            }
        }
        return candidate?.acquire()
    }

    fun isCurrent(ticket: Ticket): Boolean = synchronized(this) {
        ticket.generation == generation && ticket.request == requested
    }

    /**
     * Consume [img], publishing it only while [ticket] remains current. Stale detached results are
     * closed here and can never evict a newer cache entry.
     */
    fun publish(ticket: Ticket, img: LinearImage): Boolean {
        var accepted = false
        val previous = try {
            synchronized(this) {
                if (ticket.generation != generation || ticket.request != requested) {
                    null
                } else {
                    val current = entry
                    if (current?.image !== img) {
                        // Entry construction is the ownership-transfer point for img. If it fails,
                        // the catch below closes the still-unowned incoming image exactly once.
                        constructionHooks.beforeEntryConstruction()
                        entry = Entry(ticket.request, img, constructionHooks)
                    }
                    accepted = true
                    if (current?.image !== img) current else null
                }
            }
        } catch (failure: Throwable) {
            img.close()
            throw failure
        }
        if (accepted) {
            previous?.retire()
        } else {
            img.close()
        }
        return accepted
    }

    /** Drop the cached entry (e.g. on teardown), releasing its buffer. */
    fun invalidate() {
        val previous = synchronized(this) {
            generation++
            requested = null
            entry.also { entry = null }
        }
        previous?.retire()
    }
}
