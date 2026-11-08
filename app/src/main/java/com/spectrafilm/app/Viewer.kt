/*
 * Spektrafilm for Android — editor-grade preview viewer. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A set of self-contained composables that turn the flat preview Image into an
 * editor-grade viewer:
 *   - ZoomableImage: pinch-zoom + pan + double-tap fit/2x, clamped to bounds.
 *   - CompareSlider: a draggable split handle revealing the input (before) vs the
 *     rendered output (after) in the same frame.
 *   - PreviewHistogramOverlay: compact RGB + luma histogram overlaid on the live
 *     preview, computed off the main thread and drawn with a Compose Canvas.
 *   - MagnifierOverlay: a full-screen 1:1 view of a real full-resolution crop that
 *     the caller renders through the engine, so dye-cloud grain truly resolves.
 *
 * No new gradle dependency: everything is built on Compose pointerInput, graphicsLayer
 * and Canvas plus the Material3 widgets already used elsewhere.
 *
 * CompareSlider lives in CompareSlider.kt, the histogram in Histogram.kt and the preview
 * Bitmap read leases in PreviewLeases.kt (#273).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import com.spectrafilm.engine.LinearImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlin.math.min
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity

/** Zoom limits for [ZoomableImage]. */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_MIN_ZOOM = 2f

/** Zoom factor per mouse-wheel notch (scrollDelta.y of 1). */
private const val WHEEL_ZOOM_STEP = 1.25f

/** A keyboard zoom request (Ctrl+= / Ctrl+- / Ctrl+0) for whichever preview is on screen. */
enum class ViewerZoom { IN, OUT, FIT }

/**
 * New (scale, offset) for a keyboard zoom: one wheel notch about the view centre, so the
 * keys behave exactly like the wheel with the cursor in the middle; FIT returns to 1x.
 */
internal fun keyboardZoomTransform(scale: Float, offset: Offset, zoom: ViewerZoom): Pair<Float, Offset> =
    when (zoom) {
        ViewerZoom.FIT -> MIN_ZOOM to Offset.Zero
        ViewerZoom.IN -> wheelZoomTransform(scale, offset, Offset.Zero, Offset(0f, -1f), 0f)
        ViewerZoom.OUT -> wheelZoomTransform(scale, offset, Offset.Zero, Offset(0f, 1f), 0f)
    }

/**
 * Mouse wheel / trackpad scroll over the preview (DeX, ChromeOS, desktop windows, tablets with
 * a mouse). Delivers each scroll event's delta and pointer position, then consumes it so a
 * scrollable parent does not also move. Plain pointer-event plumbing: touch is untouched.
 */
private fun Modifier.onPointerScroll(onScroll: (delta: Offset, position: Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                onScroll(change.scrollDelta, change.position)
                event.changes.forEach { it.consume() }
            }
        }
    }

/**
 * New (scale, offset) for one scroll event, before pan clamping. Vertical wheel zooms about
 * [pointerFromCentre] (the pointer relative to the view centre, so the pixel under the cursor
 * stays put, same anchoring as the pinch centroid); wheel up (negative y) zooms in. Horizontal
 * scroll (trackpad, tilt wheel) pans while zoomed. Pure so it is unit-tested.
 */
internal fun wheelZoomTransform(
    scale: Float,
    offset: Offset,
    pointerFromCentre: Offset,
    scrollDelta: Offset,
    panPxPerUnit: Float,
): Pair<Float, Offset> {
    val newScale = (scale * WHEEL_ZOOM_STEP.pow(-scrollDelta.y)).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val zoomed = (offset - pointerFromCentre) * (newScale / scale) + pointerFromCentre
    val pan = if (newScale > MIN_ZOOM) Offset(-scrollDelta.x * panPxPerUnit, 0f) else Offset.Zero
    return newScale to (if (newScale <= MIN_ZOOM) Offset.Zero else zoomed + pan)
}

/**
 * GPU LUT preview surface for the FIT view. Shows the current look INSTANTLY — the engine's
 * baked 3D LUT sampled on-GPU (see [GpuLutPreview]) — with no per-edit CPU re-render, the way
 * Lightroom's loupe stays live. It is fit-only by design: a pinch or double-tap calls
 * [onZoomStart] so the caller hands off to the CPU [ZoomableImage], which renders the zoomed
 * region with grain/halation (a pointwise LUT can't carry those — but at the downscaled fit
 * preview they're averaged to near-invisibility anyway, so nothing meaningful is lost at fit).
 * A single tap reports normalized image coords for the magnifier. If the GL program can't build
 * on this device, [onUnavailable] fires once so the caller falls back to the CPU bitmap.
 */
@Composable
fun GpuPreviewSurface(
    proxy: LinearImage,
    lut: CubeLut,
    modifier: Modifier = Modifier,
    exposureGain: Float = 1f,
    onPointPicked: ((Float, Float) -> Unit)? = null,
    onZoomStart: () -> Unit = {},
    onZoomIn: () -> Unit = onZoomStart,
    onUnavailable: () -> Unit = {},
    // Keyboard zoom. This surface is fit-only, so zoom-in hands off like a double-tap and
    // zoom-out / fit have nothing to do.
    zoomRequests: Flow<ViewerZoom>? = null,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val aspect = proxy.width.toFloat() / proxy.height.toFloat()
    val latestOnZoomIn by rememberUpdatedState(onZoomIn)
    LaunchedEffect(zoomRequests) {
        zoomRequests?.collect { if (it == ViewerZoom.IN) latestOnZoomIn() }
    }
    val previewDesc = stringResource(R.string.tool_viewer_preview_desc)
    val zoomInLabel = stringResource(R.string.tool_viewer_zoom_in)
    Box(
        modifier = modifier
            .onSizeChanged { viewSize = it }
            // The GL view has no semantics of its own; describe the preview + its gestures here.
            // No on-screen zoom button (pinch and double-tap zoom): a screen reader zooms in
            // through this action instead.
            .semantics {
                contentDescription = previewDesc
                customActions = listOf(CustomAccessibilityAction(zoomInLabel) { latestOnZoomIn(); true })
            }
            .pointerInput(Unit) {
                // This surface is fit-only; any pinch hands off to the CPU zoom path.
                detectTransformGestures { _, _, zoom, _ ->
                    if (kotlin.math.abs(zoom - 1f) > 0.001f) onZoomStart()
                }
            }
            .onPointerScroll { delta, _ -> if (delta.y < 0f) onZoomIn() }
            .pointerInput(onPointPicked) {
                detectTapGestures(
                    onDoubleTap = { onZoomStart() },
                    onTap = { tap ->
                        val cb = onPointPicked ?: return@detectTapGestures
                        val n = fitViewToImageNormalized(tap, viewSize, aspect)
                            ?: return@detectTapGestures
                        cb(n.first, n.second)
                    },
                )
            },
    ) {
        GpuLutPreview(
            proxy = proxy, lut = lut,
            exposureGain = exposureGain,
            modifier = Modifier.fillMaxSize(), onUnavailable = onUnavailable,
        )
    }
}

/** Map a tap to normalized image coords for a ContentScale.Fit (letterboxed) image at fit zoom. */
private fun fitViewToImageNormalized(tap: Offset, view: IntSize, aspect: Float): Pair<Float, Float>? {
    if (view.width == 0 || view.height == 0) return null
    val viewA = view.width.toFloat() / view.height
    val dispW: Float
    val dispH: Float
    if (viewA > aspect) { dispH = view.height.toFloat(); dispW = dispH * aspect }
    else { dispW = view.width.toFloat(); dispH = dispW / aspect }
    val x0 = (view.width - dispW) / 2f
    val y0 = (view.height - dispH) / 2f
    val nx = (tap.x - x0) / dispW
    val ny = (tap.y - y0) / dispH
    return if (nx in 0f..1f && ny in 0f..1f) nx to ny else null
}

/**
 * A pinch-zoom + pan image that fits its bitmap to the available width (ContentScale.Fit)
 * at zoom 1. Pinch to zoom up to [MAX_ZOOM]x; drag to pan while zoomed; double-tap toggles
 * between fit (1x) and 2x centred on the tap. Pan is clamped so the image cannot be dragged
 * past its own edges. [onPointPicked] reports the tapped location in normalized image
 * coordinates (0..1, 0..1) for the magnifier; it is invoked on a single tap when not zoomed
 * past fit, otherwise a single tap is ignored (the gesture is treated as a pan inspect).
 */
@OptIn(FlowPreview::class)
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onPointPicked: ((Float, Float) -> Unit)? = null,
    // The UNEDITED render of the same frame. Press and hold the photo to see it, release to
    // come back — Lightroom's before/after, with no button. Null disables the gesture.
    //
    // It is swapped only into the drawn Image, never into [bitmap]: bitmap is what the 1:1
    // double-tap and the ROI settle do their arithmetic on, and re-keying those on a
    // momentary peek would fire a full native ROI render of the BEFORE frame every time a
    // finger rested on the photo. Sharing the Image's graphicsLayer also means the peek
    // inherits the live zoom and pan for free.
    peek: Bitmap? = null,
    // Show [peek] while true, as if the photo were held: the top bar's Compare button,
    // touched and held, peeks at the original from there.
    forcePeek: Boolean = false,
    // Lightroom-style zoom: when zoomed past fit, [onRoiSettled] fires (debounced) with the
    // visible region so the caller can render that crop at native resolution and pass it back
    // as [roiOverlay]; [onRoiCleared] fires when zoom returns to fit. [renderKey] (e.g. the
    // preview tick) re-fires the settle so an edit while zoomed re-renders the sharp region.
    renderKey: Int = 0,
    onRoiSettled: ((RoiRect) -> Unit)? = null,
    onRoiCleared: (() -> Unit)? = null,
    roiOverlay: RoiOverlay? = null,
    // Height of whatever overlays the bottom of this view (the floating adjustment panel):
    // the zoom readout centres in the part that is still visible (#181, 200% font).
    controlsBottomInset: Dp = 0.dp,
    // Scale to start at on first composition — used when the GPU fit surface hands off via its
    // "+" button so zoom-in lands already zoomed instead of at fit. Default 1f (fit) everywhere else.
    initialScale: Float = 1f,
    // A same-size mask drawn over the photo through the SAME transform (the clipping
    // warnings). Hidden while peeking at the unedited frame, which it does not describe.
    overlay: Bitmap? = null,
    // Keyboard zoom (Ctrl+= / Ctrl+- / Ctrl+0), about the view centre.
    zoomRequests: Flow<ViewerZoom>? = null,
    // Told when the view leaves fit (true) or comes back to it (false): a sideways swipe to
    // the next photo only applies at fit, where one finger has nothing to pan.
    onZoomedChange: ((Boolean) -> Unit)? = null,
) {
    var scale by remember { mutableFloatStateOf(initialScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // While zoomed, an edit (renderKey bump) makes the current sharp ROI crop stale: hide it so the
    // live (soft) proxy — which the draft render keeps current — shows through, then reveal the
    // freshly rendered crop when it lands. The zoom-path half of the draft/final render port.
    var overlayStale by remember { mutableStateOf(false) }
    LaunchedEffect(renderKey) { overlayStale = true }
    LaunchedEffect(roiOverlay) { overlayStale = false }

    // True only while a finger is held down past the long-press threshold.
    var peeking by remember { mutableStateOf(false) }
    val image = rememberLeasedImage(bitmap) ?: return
    // Leased exactly like the main preview: the before bitmap is a live render owned by the
    // editor and is retired on the next settle, so drawing it without a lease is a
    // use-after-recycle waiting for a slow finger.
    val peekImage = peek?.let { rememberLeasedImage(it) }
    val peekShown = peeking || forcePeek
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    // scale/offset are read in the draw phase by graphicsLayer {}. These three reads are
    // genuinely composition-phase (a string, two visibility gates), so derive them: the
    // percentage changes once per integer and the gates once per crossing, instead of on
    // every pointer sample.
    val zoomPercent by remember { derivedStateOf { (scale * 100f).roundToInt() } }
    val zoomedIn by remember { derivedStateOf { scale > 1.01f } }
    val zoomState = stringResource(R.string.tool_viewer_zoom_state, zoomPercent)
    val latestZoomedChange by rememberUpdatedState(onZoomedChange)
    LaunchedEffect(Unit) {
        snapshotFlow { zoomedIn }.collect { latestZoomedChange?.invoke(it) }
    }

    // Drive the sharp ROI render off the zoom/pan transform (and re-fire on a param edit via
    // renderKey). Debounced so we render on settle, not every gesture frame; the instantly
    // graphicsLayer-scaled proxy stays visible until the sharp crop lands (progressive, like
    // the two-pass preview). Reverts to the proxy when zoom returns to fit.
    if (onRoiSettled != null) {
        LaunchedEffect(renderKey, aspect) {
            snapshotFlow { Triple(scale, offset, viewSize) }
                // Raised from 280ms: each zoom settle triggers a full RAW proxy decode + an
                // all-core engine render (~1s). A longer settle coalesces a pinch/pan into a
                // single render instead of a herd of overlapping decodes (the main editing
                // battery drain seen in the device logcat).
                .debounce(500L)
                .collectLatest { (s, o, v) ->
                    val roi = viewportRoiNormalized(v, s, o, aspect)
                    if (roi == null) onRoiCleared?.invoke() else onRoiSettled(roi)
                }
        }
    }

    // Clamp the pan so the (scaled) CONTENT stays within the view bounds.
    //
    // The bound must come from the fitted CONTENT rect, not the viewport. The
    // bitmap is drawn ContentScale.Fit and is therefore LETTERBOXED: at fit it
    // fills one axis and leaves bars on the other. Using the viewport extent on
    // the letterboxed axis over-permits the pan by exactly the letterbox ratio,
    // and the image can be dragged clean out of view — a black viewport with the
    // zoom pill still reading 410%.
    //
    // Measured on device (998x1802 viewport, 4:3 landscape content, s=4.1):
    // content fits to 998x749, so the true bound is (749*4.1 - 1802)/2 = 634,
    // while the viewport formula gave (1802*(4.1-1))/2 = 2793 — 4.4x too far.
    //
    // The horizontal axis was correct only BY ACCIDENT: this content fills the
    // width at fit, so fitW == viewSize.width and the two formulas coincide.
    // That is why panning left/right behaved and up/down went black; a PORTRAIT
    // photo on this same screen would have broken the other way round.
    fun clampOffset(raw: Offset, s: Float): Offset =
        clampPanOffset(raw, viewSize, s, aspect)

    val zoomInLabel = stringResource(R.string.tool_viewer_zoom_in)
    val zoomOutLabel = stringResource(R.string.tool_viewer_zoom_out)
    val zoomFitLabel = stringResource(R.string.tool_viewer_zoom_fit)

    // The viewport shrinks and grows as the editor's panel opens and closes (the photo fits
    // above it). A pan that was legal in the taller view can overshoot the shorter one, so
    // re-clamp whenever the size changes rather than waiting for the next gesture.
    LaunchedEffect(viewSize) { offset = clampOffset(offset, scale) }
    // The wheel and keyboard handlers outlive recompositions (pointerInput(Unit) / a
    // long-lived collector), so they clamp through the LATEST clampOffset: a rotate or crop
    // changes [aspect], and a stale closure would clamp against the old content bounds.
    val latestClamp = rememberUpdatedState<(Offset, Float) -> Offset> { raw, s -> clampOffset(raw, s) }
    LaunchedEffect(zoomRequests) {
        zoomRequests?.collect { zoom ->
            val (ns, no) = keyboardZoomTransform(scale, offset, zoom)
            scale = ns
            offset = latestClamp.value(no, ns)
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .semantics {
                stateDescription = zoomState
                // No on-screen zoom buttons: a screen reader zooms through these actions.
                customActions = buildList {
                    add(CustomAccessibilityAction(zoomInLabel) {
                        val ns = (scale * 1.6f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        scale = ns
                        offset = clampOffset(offset, ns)
                        true
                    })
                    if (scale > 1.01f) {
                        add(CustomAccessibilityAction(zoomOutLabel) {
                            val ns = (scale / 1.6f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            scale = ns
                            offset = if (ns <= 1.01f) Offset.Zero else clampOffset(offset, ns)
                            true
                        })
                        add(CustomAccessibilityAction(zoomFitLabel) {
                            scale = 1f
                            offset = Offset.Zero
                            true
                        })
                    }
                }
            }
            // Mouse cursor: an open hand while zoomed says "drag to pan".
            .pointerHoverIcon(if (zoomedIn) PointerIcon.Hand else PointerIcon.Default)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    // Anchor the zoom about the gesture centroid (relative to view centre),
                    // then add the pan delta; finally clamp so edges can't be over-dragged.
                    val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
                    val centroidRel = centroid - c
                    val newOffset = (offset - centroidRel) * (newScale / oldScale) + centroidRel + pan
                    scale = newScale
                    offset = clampOffset(newOffset, newScale)
                }
            }
            .onPointerScroll { delta, position ->
                val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
                val (ns, no) = wheelZoomTransform(
                    scale, offset, position - c, delta, panPxPerUnit = viewSize.width / 10f,
                )
                scale = ns
                offset = latestClamp.value(no, ns)
            }
            .pointerInput(onPointPicked, peekImage != null) {
                detectTapGestures(
                    // Press-and-hold shows the unedited frame; any release or cancel ends it.
                    // Both live in this ONE detector so they cannot fight the double-tap zoom.
                    onPress = {
                        tryAwaitRelease()
                        peeking = false
                    },
                    onLongPress = { if (peekImage != null) peeking = true },
                    onDoubleTap = { tap ->
                        if (zoomedIn) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // One bitmap pixel per view pixel (bitmap.width / fittedWidth),
                            // but never less than 2x. The preview bitmap is at most about the
                            // screen width, so 1:1 alone came out at or below 1x and was
                            // clamped to MIN_ZOOM: double-tap did nothing on a phone. The
                            // settled zoom then renders its region at full resolution.
                            val fitted = if (viewSize.width > 0) {
                                fitRect(viewSize, aspect)[2]
                            } else {
                                0f
                            }
                            val oneToOne = if (fitted > 0f) bitmap.width / fitted else 0f
                            val target = maxOf(DOUBLE_TAP_MIN_ZOOM, oneToOne).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
                            scale = target
                            offset = clampOffset((c - tap) * target, target)
                        }
                    },
                    onTap = { tap ->
                        val cb = onPointPicked ?: return@detectTapGestures
                        val n = viewToImageNormalized(tap, viewSize, scale, offset, aspect)
                        if (n != null) cb(n.x, n.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = if (peekShown && peekImage != null) peekImage else image,
            contentDescription = if (peekShown && peekImage != null) {
                stringResource(R.string.tool_viewer_original_desc)
            } else {
                stringResource(R.string.tool_viewer_preview_desc)
            },
            contentScale = ContentScale.Fit,
            // aspectRatio alone, not after fillMaxWidth: a forced full width cannot shrink
            // when the viewport is shorter than the width-fitted height (a portrait photo
            // above an open panel), so the photo overflowed and was clipped instead of
            // refitting, and fitRect's height-fit assumption no longer matched the pixels.
            modifier = Modifier
                .aspectRatio(aspect)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )

        // Sharp ROI overlay: project the cached crop's normalized rect through the SAME
        // transform as the proxy, so it registers exactly and tracks pan/zoom. Drawn over the
        // (soft) scaled proxy; clipToBounds on the Box clips any overflow.
        val ov = roiOverlay
        // Shown only while it covers the whole visible region. Zooming out (or panning) past
        // the patch used to leave it up as a small box with its own look over the soft proxy
        // until the next settle replaced it: a visible seam that then "popped". Hiding it the
        // moment the view leaves it lets the proxy show alone until the next patch lands.
        val overlayCoversView by remember(ov, aspect) {
            derivedStateOf {
                ov != null && overlayCoversViewport(
                    RoiRect(ov.cxN, ov.cyN, ov.wN, ov.hN),
                    viewportRoiNormalized(viewSize, scale, offset, aspect),
                )
            }
        }
        // Suppressed while peeking: the cached ROI crop is a render of the EDITED frame, so
        // leaving it up would paint the edit back over the middle of the before image.
        if (
            ov != null && !overlayStale && zoomedIn && overlayCoversView &&
            viewSize.width > 0 && !peekShown
        ) {
            val roiImage = rememberLeasedImage(ov.bitmap) ?: return@Box
            Canvas(Modifier.fillMaxSize()) {
                val p0 = imageNormToView(
                    ov.cxN - ov.wN / 2f, ov.cyN - ov.hN / 2f, viewSize, scale, offset, aspect,
                )
                val p1 = imageNormToView(
                    ov.cxN + ov.wN / 2f, ov.cyN + ov.hN / 2f, viewSize, scale, offset, aspect,
                )
                val dstW = (p1.x - p0.x).toInt()
                val dstH = (p1.y - p0.y).toInt()
                if (dstW > 0 && dstH > 0) {
                    drawImage(
                        image = roiImage,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(roiImage.width, roiImage.height),
                        dstOffset = IntOffset(p0.x.toInt(), p0.y.toInt()),
                        dstSize = IntSize(dstW, dstH),
                    )
                }
            }
        }

        val overlayImage = remember(overlay) { overlay?.asImageBitmap() }
        if (overlayImage != null && !peekShown) {
            Image(
                bitmap = overlayImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .aspectRatio(aspect)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }

        // Zoom is pinch and double-tap only: the "+", "−" and "Fit" buttons were removed
        // (they covered the photo and repeated the gestures). While zoomed, a small "%"
        // readout stays at the right edge so the zoom level is still visible.
        if (scale > 1.01f) {
            Text(
                stringResource(R.string.tool_viewer_zoom_percent, (scale * 100f).roundToInt()),
                color = SpektraBrand.onCanvas,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp, bottom = controlsBottomInset)
                    .background(SpektraBrand.scrim.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** A normalized image rectangle (centre + size, all in 0..1) — the visible region under zoom. */
data class RoiRect(val cxN: Float, val cyN: Float, val wN: Float, val hN: Float)

/**
 * True when the patch rect [overlay] contains the visible region [view] (both normalized to the
 * image), with a little slack for rounding. A null [view] (at fit) is never covered: the
 * patch is only meant for zoomed views.
 */
internal fun overlayCoversViewport(overlay: RoiRect, view: RoiRect?): Boolean {
    if (view == null) return false
    val eps = 1e-3f
    return view.cxN - view.wN / 2f >= overlay.cxN - overlay.wN / 2f - eps &&
        view.cxN + view.wN / 2f <= overlay.cxN + overlay.wN / 2f + eps &&
        view.cyN - view.hN / 2f >= overlay.cyN - overlay.hN / 2f - eps &&
        view.cyN + view.hN / 2f <= overlay.cyN + overlay.hN / 2f + eps
}

/** A sharp region-of-interest render plus the normalized image rect it represents, so the
 *  overlay tracks pan/zoom (and survives a stale frame) by re-projecting that rect. */
class RoiOverlay(
    val bitmap: Bitmap,
    val cxN: Float, val cyN: Float, val wN: Float, val hN: Float,
)

/** The fitted (zoom=1) image rectangle inside [view] under ContentScale.Fit: (left, top, w, h). */
private fun fitRect(view: IntSize, aspect: Float): FloatArray {
    val viewAspect = view.width.toFloat() / view.height.toFloat()
    val fitW: Float
    val fitH: Float
    if (aspect > viewAspect) { fitW = view.width.toFloat(); fitH = fitW / aspect }
    else { fitH = view.height.toFloat(); fitW = fitH * aspect }
    val left = view.width / 2f - fitW / 2f
    val top = view.height / 2f - fitH / 2f
    return floatArrayOf(left, top, fitW, fitH)
}

/**
 * Clamp a pan offset so the SCALED CONTENT cannot be dragged outside the viewport.
 *
 * Top-level and `internal` so the math is unit-testable — the defect below shipped
 * because the only clamp coverage used a square image in a square view, i.e. the
 * one geometry where the bug is invisible.
 */
internal fun clampPanOffset(raw: Offset, view: IntSize, scale: Float, aspect: Float): Offset {
    if (view.width <= 0 || view.height <= 0 || !(aspect > 0f)) return Offset.Zero
    val (_, _, fitW, fitH) = fitRect(view, aspect)
    val maxX = max(0f, (fitW * scale - view.width) / 2f)
    val maxY = max(0f, (fitH * scale - view.height) / 2f)
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

/** Inverse of the viewport transform: a view-space point → normalized image coords (UNclamped). */
internal fun mapViewToImageNorm(
    p: Offset, view: IntSize, scale: Float, offset: Offset, aspect: Float,
): Offset {
    val cx = view.width / 2f
    val cy = view.height / 2f
    // Undo the graphicsLayer transform (scale about centre, then translate).
    val ux = (p.x - cx - offset.x) / scale + cx
    val uy = (p.y - cy - offset.y) / scale + cy
    val (left, top, fitW, fitH) = fitRect(view, aspect)
    return Offset((ux - left) / fitW, (uy - top) / fitH)
}

/** Forward transform: normalized image coords → view-space point under the current zoom/pan. */
internal fun imageNormToView(
    nx: Float, ny: Float, view: IntSize, scale: Float, offset: Offset, aspect: Float,
): Offset {
    val (left, top, fitW, fitH) = fitRect(view, aspect)
    val ux = left + nx * fitW
    val uy = top + ny * fitH
    val cx = view.width / 2f
    val cy = view.height / 2f
    return Offset((ux - cx) * scale + cx + offset.x, (uy - cy) * scale + cy + offset.y)
}

/**
 * Map a view-space tap to normalized image coordinates (0..1), accounting for the
 * ContentScale.Fit letterboxing and the current zoom/pan transform. Returns null if the
 * tap fell on the letterbox margin (outside the image).
 */
private fun viewToImageNormalized(
    tap: Offset, view: IntSize, scale: Float, offset: Offset, aspect: Float,
): Offset? {
    if (view.width == 0 || view.height == 0) return null
    val n = mapViewToImageNorm(tap, view, scale, offset, aspect)
    if (n.x < 0f || n.x > 1f || n.y < 0f || n.y > 1f) return null
    return n
}

/**
 * The visible image region under the current zoom/pan, as a normalized centre + size, or null
 * when not zoomed past fit (so the caller reverts to the proxy). Maps the viewport's corners
 * through the inverse transform and clamps to the image bounds. Pure — unit-tested.
 */
internal fun viewportRoiNormalized(
    view: IntSize, scale: Float, offset: Offset, aspect: Float,
): RoiRect? {
    if (view.width == 0 || view.height == 0 || scale <= 1.01f) return null
    val a = mapViewToImageNorm(Offset(0f, 0f), view, scale, offset, aspect)
    val b = mapViewToImageNorm(
        Offset(view.width.toFloat(), view.height.toFloat()), view, scale, offset, aspect,
    )
    val nx0 = min(a.x, b.x).coerceIn(0f, 1f)
    val ny0 = min(a.y, b.y).coerceIn(0f, 1f)
    val nx1 = max(a.x, b.x).coerceIn(0f, 1f)
    val ny1 = max(a.y, b.y).coerceIn(0f, 1f)
    val wN = nx1 - nx0
    val hN = ny1 - ny0
    if (wN <= 0f || hN <= 0f) return null
    return RoiRect((nx0 + nx1) / 2f, (ny0 + ny1) / 2f, wN, hN)
}

/**
 * A full-screen overlay that shows a real, full-resolution engine render of a crop, at
 * 1:1 (pixel-for-pixel) so that grain/dye-cloud structure actually resolves. While the
 * crop is being rendered, [crop] is null and a progress state is shown. The user dismisses
 * the overlay with the close button or by tapping the scrim.
 */
@Composable
fun MagnifierOverlay(
    crop: Bitmap?,
    rendering: Boolean,
    status: String,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SpektraBrand.scrim.copy(alpha = 0.85f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                stringResource(R.string.tool_viewer_magnifier_title),
                color = SpektraBrand.onCanvas,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            // True 1:1: a crop of N device pixels must occupy N device pixels, so the dp
            // size is N / density. Capped at 360.dp so it still fits a phone; below the cap
            // the inspector finally shows native pixels instead of a bilinear upscale.
            val density = LocalDensity.current
            val magnifierSide = with(density) {
                val cropPx = crop?.width ?: MAGNIFIER_CROP_PX
                cropPx.toDp().coerceAtMost(360.dp)
            }
            Box(
                Modifier
                    .size(magnifierSide)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                val c = crop
                if (c != null) {
                    // The crop is a real full-resolution engine render (~512px native), shown
                    // 1:1 — no upscale of the preview — so dye-cloud grain truly resolves.
                    Image(
                        bitmap = c.asImageBitmap(),
                        contentDescription = stringResource(R.string.tool_viewer_magnifier_image_desc),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SpectraLoadingIndicator(color = SpektraBrand.onCanvas)
                }
            }
            // Progress copy is announced as it changes (polite: never interrupts).
            Text(
                status,
                color = SpektraBrand.onCanvas.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (rendering) {
                Text(
                    stringResource(R.string.tool_viewer_magnifier_rendering),
                    color = SpektraBrand.onCanvas.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.tool_close), color = SpektraBrand.onCanvas)
            }
        }
    }
}
