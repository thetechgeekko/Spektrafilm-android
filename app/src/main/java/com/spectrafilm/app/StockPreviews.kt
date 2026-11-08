/*
 * Spektrafilm for Android -- per-stock previews of the user's own photo. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The stock browser shipped text-only because 28 simulate() runs were assumed to fight the
 * interactive renderer for the same cores, and nobody had measured them (see StockBrowser.kt).
 * Measured on the host engine at the shipping flags (-O3 -ffast-math, 4 threads, a 1600x1200
 * source): a 160 px preview costs 11-30 ms per stock, a full 20-film sweep about 0.5 s cold and
 * 0.3 s warm, and the first 640 px interactive render after a sweep was 321 ms against a
 * 243-263 ms steady state (one render later it was back to 254 ms). A phone is slower, but the
 * shape holds: cheap per tile, and a sweep only nudges the interactive memo for one frame.
 *
 * So previews are rendered under three rules that keep that cost off the user's edits:
 *  - only while the browser sheet is open, when no slider can be dragged;
 *  - one at a time, newest request first, so the tiles on screen fill before the ones the
 *    user scrolled past;
 *  - from one small copy of the photo ([STOCK_PREVIEW_SOURCE_EDGE]) made once per session,
 *    with grain and halation skipped, exactly like the live draft.
 *
 * A tile shows the look the user would get by tapping it: the current params with only the
 * stock swapped. The q8987308-design fork added a reference photo per stock; this shows the
 * user's own photo instead (credited in the PR). The implementation is our own.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long edge of a rendered preview tile, in pixels. 240 since the strip became a multi-browse
 * carousel: its large tile is about 168dp wide, and 160 px looked soft there.
 */
internal const val STOCK_PREVIEW_EDGE = 240

/** Long edge of the one linear copy of the photo every tile is rendered from. */
internal const val STOCK_PREVIEW_SOURCE_EDGE = 320

/**
 * Everything one set of tiles depends on. Two sessions with an equal [key] produce the same
 * tiles, so reopening the sheet with nothing changed shows the finished set at once.
 */
internal class StockPreviewSession(
    /** Equality decides reuse: the source identity plus the params minus the stock. */
    val key: Any,
    /** A small linear copy of the photo, owned (and closed) by the caller of this lambda. */
    val source: suspend () -> LinearImage?,
    /** The params a tap on [stockId] would render. Called on the view model's main thread. */
    val paramsFor: (stockId: String) -> SpektraParams,
    /** The editor's own display step (saturation, vibrance, gamut, local adjustments). */
    val toBitmap: (SimResult) -> Bitmap,
    /**
     * Source-level work a tap on [stockId] would redo, applied to a copy of the shared
     * source before that tile renders: "Balance to film stock" adapts the decoded photo to
     * the film, so a tile for another film needs the shared source re-targeted. Null when
     * the tile renders the shared source as is.
     */
    val retarget: ((stockId: String) -> FloatArray?)? = null,
)

/**
 * The 3x3 that turns a source balanced to [fromFilm] into one balanced to [toFilm]
 * (FilmStockBalance; "" = not balanced), or null when the two are the same.
 */
internal fun filmBalanceRetarget(from: FloatArray?, to: FloatArray?): FloatArray? {
    if (from == null && to == null) return null
    if (from != null && to != null && from.contentEquals(to)) return null
    val inv = from?.let { invert3x3(it) } ?: IDENTITY_3X3
    return multiply3x3(to ?: IDENTITY_3X3, inv)
}

private val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

internal fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
    val r = i / 3
    val c = i % 3
    a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
}

internal fun invert3x3(m: FloatArray): FloatArray {
    val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
    val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
    val g = m[6].toDouble(); val h = m[7].toDouble(); val k = m[8].toDouble()
    val det = a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g)
    require(kotlin.math.abs(det) > 1e-12) { "singular white-balance matrix" }
    val inv = 1.0 / det
    return floatArrayOf(
        ((e * k - f * h) * inv).toFloat(), ((c * h - b * k) * inv).toFloat(), ((b * f - c * e) * inv).toFloat(),
        ((f * g - d * k) * inv).toFloat(), ((a * k - c * g) * inv).toFloat(), ((c * d - a * f) * inv).toFloat(),
        ((d * h - e * g) * inv).toFloat(), ((b * g - a * h) * inv).toFloat(), ((a * e - b * d) * inv).toFloat(),
    )
}

/**
 * The pure scheduling core: which stock to render next, and whether a finished render still
 * belongs to the current session. No coroutines and no Android, so it is tested on the JVM.
 */
internal class StockPreviewQueue<B> {
    data class Work(val generation: Long, val stockId: String)

    var generation: Long = 0L
        private set
    private var key: Any? = null
    private val pending = ArrayDeque<String>()
    private val inFlight = mutableSetOf<String>()
    private val _done = LinkedHashMap<String, B>()

    /** Finished tiles of the current session. */
    val done: Map<String, B> get() = _done

    /**
     * Point the queue at [newKey]. Returns the tiles that no longer apply (for the caller to
     * release) -- empty when the key is unchanged, so the finished set is kept. Requests stay
     * queued across a change: they name tiles the user is looking at, and cards may ask
     * before the session that will render them is ready.
     */
    fun bind(newKey: Any): List<B> {
        if (newKey == key) return emptyList()
        key = newKey
        generation++
        requeueInFlight()
        val stale = _done.values.toList()
        // A finished tile is still wanted under the new look; render it again.
        for (id in _done.keys) if (id !in pending) pending.addLast(id)
        _done.clear()
        return stale
    }

    /** Ask for [stockId]; the most recent request is served first. No-op once rendered. */
    fun request(stockId: String) {
        if (stockId in _done || stockId in inFlight) return
        pending.remove(stockId)
        pending.addFirst(stockId)
    }

    /** Asked for but not rendered yet: these tiles show their placeholder. */
    fun waiting(): Set<String> = LinkedHashSet<String>().apply { addAll(inFlight); addAll(pending) }

    fun next(): Work? {
        val id = pending.removeFirstOrNull() ?: return null
        inFlight += id
        return Work(generation, id)
    }

    /**
     * Record a finished render. Returns false when the session moved on while it ran; the
     * caller then releases [result] itself.
     */
    fun complete(work: Work, result: B): Boolean {
        if (work.generation != generation) return false
        inFlight -= work.stockId
        _done[work.stockId] = result
        return true
    }

    /** A render failed: forget it so a later request can try again. */
    fun fail(work: Work) {
        if (work.generation == generation) inFlight -= work.stockId
    }

    /** Put unstarted in-flight work back (the sheet closed mid-render). */
    fun requeueInFlight() {
        for (id in inFlight) if (id !in pending) pending.addLast(id)
        inFlight.clear()
    }
}

/**
 * What the stock browser needs from the editor: the tiles, a way to ask for one, and the
 * open/close of a rendering session. [open] is suspending because building the session reads
 * the editor's current source request.
 */
internal class StockPreviewHost(
    val previews: StateFlow<Map<String, ImageBitmap?>>,
    val request: (stockId: String) -> Unit,
    /** Start (or refresh) rendering on behalf of an owner; each owner closes its own. */
    val open: suspend (owner: Any) -> Unit,
    val close: (owner: Any) -> Unit,
)

/** Renders one tile. Production: the shared engine; tests: a fake. */
internal fun interface StockPreviewRenderer {
    fun render(image: LinearImage, params: SpektraParams, toBitmap: (SimResult) -> Bitmap): ImageBitmap
}

/**
 * Tiles for the stock browser, keyed by stock id. A key present with a null value is a tile
 * that was asked for and is still rendering; an absent key was never asked for.
 *
 * Agreed with the editor redesign so its "Film & look" strip can share it:
 * [previews] plus [request].
 */
internal class StockPreviewViewModel(
    private val renderer: StockPreviewRenderer,
    background: CoroutineDispatcher,
) : ViewModel() {
    // One tile at a time: a single lane of the background pool, so a sweep never takes more
    // than one engine call's worth of cores away from anything else.
    private val worker = background.limitedParallelism(1)
    private val queue = StockPreviewQueue<ImageBitmap>()
    private val _previews = MutableStateFlow<Map<String, ImageBitmap?>>(emptyMap())
    val previews: StateFlow<Map<String, ImageBitmap?>> = _previews.asStateFlow()

    private var session: StockPreviewSession? = null
    private var source: LinearImage? = null
    private var sourceKey: Any? = null
    private var pump: Job? = null

    /**
     * Who currently wants tiles: the browser sheet and the Film & look strip share this view
     * model, and one closing must not stop the other's rendering.
     */
    private val owners = mutableSetOf<Any>()

    /** [owner] opened (or the look changed while open): render for [next]. */
    fun open(next: StockPreviewSession, owner: Any = DEFAULT_OWNER) {
        owners += owner
        session = next
        queue.bind(next.key) // ImageBitmaps are GC-owned; nothing to release explicitly
        if (sourceKey != next.key) {
            source?.close()
            source = null
            sourceKey = null
        }
        publish()
        ensurePump()
    }

    /**
     * [owner] closed. When nobody else wants tiles, stop rendering and keep finished tiles and
     * the source for a reopen.
     */
    fun close(owner: Any = DEFAULT_OWNER) {
        owners -= owner
        if (owners.isNotEmpty()) return
        session = null
        pump?.cancel()
        pump = null
        queue.requeueInFlight()
    }

    fun request(stockId: String) {
        queue.request(stockId)
        publish()
        ensurePump()
    }

    private fun ensurePump() {
        if (session == null || pump?.isActive == true) return
        pump = viewModelScope.launch {
            while (true) {
                val current = session ?: break
                val work = queue.next() ?: break
                val image = sourceFor(current)
                if (image == null) {
                    // No photo copy: drop the placeholder rather than leave it spinning.
                    queue.fail(work)
                    publish()
                    break
                }
                val result = runCatching {
                    // Params are built here, on the main thread: the Look strip's builder
                    // decodes into a scratch ParamsState (Compose state), which must not be
                    // written from the render worker.
                    val params = current.paramsFor(work.stockId)
                    val retarget = current.retarget?.invoke(work.stockId)
                    withContext(worker) {
                        if (retarget == null) {
                            renderer.render(image, params, current.toBitmap)
                        } else {
                            // A copy, so the shared source stays as decoded for the next tile.
                            downsampleLinearBox(image, maxOf(image.width, image.height)).use { copy ->
                                copy.acquireDataLease().use { lease ->
                                    CreativeWhiteBalance.applyInPlace(
                                        lease.data, copy.width * copy.height, retarget,
                                    )
                                }
                                renderer.render(copy, params, current.toBitmap)
                            }
                        }
                    }
                }
                result.onSuccess { queue.complete(work, it) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Diag.w("stock preview ${work.stockId} failed: ${it.message}")
                        queue.fail(work)
                    }
                publish()
            }
        }
    }

    private suspend fun sourceFor(current: StockPreviewSession): LinearImage? {
        source?.takeIf { sourceKey == current.key }?.let { return it }
        val made = runCatching { current.source() }
            .onFailure { Diag.w("stock preview source failed: ${it.message}") }
            .getOrNull() ?: return null
        if (session !== current) {
            made.close()
            return null
        }
        source = made
        sourceKey = current.key
        return made
    }

    private fun publish() {
        val map = LinkedHashMap<String, ImageBitmap?>(queue.done)
        for (id in queue.waiting()) map.putIfAbsent(id, null)
        _previews.value = map
    }

    override fun onCleared() {
        pump?.cancel()
        source?.close()
        source = null
    }

    companion object {
        /** The owner for callers that never share the model (and the JVM tests). */
        internal val DEFAULT_OWNER = Any()

        /** Production renderer: the shared process engine at preview quality. */
        fun engineRenderer(context: Context): StockPreviewRenderer {
            val app = context.applicationContext
            return StockPreviewRenderer { image, params, toBitmap ->
                EngineHolder.get(app).simulatePreview(image, params).use { res ->
                    toBitmap(res).asImageBitmap()
                }
            }
        }
    }
}

/**
 * A box-averaged linear copy of [src] whose long edge is at most [maxEdge] (an integer
 * factor, so every output pixel averages a whole block and no source pixel is skipped).
 * Averaging in linear light keeps highlights honest; the engine's own preview downscale
 * then takes it the rest of the way to [STOCK_PREVIEW_EDGE] with its AA prefilter.
 * Returns a plain copy when [src] is already small enough. The caller owns the result.
 */
internal fun downsampleLinearBox(src: LinearImage, maxEdge: Int): LinearImage {
    require(maxEdge > 0) { "maxEdge must be positive" }
    val w = src.width
    val h = src.height
    val longest = maxOf(w, h)
    // ceil, so the result fits within maxEdge; 1 when src is already small enough.
    val s = maxOf(1, (longest + maxEdge - 1) / maxEdge)
    // Trailing rows/columns that do not fill a whole block are dropped (at most s-1 pixels).
    // An edge thinner than one block (a 1-pixel-wide strip) averages what it has.
    val sx = minOf(s, w)
    val sy = minOf(s, h)
    val outW = maxOf(1, w / s)
    val outH = maxOf(1, h / s)
    val out = java.nio.ByteBuffer.allocateDirect(outW * outH * 3 * 4)
        .order(java.nio.ByteOrder.nativeOrder())
    val dst = out.asFloatBuffer()
    src.acquireDataLease().use { lease ->
        val f = lease.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val row = FloatArray(w * 3)
        val acc = FloatArray(outW * 3)
        val inv = 1f / (sx * sy)
        for (oy in 0 until outH) {
            acc.fill(0f)
            for (dy in 0 until sy) {
                f.position((oy * s + dy) * w * 3)
                f.get(row, 0, w * 3)
                for (ox in 0 until outW) {
                    var i = ox * s * 3
                    val o = ox * 3
                    for (dx in 0 until sx) {
                        acc[o] += row[i]
                        acc[o + 1] += row[i + 1]
                        acc[o + 2] += row[i + 2]
                        i += 3
                    }
                }
            }
            for (k in acc.indices) acc[k] *= inv
            dst.put(acc)
        }
    }
    return LinearImage(out, outW, outH, colorSpace = src.colorSpace)
}
