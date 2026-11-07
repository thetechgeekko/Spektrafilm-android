/*
 * Spektrafilm for Android — preview bitmap leases. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Read leases that keep a preview Bitmap alive until every reader is done, so it can be
 * recycled safely. Split out of Viewer.kt (#273).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Small identity-keyed read-lease registry. The owner may retire a value immediately; physical
 * release is deferred until existing readers close, and new readers are rejected after retirement.
 */
internal class RetirableReadLeaseRegistry<T : Any>(
    private val isPhysicallyRetired: (T) -> Boolean,
    private val physicallyRetire: (T) -> Unit,
    private val beforeLeaseConstruction: () -> Unit = {},
) {
    private data class State(var readers: Int = 0, var retired: Boolean = false)

    internal class Lease<T : Any>(
        val value: T,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private val states = IdentityHashMap<T, State>()

    @Synchronized
    fun acquire(value: T): Lease<T>? {
        if (isPhysicallyRetired(value)) return null
        val state = states.getOrPut(value) { State() }
        if (state.retired) return null
        state.readers++
        return try {
            beforeLeaseConstruction()
            Lease(value) { release(value, state) }
        } catch (failure: Throwable) {
            state.readers--
            if (state.readers == 0 && !state.retired) states.remove(value)
            throw failure
        }
    }

    @Synchronized
    fun retire(value: T) {
        if (isPhysicallyRetired(value)) return
        val state = states[value]
        if (state == null) {
            // Retire under the same monitor as acquire. This closes the acquire/recycle window.
            physicallyRetire(value)
            return
        }
        if (state.retired) return
        state.retired = true
        if (state.readers == 0) retireNow(value, state)
    }

    @Synchronized
    private fun release(value: T, expected: State) {
        val state = states[value]
        check(state === expected && state.readers > 0) { "read-lease registry underflow" }
        state.readers--
        if (state.retired && state.readers == 0) retireNow(value, state)
    }

    /** Caller holds this registry's monitor. */
    private fun retireNow(value: T, expected: State) {
        check(states[value] === expected)
        physicallyRetire(value)
        states.remove(value)
    }
}

internal val previewBitmapReadLeases = RetirableReadLeaseRegistry<Bitmap>(
    isPhysicallyRetired = { bitmap -> bitmap.isRecycled },
    physicallyRetire = { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() },
)

/** Retire a preview deterministically once every histogram reader has released it. */
internal fun retirePreviewBitmap(bitmap: Bitmap) {
    previewBitmapReadLeases.retire(bitmap)
}

/** Take a read lease on a preview frame; null once it is retired. */
internal fun acquirePreviewLease(bitmap: Bitmap): RetirableReadLeaseRegistry.Lease<Bitmap>? =
    previewBitmapReadLeases.acquire(bitmap)

/**
 * An [ImageBitmap] view of [bitmap] that holds a read lease for as long as it is composed.
 *
 * `retirePreviewBitmap` defers the physical recycle until the last lease closes, so this is what
 * keeps the draw pass from reading recycled pixel storage when a render settles mid-frame.
 * Returns null if the frame was already retired, in which case there is nothing to draw.
 */
@Composable
internal fun rememberLeasedImage(bitmap: Bitmap): ImageBitmap? {
    val leased = remember(bitmap) {
        acquirePreviewLease(bitmap)?.let { lease -> lease to bitmap.asImageBitmap() }
    }
    DisposableEffect(leased) { onDispose { leased?.first?.close() } }
    return leased?.second
}
