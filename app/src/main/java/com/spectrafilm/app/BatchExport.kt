/*
 * Spektrafilm for Android — batch export: render many photos in one run. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Two kinds of batch share this file: the editor's "Export more photos with this edit"
 * ([BatchEditSource.SharedLook], below) and the Library's export of selected photos, each with
 * its own saved edit ([BatchEditSource.OwnEdits], LibraryExport.kt).
 *
 * The editor exports one photo. A batch takes the editor's LOOK (every parameter except the
 * photo's own crop and masks, exactly what applying a preset carries — Presets.decodeLook)
 * and the current export options, and renders each picked photo with them, one at a time,
 * inside ExportWorkRuntime. Running inside that runtime is the point: it is single-flight
 * with the editor's own export (two full-resolution renders at once is the OOM this app
 * spent #268-#271 removing), it owns the foreground service, and its cancel stops a batch
 * the same way it stops one export.
 *
 * A batch is UNBOUND to the editor's source, so the editor never adopts its outcome or
 * pixels; progress and the result reach the UI through [BatchExportTracker] instead.
 *
 * One photo failing (an unreadable file, a revoked grant) does not stop the rest: it is
 * counted and the batch moves on. Cancellation stops it between or inside photos.
 *
 * Not cached (#179): the cache key is built around the editor's own source and decode
 * identity, and a batch is a one-off pass over many sources.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.spectrafilm.app.masks.LocalAdjustment
import com.spectrafilm.app.masks.MaskCompositor
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.RenderKind
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One photo picked for a batch. */
internal data class BatchExportSource(val uri: Uri, val name: String, val kind: SourceKind)

/** A batch photo's settings: the whole edit, the manual turn and the masks to grade with. */
internal class BatchPhotoEdit(
    val state: ParamsState,
    val rotation: SourceRotation,
    /** Shape and skin masks; skin masks are bound to skin found on the photo at render time. */
    val adjustments: List<LocalAdjustment>,
)

/** Where each photo of a batch gets its settings from. */
internal sealed interface BatchEditSource {
    /** One look for every photo: the editor's "Export more photos with this edit". */
    class SharedLook(val look: JSONObject) : BatchEditSource

    /**
     * Every photo with its own saved edit, crop, turn and masks included, or the settings a
     * first open gives it when it has none: the Library's export of selected photos. Called on
     * the batch's coroutine, once per photo, right before that photo renders, so an edit saved
     * while the batch runs is the one exported.
     */
    class OwnEdits(val editFor: suspend (BatchExportSource) -> BatchPhotoEdit) : BatchEditSource
}

/** [look] as the photo's settings: no crop, no turn, only the look's skin masks (intent). */
internal fun sharedLookEdit(look: JSONObject): BatchPhotoEdit {
    val state = ParamsState()
    Presets.decodeLook(look, state)
    return BatchPhotoEdit(state, SourceRotation.NONE, Presets.decodeSkinMasks(look))
}

/**
 * The settings a photo opens with in the editor, as its restore builds them: the user's
 * defaults ([applyDefaults]) over a fresh state, then the photo's saved [recipe] over those,
 * and an installed profile in place of one [profiles] no longer has. Unit-tested.
 */
internal fun ownEditFor(
    recipe: RecipeDocument?,
    profiles: List<String>,
    applyDefaults: (ParamsState, List<String>) -> Unit,
): BatchPhotoEdit {
    val state = ParamsState()
    applyDefaults(state, profiles)
    if (recipe != null) Presets.decode(recipe.params, state)
    if (profiles.isNotEmpty()) {
        if (state.filmProfile !in profiles) state.filmProfile = profiles.first()
        if (state.printProfile !in profiles) state.printProfile = profiles.first()
    }
    return BatchPhotoEdit(
        state,
        SourceRotation.fromDegrees(recipe?.manualRotationDeg ?: 0),
        state.localAdjustments.toList(),
    )
}

/** Where a batch is, for the editor's status line and its final snackbar. */
internal data class BatchExportProgress(
    val total: Int,
    val done: Int,
    val failed: Int,
    val current: String?,
    val finished: Boolean,
    val cancelled: Boolean = false,
    /** A half-frame pair export (HalfFramePair.kt): one picture made of two photos. */
    val pair: Boolean = false,
) {
    /** 1-based position of the photo being rendered now. */
    val position: Int get() = (done + failed + 1).coerceAtMost(total)
}

/** Process-wide progress of the one batch that can run at a time. */
internal object BatchExportTracker {
    private val mutable = MutableStateFlow<BatchExportProgress?>(null)
    val state: StateFlow<BatchExportProgress?> = mutable.asStateFlow()

    fun publish(progress: BatchExportProgress) {
        mutable.value = progress
    }

    /** The UI has shown the final result; forget it. */
    fun acknowledge() {
        if (mutable.value?.finished == true) mutable.value = null
    }
}

/**
 * The batch kind of a picked document, or null when a batch cannot render it (video,
 * MotionCam RAW). Same routing as the editor's own open path (detectSourceKind).
 */
internal fun batchSourceKind(name: String, mimeType: String?): SourceKind? =
    when (detectSourceKind(name, mimeType)) {
        SourceFileKind.PHOTO -> SourceKind.PHOTO
        SourceFileKind.RAW -> SourceKind.RAW
        SourceFileKind.VIDEO, SourceFileKind.MCRAW -> null
    }

/** Resolve picked URIs into batch sources, dropping the ones a batch cannot render. */
internal fun resolveBatchSources(context: Context, uris: List<Uri>): List<BatchExportSource> =
    uris.distinct().mapNotNull { uri ->
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "photo"
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        batchSourceKind(name, mime)?.let { BatchExportSource(uri, name, it) }
    }

/**
 * File name for photo [index] (0-based) of [total]. Same template and tokens as a single
 * export; when the template cannot tell the photos apart (no `{name}`), a 1-based,
 * zero-padded counter keeps every file distinct, e.g. `Trip_03`.
 */
internal fun batchExportBaseName(
    template: String,
    timestampMs: Long,
    tokens: ExportNameTokens,
    index: Int,
    total: Int,
): String {
    val base = exportBaseName(template, timestampMs, tokens)
    if (total <= 1 || template.contains("{name}", ignoreCase = true)) return base
    val width = total.toString().length.coerceAtLeast(2)
    return base + "_" + (index + 1).toString().padStart(width, '0')
}

/** Whether a batch can use [options] at all (scene-linear exports the decode, not an edit). */
internal fun batchSupports(options: ExportOptions): Boolean =
    options.format != ExportFormat.SCENE_LINEAR_TIFF

/**
 * Run a batch. Returns the runtime's terminal outcome: Success when at least one photo was
 * published (bitmap always null; the editor never adopts batch pixels), Failure when every
 * photo failed. [BatchExportTracker] carries the counts either way.
 */
internal suspend fun runBatchExport(
    context: Context,
    engine: SpektraEngine,
    sources: List<BatchExportSource>,
    edits: BatchEditSource,
    options: ExportOptions,
    keepGps: Boolean,
    startedAtMillis: Long,
): ExportTerminalOutcome {
    require(batchSupports(options)) { "batch export does not support ${options.format}" }
    val total = sources.size
    var done = 0
    var failed = 0
    var lastPublished: Uri? = null
    var finished = false
    try {
        for ((index, source) in sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            BatchExportTracker.publish(BatchExportProgress(total, done, failed, source.name, finished = false))
            // Set by the writer once the file is in the gallery, so a photo that was published
            // still counts when cancellation (or a later step) wins the race back from IO.
            var committed = false
            try {
                lastPublished = exportBatchPhoto(
                    context, engine, source, edits, options, keepGps, index, total,
                ) { committed = true }
                done++
            } catch (cancel: CancellationException) {
                if (committed) done++
                throw cancel
            } catch (failure: Throwable) {
                if (committed) done++ else failed++
                Diag.w("batch export: photo ${index + 1} of $total failed: ${failure.javaClass.simpleName}")
            } finally {
                // Between photos, hand back the GPU buffers a full-resolution render holds, as
                // the runtime does after a single export (#269).
                releaseGpuAfterFullResRender("batch export")
            }
        }
        finished = true
        BatchExportTracker.publish(BatchExportProgress(total, done, failed, null, finished = true))
    } finally {
        if (!finished) {
            BatchExportTracker.publish(
                BatchExportProgress(total, done, failed, null, finished = true, cancelled = true),
            )
        }
    }
    val elapsed = System.currentTimeMillis() - startedAtMillis
    Diag.i("batch export done: $done of $total published, $failed failed, in $elapsed ms")
    return if (done == 0 && total > 0) {
        ExportTerminalOutcome.Failure(
            format = options.format,
            renderId = 0L,
            elapsedMs = elapsed,
            cause = IllegalStateException("batch export: all $failed photos failed"),
        )
    } else {
        ExportTerminalOutcome.Success(
            format = options.format,
            renderId = 0L,
            bitmap = null,
            totalMs = elapsed,
            phases = ExportPhaseSnapshot(0L, 0L, 0L, 0L, 0L, 0L),
            publishedUri = lastPublished?.toString(),
            publishedMimeType = options.format.mime,
        )
    }
}

/**
 * Render and publish one photo. Mirrors the editor's export block (EditorScreen) step for
 * step — decode, simulate, grade, size, sharpen, border, gain map, encode — minus its cache
 * and preview hand-off, so a batch photo is the same file the editor would have written.
 */
private suspend fun exportBatchPhoto(
    context: Context,
    engine: SpektraEngine,
    source: BatchExportSource,
    edits: BatchEditSource,
    options: ExportOptions,
    keepGps: Boolean,
    index: Int,
    total: Int,
    onCommitted: () -> Unit,
): Uri {
    val app = context.applicationContext
    val io = ProcessDispatchers.io
    // A fresh state per photo: the shared look (with this photo's own, absent, crop and shape
    // masks), or this photo's own whole edit.
    val edit = when (edits) {
        is BatchEditSource.SharedLook -> sharedLookEdit(edits.look)
        is BatchEditSource.OwnEdits -> edits.editFor(source)
    }
    val state = edit.state
    val descriptor = options.outputDescriptor(
        state.outputColorSpace,
        state.savingCctfEncoding,
        Build.VERSION.SDK_INT,
    ).also { ColorManagement.requireIccBytes(app, it) }
    val params = if (descriptor.engineColorSpace == null) {
        state.toParams()
    } else {
        descriptor.applyTo(state.toParams())
    }
    val cctf = descriptor.engineCctfEncoding ?: false
    val longEdge = options.targetLongEdge()
    val border = options.effectiveFrame()
    val sharpen = options.effectiveSharpen()
    val baseName = batchExportBaseName(
        options.customName,
        System.currentTimeMillis(),
        ExportNameTokens(
            sourceName = source.name,
            film = StockCatalog.displayName(app, state.filmProfile),
            print = StockCatalog.displayName(app, state.printProfile),
        ),
        index,
        total,
    )
    val request = SourceDecodeRequest(
        context = app,
        uri = source.uri,
        kind = source.kind,
        authorizationRequired = false,
        rawWhiteBalance = state.rawWhiteBalance,
        rawTemperature = state.rawTemperature,
        rawTint = state.rawTint,
        // EXIF orientation is applied by the decode itself; a manual turn comes only with the
        // photo's own edit (a shared look's turn belongs to the photo it was set on).
        rotation = edit.rotation,
        creativeTemp = state.creativeWbTemp,
        creativeTint = state.creativeWbTint,
        balanceToFilmStock = state.balanceToFilmStock,
        filmProfile = state.filmProfile,
        rawHotPixels = state.rawHotPixels,
        rawChromaNoise = state.rawChromaNoise,
        straightenDegrees = state.straightenDegrees,
    )
    val srcExif = withContext(io) { readSourceExif(app, source.uri, keepGps = keepGps) }
    val frameCaption = FrameCaptions.text(border.look, srcExif.tags, StockCatalog.displayName(app, state.filmProfile))
    val image = decodeSourceRequest(request, EXPORT_MAX_EDGE_PX, io).image
    var sceneTap: SceneLuma? = null
    // Skin masks are bound to skin found on this photo, before the render, while the decode
    // is still open. A shared look carries only those (intent, unlike shape masks); an own
    // edit carries its shape masks too, which are already this photo's.
    var adjustments: List<LocalAdjustment> = emptyList()
    val res = try {
        adjustments = edit.adjustments.withSkinFoundOn(app, image, params.io)
        runCancellableNative(
            onLateResult = { late ->
                late.reportOutcome(AppRenderOutcome.CANCELLED)
                late.close()
            },
        ) { cancellation ->
            engine.simulate(image, params, RenderKind.EXPORT, cancellation)
        }.also { rendered ->
            sceneTap = runCatching {
                captureSceneTapForGainmap(context, image, params, rendered.width, rendered.height, descriptor)
            }.getOrNull()
        }
    } finally {
        image.close()
    }
    var bitmap: Bitmap? = null
    try {
        check(res.colorSpace == descriptor.engineColorSpace) {
            "Engine result color space disagrees with output contract"
        }
        val published = when (descriptor.encoder) {
            OutputEncoder.ANDROID_BITMAP_PNG,
            OutputEncoder.ANDROID_BITMAP_JPEG,
            -> {
                val full = simResultToBitmapGraded(
                    res, cctf, state.saturation, state.vibrance, state.gamutCompress, adjustments,
                )
                bitmap = full
                val photoEdge = longEdge?.let { edge ->
                    FilmBorderGeometry.photoLongEdgeFor(border, full.width, full.height, edge, frameCaption)
                }
                val sized = photoEdge?.let { edge ->
                    scaleBitmapToLongEdge(full, edge).also { scaled ->
                        if (scaled !== full) {
                            bitmap = scaled
                            runCatching { full.recycle() }
                        }
                    }
                } ?: full
                if (sized.isMutable) sharpenBitmapInPlace(sized, sharpen)
                val layout = FilmBorderGeometry.layout(
                    border,
                    sized.width,
                    sized.height,
                    FilmBorderGeometry.frameNumberFor(source.uri.toString()),
                    frameCaption,
                )
                val framed = layout?.let { l ->
                    FilmBorderRenderer.render(sized, l).also {
                        bitmap = it
                        runCatching { sized.recycle() }
                    }
                } ?: sized
                if (descriptor.metadata.hdrGainMap != null) {
                    runCatching {
                        attachRenderedGainmap(res, framed, descriptor, layout, sceneTap)
                    }.onFailure { Diag.w("batch gain map failed; exporting SDR base: $it") }
                }
                withContext(io) {
                    saveToGallery(
                        app, framed, descriptor, options.jpegQuality, srcExif,
                        displayName = baseName, onCommitted = onCommitted,
                    )
                }
            }
            OutputEncoder.NATIVE_TIFF_UINT16,
            OutputEncoder.NATIVE_TIFF_FLOAT32,
            OutputEncoder.NATIVE_PNG16,
            -> {
                res.acquireDataLease().use { lease ->
                    ColorGrade.applyInPlace(
                        lease.data, res.width, res.height, res.colorSpace,
                        cctf, state.saturation, state.vibrance, state.gamutCompress,
                    )
                    if (adjustments.isNotEmpty()) {
                        MaskCompositor.applyInPlace(
                            lease.data, res.width, res.height, res.colorSpace, cctf, adjustments,
                        )
                    }
                }
                withContext(io) {
                    if (descriptor.encoder == OutputEncoder.NATIVE_PNG16) {
                        saveSimResultAsPng16(app, res, descriptor, displayName = baseName, onCommitted = onCommitted)
                    } else {
                        saveSimResultAsTiff(app, res, descriptor, displayName = baseName, onCommitted = onCommitted)
                    }
                }
            }
            else -> error("batch export cannot encode ${descriptor.encoder}")
        }
        SimResult.reportOutcome(res.renderId, AppRenderOutcome.CONSUMED)
        return published
    } catch (failure: Throwable) {
        SimResult.reportOutcome(
            res.renderId,
            if (failure is CancellationException) AppRenderOutcome.CANCELLED else AppRenderOutcome.FAILED,
        )
        throw failure
    } finally {
        bitmap?.let { runCatching { it.recycle() } }
        res.close()
    }
}
