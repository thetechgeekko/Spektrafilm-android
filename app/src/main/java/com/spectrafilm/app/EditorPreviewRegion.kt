/*
 * Spektrafilm for Android — the editor's photo canvas: preview, compare, zoom and the
 * canvas overlays. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Split out of EditorScreen.kt (#273).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.LinearImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The pinned preview region on the near-black canvas.
 *
 * Nothing sits on the photo but its status: the round histogram / clipping / loupe /
 * compare / crop / rotate buttons that used to float over its bottom edge moved to the app
 * bar (Crop) and its More menu (the view toggles), and rotate lives inside Crop. The canvas
 * pattern is full-bleed content (m3-design-language, ux-flows §3, Map / canvas).
 */
@Composable
internal fun PreviewRegion(
    /** One-shot zoom commands from the app bar / keyboard (EditorViewerViewModel). */
    zoomRequests: kotlinx.coroutines.flow.Flow<ViewerZoom>,
    preview: Bitmap?,
    placeholder: Bitmap?,
    before: Bitmap?,
    busy: Boolean,
    decoding: Boolean,
    exporting: Boolean,
    lastRenderMs: Long?,
    renderErr: String?,
    compareMode: Boolean,
    showHistogram: Boolean,
    showClipping: Boolean,
    gpuEnabled: Boolean,
    gpuProxy: LinearImage?,
    gpuLut: CubeLut?,
    gpuGain: Float,
    onPointPicked: (Float, Float) -> Unit,
    onOpenMagnifier: () -> Unit,
    renderKey: Int,
    roiOverlay: RoiOverlay?,
    onRoiSettled: (RoiRect) -> Unit,
    onRoiCleared: () -> Unit,
    controlsBottomInset: Dp = 0.dp,
    /** The top bar's Compare is held: show the original frame until it is released. */
    peekOriginal: Boolean = false,
    /** Library neighbours a sideways swipe on the photo opens; null when there are none. */
    swipe: PhotoSwipeNeighbours? = null,
    /**
     * Set while a sideways drag on the photo adjusts the last control (Edit open, a control
     * touched); it then takes the sideways drag from [swipe].
     */
    scrub: PhotoScrub? = null,
    /** The folder strip, shown from the frame counter; null when there is no folder. */
    strip: FolderStripModel? = null,
) {
    // Accessibility: the canvas gestures (pinch/double-tap zoom, drag to inspect a region,
    // tap to open the 100% grain magnifier) have no widget of their own, so the region is
    // described and the tap-to-pick gesture gets a custom action at the frame centre. The
    // ROI drag is bound to the live zoom state inside ZoomableImage and has no safe
    // one-shot equivalent, so it is described only. Semantics-only: no gesture changes.
    val previewDesc = when {
        preview == null -> stringResource(R.string.editor_preview_desc_empty)
        compareMode -> stringResource(R.string.editor_preview_desc_compare)
        else -> stringResource(R.string.editor_preview_desc_film)
    }
    val previewActions = if (preview != null && !compareMode) {
        listOf(
            // The magnifier moved off the tap and onto its own button, so this action
            // calls the button's handler rather than the (now unrelated) tap callback.
            CustomAccessibilityAction(stringResource(R.string.editor_preview_action_magnifier)) {
                onOpenMagnifier()
                true
            },
            // The tap gesture's own equivalent: hiding the chrome is worth reaching
            // without a gesture, since the gesture has nothing on screen to announce it.
            CustomAccessibilityAction(stringResource(R.string.editor_toggle_chrome)) {
                onPointPicked(0.5f, 0.5f)
                true
            },
        )
    } else {
        emptyList()
    }
    // --- Sideways swipe to the next / previous Library frame (PhotoSwipe) ---------------
    // The photo follows the finger and shrinks into a rounded card while the neighbour's
    // thumbnail slides in after it, growing to fill (the Material 3 carousel's mask and
    // parallax motion). Only at fit: zoomed, one finger pans the photo instead.
    val swipeScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeGapPx = with(density) { PhotoSwipeRules.GAP_DP.dp.toPx() }
    val swipeFlingPx = with(density) { SWIPE_FLING_DP.dp.toPx() }
    val swipeSlopPx = LocalViewConfiguration.current.touchSlop
    var zoomed by remember { mutableStateOf(false) }
    var swipeWidth by remember { mutableFloatStateOf(0f) }
    val swipeX = remember { Animatable(0f) }
    // True from the moment a swipe takes the finger until it settles (springs back, or the
    // next photo is on screen): the GPU fit surface steps aside meanwhile, because a
    // SurfaceView cannot be moved, scaled or clipped by graphicsLayer.
    var swiping by remember { mutableStateOf(false) }
    // The neighbour that slid in, held while the editor opens it: the neighbours are
    // recomputed for the new photo as soon as it is committed.
    var heldIncoming by remember { mutableStateOf<Bitmap?>(null) }
    // True while a released swipe animates out (and holds for the next photo) or springs
    // back. No new swipe starts until it settles, so two transitions never overlap.
    var settling by remember { mutableStateOf(false) }
    val latestSwipe by rememberUpdatedState(swipe)
    val latestShown by rememberUpdatedState(preview ?: placeholder)
    val swipeEnabled = scrub == null && swipe != null && (swipe.hasNext || swipe.hasPrevious) &&
        !zoomed && !compareMode && !peekOriginal && (preview != null || placeholder != null)
    // The folder strip: shown from the counter, hidden while zoomed in. A touch that starts
    // on it scrolls the strip, never the photo.
    var stripOpen by rememberSaveable { mutableStateOf(false) }
    val stripShown = strip != null && stripOpen && !zoomed && !compareMode
    var stripTopPx by remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    val latestStripShown by rememberUpdatedState(stripShown)
    val haptics = LocalHapticFeedback.current
    val swipeModifier = Modifier.photoSwipeGesture(
        enabled = (swipeEnabled || swiping) && !settling,
        ignoreDownAt = { latestStripShown && it.y >= stripTopPx },
        onStart = {
            swiping = true
            swipeScope.launch { swipeX.stop() }
        },
        onDrag = { dx ->
            val nb = latestSwipe
            val shown = PhotoSwipeRules.shownOffset(dx, nb?.hasNext == true, nb?.hasPrevious == true)
            swipeScope.launch { swipeX.snapTo(shown) }
        },
        onEnd = { dx, velocityX ->
            val nb = latestSwipe
            val step = if (velocityX == null || nb == null) 0 else PhotoSwipeRules.commitStep(
                dx, velocityX, swipeWidth, swipeFlingPx, swipeSlopPx, nb.hasNext, nb.hasPrevious,
            )
            settling = true
            swipeScope.launch {
                try {
                    val incomingThumb = if (step > 0) nb?.next else nb?.previous
                    if (step != 0 && nb != null && incomingThumb == null) {
                        // No thumbnail to slide in (still loading, or none exists): keep the
                        // current photo on screen and open the neighbour in place, rather
                        // than sliding out to a blank canvas.
                        swipeX.animateTo(0f, tween(SWIPE_BACK_MS))
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        nb.onCommit(step)
                    } else if (step != 0 && nb != null) {
                        heldIncoming = incomingThumb
                        swipeX.animateTo(-step * (swipeWidth + swipeGapPx), tween(SWIPE_COMMIT_MS))
                        val leaving = latestShown
                        val leavingKey = nb.sourceKey
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        nb.onCommit(step)
                        // Hold the neighbour in place until the editor has switched to the new
                        // photo AND it has something on screen (its stand-in or its render),
                        // so nothing blank flashes and a late frame of the old photo does not
                        // end the hold.
                        withTimeoutOrNull(SWIPE_HOLD_MAX_MS) {
                            snapshotFlow { latestSwipe?.sourceKey to latestShown }
                                .first { (key, shown) ->
                                    key != leavingKey && shown != null && shown !== leaving
                                }
                        }
                    } else {
                        swipeX.animateTo(0f, tween(SWIPE_BACK_MS))
                    }
                } finally {
                    // Also on cancellation (the editor leaving): never strand a transition.
                    withContext(NonCancellable) { swipeX.snapTo(0f) }
                    heldIncoming = null
                    swiping = false
                    settling = false
                }
            }
        },
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Edge to edge: no side gutter. A 16dp gutter on each side cost a landscape
            // photo (the common case on a portrait phone) 32dp of width and therefore
            // ~8% of its displayed size. The overlays below carry their own insets.
            .semantics {
                contentDescription = previewDesc
                customActions = previewActions
            }
            // On this outer box, an ancestor of both the GPU surface and the CPU image, so
            // the swipe sees the finger first without hiding either surface's own gestures.
            .onSizeChanged { swipeWidth = it.width.toFloat() }
            .then(swipeModifier)
            .photoScrubGesture(
                scrub,
                enabled = !swiping && !settling && !zoomed && !compareMode && !peekOriginal,
                ignoreDownAt = { latestStripShown && it.y >= stripTopPx },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = preview
        // GPU LUT surface is the FIT view's instant path (the baked look sampled on-GPU,
        // no per-edit CPU re-render); the CPU ZoomableImage owns ZOOM, where it renders the
        // region with grain/halation via the ROI path. gpuBroken latches if GL can't build
        // on this device (→ CPU fallback, never a black screen); gpuZoomHandoff flips when
        // the user pinches/double-taps the GPU surface and resets when zoom returns to fit.
        var gpuBroken by remember { mutableStateOf(false) }
        var gpuZoomHandoff by remember { mutableStateOf(false) }
        // Scale the CPU ZoomableImage starts at after a GPU-surface handoff: 1f for a pinch/
        // double-tap (continue from fit), 2f for the explicit "+" so it lands already zoomed.
        var gpuZoomInitial by remember { mutableFloatStateOf(1f) }
        val clipMask = rememberClipMask(bmp, showClipping && !compareMode)
        val gpuActive = gpuEnabled && gpuProxy != null && gpuLut != null &&
            !compareMode && !(peekOriginal && before != null) && !showClipping && !gpuBroken && !gpuZoomHandoff &&
            !swiping
        // The GPU surface is at fit whenever it shows: clear a zoom the CPU viewer reported
        // (e.g. the stand-in used during a swipe), so swiping is not left disabled.
        LaunchedEffect(gpuActive) { if (gpuActive) zoomed = false }
        if (gpuActive) GpuPreviewSurface(
            proxy = gpuProxy!!,
            lut = gpuLut!!,
            exposureGain = gpuGain,
            modifier = Modifier.fillMaxSize(),
            onPointPicked = onPointPicked,
            onZoomStart = { gpuZoomHandoff = true; gpuZoomInitial = 1f },
            onZoomIn = { gpuZoomHandoff = true; gpuZoomInitial = 2f },
            zoomRequests = zoomRequests,
            onUnavailable = {
                gpuBroken = true
                Diag.w("gpu surface unavailable (GL program build failed) -> CPU preview")
            },
        )
        // #286: the render fades in over the open stand-in instead of popping in. Starts
        // transparent only while a stand-in is showing, so an ordinary re-render (no
        // stand-in) is never faded.
        val revealAlpha = remember { Animatable(1f) }
        LaunchedEffect(placeholder != null, bmp != null) {
            when {
                placeholder != null && bmp == null -> revealAlpha.snapTo(0f)
                placeholder != null -> revealAlpha.animateTo(1f, tween(OPEN_PLACEHOLDER_FADE_MS))
                else -> revealAlpha.snapTo(1f)
            }
        }
        // Everything except the GPU surface lives in the part of the canvas ABOVE the
        // floating panel ([controlsBottomInset]), so an open panel moves and fits the photo
        // instead of covering it, and the canvas buttons ride on top of the panel. The GPU
        // surface keeps the full box: resizing a GLSurfaceView every animation frame is
        // the churn documented on PanelOverlay.
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = controlsBottomInset)
                .graphicsLayer {
                    // The outgoing photo: follows the finger, shrinking into a rounded card.
                    val x = swipeX.value
                    if (x != 0f) {
                        val p = PhotoSwipeRules.progress(x, size.width)
                        val sc = PhotoSwipeRules.scaleAt(p)
                        translationX = x
                        scaleX = sc
                        scaleY = sc
                        shape = RoundedCornerShape(PhotoSwipeRules.cornerDpAt(p).dp.toPx())
                        clip = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (placeholder != null && !gpuActive) {
                Image(
                    bitmap = placeholder.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            when {
                gpuActive -> Unit
                // Holding Compare shows the whole original even over the latched split
                // view; releasing it brings the split back.
                bmp != null && compareMode && before != null && !peekOriginal ->
                    CompareSlider(before = before, after = bmp)
                bmp != null -> ZoomableImage(
                    bitmap = bmp,
                    // Clipping warnings: the GPU surface steps aside while they show
                    // (gpuActive above), as it does for compare, so the mask always has
                    // the CPU frame it was computed from underneath it.
                    overlay = clipMask,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = revealAlpha.value },
                    // The swipe's stand-in for the GPU surface always starts at fit.
                    initialScale = if (swiping) 1f else gpuZoomInitial,
                    zoomRequests = zoomRequests,
                    onPointPicked = onPointPicked,
                    // Press and hold the photo for the unedited frame. The bitmap already
                    // exists for the latched CompareSlider above, so the gesture costs no
                    // extra render.
                    //
                    // PreviewRegion.onPointPicked is NOT nullable -- it is always the
                    // magnifier opener -- so there is no sampler to yield to here, and a
                    // long press is otherwise unused on this surface. The colour/WB sampler
                    // lives behind the eyedropper (toolUi.sampleOpen), which replaces the preview outright.
                    peek = before,
                    forcePeek = peekOriginal,
                    // Lightroom-style zoom: render the visible region at native resolution
                    // (renderKey = previewTick so an edit while zoomed re-renders the crop).
                    renderKey = renderKey,
                    onRoiSettled = onRoiSettled,
                    // Returning to fit also re-arms the GPU fit surface.
                    onRoiCleared = { onRoiCleared(); gpuZoomHandoff = false },
                    roiOverlay = roiOverlay,
                    // No inset: this Box already stops at the panel's top edge.
                    onZoomedChange = { zoomed = it },
                )
                placeholder != null -> Unit
                else -> Text(stringResource(R.string.editor_preview_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Compact translucent histogram overlaid at the TOP EDGE of the preview
            // (Lightroom-style). Driven by the same `showHistogram` state as the
            // Source-panel toggle. PreviewHistogramOverlay acquires an explicit read lease and
            // samples off-main; preview retirement defers recycle until that lease closes.
            if (showHistogram && bmp != null) {
                PreviewHistogramOverlay(
                    bitmap = bmp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp),
                )
            }

            // Status pill — top-start of the preview, over the canvas.
            StatusPill(
                exporting = exporting,
                rendering = busy,
                decoding = decoding,
                lastRenderMs = lastRenderMs,
                renderErr = renderErr,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 6.dp),
            )
        }

        // The neighbour sliding in after the photo: grows from a rounded card to fill, its
        // image drifting inside the frame (the carousel's parallax) until it lands.
        val swipeSign by remember { derivedStateOf { kotlin.math.sign(swipeX.value) } }
        val incoming = heldIncoming ?: when {
            swipeSign < 0f -> swipe?.next
            swipeSign > 0f -> swipe?.previous
            else -> null
        }
        if (incoming != null && !incoming.isRecycled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = controlsBottomInset)
                    .graphicsLayer {
                        val x = swipeX.value
                        val towardNext = x < 0f
                        val p = PhotoSwipeRules.progress(x, size.width)
                        val sc = PhotoSwipeRules.scaleAt(1f - p)
                        translationX = x + (if (towardNext) 1f else -1f) * (size.width + swipeGapPx)
                        scaleX = sc
                        scaleY = sc
                        shape = RoundedCornerShape(PhotoSwipeRules.cornerDpAt(1f - p).dp.toPx())
                        clip = true
                    },
            ) {
                Image(
                    bitmap = incoming.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val x = swipeX.value
                            val p = PhotoSwipeRules.progress(x, size.width)
                            translationX = (if (x < 0f) 1f else -1f) *
                                (1f - p) * size.width * PhotoSwipeRules.PARALLAX
                        },
                )
            }
        }

        // The frame counter ("7 / 24") and, when opened from it, the folder strip.
        if (strip != null && strip.currentIndex >= 0) {
            FolderStripToggle(
                position = strip.currentIndex + 1,
                count = strip.frames.size,
                open = stripOpen,
                onToggle = { stripOpen = !stripOpen },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp),
            )
        }
        if (stripShown && strip != null) {
            FolderStrip(
                model = FolderStripModel(
                    frames = strip.frames,
                    currentIndex = strip.currentIndex,
                    looks = strip.looks,
                    lookKey = strip.lookKey,
                    paused = strip.paused || swiping,
                    io = strip.io,
                    onOpen = { index ->
                        if (index != strip.currentIndex) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            strip.onOpen(index)
                        }
                    },
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = controlsBottomInset + 8.dp)
                    .onGloballyPositioned { stripTopPx = it.positionInParent().y },
            )
        }
    }
}

/** A release faster than this (dp/s) toward a neighbour opens it, however short the swipe. */
private const val SWIPE_FLING_DP = 400f

/** The neighbour sliding the rest of the way in, ms. */
private const val SWIPE_COMMIT_MS = 250

/** A swipe that did not commit springing back, ms. */
private const val SWIPE_BACK_MS = 200

/** Longest the neighbour holds the screen while the editor opens it. */
private const val SWIPE_HOLD_MAX_MS = 3_000L
