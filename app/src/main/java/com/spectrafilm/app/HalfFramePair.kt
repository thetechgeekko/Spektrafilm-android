/*
 * Spektrafilm for Android — half-frame pair export. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A half-frame camera puts two 18 x 24 mm frames where a 135 camera puts one 36 x 24 mm
 * frame, so a print of that piece of negative shows two pictures side by side (camera held
 * level: two upright frames) or one above the other (camera turned: two level frames), with a
 * thin unexposed line between them that prints black.
 *
 * This export builds that print from two photos: the one open in the editor, with its edit,
 * and a second one with ITS own saved edit (or, when it has none, the editor's look). Each
 * frame is rendered through the whole film pipeline on its own, so each keeps its own
 * exposure, white balance, film, print and grade, then both are cut to the half-frame gate
 * and set on one picture, which the export's border and size options apply to as usual.
 *
 * The idea is SpektraLab's half-frame pair (https://github.com/JamesQiu2005/SpektraLab,
 * GPL-3.0-or-later); this is an independent implementation of it.
 *
 * Runs inside ExportWorkRuntime like a batch (single-flight with every other export, the
 * foreground service, its cancel), unbound to the editor's source, and reports through
 * [BatchExportTracker] with `pair = true`.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.RenderKind
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the two frames of a pair go on the picture, in pixels. Pure, so it is unit-tested. */
internal object HalfFramePairGeometry {
    /** Short side : long side of one half frame (18 x 24 mm). */
    private const val SHORT = 3
    private const val LONG = 4

    /**
     * Frame line between the two frames as a fraction of a frame's long side: about 1 mm on a
     * 24 mm frame. Never less than one pixel.
     */
    private const val GAP_PER_LONG_SIDE = 1.0 / 24.0

    data class Layout(
        val width: Int,
        val height: Int,
        /** Where each frame goes on the picture. */
        val first: BorderRect,
        val second: BorderRect,
        /** The part of each rendered frame that fills its gate (a centered cut to 3:4). */
        val firstSource: BorderRect,
        val secondSource: BorderRect,
        val stacked: Boolean,
    )

    /**
     * Lays out frames of [aW] x [aH] and [bW] x [bH]. Two level (landscape) frames stack one
     * above the other; any other pair stands side by side as two upright frames. The common
     * frame size is the smaller of the two cuts, so neither frame is enlarged.
     */
    fun layout(aW: Int, aH: Int, bW: Int, bH: Int): Layout {
        require(aW > 0 && aH > 0 && bW > 0 && bH > 0) { "empty frame" }
        val stacked = aW > aH && bW > bH
        // Gate aspect as width : height.
        val gw = if (stacked) LONG else SHORT
        val gh = if (stacked) SHORT else LONG
        val cutA = centeredCut(aW, aH, gw, gh)
        val cutB = centeredCut(bW, bH, gw, gh)
        return if (stacked) {
            val frameW = min(cutA.width, cutB.width)
            val frameH = max(1, (frameW.toLong() * gh / gw).toInt())
            val gap = max(1, (frameW * GAP_PER_LONG_SIDE).roundToInt())
            Layout(
                width = frameW,
                height = frameH * 2 + gap,
                first = BorderRect(0, 0, frameW, frameH),
                second = BorderRect(0, frameH + gap, frameW, frameH * 2 + gap),
                firstSource = cutA,
                secondSource = cutB,
                stacked = true,
            )
        } else {
            val frameH = min(cutA.height, cutB.height)
            val frameW = max(1, (frameH.toLong() * gw / gh).toInt())
            val gap = max(1, (frameH * GAP_PER_LONG_SIDE).roundToInt())
            Layout(
                width = frameW * 2 + gap,
                height = frameH,
                first = BorderRect(0, 0, frameW, frameH),
                second = BorderRect(frameW + gap, 0, frameW * 2 + gap, frameH),
                firstSource = cutA,
                secondSource = cutB,
                stacked = false,
            )
        }
    }

    /** The largest [gw]:[gh] rectangle centered in [w] x [h]. */
    fun centeredCut(w: Int, h: Int, gw: Int, gh: Int): BorderRect {
        // Wider than the gate: keep the full height. Otherwise keep the full width.
        return if (w.toLong() * gh > h.toLong() * gw) {
            val cw = max(1, (h.toLong() * gw / gh).toInt())
            val x = (w - cw) / 2
            BorderRect(x, 0, x + cw, h)
        } else {
            val ch = max(1, (w.toLong() * gh / gw).toInt())
            val y = (h - ch) / 2
            BorderRect(0, y, w, y + ch)
        }
    }
}

/** One frame of a pair: a photo, its whole edit and its own turn. */
internal data class HalfFrameSource(
    val uri: Uri,
    val name: String,
    val kind: SourceKind,
    /** A complete params document (Presets.encode), crop and masks included. */
    val params: JSONObject,
    val rotationDegrees: Int,
)

/** The pair is drawn on a Bitmap, so only the Bitmap encoders can write it. */
internal fun halfFramePairSupports(options: ExportOptions): Boolean =
    options.format == ExportFormat.JPEG || options.format == ExportFormat.PNG

/**
 * The second frame's edit: its own saved recipe when it has one, else the editor's look on
 * it (the editor's whole edit minus crop and shape masks, as a batch does). Either way the output
 * color space is the first frame's, since both frames end up in one picture.
 */
internal fun halfFrameSecondSource(
    recipes: RecipeRepository,
    picked: BatchExportSource,
    first: HalfFrameSource,
): HalfFrameSource {
    val state = ParamsState()
    var rotation = 0
    val own = Recipes.keyFor(picked.uri)?.let { key ->
        (runCatching { recipes.readResult(key) }.getOrNull() as? RecipeReadResult.Loaded)?.document
    }
    if (own != null) {
        Presets.decode(own.params, state)
        rotation = own.manualRotationDeg
    } else {
        Presets.decodeLook(first.params, state)
        // Skin masks store intent, not a place on the first photo, so they carry over.
        state.localAdjustments = Presets.decodeSkinMasks(first.params)
    }
    val firstState = ParamsState().also { Presets.decode(first.params, it) }
    state.outputColorSpace = firstState.outputColorSpace
    state.savingCctfEncoding = firstState.savingCctfEncoding
    return HalfFrameSource(picked.uri, picked.name, picked.kind, Presets.encode(state), rotation)
}

/**
 * Render both frames, set them on one picture and publish it. Returns the runtime's terminal
 * outcome; [BatchExportTracker] carries the result for the editor's snackbar.
 */
internal suspend fun runHalfFramePairExport(
    context: Context,
    engine: SpektraEngine,
    first: HalfFrameSource,
    second: HalfFrameSource,
    options: ExportOptions,
    keepGps: Boolean,
    startedAtMillis: Long,
): ExportTerminalOutcome {
    require(halfFramePairSupports(options)) { "a half-frame pair cannot be written as ${options.format}" }
    val app = context.applicationContext
    var published: Uri? = null
    var committed = false
    var finished = false
    var failure: Throwable? = null
    BatchExportTracker.publish(BatchExportProgress(1, 0, 0, first.name, finished = false, pair = true))
    try {
        try {
            published = exportPair(app, engine, first, second, options, keepGps) { committed = true }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            failure = t
            Diag.w("half-frame pair export failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        finished = true
        val done = if (published != null || committed) 1 else 0
        BatchExportTracker.publish(BatchExportProgress(1, done, 1 - done, null, finished = true, pair = true))
    } finally {
        if (!finished) {
            val done = if (committed) 1 else 0
            BatchExportTracker.publish(
                BatchExportProgress(1, done, 0, null, finished = true, cancelled = true, pair = true),
            )
        }
    }
    val elapsed = System.currentTimeMillis() - startedAtMillis
    Diag.i("half-frame pair export done in $elapsed ms, published=${published != null}")
    return if (published == null && !committed) {
        ExportTerminalOutcome.Failure(
            format = options.format,
            renderId = 0L,
            elapsedMs = elapsed,
            cause = failure ?: IllegalStateException("half-frame pair export failed"),
        )
    } else {
        ExportTerminalOutcome.Success(
            format = options.format,
            renderId = 0L,
            bitmap = null,
            totalMs = elapsed,
            phases = ExportPhaseSnapshot(0L, 0L, 0L, 0L, 0L, 0L),
            publishedUri = published?.toString(),
            publishedMimeType = options.format.mime,
        )
    }
}

private suspend fun exportPair(
    app: Context,
    engine: SpektraEngine,
    first: HalfFrameSource,
    second: HalfFrameSource,
    options: ExportOptions,
    keepGps: Boolean,
    onCommitted: () -> Unit,
): Uri {
    val io = ProcessDispatchers.io
    val firstState = ParamsState().also { Presets.decode(first.params, it) }
    val descriptor = options.outputDescriptor(
        firstState.outputColorSpace,
        firstState.savingCctfEncoding,
        Build.VERSION.SDK_INT,
    ).also { ColorManagement.requireIccBytes(app, it) }
    val srcExif = withContext(io) { readSourceExif(app, first.uri, keepGps = keepGps) }
    val owned = ArrayList<Bitmap>(4)
    try {
        val a = renderFrame(app, engine, first, descriptor).also { owned += it }
        currentCoroutineContext().ensureActive()
        val b = renderFrame(app, engine, second, descriptor).also { owned += it }
        currentCoroutineContext().ensureActive()
        val layout = HalfFramePairGeometry.layout(a.width, a.height, b.width, b.height)
        val pair = composePair(a, b, layout).also { owned += it }
        // The frames are on the picture now; give their memory back before resizing.
        for (frame in listOf(a, b)) {
            runCatching { frame.recycle() }
            owned.remove(frame)
        }
        val border = options.effectiveFrame()
        val frameCaption = FrameCaptions.text(
            border.look,
            srcExif.tags,
            StockCatalog.displayName(app, firstState.filmProfile),
        )
        val photoEdge = options.targetLongEdge()?.let { edge ->
            FilmBorderGeometry.photoLongEdgeFor(border, pair.width, pair.height, edge, frameCaption)
        }
        val sized = photoEdge?.let { scaleBitmapToLongEdge(pair, it) } ?: pair
        if (sized !== pair) {
            owned += sized
            runCatching { pair.recycle() }
            owned.remove(pair)
        }
        if (sized.isMutable) sharpenBitmapInPlace(sized, options.effectiveSharpen())
        val frameLayout = FilmBorderGeometry.layout(
            border,
            sized.width,
            sized.height,
            FilmBorderGeometry.frameNumberFor(first.uri.toString()),
            frameCaption,
        )
        val framed = frameLayout?.let { FilmBorderRenderer.render(sized, it) } ?: sized
        if (framed !== sized) owned += framed
        val baseName = exportBaseName(
            options.customName,
            System.currentTimeMillis(),
            ExportNameTokens(
                sourceName = first.name,
                film = StockCatalog.displayName(app, firstState.filmProfile),
                print = StockCatalog.displayName(app, firstState.printProfile),
            ),
        ) + "_pair"
        return withContext(io) {
            saveToGallery(
                app, framed, descriptor, options.jpegQuality, srcExif,
                displayName = baseName, onCommitted = onCommitted,
            )
        }
    } finally {
        for (bitmap in owned) runCatching { bitmap.recycle() }
    }
}

/**
 * One frame through the whole pipeline, graded, then cut to its half-frame gate. Mirrors the
 * batch's per-photo render (decode, simulate, grade), with the frame's own turn and crop.
 */
private suspend fun renderFrame(
    app: Context,
    engine: SpektraEngine,
    source: HalfFrameSource,
    descriptor: OutputDescriptor,
): Bitmap {
    val io = ProcessDispatchers.io
    val state = ParamsState().also { Presets.decode(source.params, it) }
    val params = if (descriptor.engineColorSpace == null) {
        state.toParams()
    } else {
        descriptor.applyTo(state.toParams())
    }
    val cctf = descriptor.engineCctfEncoding ?: false
    val request = SourceDecodeRequest(
        context = app,
        uri = source.uri,
        kind = source.kind,
        authorizationRequired = false,
        rawWhiteBalance = state.rawWhiteBalance,
        rawTemperature = state.rawTemperature,
        rawTint = state.rawTint,
        rotation = SourceRotation.fromDegrees(source.rotationDegrees),
        creativeTemp = state.creativeWbTemp,
        creativeTint = state.creativeWbTint,
        balanceToFilmStock = state.balanceToFilmStock,
        filmProfile = state.filmProfile,
        rawHotPixels = state.rawHotPixels,
        rawChromaNoise = state.rawChromaNoise,
        straightenDegrees = state.straightenDegrees,
    )
    val image = decodeSourceRequest(request, EXPORT_MAX_EDGE_PX, io).image
    var adjustments = state.localAdjustments
    val res = try {
        // Skin masks bind to skin found on this frame's own decode, before the render closes it.
        adjustments = adjustments.withSkinFoundOn(app, image, params.io)
        runCancellableNative(
            onLateResult = { late ->
                late.reportOutcome(AppRenderOutcome.CANCELLED)
                late.close()
            },
        ) { cancellation ->
            engine.simulate(image, params, RenderKind.EXPORT, cancellation)
        }
    } finally {
        image.close()
    }
    try {
        check(res.colorSpace == descriptor.engineColorSpace) {
            "Engine result color space disagrees with output contract"
        }
        // Local adjustments (masks) are applied in the grade, not by the engine: pass this
        // frame's own, as the editor's export does.
        val full = simResultToBitmapGraded(
            res, cctf, state.saturation, state.vibrance, state.gamutCompress, adjustments,
        )
        SimResult.reportOutcome(res.renderId, AppRenderOutcome.CONSUMED)
        return full
    } catch (failure: Throwable) {
        SimResult.reportOutcome(
            res.renderId,
            if (failure is CancellationException) AppRenderOutcome.CANCELLED else AppRenderOutcome.FAILED,
        )
        throw failure
    } finally {
        res.close()
        releaseGpuAfterFullResRender("half-frame pair")
    }
}

/** Both frames on black (the unexposed frame line prints black), each cut to its gate. */
internal fun composePair(a: Bitmap, b: Bitmap, layout: HalfFramePairGeometry.Layout): Bitmap {
    val config = a.config ?: Bitmap.Config.ARGB_8888
    val out = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && a.colorSpace != null) {
        Bitmap.createBitmap(layout.width, layout.height, config, false, a.colorSpace!!)
    } else {
        Bitmap.createBitmap(layout.width, layout.height, config)
    }
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    canvas.drawBitmap(a, layout.firstSource.toRect(), layout.first.toRect(), paint)
    canvas.drawBitmap(b, layout.secondSource.toRect(), layout.second.toRect(), paint)
    return out
}

private fun BorderRect.toRect() = Rect(left, top, right, bottom)
