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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The pinned preview region on the near-black canvas.
 *
 * Nothing sits on the photo but its status: the round histogram / clipping / loupe /
 * compare / crop / rotate buttons that used to float over its bottom edge moved to the app
 * bar (Crop) and its More menu (the view toggles), and rotate lives inside Crop. The canvas
 * pattern is full-bleed content (m3-design-language, ux-flows §3, Map / canvas).
 */
@Composable
internal fun MainActivity.PreviewRegion(
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
            },
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
            !compareMode && !showClipping && !gpuBroken && !gpuZoomHandoff
        if (gpuActive) GpuPreviewSurface(
            proxy = gpuProxy!!,
            lut = gpuLut!!,
            exposureGain = gpuGain,
            modifier = Modifier.fillMaxSize(),
            controlsBottomInset = controlsBottomInset,
            onPointPicked = onPointPicked,
            onZoomStart = { gpuZoomHandoff = true; gpuZoomInitial = 1f },
            onZoomIn = { gpuZoomHandoff = true; gpuZoomInitial = 2f },
            zoomRequests = viewerVm.zoomRequests,
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
                .padding(bottom = controlsBottomInset),
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
                bmp != null && compareMode && before != null ->
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
                    initialScale = gpuZoomInitial,
                    zoomRequests = viewerVm.zoomRequests,
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
                    // Lightroom-style zoom: render the visible region at native resolution
                    // (renderKey = previewTick so an edit while zoomed re-renders the crop).
                    renderKey = renderKey,
                    onRoiSettled = onRoiSettled,
                    // Returning to fit also re-arms the GPU fit surface.
                    onRoiCleared = { onRoiCleared(); gpuZoomHandoff = false },
                    roiOverlay = roiOverlay,
                    // No inset: this Box already stops at the panel's top edge.
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
    }
}
