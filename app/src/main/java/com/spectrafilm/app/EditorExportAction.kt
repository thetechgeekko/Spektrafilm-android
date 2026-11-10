/*
 * Spektrafilm for Android — editor export action. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * What the export sheet's Export button does: permission and source checks, remembering the
 * choices as defaults, and submitting the render/encode/publish run. Moved verbatim out of
 * EditorScreen.kt (#273); the editor state it reads is passed in, and `status` writes go
 * through [setStatus].
 */
package com.spectrafilm.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.material3.SnackbarHostState
import androidx.core.content.ContextCompat
import com.spectrafilm.app.masks.MaskCompositor
import com.spectrafilm.app.masks.hasSemantic
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.RenderKind
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun startEditorExport(
    dispatchers: AppDispatchers,
    exportVm: ExportViewModel,
    exportPresetVm: ExportPresetViewModel,
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    engine: SpektraEngine?,
    sourceRenderAllowed: Boolean,
    sourceUri: Uri?,
    sourceName: String,
    exportSourceIdentity: ExportSourceIdentity,
    exportPresets: ExportPresetDocument,
    snackbarHost: SnackbarHostState,
    legacyStoragePermission: ManagedActivityResultLauncher<String, Boolean>,
    mediaLocationPermissionMissing: () -> Boolean,
    currentSourceDecodeRequest: () -> SourceDecodeRequest,
    checkpointEditorSession: () -> Boolean,
    onSettingsAction: (SettingsAction) -> Unit,
    setStatus: (String) -> Unit,
    rememberPreset: Boolean = true,
) {
    val exportOptions = exportVm.current.options
    val exportKeepGps = exportVm.current.keepGps
    // The next sheet opens with this preset (or with none, if the choices
    // no longer match one).
    if (rememberPreset) {
        exportPresetVm.markUsed(
            exportPresets.presets.firstOrNull { it.matches(exportOptions, exportKeepGps) }?.name,
        )
    }
    if (!sourceRenderAllowed) {
        exportVm.onAction(ExportAction.CloseSheet)
        setStatus(ctx.getString(R.string.editor_status_source_access_required))
        return
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        runCatching {
            legacyStoragePermission.launch(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }
        setStatus(ctx.getString(R.string.editor_status_grant_storage))
        return
    }
    if (mediaLocationPermissionMissing()) {
        // Not a block: the file can still be written, it just cannot
        // carry GPS. Say so once and proceed; readSourceExif falls back
        // to the redacted stream. No permission dialog here either — a
        // system dialog over the render is the focus-loss cost the
        // notification comment above measured (§15.1).
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(
                ctx.getString(R.string.editor_snack_export_without_gps),
            )
        }
    }
    val e = engine
    if (e != null) {
        val outputDescriptor = runCatching {
            exportOptions.outputDescriptor(
                state.outputColorSpace,
                state.savingCctfEncoding,
                Build.VERSION.SDK_INT,
            ).also { ColorManagement.requireIccBytes(ctx, it) }
        }.getOrElse { failure ->
            Diag.w("export settings rejected: ${failure.message}")
            val message = ctx.getString(R.string.editor_msg_export_settings_unsupported)
            setStatus(message)
            // The snackbar host lives under the sheet, so close the sheet first or the
            // message would show behind it; the action reopens it to change the choice.
            exportVm.onAction(ExportAction.CloseSheet)
            scope.showEditorMessage(
                snackbarHost,
                message,
                actionLabel = ctx.getString(R.string.editor_msg_change_settings),
            ) { exportVm.onAction(ExportAction.OpenSheet) }
            return
        }
        exportVm.onAction(ExportAction.CloseSheet)
        // Remember the choices as the new Settings defaults, through the
        // settings ViewModel so an open Settings screen shows them at once.
        onSettingsAction(SettingsAction.SetExportFormat(exportOptions.format))
        onSettingsAction(SettingsAction.SetExportQuality(exportOptions.jpegQuality))
        onSettingsAction(SettingsAction.SetKeepGps(exportKeepGps))
        onSettingsAction(SettingsAction.SetExportBorder(exportOptions.border))
        onSettingsAction(SettingsAction.SetExportFrame(exportOptions.frame))
        val fmt = outputDescriptor.format
        val exportFmt = fmt
        val baseName = exportBaseName(
            exportOptions.customName,
            System.currentTimeMillis(),
            ExportNameTokens(
                sourceName = sourceName,
                film = StockCatalog.displayName(ctx, state.filmProfile),
                print = StockCatalog.displayName(ctx, state.printProfile),
            ),
        )
        val longEdge = exportOptions.targetLongEdge()
        val exportBorder = exportOptions.effectiveBorder()
        val exportFrame = exportOptions.effectiveFrame()
        // Read on the caller's thread with the rest of the editor state; the caption uses it.
        val frameStock = StockCatalog.displayName(ctx, state.filmProfile)
        val exportSharpen = exportOptions.effectiveSharpen()
        val keepGps = exportKeepGps
        // Wall-clock breadcrumbs for the on-device baseline capture
        // (issue #119): logcat-visible start marker + duration on ok/fail.
        val exportStartMs = System.currentTimeMillis()
        Diag.i("export start format=${exportFmt.name} contract=${outputDescriptor.existingExportClass.id}")
        setStatus(ctx.getString(R.string.editor_status_rendering_full))
        val exportContext = ctx.applicationContext
        val exportParams = if (outputDescriptor.engineColorSpace == null) {
            state.toParams()
        } else {
            outputDescriptor.applyTo(state.toParams())
        }
        val exportCctf = outputDescriptor.engineCctfEncoding ?: false
        val exportSaturation = state.saturation
        val exportVibrance = state.vibrance
        val exportGamutCompress = state.gamutCompress
        // Skin masks are bound here, once, to the field found for this photo; the cache key below
        // names that field so an export with skin found never reuses one rendered without it.
        val exportSkin = SkinFields.current()
        val exportAdjustments = state.localAdjustments.toList()
            .withSkinField(exportParams.io, entry = exportSkin)
        val exportJpegQuality = exportOptions.jpegQuality
        val exportSourceUri = sourceUri
        val exportDecodeRequest = currentSourceDecodeRequest()
        // #179 cache identity. The decode identity is spelled out rather
        // than taken from exportDecodeRequest.toString(), which embeds a
        // Context whose toString differs between two identical exports.
        val exportDecodeIdentity = decodeIdentityOf(exportDecodeRequest)
        val exportGradeKey = ExportCacheKey.Grade(
            exportCctf, exportSaturation, exportVibrance,
            exportGamutCompress, state.localAdjustments.toList(),
            skinField = if (state.localAdjustments.any { it.mask.hasSemantic }) exportSkin?.fingerprint ?: "none" else null,
        )
        val launched = ExportWorkRuntime.launch(
            context = exportContext,
            format = exportFmt,
            startedAtMillis = exportStartMs,
            sourceIdentity = exportSourceIdentity,
        ) {
            var phSetup = 0L
            var phDecode = 0L
            var phExif = 0L
            var phSim = 0L
            var phGrade = 0L
            var phEnc = 0L
            var exportCacheKey: String? = null
            var exportPrerenderKey: String? = null
            var servedFromCache = false
            var renderId = 0L
            val destinationCommit = ExportCommitLinearization()
            var previewCandidate: Bitmap? = null
            // #162: the committed MediaStore row, threaded into the terminal
            // outcome so the result UI can open/share it by content URI.
            var publishedUri: Uri? = null
            try {
                val bitmap =
                withContext(dispatchers.default) {
                    // Full-res off-heap buffers (the OOM fix) — close input/result promptly.
                    // Phase breadcrumbs: the engine's own `stage timings` line
                    // accounts for only ~61% of an export (8819 ms of 14476 on a
                    // 12.5 MP Ultra HDR run). The other ~39% is decode, the
                    // full-res bitmap grade and the encode — none of which any
                    // measurement has ever separated, which makes it impossible
                    // to say what a GPU pipeline could and could not reach.
                    // setup = the permission check, the startForegroundService
                    // IPC round trip and the coroutine dispatch hops.
                    val tDecode0 = System.currentTimeMillis()
                    phSetup = tDecode0 - exportStartMs
                    ExportWorkRuntime.reportStage(ExportStage.DECODE)

                    // #179: a complete-key hit publishes the exact bytes a
                    // previous export produced, skipping decode, engine,
                    // grade and encode. Scene-linear TIFF bypasses the engine
                    // on a separate path and is not cached.
                    val exportSource =
                        if (exportFmt == ExportFormat.SCENE_LINEAR_TIFF) {
                            null
                        } else {
                            ExportCacheKey.digestSource {
                                exportSourceUri?.let {
                                    exportContext.contentResolver.openInputStream(it)
                                }
                            }?.let { (sha, bytes) ->
                                ExportCacheKey.Source(
                                    sha, bytes, EXPORT_MAX_EDGE_PX,
                                    exportDecodeIdentity,
                                )
                            }
                        }
                    val exportContract = ExportCaches.contractVersionOf(exportContext)
                    exportCacheKey = exportSource?.let {
                        ExportCacheKey.compute(
                            it,
                            exportParams,
                            exportGradeKey,
                            outputDescriptor,
                            longEdge,
                            exportJpegQuality,
                            keepGps,
                            exportBorder,
                            exportContract,
                            exportSharpen,
                            exportFrame.look,
                        )
                    }
                    // Pre-encode identity: the same payload serves every
                    // container, quality and resize of this edit, so it is keyed
                    // on the engine inputs alone (#179).
                    exportPrerenderKey = exportSource?.let {
                        RenderPayloads.key(
                            it,
                            exportParams,
                            outputDescriptor.engineColorSpace,
                            exportContract,
                        )
                    }
                    // #270: an idle pre-render of this very edit is joined, so its
                    // payload is read below instead of rendered a second time; one
                    // of any other edit is cancelled and waited out, so the two never
                    // render at once.
                    if (RenderPayloads.flight.settle(exportPrerenderKey)) {
                        Diag.i("export joined the in-flight pre-render")
                    }
                    val cached = exportCacheKey?.let {
                        ExportCaches.of(exportContext).get(it)
                    }
                    if (cached != null) {
                        publishedUri = withContext(dispatchers.io) {
                            publishCachedExport(
                                exportContext,
                                cached,
                                "$baseName.${exportFmt.ext}",
                                outputDescriptor.format.mime,
                                onCommitted = destinationCommit::markPublished,
                            )
                        }
                        servedFromCache = true
                        phEnc = System.currentTimeMillis() - tDecode0
                        Diag.i("export served from cache in ${phEnc} ms")
                        return@withContext null
                    }

                    // #179: the idle pre-render stores the ENGINE output, so a
                    // hit skips decode and simulate (~6 s on BASE, ~14.5 s on
                    // HEAVY) while the export still runs the real encoder — the
                    // published bytes come from the same code either way.
                    val restored = exportPrerenderKey?.let {
                        RenderPayloads.of(exportContext).get(it)
                    }
                    val image = if (restored != null) null else {
                        decodeSourceRequest(
                            exportDecodeRequest,
                            EXPORT_MAX_EDGE_PX,
                            dispatchers.io,
                        ).image
                    }
                    phDecode = System.currentTimeMillis() - tDecode0
                    if (exportFmt == ExportFormat.SCENE_LINEAR_TIFF) {
                        // Export the decoded scene-linear INPUT (before the film
                        // engine) as a 32-bit float TIFF; the engine is skipped.
                        ExportWorkRuntime.reportStage(ExportStage.ENCODE)
                        val tEnc0 = System.currentTimeMillis()
                        try {
                            publishedUri = withContext(dispatchers.io) {
                                saveLinearInputAsTiff32f(
                                    exportContext,
                                    // Scene-linear exports the DECODE, never the
                                    // engine output, so they are never pre-rendered
                                    // and the image is always present here.
                                    requireNotNull(image) { "scene-linear needs a decode" },
                                    outputDescriptor,
                                    baseName,
                                    onCommitted = destinationCommit::markPublished,
                                )
                            }
                        } finally {
                            image?.close()
                        }
                        phEnc = System.currentTimeMillis() - tEnc0
                        null  // no rendered bitmap to preview
                    } else {
                        // Copy source EXIF; GPS only when opted in.
                        // A full EXIF read of a ~25 MB RAW plus two thread-pool
                        // hops — its own phase, not silently between two others.
                        ExportWorkRuntime.reportStage(ExportStage.EXIF)
                        val tExif0 = System.currentTimeMillis()
                        val srcExif = withContext(dispatchers.io) {
                            readSourceExif(exportContext, exportSourceUri, keepGps = keepGps)
                        }
                        phExif = System.currentTimeMillis() - tExif0
                        // The frame caption is worded from this photo's EXIF and film stock.
                        val frameCaption = FrameCaptions.text(
                            exportFrame.look,
                            srcExif.tags,
                            frameStock,
                        )
                        // `simulate` is the engine plus JNI marshalling and the
                        // ~140 MB result allocation/wrap. #163's outer native
                        // timing now covers that whole boundary; this Kotlin phase
                        // additionally includes the input close below and dispatch
                        // bookkeeping, so a small positive gap remains expected.
                        ExportWorkRuntime.reportStage(ExportStage.SIMULATE)
                        val tSim0 = System.currentTimeMillis()
                        // Ultra HDR: the scene's highlight light, sampled while the
                        // decode is still open (SceneHighlightTap). Null otherwise.
                        var sceneTap: SceneLuma? = null
                        val res = restored ?: try {
                            // #269: one reclaim-and-retry on memory exhaustion.
                            retryOnceAfterMemoryReclaim("export render") {
                                runCancellableNative(
                                    onLateResult = { late ->
                                        late.reportOutcome(AppRenderOutcome.CANCELLED)
                                        late.close()
                                    },
                                ) { cancellation ->
                                    e.simulate(
                                        requireNotNull(image) { "render needs a decode" },
                                        exportParams,
                                        RenderKind.EXPORT,
                                        cancellation,
                                    )
                                }
                            }.also { rendered ->
                                renderId = rendered.renderId
                                sceneTap = image?.let { source ->
                                    runCatching {
                                        captureSceneTapForGainmap(
                                            ctx, source, exportParams, rendered.width,
                                            rendered.height, outputDescriptor,
                                        )
                                    }.onFailure {
                                        Diag.w("gain map scene tap failed: $it")
                                    }.getOrNull()
                                }
                            }
                        } finally {
                            image?.close()
                        }
                        if (restored != null && outputDescriptor.metadata.hdrGainMap != null) {
                            sceneTap = restoredSceneTap(
                                dispatchers,
                                exportContext, ctx, exportPrerenderKey, exportDecodeRequest,
                                exportParams, res, outputDescriptor,
                            )
                        }
                        phSim = System.currentTimeMillis() - tSim0
                        if (restored != null) {
                            Diag.i("export used pre-rendered payload in $phSim ms")
                        }
                        try {
                            check(res.colorSpace == outputDescriptor.engineColorSpace) {
                                "Engine result color space disagrees with output contract"
                            }
                            ExportWorkRuntime.reportStage(ExportStage.GRADE)
                            val tGrade0 = System.currentTimeMillis()
                            val bitmapEncoder = when (outputDescriptor.encoder) {
                                OutputEncoder.ANDROID_BITMAP_PNG,
                                OutputEncoder.ANDROID_BITMAP_JPEG,
                                -> true
                                OutputEncoder.NATIVE_TIFF_UINT16,
                                OutputEncoder.NATIVE_TIFF_FLOAT32,
                                OutputEncoder.NATIVE_PNG16,
                                -> false
                                else -> error("Scene-linear encoder reached rendered branch")
                            }
                            val bmp = if (bitmapEncoder) {
                                val full = simResultToBitmapGraded(
                                    res,
                                    exportCctf,
                                    exportSaturation,
                                    exportVibrance,
                                    exportGamutCompress,
                                    exportAdjustments,
                                )
                                // Take ownership immediately. If downscaling or any
                                // pre-commit step throws, the outer failure path recycles it.
                                previewCandidate = full
                                // Post-render downscale for Bitmap encoders only. Native
                                // high-bit writers consume the float buffer without ever
                                // materialising a full-resolution ARGB_8888 Bitmap.
                                // With a border, the requested long edge is the
                                // FINISHED image's, so a 2048 px export is still
                                // 2048 px wide: shrink the photo to leave room.
                                val photoEdge = longEdge?.let { edge ->
                                    FilmBorderGeometry.photoLongEdgeFor(
                                        exportFrame, full.width, full.height, edge, frameCaption,
                                    )
                                }
                                val sized = photoEdge?.let { edge ->
                                    scaleBitmapToLongEdge(full, edge).also { scaled ->
                                        if (scaled !== full) {
                                            previewCandidate = scaled
                                            runCatching { full.recycle() }
                                        }
                                    }
                                } ?: full
                                // Output sharpening, once, on the final-size
                                // photo: after the resize (so it suits the size it
                                // is seen at) and before the border (so the frame
                                // edges stay clean). The gain map below is computed
                                // from the float render, not these pixels.
                                if (sized.isMutable) {
                                    sharpenBitmapInPlace(sized, exportSharpen)
                                } else if (exportSharpen != OutputSharpen.NONE) {
                                    Diag.w("export bitmap immutable; output sharpening skipped")
                                }
                                // Film border around the photo (output grows).
                                // Frame number is stable per source, so the
                                // cache key's source hash already covers it.
                                val borderLayout = FilmBorderGeometry.layout(
                                    exportFrame,
                                    sized.width,
                                    sized.height,
                                    FilmBorderGeometry.frameNumberFor(
                                        exportSource?.contentSha256,
                                    ),
                                    frameCaption,
                                )
                                val framed = borderLayout?.let { layout ->
                                    FilmBorderRenderer.render(sized, layout).also {
                                        previewCandidate = it
                                        runCatching { sized.recycle() }
                                    }
                                } ?: sized
                                // #140: derive the Ultra HDR gain map from the
                                // render itself, here, while the engine's float
                                // buffer is still alive — it holds the values
                                // above SDR white that the 8-bit bitmap clamped
                                // away, and they are the only honest HDR signal
                                // this pipeline has.
                                if (outputDescriptor.metadata.hdrGainMap != null) {
                                    runCatching {
                                        attachRenderedGainmap(
                                            res, framed, outputDescriptor, borderLayout, sceneTap,
                                        )
                                    }.onFailure {
                                        Diag.w("gain map failed; exporting SDR base: $it")
                                    }
                                }
                                framed
                            } else {
                                // Preserve WYSIWYG grading in the writer's float source while
                                // avoiding the additional ~4 bytes/pixel full-res Bitmap.
                                res.acquireDataLease().use { lease ->
                                    ColorGrade.applyInPlace(
                                        lease.data, res.width, res.height, res.colorSpace,
                                        exportCctf, exportSaturation, exportVibrance,
                                        exportGamutCompress,
                                    )
                                    MaskCompositor.applyInPlace(
                                        lease.data, res.width, res.height, res.colorSpace,
                                        exportCctf, exportAdjustments,
                                    )
                                }
                                null
                            }
                            previewCandidate = bmp
                            phGrade = System.currentTimeMillis() - tGrade0
                            ExportWorkRuntime.reportStage(ExportStage.ENCODE)
                            val tEnc0 = System.currentTimeMillis()
                            publishedUri = withContext(dispatchers.io) {
                                when (outputDescriptor.encoder) {
                                    OutputEncoder.NATIVE_TIFF_UINT16,
                                    OutputEncoder.NATIVE_TIFF_FLOAT32 -> saveSimResultAsTiff(
                                        exportContext,
                                        res,
                                        outputDescriptor,
                                        displayName = baseName,
                                        onCommitted = destinationCommit::markPublished,
                                        onStaged = { file, len, sha ->
                                            exportCacheKey?.let { k ->
                                                ExportCaches.of(exportContext)
                                                    .adopt(k, file, len, sha)
                                            }
                                        },
                                    )
                                    OutputEncoder.NATIVE_PNG16 -> saveSimResultAsPng16(
                                        exportContext,
                                        res,
                                        outputDescriptor,
                                        displayName = baseName,
                                        onCommitted = destinationCommit::markPublished,
                                        onStaged = { file, len, sha ->
                                            exportCacheKey?.let { k ->
                                                ExportCaches.of(exportContext)
                                                    .adopt(k, file, len, sha)
                                            }
                                        },
                                    )
                                    OutputEncoder.ANDROID_BITMAP_PNG,
                                    OutputEncoder.ANDROID_BITMAP_JPEG -> saveToGallery(
                                        exportContext,
                                        requireNotNull(bmp) {
                                            "Bitmap encoder missing graded bitmap"
                                        },
                                        outputDescriptor,
                                        exportJpegQuality,
                                        srcExif,
                                        displayName = baseName,
                                        onCommitted = destinationCommit::markPublished,
                                        onStaged = { file, len, sha ->
                                            exportCacheKey?.let { k ->
                                                ExportCaches.of(exportContext)
                                                    .adopt(k, file, len, sha)
                                            }
                                        },
                                    )
                                    else -> error("Scene-linear encoder reached rendered branch")
                                }
                            }
                            phEnc = System.currentTimeMillis() - tEnc0
                            // Native high-bit exports deliberately retain no preview; the
                            // existing editor preview stays visible at bounded fit size.
                            bmp
                        } finally {
                            res.close()
                        }
                    }
                }
                // A framed export is not handed back as the editor preview: the preview
                // is the photo alone, and the Frame tab paints the frame around it, so
                // a framed bitmap there would come back with a second frame.
                val retainedBitmap = if (!exportFrame.isNone) {
                    runCatching { bitmap?.recycle() }
                    null
                } else runCatching {
                    bitmap?.let { full ->
                        scaleBitmapToLongEdge(full, 2_048).also { proxy ->
                            if (proxy !== full) full.recycle()
                        }
                    }
                }.getOrElse {
                    runCatching { bitmap?.recycle() }
                    null
                }
                // Keep cleanup authority pointed at the final proxy until the terminal
                // outcome object is successfully constructed and owns it.
                previewCandidate = retainedBitmap
                // A cache hit never asked the engine to render, so there is
                // no outcome to report and renderId is still its 0 sentinel.
                if (!servedFromCache) {
                    runCatching {
                        SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
                    }
                }
                val totalMs = System.currentTimeMillis() - exportStartMs
                Diag.i(
                    "export done in $totalMs ms " +
                        (if (servedFromCache) "(cache hit)" else "(rendered)"),
                )
                val success = ExportTerminalOutcome.Success(
                    format = exportFmt,
                    renderId = renderId,
                    bitmap = retainedBitmap,
                    totalMs = totalMs,
                    publishedUri = publishedUri?.toString(),
                    publishedMimeType = exportFmt.mime,
                    phases = ExportPhaseSnapshot(
                        setupMs = phSetup,
                        decodeMs = phDecode,
                        exifMs = phExif,
                        simulateMs = phSim,
                        gradeMs = phGrade,
                        encodeMs = phEnc,
                    ),
                )
                previewCandidate = null
                success
            } catch (failure: Throwable) {
                if (destinationCommit.isPublished) {
                    // Publication is the transaction's linearization point.
                    // Preview scaling, native telemetry, close/unwind, or outcome
                    // construction failures after it can never turn success into a
                    // retryable failure/duplicate export.
                    runCatching { previewCandidate?.recycle() }
                    runCatching {
                        SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
                    }
                    ExportTerminalOutcome.Success(
                        format = exportFmt,
                        renderId = renderId,
                        bitmap = null,
                        totalMs = System.currentTimeMillis() - exportStartMs,
                        publishedUri = publishedUri?.toString(),
                        publishedMimeType = exportFmt.mime,
                        phases = ExportPhaseSnapshot(
                            setupMs = phSetup,
                            decodeMs = phDecode,
                            exifMs = phExif,
                            simulateMs = phSim,
                            gradeMs = phGrade,
                            encodeMs = phEnc,
                        ),
                    )
                 } else {
                    runCatching { previewCandidate?.recycle() }
                    if (failure is CancellationException) {
                        SimResult.reportOutcome(renderId, AppRenderOutcome.CANCELLED)
                        ExportTerminalOutcome.Cancelled(
                            format = exportFmt,
                            renderId = renderId,
                            elapsedMs = System.currentTimeMillis() - exportStartMs,
                        )
                    } else {
                        SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
                        ExportTerminalOutcome.Failure(
                            format = exportFmt,
                            renderId = renderId,
                            elapsedMs = System.currentTimeMillis() - exportStartMs,
                            cause = failure,
                        )
                    }
                 }
            }
        }
        if (launched == null) {
            setStatus(ctx.getString(R.string.editor_status_export_running))
        } else {
            exportVm.onAction(ExportAction.Running(launched))
            checkpointEditorSession()
        }
    }
}

/**
 * The Ultra HDR scene tap for an export served from the idle pre-render, which has no decode of
 * its own. The pre-render stores the tap with its payload; when it has none (it ran while another
 * format was selected, or before the tap existed) the source is decoded once more just for the
 * tap, because without it the gain map is flat: the film render itself never exceeds SDR white.
 */
private suspend fun restoredSceneTap(
    dispatchers: AppDispatchers,
    exportContext: android.content.Context,
    ctx: android.content.Context,
    prerenderKey: String?,
    decodeRequest: SourceDecodeRequest,
    params: com.spectrafilm.engine.SpektraParams,
    rendered: com.spectrafilm.engine.SimResult,
    descriptor: OutputDescriptor,
): SceneLuma? {
    prerenderKey?.let { RenderPayloads.of(exportContext).getScene(it) }?.let {
        Diag.i("gain map scene tap restored with the pre-render")
        return it
    }
    return runCatching {
        decodeSourceRequest(decodeRequest, EXPORT_MAX_EDGE_PX, dispatchers.io).image.use { source ->
            captureSceneTapForGainmap(ctx, source, params, rendered.width, rendered.height, descriptor)
        }
    }.onSuccess {
        Diag.i("gain map scene tap re-decoded for the pre-render")
    }.onFailure {
        Diag.w("gain map scene tap failed: $it")
    }.getOrNull()
}
