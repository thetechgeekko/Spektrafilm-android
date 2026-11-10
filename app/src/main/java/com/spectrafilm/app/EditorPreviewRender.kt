/*
 * Spektrafilm for Android — editor preview render passes. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The three preview LaunchedEffect bodies of EditorScreen, moved verbatim (#273): the live
 * DRAFT pass, the crisp FINAL pass on settle, and the experimental GPU LUT feed. Each runs in
 * the LaunchedEffect's own scope. The editor `var`s they read and write are passed as their
 * MutableState holders and re-bound as local delegates, so every read is live, exactly as the
 * closures were.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Live DRAFT pass; see the comment at its LaunchedEffect in EditorScreen. */
internal suspend fun CoroutineScope.runDraftPreviewPass(
    dispatchers: AppDispatchers,
    /** True while an on-photo tool or before/after compare is up: no draft pass then. */
    draftSuppressed: () -> Boolean,
    ctx: Context,
    state: ParamsState,
    sourceRenderAllowed: Boolean,
    previewRevision: Long,
    publicationGate: RenderPublicationGate,
    gradeCache: GradeCache,
    interacting: MutableState<Boolean>,
    settings: AppSettings,
    sourceCache: DecodedSourceCache,
    gradeCacheKey: (Int) -> GradeCache.Key,
    cachedSourceDecodeRequest: suspend (DecodedSourceCache, Int) -> CachedSourceDecodeRequest,
    engineState: State<SpektraEngine?>,
    previewState: MutableState<Bitmap?>,
) {
    val engine by engineState
    var preview by previewState
    if (!sourceRenderAllowed) return
    val publicationTicket = publicationGate.begin(
        previewRevision,
        RenderPublicationPriority.DRAFT,
    )
    val e = engine ?: return
    if (draftSuppressed()) return
    // Lightroom's ICBSliderTrackingBegin/End gate. `interacting` has been
    // written by SliderInteraction since the widget shipped but was never
    // read, so the draft pass fired on EVERY edit — including discrete ones
    // (a switch, a dropdown, applying a preset), where it buys nothing: the
    // crisp settle pass is already only 500 ms away, so the draft just burns
    // a render and flashes a soft frame before the sharp one lands.
    //
    // Reading it here is what the widget's own doc comment always described:
    // draft while a slider is actually moving, straight to crisp otherwise.
    if (!interacting.value) return
    val fullEdge = state.previewMaxSize.coerceAtLeast(256)
    // Grade-only edit? Re-grade the retained full-quality settle result in
    // pure Kotlin — zero native work, and it beats a low-res draft on quality.
    gradeCache.lookup(gradeCacheKey(fullEdge))?.let { pristine ->
        val gradeResult = try {
            runCatching {
                withOwnedContext(
                    context = dispatchers.default,
                    dispose = { bitmap: Bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    },
                ) {
                    pristine.withScratch { scratch ->
                        gradeBufferToBitmap(scratch, pristine.width,
                            pristine.height, pristine.colorSpace, state.savingCctfEncoding,
                            state.saturation, state.vibrance, state.gamutCompress,
                            state.localAdjustments.withSkinField(state.cropIo()))
                    }
                }
            }
        } finally {
            pristine.close()
        }
        gradeResult.onSuccess { bmp ->
            withContext(dispatchers.main) {
                if (publicationGate.tryClaim(publicationTicket)) {
                    preview = bmp
                } else if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
            .onFailure { Diag.w("render mode=draft failed: ${it.message}") }
        return
    }
    // Progressive ladder rung. The edge is a setting rather than a constant
    // so the coarse/fine trade can be swept on a real device: lower = the
    // image tracks the finger more closely, higher = the live frame is closer
    // to what the settle pass will show. Defaults to DRAFT_RENDER_MAX_PX, so
    // an untouched install renders exactly as before.
    val draftEdge = minOf(settings.effectiveDraftEdge(), fullEdge)
    if (draftEdge >= fullEdge) return // no meaningful step-down to draft
    val proxyRequest = cachedSourceDecodeRequest(sourceCache, fullEdge)
    val proxyLease = sourceCache.acquire(
        proxyRequest.ticket,
    ) ?: return                      // proxy not cached yet — settle owns the decode
    var renderId = 0L
    val draftResult = proxyLease.use { lease ->
        runCatching {
            withOwnedContext(
                context = dispatchers.default,
                dispose = { bitmap: Bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                },
            ) {
                runCancellableNative(
                    onLateResult = { late ->
                        late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                        late.close()
                    },
                ) { cancellation ->
                    val draftStartNs = System.nanoTime()
                    e.simulatePreview(
                        lease.image,
                        state.toParams(previewMaxSizeOverride = draftEdge, skipGrainHalation = true),
                        cancellation = cancellation,
                    ).also {
                        // Only a completed native render reaches here (cancel/failure
                        // throws), so the calibrator never learns from a cut-short run.
                        settings.recordDraftTiming(
                            draftEdge,
                            (System.nanoTime() - draftStartNs) / 1_000_000L,
                        )
                    }
                }.use { res ->
                    renderId = res.renderId
                    simResultToBitmapGraded(res, state.savingCctfEncoding, state.saturation, state.vibrance, state.gamutCompress, state.localAdjustments.withSkinField(state.cropIo()))
                }
            }
        }
    }
    if (!isActive || !publicationGate.tryClaim(publicationTicket)) {
        SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
        draftResult.getOrNull()?.let { if (!it.isRecycled) it.recycle() }
        return
    }
    draftResult.onSuccess { bmp ->
        try {
            withContext(dispatchers.main) { preview = bmp }
            SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
        } catch (cancelled: CancellationException) {
            SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
            if (!bmp.isRecycled) bmp.recycle()
            throw cancelled
        }
    }.onFailure {
        SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
        Diag.w("render mode=draft failed: ${it.message}")
    }
}

/** Crisp FINAL preview render on settle; see the comment at its LaunchedEffect in EditorScreen. */
internal suspend fun CoroutineScope.runFinalPreviewPass(
    dispatchers: AppDispatchers,
    ctx: Context,
    state: ParamsState,
    sourceRenderAllowed: Boolean,
    previewRevision: Long,
    publicationGate: RenderPublicationGate,
    gradeCache: GradeCache,
    zoomPrewarmEligible: Boolean,
    gradeCacheKey: (Int) -> GradeCache.Key,
    loadSourceCachedForPreview: suspend (Int) -> DecodedSourceCache.Lease,
    retireSourceResources: () -> Unit,
    scheduleZoomPrewarm: () -> Unit,
    engineState: State<SpektraEngine?>,
    previewState: MutableState<Bitmap?>,
    beforePreviewState: MutableState<Bitmap?>,
    cropFreePreviewState: MutableState<Bitmap?>,
    decodingState: MutableState<Boolean>,
    lastRenderMsState: MutableState<Long?>,
    previewBusyState: MutableState<Boolean>,
    renderErrState: MutableState<String?>,
    statusState: MutableState<String>,
    sourceState: State<EditorSourceState>,
    requireSourceAuthorization: () -> Unit,
) {
    val engine by engineState
    var preview by previewState
    var beforePreview by beforePreviewState
    var decoding by decodingState
    var lastRenderMs by lastRenderMsState
    var previewBusy by previewBusyState
    var renderErr by renderErrState
    var status by statusState
    if (!sourceRenderAllowed) return
    val e = engine ?: return
    val previewProbeLabel = editorProbe.sourceProbeLabel(sourceState.value.uri)
    // Settle debounce, raised from 350ms. Each preview render maxes all CPU cores for
    // ~1s, so waiting a bit longer for the user to pause means fewer renders that start
    // then get cancelled mid-flight on the next edit ("coroutine scope left the
    // composition" in the logcat) — wasted all-core CPU that drains battery.
    delay(500)
    val publicationTicket = publicationGate.begin(
        previewRevision,
        RenderPublicationPriority.SETTLE,
    )
    previewBusy = true
    renderErr = null
    status = ctx.getString(R.string.editor_status_rendering_preview)
    // Every reset below used to be a plain statement. Cancellation skips every
    // statement after the suspension point it unwinds from, so a render cancelled
    // mid-flight left decoding/previewBusy set and the status pinned to
    // "rendering preview…" — with no work running, all engine threads idle,
    // and nothing to restart the effect (its keys are previewTick and
    // sourceRenderAllowed). The editor sat there permanently. Observed on a
    // 4080x3060 DNG after a speculative export pre-render exhausted the memory
    // budget. A finally runs on cancellation, which is the whole point.
    var settled = false
    try {
        val renderStart = System.currentTimeMillis()
        val fullEdge = state.previewMaxSize.coerceAtLeast(256)
        // Key built on MAIN (reads Compose state); the same snapshot renders below,
        // so the key and the render can never disagree.
        val cacheKey = gradeCacheKey(fullEdge)
        val cacheStoreTicket = gradeCache.beginStore(cacheKey)
        val cached = gradeCache.lookup(cacheKey)
        val prevBefore = beforePreview
        var renderId = 0L
        var completionProbeHandled = false
        val result = try {
            runCatching {
                withOwnedContext(
                    context = dispatchers.default,
                    dispose = { submission: Triple<Bitmap, Bitmap, Boolean> ->
                        if (!submission.second.isRecycled) submission.second.recycle()
                        if (submission.third && !submission.first.isRecycled) {
                            submission.first.recycle()
                        }
                    },
                ) {
                    if (cached != null && prevBefore != null) {
                        // Grade-only edit (identical engine inputs): re-grade the retained
                        // pristine engine result — ZERO native work. The ungraded `before`
                        // is unchanged by construction.
                        var after: Bitmap? = null
                        var transferred = false
                        try {
                            val rendered = cached.withScratch { scratch ->
                                gradeBufferToBitmap(scratch, cached.width,
                                    cached.height, cached.colorSpace, state.savingCctfEncoding,
                                    state.saturation, state.vibrance, state.gamutCompress,
                                    state.localAdjustments.withSkinField(cacheKey.engineParams.io))
                            }
                            after = rendered
                            val submission = Triple(prevBefore, rendered, false)
                            transferred = true
                            submission
                        } finally {
                            if (!transferred) after?.let { if (!it.isRecycled) it.recycle() }
                        }
                    } else {
                        decoding = true
                    // The live DRAFT effect above already paints a fast low-res proxy during the
                    // edit, so this settle pass goes straight to the crisp full render (no separate
                    // coarse pass / extra fresh decode). Cached proxy source — re-decodes only when
                    // a decode-affecting key (URI/kind/WB/temp/tint/rotation/edge) changed;
                    // look-param edits reuse it.
                        var before: Bitmap? = null
                        var after: Bitmap? = null
                        var transferred = false
                        var completionProbeHeld = false
                        try {
                            val submission = loadSourceCachedForPreview(fullEdge).use { lease ->
                                decoding = false
                                // Cut to the engine's crop box so compare/peek register (#254).
                                val ownedBefore = beforeFrameBitmap(lease.image, cacheKey.engineParams)
                                before = ownedBefore
                                // Fit preview skips grain/halation (the user's "grain at 100%" choice): they
                                // are rendered by the zoom ROI, the magnifier and export, never the fit settle.
                                // Render with cacheKey.engineParams — the exact snapshot the key hashed.
                                val ownedAfter = runCancellableNative(
                                    onLateResult = { late ->
                                        late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                                        late.close()
                                    },
                                ) { cancellation ->
                                    e.simulatePreview(
                                        lease.image,
                                        cacheKey.engineParams,
                                        cancellation = cancellation,
                                    )
                                }.use { res ->
                                    renderId = res.renderId
                                    // Retain the PRISTINE engine output BEFORE the grade mutates it.
                                    res.acquireDataLease().use { resultLease ->
                                        val cacheOutcome = storeGradeCacheBestEffort {
                                            gradeCache.store(
                                                cacheStoreTicket,
                                                cacheKey,
                                                resultLease.data,
                                                res.width,
                                                res.height,
                                                res.colorSpace,
                                            )
                                        }
                                        if (cacheOutcome == GradeCacheStoreOutcome.CAPACITY_DENIED) {
                                            Diag.w("grade cache skipped: memory admission denied")
                                        }
                                    }
                                    val rendered = simResultToBitmapGraded(
                                        res,
                                        state.savingCctfEncoding,
                                        state.saturation,
                                        state.vibrance,
                                        state.gamutCompress,
                                        state.localAdjustments.withSkinField(cacheKey.engineParams.io),
                                    ).also { bitmap -> after = bitmap }
                                    // The test seam is exactly the completed native settle's
                                    // production completion-before-publication boundary. The
                                    // SimResult and decoded-source lease remain owned here.
                                    completionProbeHeld = editorProbe
                                        .awaitCompletedPreviewBeforePublication(previewProbeLabel)
                                    rendered
                                }
                                Triple(ownedBefore, ownedAfter, true)
                            }
                            if (completionProbeHeld) {
                                // B cancels this LaunchedEffect while A is held. Complete the
                                // actual gate decision and full resource cleanup synchronously
                                // here, before withOwnedContext's prompt-cancellation return can
                                // discard the owned Triple. This branch is test-armed only.
                                val claimed = publicationGate.tryClaim(publicationTicket)
                                editorProbe.publishPreviewDecision(
                                    previewProbeLabel,
                                    claimed,
                                )
                                SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
                                val (probeBefore, probeAfter, ownsProbeBefore) = submission
                                if (!probeAfter.isRecycled) probeAfter.recycle()
                                if (ownsProbeBefore && !probeBefore.isRecycled) probeBefore.recycle()
                                completionProbeHandled = true
                                editorProbe.publishPreviewCleanup(previewProbeLabel)
                            }
                            transferred = true
                            submission
                        } finally {
                            if (!transferred) {
                                after?.let { if (!it.isRecycled) it.recycle() }
                                before?.let { if (!it.isRecycled) it.recycle() }
                            }
                        }
                    }
                }
            }
        } finally {
            cached?.close()
        }
        decoding = false
        if (completionProbeHandled) return
        val publicationClaimed = publicationGate.tryClaim(publicationTicket)
        editorProbe.publishPreviewDecision(
            previewProbeLabel,
            publicationClaimed && isActive,
        )
        if (!isActive || !publicationClaimed) {
            SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
            result.getOrNull()?.let { (before, after, ownsBefore) ->
                if (!after.isRecycled) after.recycle()
                if (ownsBefore && !before.isRecycled) before.recycle()
            }
            editorProbe.publishPreviewCleanup(previewProbeLabel)
            return
        }
        result.onSuccess { (before, after, _) ->
            beforePreview = before; preview = after
            // The Crop tab draws on this frame only when it was rendered without the crop.
            cropFreePreviewState.value = after.takeIf { !cacheKey.engineParams.io.crop }
            SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
            editorProbe.publishLivePreview(previewProbeLabel)
            lastRenderMs = System.currentTimeMillis() - renderStart
            Diag.i("render mode=preview ${after.width}x${after.height} ${after.width * after.height}px ${lastRenderMs}ms")
            if (zoomPrewarmEligible) scheduleZoomPrewarm()
            renderErr = null
            status = ctx.getString(R.string.editor_status_preview_ready)
        }.onFailure {
            SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
            Diag.w("render mode=preview failed after ${System.currentTimeMillis() - renderStart}ms: ${it.message}")
            if (sourceState.value.kind != SourceKind.DEMO && it.isSourceAuthorizationFailure()) {
                retireSourceResources()
                requireSourceAuthorization()
                renderErr = ctx.getString(R.string.editor_render_error_auth_required)
                status = ctx.getString(R.string.editor_status_source_expired)
            } else {
                renderErr = it.message?.take(60)
                status = ctx.getString(R.string.editor_status_preview_error, it.message)
            }
        }
        previewBusy = false
        settled = true
    } finally {
        decoding = false
        previewBusy = false
        // Not settled means cancelled, not finished. When a NEWER render caused the
        // cancellation it overwrites this within a frame; when nothing did, this is
        // the difference between a recoverable editor and a dead one.
        if (!settled) status = ctx.getString(R.string.editor_status_preview_interrupted)
    }
}

/** GPU LUT preview feed (experimental); see the comment at its LaunchedEffect in EditorScreen. */
internal suspend fun CoroutineScope.runGpuPreviewFeed(
    dispatchers: AppDispatchers,
    state: ParamsState,
    sourceRenderAllowed: Boolean,
    gpuEnabled: Boolean,
    loadSourceCachedForPreview: suspend (Int) -> DecodedSourceCache.Lease,
    engineState: State<SpektraEngine?>,
    gpuGainState: MutableFloatState,
    gpuLutState: MutableState<CubeLut?>,
    gpuProxyLeaseState: MutableState<DecodedSourceCache.Lease?>,
) {
    val engine by engineState
    var gpuGain by gpuGainState
    var gpuLut by gpuLutState
    var gpuProxyLease by gpuProxyLeaseState
    if (!gpuEnabled || !sourceRenderAllowed) {
        gpuLut = null
        gpuProxyLease?.close()
        gpuProxyLease = null
        gpuGain = 1f
        return
    }
    val e = engine ?: return
    delay(380)
    val gpuMaxEdge = state.previewMaxSize.coerceAtLeast(256)
    val gpuParams = state.toParams()
    var lutRenderId = 0L
    val bakeResult = runCatching {
        withOwnedContext(
            context = dispatchers.default,
            dispose = { submission: Triple<DecodedSourceCache.Lease, CubeLut?, Float> ->
                submission.first.close()
            },
        ) {
            val lease = loadSourceCachedForPreview(gpuMaxEdge)
            try {
                // SHAPER_SRGB is REQUIRED here, not optional: LutGpuPreview's shader
                // always indexes through the sRGB transfer, so a LUT baked on the linear
                // lattice would be sampled at the wrong cell entirely — mid-grey 0.18
                // looking up at 0.46, i.e. overexposed and flat. The only unshaped bake
                // is the user-facing .cube export, which no shader consumes.
                val bake = runCancellableNative(
                    onLateResult = { late ->
                        late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                    },
                ) { cancellation ->
                    e.bakeCubeLut(
                        gpuParams,
                        33,
                        SpektraEngine.SHAPER_SRGB,
                        cancellation,
                    )
                }
                lutRenderId = bake.renderId
                val lut = CubeLut.parse(bake.text)
                // The bake emits the pointwise transform at UNITY gain (a 3D LUT
                // cannot carry auto-exposure), so meter the SAME proxy through the
                // engine's own metering and hand the gain to the shader. Without it
                // the GPU preview renders dark with lifted shadows — the scene lands
                // in the film curve's toe. Metering is cheap next to the bake.
                val gain = runCancellableNative { cancellation ->
                    e.exposureGain(lease.image, gpuParams, cancellation)
                }
                Triple(lease, lut, gain)
            } catch (failure: Throwable) {
                lease.close()
                throw failure
            }
        }
    }
    if (!isActive) {
        SimResult.reportOutcome(lutRenderId, AppRenderOutcome.SUPERSEDED)
        bakeResult.getOrNull()?.first?.close()
        return
    }
    bakeResult.onSuccess { (lease, lut, gain) ->
        if (lut != null) {
            val previous = gpuProxyLease
            gpuProxyLease = lease
            previous?.close()
            gpuLut = lut; gpuGain = gain
            SimResult.reportOutcome(lutRenderId, AppRenderOutcome.CONSUMED)
            Diag.i("gpu lut baked ${lut.size}^3 gain=%.3f — fit preview runs on GPU".format(gain))
        } else {
            lease.close()
            SimResult.reportOutcome(lutRenderId, AppRenderOutcome.FAILED)
            Diag.w("gpu lut parse failed -> CPU preview")
        }
    }.onFailure {
        SimResult.reportOutcome(lutRenderId, AppRenderOutcome.FAILED)
        Diag.w("gpu lut bake failed: ${it.message} -> CPU preview")
    }
}
