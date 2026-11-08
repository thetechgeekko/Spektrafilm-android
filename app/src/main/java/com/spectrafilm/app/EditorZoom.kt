/*
 * Spektrafilm for Android — editor zoom state: the 100% magnifier and the Lightroom-style ROI
 * patch. GPLv3. Film modeling powered by spektrafilm.
 *
 * Moved out of EditorScreen (#273). The state, jobs, gates and the patch cache live in one
 * holder; the renders themselves stay in EditorZoomRender.kt. Values that change between
 * compositions (source authorization, the zoom-source loader) are refreshed on every
 * composition, so launched work always sees the latest composition's values, as before.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
internal class EditorZoomController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val state: ParamsState,
    private val engineState: State<SpektraEngine?>,
    private val previewTickState: MutableIntState,
    private val latestRenderKeyState: State<Int>,
    private val sourceRenderAllowedState: State<Boolean>,
    private val patchSuppressed: () -> Boolean,
) {
    // 100% grain magnifier
    val magnifierOpenState = mutableStateOf(false)
    var magnifierOpen by magnifierOpenState
    val magnifierBitmapState = mutableStateOf<Bitmap?>(null)
    var magnifierBitmap by magnifierBitmapState
    val magnifierRenderingState = mutableStateOf(false)
    var magnifierRendering by magnifierRenderingState
    val magnifierStatusState = mutableStateOf("")
    var magnifierStatus by magnifierStatusState
    // Tracks the in-flight magnifier render so a rapid re-tap can cancel the previous
    // request (last-tap-wins) instead of racing N full-res renders into magnifierBitmap.
    // A plain holder (never read during composition).
    private val magnifierJobRef = mutableStateOf<Job?>(null)
    // Zoom step 4: background decode of the zoom source after an idle settle (ZoomPrewarm).
    private val zoomPrewarmJobRef = mutableStateOf<Job?>(null)
    val zoomPrewarmEligible = ZoomPrewarm.eligible(ctx)
    private val magnifierPublicationGate = RoiRenderPublicationGate()

    // Lightroom-style zoom: the sharp render of the currently-visible region (rendered at
    // ~screen resolution from a native-pixel crop), overlaid on the scaled proxy.
    val roiOverlayState = mutableStateOf<RoiOverlay?>(null)
    var roiOverlay by roiOverlayState
    private val roiJobRef = mutableStateOf<Job?>(null)
    private val roiPatchTracker = RoiPatchTracker()
    // Zoom step 3: recent finished patch renders for the current edit (RoiPatchCache).
    // 64 MiB like rawr's tile LRU on roomy devices, 16 MiB elsewhere.
    val roiPatchCache = RoiPatchCache<RoiOverlay>(
        maxBytes = if (zoomPrewarmEligible) 64L shl 20 else 16L shl 20,
        sizeOf = { it.bitmap.allocationByteCount.toLong() },
        // Through the lease registry: a patch the Canvas still draws this frame is
        // recycled only once its draw lease closes.
        recycle = { retirePreviewBitmap(it.bitmap) },
        isShown = { it === roiOverlay },
    )
    private val roiPublicationGate = RoiRenderPublicationGate()

    /**
     * The zoom-source loader. EditorScreen declares it after this holder (it needs the decode
     * request built further down) and re-assigns it on every composition, the same write an
     * updated-state holder makes, so launched work always calls the latest composition's one.
     */
    lateinit var loadSourceCachedForZoom: suspend (Int) -> DecodedSourceCache.Lease

    fun closeMagnifier() {
        // Cancel any in-flight crop render so it doesn't resurrect the overlay
        // state or leak its bitmap (a cancelled job recycles its own result).
        magnifierJobRef.value?.cancel()
        magnifierRendering = false
        magnifierOpen = false
        magnifierBitmap = null
    }

    /** The zoom share of retiring every source-derived resource (see retireSourceResources). */
    fun retireSource() {
        zoomPrewarmJobRef.value?.cancel()
        roiPublicationGate.invalidate()
        roiJobRef.value?.cancel()
        roiPatchCache.clear()
        roiOverlay = null
        magnifierPublicationGate.invalidate()
        magnifierJobRef.value?.cancel()
        magnifierBitmap = null
        magnifierRendering = false
    }

    /**
     * Invalidate an old edit generation before its next main-thread publication, cancel its
     * remaining native work, and retire its overlay while the replacement preview is pending.
     */
    fun onEdit(previewTick: Int) {
        // An edit is under way: don't let the idle zoom-source decode compete with it.
        zoomPrewarmJobRef.value?.cancel()
        roiPublicationGate.invalidate()
        roiJobRef.value?.cancel()
        // Before roiOverlay is cleared, so the shown patch is skipped here and retired by
        // DisposableEffect(roiOverlay) once it has left the screen.
        roiPatchCache.retain(previewTick)
        roiOverlay = null
        magnifierPublicationGate.invalidate()
        magnifierJobRef.value?.cancel()
        if (magnifierOpen) {
            magnifierBitmap = null
            magnifierRendering = false
            magnifierStatus = ctx.getString(R.string.editor_magnifier_edit_changed)
        }
    }

    /** The editor is leaving composition: stop every zoom job and retire every cached patch. */
    fun dispose() {
        magnifierJobRef.value?.cancel()
        roiJobRef.value?.cancel()
        zoomPrewarmJobRef.value?.cancel()
        // The editor is leaving: retire every cached patch, the one on screen included
        // (its own disposal may have skipped it as still cached). Lease-safe and
        // idempotent, so a patch retired twice or still mid-draw is fine.
        roiPatchCache.clear(includeShown = true)
    }

    // Zoom step 4: once the fit preview has settled and the editor stays idle, decode the
    // zoom source into zoomSourceCache so the first pinch starts from a cache hit (see
    // ZoomPrewarm). Cancelled by the next edit; a zoom during the decode joins its flight.
    fun scheduleZoomPrewarm() {
        if (!sourceRenderAllowedState.value) return
        zoomPrewarmJobRef.value?.cancel()
        zoomPrewarmJobRef.value = scope.launch {
            delay(ZoomPrewarm.IDLE_DELAY_MS)
            val start = System.nanoTime()
            runCatching { loadSourceCachedForZoom(MAX_EDGE_PX).close() }
                .onSuccess {
                    Diag.i("zoom prewarm ready ms=${(System.nanoTime() - start) / 1_000_000L}")
                }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    Diag.w("zoom prewarm failed: ${t.message}")
                }
        }
    }

    // 100% grain magnifier: render a real FULL-RESOLUTION crop around a tapped point.
    //
    // PERF/RACE: each tap previously launched an independent coroutine with no
    // cancellation, so rapid taps ran several full-res simulate()s in parallel and
    // raced their results into magnifierBitmap (last-write-wins, wasteful, and an
    // orphaned bitmap per superseded run). We now cancel the prior in-flight render
    // before starting a new one (last-tap-wins), and the result is only published if
    // the coroutine is still active — a cancelled run recycles its own bitmap instead
    // of writing it, so a superseded render can never leak or land on screen.
    fun openMagnifier(nx: Float, ny: Float) {
        startMagnifierRender(
            dispatchers = dispatchers,
            nx = nx,
            ny = ny,
            ctx = ctx,
            scope = scope,
            state = state,
            sourceRenderAllowed = sourceRenderAllowedState.value,
            magnifierJobRef = magnifierJobRef,
            magnifierPublicationGate = magnifierPublicationGate,
            loadSourceCachedForZoom = loadSourceCachedForZoom,
            engineState = engineState,
            previewTickState = previewTickState,
            magnifierBitmapState = magnifierBitmapState,
            magnifierOpenState = magnifierOpenState,
            magnifierRenderingState = magnifierRenderingState,
            magnifierStatusState = magnifierStatusState,
            latestMagnifierRenderKeyState = latestRenderKeyState,
        )
    }

    // Lightroom-style zoom: render the currently-visible region (a native-pixel crop of the
    // source) at ~screen resolution via the FAST preview path, then overlay it sharply on the
    // graphicsLayer-scaled proxy. Modeled on openMagnifier's last-wins cancellation. Memory is
    // bounded by ROI_RENDER_MAX_PX (a few MB), independent of source megapixels — this is the
    // Lightroom "smart preview" strategy, so it sidesteps the full-res OOM entirely. Suppressed
    // while cropping or comparing (those branches own the gestures / have no zoom).
    fun renderRoi(roi: RoiRect) {
        startRoiPatchRender(
            dispatchers = dispatchers,
            patchSuppressed = patchSuppressed,
            roi = roi,
            scope = scope,
            state = state,
            sourceRenderAllowed = sourceRenderAllowedState.value,
            roiJobRef = roiJobRef,
            roiPatchCache = roiPatchCache,
            roiPatchTracker = roiPatchTracker,
            roiPublicationGate = roiPublicationGate,
            loadSourceCachedForZoom = loadSourceCachedForZoom,
            engineState = engineState,
            previewTickState = previewTickState,
            roiOverlayState = roiOverlayState,
            latestRoiRenderKeyState = latestRenderKeyState,
        )
    }

    fun clearRoi() {
        roiPublicationGate.invalidate()
        roiJobRef.value?.cancel()
        roiOverlay = null  // bitmap recycled by DisposableEffect(roiOverlay)
    }
}

@Composable
internal fun rememberEditorZoom(
    ctx: Context,
    scope: CoroutineScope,
    dispatchers: AppDispatchers,
    state: ParamsState,
    engineState: State<SpektraEngine?>,
    previewTickState: MutableIntState,
    sourceRenderAllowed: Boolean,
    patchSuppressed: () -> Boolean,
): EditorZoomController {
    val latestRenderKeyState = rememberUpdatedState(previewTickState.intValue)
    val sourceRenderAllowedState = rememberUpdatedState(sourceRenderAllowed)
    val patchSuppressedState = rememberUpdatedState(patchSuppressed)
    val zoom = remember {
        EditorZoomController(
            ctx = ctx,
            scope = scope,
            dispatchers = dispatchers,
            state = state,
            engineState = engineState,
            previewTickState = previewTickState,
            latestRenderKeyState = latestRenderKeyState,
            sourceRenderAllowedState = sourceRenderAllowedState,
            patchSuppressed = { patchSuppressedState.value() },
        )
    }
    // Cancel any in-flight ROI / magnifier render job when the editor leaves composition
    // (navigation), so a superseded job doesn't keep rendering into a gone Composable.
    DisposableEffect(Unit) {
        onDispose { zoom.dispose() }
    }
    // Recycle a superseded ROI bitmap once it has left composition (safe — not mid-draw).
    val roiOverlay = zoom.roiOverlay
    DisposableEffect(roiOverlay) {
        val current = roiOverlay
        onDispose {
            // A patch still held by roiPatchCache is the cache's to recycle.
            if (current != null && !zoom.roiPatchCache.holds(current)) {
                retirePreviewBitmap(current.bitmap)
            }
        }
    }
    return zoom
}

/** The 100% grain magnifier overlay, shown while [EditorZoomController.magnifierOpen]. */
@Composable
internal fun EditorMagnifierLayer(zoom: EditorZoomController) {
    if (zoom.magnifierOpen) {
        val magnifierBitmap = zoom.magnifierBitmap
        // MEMORY: the magnifier crop is a real full-resolution ARGB_8888 render
        // (~512px native here, but it is the only path that decodes at MAX_EDGE_PX
        // before cropping). Recycle it deterministically once the overlay leaves
        // composition (close) or the rendered crop is replaced by a re-render. This
        // DisposableEffect is scoped INSIDE `if (magnifierOpen)`, so onDispose runs
        // only after the overlay is no longer composed/drawn — never a use-after-
        // recycle of the bitmap still on screen. `crop` is the value captured for
        // THIS effect instance; when `magnifierBitmap` changes (e.g. -> null on a
        // re-open) or the overlay closes, the captured previous bitmap is freed.
        val cropToFree = magnifierBitmap
        DisposableEffect(magnifierBitmap) {
            onDispose {
                if (cropToFree != null && !cropToFree.isRecycled) cropToFree.recycle()
            }
        }
        MagnifierOverlay(
            crop = magnifierBitmap,
            rendering = zoom.magnifierRendering,
            status = zoom.magnifierStatus,
            onClose = { zoom.closeMagnifier() },
        )
    }
}
