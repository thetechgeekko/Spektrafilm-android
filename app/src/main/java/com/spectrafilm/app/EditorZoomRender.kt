/*
 * Spektrafilm for Android — editor magnifier and zoom-patch renders. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The bodies of EditorScreen's openMagnifier and renderRoi, moved verbatim (#273). EditorScreen
 * keeps both as one-line local functions that call these. Editor `var`s are passed as their
 * state holders and re-bound as local delegates, so reads inside launched work stay live.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.RenderKind
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Opens the 1:1 magnifier at (nx, ny) and renders its crop. Body of EditorScreen.openMagnifier. */
internal fun startMagnifierRender(
    dispatchers: AppDispatchers,
    nx: Float, ny: Float,
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    sourceRenderAllowed: Boolean,
    magnifierJobRef: MutableState<Job?>,
    magnifierPublicationGate: RoiRenderPublicationGate,
    loadSourceCachedForZoom: suspend (Int) -> DecodedSourceCache.Lease,
    engineState: State<SpektraEngine?>,
    previewTickState: MutableIntState,
    magnifierBitmapState: MutableState<Bitmap?>,
    magnifierOpenState: MutableState<Boolean>,
    magnifierRenderingState: MutableState<Boolean>,
    magnifierStatusState: MutableState<String>,
    latestMagnifierRenderKeyState: State<Int>,
) {
    val engine by engineState
    var previewTick by previewTickState
    var magnifierBitmap by magnifierBitmapState
    var magnifierOpen by magnifierOpenState
    var magnifierRendering by magnifierRenderingState
    var magnifierStatus by magnifierStatusState
    val latestMagnifierRenderKey by latestMagnifierRenderKeyState
    if (!sourceRenderAllowed) return
    val e = engine ?: return
    magnifierJobRef.value?.cancel()
    val renderSnapshot = state.captureInteractiveRenderSnapshot(previewTick)
    val publicationTicket = magnifierPublicationGate.begin(renderSnapshot.renderKey)
    magnifierOpen = true
    magnifierBitmap = null
    magnifierRendering = true
    magnifierStatus = ctx.getString(R.string.editor_magnifier_rendering)
    magnifierJobRef.value = scope.launch {
        var renderId = 0L
        val result = runCatching {
            withOwnedContext(
                context = dispatchers.default,
                dispose = { bitmap: Bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                },
            ) {
                loadSourceCachedForZoom(MAX_EDGE_PX).use { fullLease ->
                    val full = fullLease.image
                    val frameGain = frameExposureGain(e, full, renderSnapshot.params)
                    cropLinearImage(full, nx, ny, MAGNIFIER_CROP_PX, frameGain).use { crop ->
                        // Effective film format so the crop's pixel_size_um matches the proxy it was
                        // cut from (the crop spans only cropFrac of the frame). Without it the engine
                        // treats the crop as a whole 35mm frame and grain/halation (µm-based) render
                        // too weak even at 1:1. See toParams(filmFormatMmOverride).
                        val cropFrac = maxOf(crop.width, crop.height).toFloat() /
                            maxOf(full.width, full.height).coerceAtLeast(1)
                        // SimResult holds a native off-heap buffer; close() frees it once the
                        // bitmap copy is made.
                        runCancellableNative(
                            onLateResult = { late ->
                                late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                                late.close()
                            },
                        ) { cancellation ->
                            e.simulate(
                                crop,
                                renderSnapshot.paramsForCrop(cropFrac),
                                RenderKind.MAGNIFIER,
                                cancellation,
                            )
                        }.use { res ->
                            renderId = res.renderId
                            simResultToBitmapGraded(
                                res,
                                renderSnapshot.cctfEncoded,
                                renderSnapshot.saturation,
                                renderSnapshot.vibrance,
                                renderSnapshot.gamutCompress,
                                renderSnapshot.localAdjustments.withSkinField(
                                    renderSnapshot.params.io, crop.width, crop.height,
                                    regionOf(
                                        centredCropBox(full.width, full.height, nx, ny, MAGNIFIER_CROP_PX, MAGNIFIER_CROP_PX),
                                        full.width, full.height,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (
            !isActive || !magnifierPublicationGate.canPublish(
                publicationTicket,
                latestMagnifierRenderKey,
            )
        ) {
            SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
            // Superseded by a newer tap (this job was cancelled): drop our render so
            // it neither overwrites the latest request nor leaks an orphaned bitmap.
            result.getOrNull()?.let { if (!it.isRecycled) it.recycle() }
            return@launch
        }
        result.onSuccess {
            magnifierBitmap = it
            SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
            magnifierStatus = ctx.getString(R.string.editor_magnifier_done, it.width, it.height)
        }.onFailure {
            SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
            magnifierStatus = ctx.getString(R.string.editor_magnifier_failed, it.message)
        }
        magnifierRendering = false
    }
}

/** Renders the sharp zoom patch for the visible region. Body of EditorScreen.renderRoi. */
internal fun startRoiPatchRender(
    dispatchers: AppDispatchers,
    /** True while an on-photo tool or before/after compare is up: no patch render then. */
    patchSuppressed: () -> Boolean,
    roi: RoiRect,
    scope: CoroutineScope,
    state: ParamsState,
    sourceRenderAllowed: Boolean,
    roiJobRef: MutableState<Job?>,
    roiPatchCache: RoiPatchCache<RoiOverlay>,
    roiPatchTracker: RoiPatchTracker,
    roiPublicationGate: RoiRenderPublicationGate,
    loadSourceCachedForZoom: suspend (Int) -> DecodedSourceCache.Lease,
    engineState: State<SpektraEngine?>,
    previewTickState: MutableIntState,
    roiOverlayState: MutableState<RoiOverlay?>,
    latestRoiRenderKeyState: State<Int>,
) {
    val engine by engineState
    var previewTick by previewTickState
    var roiOverlay by roiOverlayState
    val latestRoiRenderKey by latestRoiRenderKeyState
    if (!sourceRenderAllowed) return
    val e = engine ?: return
    if (patchSuppressed()) return
    // A settle inside the patch already on screen (or already rendering) at the same edit
    // and sharpness needs no new render: keep it rather than cancel and redo it.
    roiPatchTracker.reuseReason(
        roi, previewTick, roiOverlay, roiJobRef.value, ROI_RENDER_MAX_PX,
    )?.let { reason ->
        if (reason == RoiPatchTracker.REUSED_SHOWN) {
            // The view came back inside the shown patch: any render still running for
            // an intermediate viewport must not land over it (it would not cover this
            // view, and no further settle would come to fix that).
            roiPublicationGate.invalidate()
            roiJobRef.value?.cancel()
        }
        Diag.i("zoom roi reused $reason")
        return
    }
    // A finished patch for this edit that already covers the view (pan back, zoom back):
    // show it now instead of rendering again.
    roiPatchCache.find(roi, previewTick, ROI_RENDER_MAX_PX)?.let { (record, cached) ->
        roiPublicationGate.invalidate()
        roiJobRef.value?.cancel()
        roiOverlay = cached
        roiPatchTracker.shownOverlay = cached
        roiPatchTracker.shown = record
        Diag.i("zoom roi reused cache entries=${roiPatchCache.size}")
        return
    }
    roiJobRef.value?.cancel()
    val renderSnapshot = state.captureInteractiveRenderSnapshot(previewTick)
    val roiPublicationTicket = roiPublicationGate.begin(renderSnapshot.renderKey)
    roiJobRef.value = scope.launch {
        val pendingOutcomes = PendingRenderOutcomes()
        // Zoom timing (rawr study step 1): settle -> source -> draft -> final, all in ms
        // from the settle. One line per completed or superseded ROI render.
        val timing = RoiTiming()
        val result = runCatching {
            loadSourceCachedForZoom(MAX_EDGE_PX).use { fullLease ->
                timing.sourceMs = timing.sinceStartMs()
                val full = fullLease.image
                // Render a grid-snapped patch around the viewport, not the viewport
                // itself (RoiPatch): the margin keeps halation/blur light from just off
                // screen, and a later pan inside the patch reuses this render.
                val box = RoiPatch.box(roi, full.width, full.height)
                val patchRect = RoiPatch.rectOf(box, full.width, full.height)
                val scale = RoiPatch.neededScale(
                    roi, full.width, full.height, ROI_RENDER_MAX_PX,
                )
                val draftScale = RoiPatch.neededScale(
                    roi, full.width, full.height, ROI_DRAFT_MAX_PX,
                )
                val patchRecord = RoiPatchRecord(
                    renderKey = renderSnapshot.renderKey,
                    srcW = full.width,
                    srcH = full.height,
                    box = box,
                    scale = scale,
                )
                currentCoroutineContext()[Job]?.let { job ->
                    roiPatchTracker.inFlightJob = job
                    roiPatchTracker.inFlight = patchRecord
                }
                timing.cropW = box.width
                timing.cropH = box.height
                withOwnedContext(
                    context = dispatchers.default,
                    dispose = LinearImage::close,
                ) {
                    // Expose the patch like the whole frame, not like the patch on its own.
                    cropLinearImageBox(full, box, frameExposureGain(e, full, renderSnapshot.params))
                }.use { crop ->
                    // Effective film format so the crop's pixel_size_um matches the proxy (the crop
                    // spans only cropFrac of the frame); keeps grain/halation at the right strength
                    // when zoomed instead of treating the crop as a whole frame.
                    val cropFrac = maxOf(crop.width, crop.height).toFloat() /
                        maxOf(full.width, full.height).coerceAtLeast(1)
                    suspend fun renderAt(edge: Int): Pair<Bitmap, Long> =
                        withOwnedContext(
                            context = dispatchers.default,
                            dispose = { rendered: Pair<Bitmap, Long> ->
                                if (!rendered.first.isRecycled) rendered.first.recycle()
                            },
                        ) {
                            runCancellableNative(
                                onLateResult = { late ->
                                    late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                                    late.close()
                                },
                            ) { cancellation ->
                                e.simulatePreview(
                                    crop,
                                    renderSnapshot.paramsForCrop(
                                        cropFraction = cropFrac,
                                        previewMaxSize = edge,
                                    ),
                                    RenderKind.ROI,
                                    cancellation,
                                )
                            }.use { res ->
                                pendingOutcomes.add(res.renderId)
                                simResultToBitmapGraded(
                                    res,
                                    renderSnapshot.cctfEncoded,
                                    renderSnapshot.saturation,
                                    renderSnapshot.vibrance,
                                    renderSnapshot.gamutCompress,
                                    renderSnapshot.localAdjustments.withSkinField(
                                        renderSnapshot.params.io, crop.width, crop.height,
                                        regionOf(box, full.width, full.height),
                                    ),
                                ) to res.renderId
                            }
                        }
                    // DRAFT pass: a fast low-res sharp crop so the zoomed region resolves ~5x sooner,
                    // then refine to ROI_RENDER_MAX_PX. Every bitmap is published (then owned by the
                    // DisposableEffect(roiOverlay), which recycles the prior one) OR recycled here —
                    // so a cancel mid-flight never leaks one.
                    val (draft, draftRenderId) =
                        renderAt(RoiPatch.renderEdge(box, draftScale))
                    timing.draftMs = timing.sinceStartMs()
                    if (
                        isActive && roiPublicationGate.canPublish(
                            roiPublicationTicket,
                            latestRoiRenderKey,
                        )
                    ) {
                        roiOverlay = RoiOverlay(
                            draft, patchRect.cxN, patchRect.cyN, patchRect.wN, patchRect.hN,
                        )
                        pendingOutcomes.resolve(draftRenderId, AppRenderOutcome.CONSUMED)
                    } else {
                        pendingOutcomes.resolveAll(AppRenderOutcome.SUPERSEDED)
                        if (!draft.isRecycled) draft.recycle()
                        return@runCatching null
                    }
                    renderAt(RoiPatch.renderEdge(box, scale)).also {
                        timing.finalMs = timing.sinceStartMs()
                    }.let { (bmp, id) -> Triple(bmp, id, patchRecord to patchRect) }
                }
            }
        }
        if (
            !isActive || !roiPublicationGate.canPublish(
                roiPublicationTicket,
                latestRoiRenderKey,
            )
        ) {
            pendingOutcomes.resolveAll(AppRenderOutcome.SUPERSEDED)
            // Superseded (cancelled): drop the final render (the draft, if published, stays
            // and is recycled by the DisposableEffect when the next overlay replaces it).
            result.getOrNull()?.first?.let { if (!it.isRecycled) it.recycle() }
            Diag.i(timing.line("superseded"))
            return@launch
        }
        // On success publish the sharp final; the prior overlay (the draft) is recycled by
        // the DisposableEffect(roiOverlay) below. On failure keep the scaled proxy.
        result.onSuccess { rendered ->
            rendered?.let { (bmp, renderId, patch) ->
                val (record, rect) = patch
                val overlay = RoiOverlay(bmp, rect.cxN, rect.cyN, rect.wN, rect.hN)
                roiOverlay = overlay
                roiPatchTracker.shownOverlay = overlay
                roiPatchTracker.shown = record
                roiPatchCache.put(record, overlay)
                pendingOutcomes.resolve(renderId, AppRenderOutcome.CONSUMED)
            }
            Diag.i(timing.line("done"))
        }.onFailure {
            pendingOutcomes.resolveAll(AppRenderOutcome.FAILED)
            Diag.i(timing.line("failed"))
        }
    }
}

/**
 * The linear gain the engine's auto-exposure gives the WHOLE frame [full] under [params]
 * (1 when auto-exposure is off). The zoom patch and the magnifier fold it into their crop and
 * render with auto-exposure off ([InteractiveRenderSnapshot.paramsForCrop]): metering the crop
 * itself exposed every zoomed view for its own content, so zooming changed the brightness.
 *
 * Uses the engine's own metering ([SpektraEngine.exposureGain]), the same code the full render
 * runs, so the two cannot drift. Metering the full-resolution source walks every pixel, so the
 * last result is kept for the same source and metering settings: pans and zoom steps reuse it.
 */
internal fun frameExposureGain(e: SpektraEngine, full: LinearImage, params: SpektraParams): Float {
    if (!params.camera.autoExposure) return 1f
    val key = FrameExposureKey(
        method = params.camera.autoExposureMethod,
        inputCctfDecoding = params.io.inputCctfDecoding,
    )
    FrameExposureCache.lookup(full, key)?.let { return it }
    val gain = e.exposureGain(full, params)
    // A degenerate meter (black frame) must not blow the crop up or zero it: unity instead.
    val safe = if (gain.isFinite() && gain > 0f) gain else 1f
    FrameExposureCache.store(full, key, safe)
    return safe
}

/** The metering inputs besides the pixels: what [frameExposureGain] caches on. */
internal data class FrameExposureKey(val method: String, val inputCctfDecoding: Boolean)

/** One-entry cache of the last whole-frame meter, held weakly so it never pins a source. */
internal object FrameExposureCache {
    private var image: WeakReference<LinearImage>? = null
    private var key: FrameExposureKey? = null
    private var gain = 1f

    @Synchronized
    fun lookup(full: LinearImage, k: FrameExposureKey): Float? =
        if (image?.get() === full && key == k) gain else null

    @Synchronized
    fun store(full: LinearImage, k: FrameExposureKey, g: Float) {
        image = WeakReference(full)
        key = k
        gain = g
    }
}
