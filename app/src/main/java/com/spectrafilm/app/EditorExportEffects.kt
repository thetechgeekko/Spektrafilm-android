/*
 * Spektrafilm for Android — editor export effects. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Two LaunchedEffect bodies of EditorScreen, moved verbatim (#273): the observer of the
 * process-owned export runtime, and the speculative export pre-render. Each still runs in its
 * LaunchedEffect's own scope with the same keys. Editor `var`s are passed as their state
 * holders and re-bound as local delegates, so reads after a suspension stay live.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.RenderKind
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Observes the process-owned export runtime; see the comment at its LaunchedEffect in EditorScreen. */
internal suspend fun CoroutineScope.runExportRuntimeObserver(
    dispatchers: AppDispatchers,
    exportVm: ExportViewModel,
    screen: EditorScreenState,
    ctx: Context,
    exportSourceIdentity: ExportSourceIdentity,
    exportRuntimeStateState: State<ExportRuntimeState>,
    statusState: MutableState<String>,
    messageScope: CoroutineScope,
) {
    val publicationGate = screen.publicationGate
    val sessionCheckpointAction = screen.sessionCheckpointAction
    val exportPublicationState = screen.exportPublicationState
    val lastHandledExportRunState = screen.lastHandledExportRunState
    val recipeReadyState = screen.recipeReadyState
    val previewState = screen.previewState
    val snackbarHost = screen.snackbarHost
    val exportRuntimeState by exportRuntimeStateState
    var exportPublication by exportPublicationState
    var lastHandledExportRun by lastHandledExportRunState
    var recipeReady by recipeReadyState
    var preview by previewState
    var status by statusState
    when (val runtime = exportRuntimeState) {
        ExportRuntimeState.Idle -> {
            if (exportVm.current.phase == EditorExportPhase.RECONCILING) {
                exportVm.onAction(ExportAction.RuntimeIdle)
                status = ctx.getString(R.string.editor_status_prev_export_reconciling)
            }
        }
        is ExportRuntimeState.Running -> {
            // Ownership (exact current source binding) tracks the durable cursor; full
            // publication authorization additionally requires an authorization-free
            // source and gates only the pixels. A revoked source still remembers its own
            // in-flight run so process-death recovery can reconcile it (ticket #139).
            if (!exportRunOwnedByEditor(runtime.sourceIdentity, exportSourceIdentity)) {
                exportVm.onAction(ExportAction.RunDisowned(runtime.runId))
                return
            }
            if (
                exportPublication?.first != runtime.runId &&
                exportPublicationAuthorized(runtime.sourceIdentity, exportSourceIdentity)
            ) {
                exportPublication = runtime.runId to publicationGate.begin(
                    publicationGate.nextRevision(),
                    RenderPublicationPriority.EXPORT,
                )
            }
            exportVm.onAction(ExportAction.Running(runtime.runId))
            status = ctx.getString(R.string.editor_status_rendering_full)
            sessionCheckpointAction[0]?.invoke()
        }
        is ExportRuntimeState.Finished -> {
            if (!exportPublicationAuthorized(runtime.sourceIdentity, exportSourceIdentity)) {
                val discarded = ExportWorkRuntime.claimFinished(runtime.runId)
                    ?: return
                recycleUnpublishedExport(discarded)
                lastHandledExportRun = runtime.runId
                exportPublication = null
                exportVm.onAction(ExportAction.RunDisowned(runtime.runId))
                return
            }
            if (!recipeReady || sessionCheckpointAction[0] == null) return
            if (runtime.runId == lastHandledExportRun) return
            // Persist a sanitized terminal marker before atomically claiming the process
            // result. If recreation cancels this coroutine before/during the IO flush, the
            // process runtime still owns the unclaimed result; if it cancels after the
            // flush, the replacement UI can restore the terminal marker exactly once.
            when (val pending = runtime.outcome) {
                is ExportTerminalOutcome.Success -> {
                    exportVm.onAction(ExportAction.Terminal(EditorExportPhase.SUCCESS))
                    status = ctx.getString(R.string.editor_status_saved_to_gallery)
                }
                is ExportTerminalOutcome.Failure -> {
                    exportVm.onAction(
                        ExportAction.Terminal(
                            if (pending.cause is ExportReconciliationPendingException) {
                                EditorExportPhase.RECONCILING
                            } else {
                                EditorExportPhase.FAILURE
                            },
                        ),
                    )
                }
                is ExportTerminalOutcome.Cancelled -> {
                    exportVm.onAction(ExportAction.Terminal(EditorExportPhase.CANCELLED))
                }
            }
            val durableTerminal = awaitDurableEditorSessionCheckpoint(
                checkpoint = { sessionCheckpointAction[0]?.invoke() == true },
                flush = {
                    withContext(dispatchers.io) {
                        EditorSessionCheckpointRuntime.flush()
                    }
                },
                onRetryableFailure = { failure ->
                    // The process runtime keeps the terminal result unclaimed while the
                    // latest checkpoint remains pending. Retry without exposing session
                    // contents or allowing an older disk generation to count as success.
                    Diag.w(
                        "terminal editor session save retrying " +
                            "(${failure.javaClass.simpleName})",
                    )
                    status = ctx.getString(R.string.editor_status_export_complete_retrying_save)
                    delay(500)
                },
            )
            if (!durableTerminal) {
                // Startup storage may still be recovering, or a future-version session may
                // be protected. In both cases the process outcome remains retained.
                status = ctx.getString(R.string.editor_status_export_complete_save_waiting)
                return
            }
            val publicationTicket = exportPublication
                ?.takeIf { it.first == runtime.runId }
                ?.second
                ?: publicationGate.begin(
                    publicationGate.nextRevision(),
                    RenderPublicationPriority.EXPORT,
                )
            // Identity decides the durable outcome; the render gate only decides whether
            // this export's pixels may replace the preview. A newer interactive render
            // outranking the export ticket must suppress stale pixels, never rewrite a
            // committed MediaStore success into RECONCILING (ticket #139).
            val identityAuthorized =
                exportPublicationAuthorized(runtime.sourceIdentity, exportSourceIdentity)
            val publicationAllowed =
                identityAuthorized && publicationGate.tryClaim(publicationTicket)
            val claimedOutcome = ExportWorkRuntime.claimFinished(runtime.runId)
                ?: return
            lastHandledExportRun = runtime.runId
            exportPublication = null
            when (val outcome = claimedOutcome) {
                is ExportTerminalOutcome.Success -> {
                    outcome.bitmap?.let { bitmap ->
                        if (publicationAllowed) {
                            preview = bitmap
                        } else if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                    if (!identityAuthorized) {
                        exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.RECONCILING))
                        status = ctx.getString(R.string.editor_status_export_stale_source)
                        sessionCheckpointAction[0]?.invoke()
                        return
                    }
                    val phases = outcome.phases
                    val accounted = phases.setupMs + phases.decodeMs + phases.exifMs +
                        phases.simulateMs + phases.gradeMs + phases.encodeMs
                    Diag.i(
                        "export phases ms: setup=${phases.setupMs} decode=${phases.decodeMs} " +
                            "exif=${phases.exifMs} simulate=${phases.simulateMs} " +
                            "grade=${phases.gradeMs} encode=${phases.encodeMs} " +
                            "residual=${outcome.totalMs - accounted} total=${outcome.totalMs}",
                    )
                    Diag.i("export format=${outcome.format.name} ok in ${outcome.totalMs}ms")
                    exportVm.onAction(
                        ExportAction.SetShare(
                            outcome.publishedUri?.let { published ->
                                published to (outcome.publishedMimeType ?: "image/*")
                            },
                        ),
                    )
                    exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.SUCCESS))
                    status = ctx.getString(R.string.editor_status_saved_to_gallery)
                }
                is ExportTerminalOutcome.Failure -> {
                    Diag.w(
                        "export format=${outcome.format.name} failed after " +
                            "${outcome.elapsedMs}ms: ${outcome.cause.message}" +
                            // #269: a memory failure report should carry the budget
                            // state without the user having to export diagnostics.
                            if (isMemoryExhaustion(outcome.cause)) {
                                " budget=${memoryBudgetSnapshotForLog()}"
                            } else {
                                ""
                            },
                    )
                    if (outcome.cause is ExportReconciliationPendingException) {
                        exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.RECONCILING))
                        status = ctx.getString(R.string.editor_status_export_pending_reconciliation)
                        messageScope.showEditorMessage(
                            snackbarHost,
                            ctx.getString(R.string.editor_toast_export_reconciling),
                        )
                    } else {
                        exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.FAILURE))
                        // The cause is in the diagnostics log above; the user gets what
                        // happened and what to try, with Retry reopening the export sheet.
                        val cause = outcome.cause
                        val message = ctx.getString(
                            when {
                                cause is ExportSourceDegradedException && cause.previewOnly ->
                                    R.string.editor_msg_export_failed_raw_preview
                                cause is ExportSourceDegradedException ->
                                    R.string.editor_msg_export_failed_raw_memory
                                cause is com.spectrafilm.libraw.RawDecodeException &&
                                    cause.status == com.spectrafilm.libraw.DecodeStatus.NO_MEMORY ->
                                    R.string.editor_msg_export_failed_raw_memory
                                isMemoryExhaustion(cause) -> R.string.editor_msg_export_failed_memory
                                else -> R.string.editor_msg_export_failed
                            },
                        )
                        status = message
                        messageScope.showEditorMessage(
                            snackbarHost,
                            message,
                            actionLabel = ctx.getString(R.string.editor_msg_retry),
                        ) { exportVm.onAction(ExportAction.OpenSheet) }
                    }
                }
                is ExportTerminalOutcome.Cancelled -> {
                    Diag.i(
                        "export format=${outcome.format.name} cancelled after " +
                            "${outcome.elapsedMs}ms",
                    )
                    exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.CANCELLED))
                    status = ctx.getString(R.string.editor_status_export_cancelled)
                }
            }
            sessionCheckpointAction[0]?.invoke()
        }
    }
}

/** Speculative export pre-render; see the comment at its LaunchedEffect in EditorScreen. */
internal suspend fun CoroutineScope.runSpeculativePrerender(
    dispatchers: AppDispatchers,
    exportVm: ExportViewModel,
    ctx: Context,
    state: ParamsState,
    sourceRenderAllowed: Boolean,
    currentSourceDecodeRequest: () -> SourceDecodeRequest,
    engineState: State<SpektraEngine?>,
    previewBusyState: MutableState<Boolean>,
    sourceState: State<EditorSourceState>,
    prerenderDigestState: MutableState<Pair<String, Long>?>,
    prerenderOutOfMemoryState: MutableState<Boolean>,
    previewState: State<Bitmap?>,
    decodingState: State<Boolean>,
) {
    val engine by engineState
    var previewBusy by previewBusyState
    var prerenderDigest by prerenderDigestState
    var prerenderOutOfMemory by prerenderOutOfMemoryState
    val activeEngine = engine ?: return
    val activeUri = sourceState.value.uri?.let(Uri::parse) ?: return
    if (!sourceRenderAllowed || exportVm.current.inFlight ||
        ExportWorkRuntime.state.value is ExportRuntimeState.Running
    ) return
    if (prerenderOutOfMemory) return
    delay(RenderPayloads.IDLE_DEBOUNCE_MS)
    // Only once the editor's own first preview is on screen and idle: opening a large RAW
    // decodes it for the preview, and a full-size pre-render on top of that is what had the
    // low-memory killer take background apps.
    val settled = withTimeoutOrNull(RenderPayloads.PREVIEW_SETTLE_TIMEOUT_MS) {
        while (previewState.value == null || decodingState.value || previewBusy) delay(250)
        delay(RenderPayloads.PREVIEW_SETTLE_MS)
        true
    } == true
    if (!settled || previewState.value == null || decodingState.value || previewBusy ||
        exportVm.current.inFlight || ExportWorkRuntime.state.value is ExportRuntimeState.Running
    ) return
    val app = ctx.applicationContext
    val descriptor = runCatching {
        exportVm.current.options.outputDescriptor(
            state.outputColorSpace, state.savingCctfEncoding, Build.VERSION.SDK_INT,
        )
    }.getOrNull() ?: return
    // Scene-linear exports the DECODE, not the engine output: nothing to pre-render.
    if (descriptor.format == ExportFormat.SCENE_LINEAR_TIFF) return
    val params = if (descriptor.engineColorSpace == null) {
        state.toParams()
    } else {
        descriptor.applyTo(state.toParams())
    }
    val request = runCatching { currentSourceDecodeRequest() }.getOrNull()
        ?: return
    // The source cannot change under a stable Uri, so hash it once per source, not once
    // per idle: it is a full read of the file (~37 MB on the bench corpus).
    val digest = prerenderDigest ?: withContext(dispatchers.io) {
        ExportCacheKey.digestSource { app.contentResolver.openInputStream(activeUri) }
    }?.also { prerenderDigest = it } ?: return
    // #492: opening the store, the contract version and the holds() probe all read files
    // (StrictMode caught up to ~39 ms on Main per idle pass), so they run on IO.
    val (store, key, held) = withContext(dispatchers.io) {
        val store = RenderPayloads.of(app)
        val key = RenderPayloads.key(
            ExportCacheKey.Source(
                digest.first, digest.second, EXPORT_MAX_EDGE_PX, decodeIdentityOf(request),
            ),
            params,
            descriptor.engineColorSpace,
            ExportCaches.contractVersionOf(app),
        )
        Triple(store, key, store.holds(key))
    }
    if (held) return
    val sourcePixels = withContext(dispatchers.io) { mediaStorePixelCount(app, activeUri) }
    // Two readings a few seconds apart: one taken right after the system reclaimed cached
    // memory overstated what the render would have (5.7 GB, falling to 1.9 GB mid-render).
    if (!RenderPayloads.shouldPrerender(app, sourcePixels)) return
    delay(RenderPayloads.PRERENDER_RECHECK_MS)
    if (!RenderPayloads.shouldPrerender(app, sourcePixels)) return
    val startedMs = System.currentTimeMillis()
    // #270: the render runs as a tracked flight, not as this effect's child, so an
    // export of this very edit can join it instead of throwing it away (see
    // PrerenderFlight). Any other cancellation of this effect cancels it as before.
    val flight = RenderPayloads.flight.launch(key) {
        withContext(dispatchers.default) {
            val image = decodeSourceRequest(request, EXPORT_MAX_EDGE_PX, dispatchers.io).image
            // Ultra HDR's scene tap reads the decode, which an export served from
            // this payload will not have: take it now and store it alongside.
            var sceneTap: SceneLuma? = null
            val rendered = try {
                runCancellableNative(
                    onLateResult = { late ->
                        late.reportOutcome(AppRenderOutcome.CANCELLED)
                        late.close()
                    },
                ) { cancellation ->
                    activeEngine.simulate(image, params, RenderKind.EXPORT, cancellation)
                }.also { r ->
                    sceneTap = runCatching {
                        captureSceneTapForGainmap(app, image, params, r.width, r.height, descriptor)
                    }.getOrNull()
                }
            } finally {
                image.close()
                // Speculative full-res render: do not leave its GPU buffers
                // holding budget the real export will need (#269).
                releaseGpuAfterFullResRender("pre-render")
            }
            rendered.use {
                val stored = store.put(key, it, sceneTap)
                // A stored payload WAS consumed — its bytes outlive the result. One that
                // could not be stored is a render nothing ever used.
                it.reportOutcome(
                    if (stored) AppRenderOutcome.CONSUMED else AppRenderOutcome.FAILED,
                )
                stored
            }
        }
    }
    runCatching {
        try {
            flight.await()
        } catch (cancelled: CancellationException) {
            // An export start cancels this effect (it is one of its keys); the export
            // then settles the flight itself. Anything else ends the speculation here.
            if (!exportVm.current.inFlight) flight.cancel()
            throw cancelled
        }
    }.onSuccess { stored ->
        if (stored) {
            Diag.i("pre-rendered export in ${System.currentTimeMillis() - startedMs} ms")
        }
    }.onFailure { failure ->
        if (failure is CancellationException) throw failure
        Diag.w("pre-render skipped: $failure")
        // This is SPECULATIVE work for an export the user has not asked for, and it
        // decodes at EXPORT_MAX_EDGE_PX. On the 4080x3060 DNG that exposed this it cost
        // a full decode plus most of an export — 13 s and ~650 MB — before failing, while
        // the preview the user was actually waiting for got nothing. The budget will not
        // have grown by the next previewTick, so retrying only repeats the damage.
        // shouldPrerender's own guards do not catch this: they read the SYSTEM low-memory
        // flag, not this process's own budget, which is what refused the allocation.
        if (generateSequence(failure) { it.cause }.take(8).any { it is OutOfMemoryError }) {
            prerenderOutOfMemory = true
            Diag.w("pre-render stood down for this source after OOM")
        }
    }
}

/** The source's pixel count from MediaStore's width and height, or null when it has none. */
internal fun mediaStorePixelCount(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.MediaStore.MediaColumns.WIDTH, android.provider.MediaStore.MediaColumns.HEIGHT),
        null, null, null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val w = cursor.getLong(0)
        val h = cursor.getLong(1)
        if (w > 0 && h > 0) w * h else null
    }
}.getOrNull()
