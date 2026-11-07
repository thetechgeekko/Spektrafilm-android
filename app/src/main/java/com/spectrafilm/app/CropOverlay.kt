/*
 * Spektrafilm for Android — interactive crop overlay. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A Lightroom-mobile-style full-screen crop tool that replaces the old numeric
 * crop sliders. The user drags corner/edge handles of a crop rectangle over the
 * preview image, with a rule-of-thirds grid and a dimmed exterior; aspect-ratio
 * preset chips constrain the rectangle; a 90° rotate control is included.
 *
 * On confirm the selected rectangle is translated into the engine's existing crop
 * parameters (io.crop / io.crop_center / io.crop_size). The mapping is derived
 * directly from the engine port (runtime/stages/crop_resize.cpp::crop_image):
 *
 *   center is (x, y), fractions of (W, H).
 *   size   is (x, y), fractions of the LONG side max(W, H).
 *
 * So for a normalized rectangle [left, top, right, bottom] (each 0..1 of W or H):
 *   center.x = (left + right) / 2          center.y = (top + bottom) / 2
 *   size.x   = (right - left) * W / maxDim size.y   = (bottom - top) * H / maxDim
 *
 * where maxDim = max(W, H). This reproduces, on the engine side,
 *   cn  = round(shape * center_yx);  sz = round(maxDim * size_yx)
 * i.e. exactly the user's selected pixel box. We snap center so the box stays
 * inside the image (the engine also clamps x0, but we keep the UI honest).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Aspect-ratio presets for the crop overlay. Fixed ratios are stored long:short and turned
 * portrait or landscape by the orientation toggle, so seven buttons cover what used to be
 * ten chips (#258 asked for 2:3; 4:5 and 9:16 are the social-feed and story frames).
 */
internal enum class CropAspect(val long: Int, val short: Int) {
    FREE(0, 0),
    ORIGINAL(0, 0), // resolved at runtime to the image aspect
    SQUARE(1, 1),
    R5_4(5, 4),
    R4_3(4, 3),
    R3_2(3, 2),
    R16_9(16, 9);

    val isFixed: Boolean get() = long > 0

    /** W:H for this preset in the given orientation; null for Free / Original. */
    fun ratio(portrait: Boolean): Float? = when {
        !isFixed -> null
        portrait -> short.toFloat() / long
        else -> long.toFloat() / short
    }

    /** "4:5" in portrait, "5:4" in landscape (digits read the same in every locale). */
    fun label(portrait: Boolean): String =
        if (portrait) "$short:$long" else "$long:$short"
}

/**
 * The interactive crop tool. [bitmap] is the live preview (used purely to show the
 * image and read its aspect ratio). [initialCrop]/[initialCenter]/[initialSize]
 * seed the rectangle from the current recipe. [onRotate] rotates the source 90°
 * (the same control the preview uses). [onConfirm] reports `crop`, `center` (x,y)
 * and `size` (x,y) ready to write straight into ParamsState; [onCancel] discards.
 */
@Composable
fun CropOverlay(
    bitmap: Bitmap,
    initialCrop: Boolean,
    initialCenter: Pair<Float, Float>,
    initialSize: Pair<Float, Float>,
    onRotate: () -> Unit,
    onConfirm: (crop: Boolean, center: Pair<Float, Float>, size: Pair<Float, Float>) -> Unit,
    onCancel: () -> Unit,
) {
    val imgW = bitmap.width.toFloat().coerceAtLeast(1f)
    val imgH = bitmap.height.toFloat().coerceAtLeast(1f)
    val maxDim = max(imgW, imgH)
    val imageAspect = imgW / imgH

    val image: ImageBitmap = rememberLeasedImage(bitmap) ?: return

    // The crop rectangle in NORMALIZED IMAGE COORDS: left/top/right/bottom in 0..1
    // of (W, H). Seed from the incoming recipe crop, or default to the full frame.
    var rect by remember {
        mutableStateOf(
            if (initialCrop) {
                // Invert the engine mapping to recover the normalized rectangle.
                val halfW = (initialSize.first * maxDim / imgW) / 2f
                val halfH = (initialSize.second * maxDim / imgH) / 2f
                val cx = initialCenter.first
                val cy = initialCenter.second
                Rect(
                    left = (cx - halfW).coerceIn(0f, 1f),
                    top = (cy - halfH).coerceIn(0f, 1f),
                    right = (cx + halfW).coerceIn(0f, 1f),
                    bottom = (cy + halfH).coerceIn(0f, 1f),
                )
            } else {
                Rect(0f, 0f, 1f, 1f)
            }
        )
    }

    var aspect by remember { mutableStateOf(CropAspect.FREE) }
    // Fixed ratios follow the photo's own orientation by default, so the common case
    // (a 4:5 crop of a portrait photo) is one tap; the toggle flips it.
    var portrait by remember(imgW, imgH) { mutableStateOf(imgH > imgW) }

    // Fixed #139 evidence is gated inside the bridge and is a no-op for normal app sessions. The
    // effect runs only after this actual overlay instance has initialized its remembered draft.
    LaunchedEffect(Unit) {
        Ticket139EditorProbe.publishCropDraftInitialized(
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            initialCrop = initialCrop,
            initialCenter = initialCenter,
            initialSize = initialSize,
            actualLeft = rect.left,
            actualTop = rect.top,
            actualRight = rect.right,
            actualBottom = rect.bottom,
        )
    }

    // Pixel size of the laid-out image inside the canvas (set by onSizeChanged on
    // the image Box). Used to convert drag pixels <-> normalized coords.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // The handle grabbed at drag-start. Held in a remember box (mutated from the
    // gesture callbacks, never read during composition) so each overlay instance
    // owns its own handle state.
    val handleRef = remember { mutableStateOf(Handle.NONE) }
    // Mouse/trackpad (DeX, ChromeOS, desktop windows): the handle under the cursor, so the
    // cursor shows what a drag from here will do, like any desktop crop tool.
    var hoverHandle by remember { mutableStateOf(Handle.NONE) }

    Box(
        Modifier
            .fillMaxSize()
            .background(SpektraBrand.scrim.copy(alpha = 0.92f))
            .topBarSafePadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // --- top action bar: cancel / title / rotate / confirm ---
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cancelLabel = stringResource(R.string.tool_crop_cancel)
                TextTooltip(cancelLabel) {
                    androidx.compose.material3.IconButton(onClick = onCancel) {
                        Icon(SpectraIcons.Cancel, contentDescription = cancelLabel, tint = SpektraBrand.onCanvas)
                    }
                }
                Text(
                    stringResource(R.string.tool_crop_title), color = SpektraBrand.onCanvas,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                val rotateLabel = stringResource(R.string.tool_crop_rotate)
                TextTooltip(rotateLabel) {
                    androidx.compose.material3.IconButton(onClick = onRotate) {
                        Icon(SpectraIcons.Rotate, contentDescription = rotateLabel, tint = SpektraBrand.onCanvas)
                    }
                }
                val applyLabel = stringResource(R.string.tool_crop_apply)
                // The tool's one action, as the kit's editor does it: a filled "Done" button,
                // not a bare check icon. Its accessible name stays "Apply crop".
                TextTooltip(applyLabel) {
                    androidx.compose.material3.Button(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .semantics { contentDescription = applyLabel },
                        onClick = {
                            val r = rect
                            val w = (r.right - r.left).coerceIn(0f, 1f)
                            val h = (r.bottom - r.top).coerceIn(0f, 1f)
                            // Full-frame selection => disable crop entirely.
                            val isFull = r.left <= 0.001f && r.top <= 0.001f &&
                                r.right >= 0.999f && r.bottom >= 0.999f
                            if (isFull) {
                                onConfirm(false, 0.5f to 0.5f, initialSize)
                            } else {
                                val cx = (r.left + r.right) / 2f
                                val cy = (r.top + r.bottom) / 2f
                                // size in fractions of the LONG side (see file header).
                                val sx = (w * imgW / maxDim)
                                val sy = (h * imgH / maxDim)
                                onConfirm(true, cx to cy, sx to sy)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.tool_done))
                    }
                }
            }

            // --- the image + crop rectangle ---
            val canvasDesc = stringResource(R.string.tool_crop_canvas_desc)
            val resetLabel = stringResource(R.string.tool_crop_reset)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // #255: keep the rectangle's side edges, and so all four corners, out of the
                    // system back-gesture zones. With only the 12 dp padding below, a full-frame
                    // rect's left/right edges sat 12 dp from the screen edge on a gesture-navigation
                    // phone, inside the 24+ dp back zone: a side or corner drag was taken by the
                    // system as Back, which the editor's BackHandler turned into "cancel crop".
                    // Reproduced on an S26 Ultra; zero-width on three-button navigation.
                    .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .aspectRatio(imageAspect)
                        .fillMaxWidth()
                        .onSizeChanged { canvasSize = it }
                        // Drag-only canvas: a screen reader can at least reset to the full frame;
                        // the aspect chips below remain the accessible way to constrain it.
                        .semantics {
                            contentDescription = canvasDesc
                            customActions = listOf(
                                CustomAccessibilityAction(resetLabel) { rect = Rect(0f, 0f, 1f, 1f); true },
                            )
                        }
                        .pointerInput(canvasSize) {
                            val tol = 24.dp.toPx()
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pos = event.changes.firstOrNull()?.position ?: continue
                                    hoverHandle = when (event.type) {
                                        PointerEventType.Exit -> Handle.NONE
                                        PointerEventType.Enter, PointerEventType.Move ->
                                            pickHandle(rect, pos, canvasSize, tol)
                                        else -> continue
                                    }
                                }
                            }
                        }
                        .pointerHoverIcon(PointerIcon(cropCursorType(hoverHandle)))
                        .pointerInput(aspect, portrait, canvasSize) {
                            // 24 dp either side of an edge = a 48 dp grab band (the Material touch
                            // target). It was 48 px, 18 dp on a 420 dpi phone, so corners took aim.
                            val tol = 24.dp.toPx()
                            detectDragGestures(
                                onDragStart = { pos ->
                                    handleRef.value = pickHandle(rect, pos, canvasSize, tol)
                                },
                                onDragEnd = { handleRef.value = Handle.NONE },
                                onDragCancel = { handleRef.value = Handle.NONE },
                            ) { change, drag ->
                                change.consume()
                                val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                val dx = drag.x / w
                                val dy = drag.y / h
                                rect = applyDrag(
                                    rect, handleRef.value, dx, dy,
                                    lockedAspect = resolveAspect(aspect, portrait, imageAspect),
                                    imgW = imgW, imgH = imgH,
                                )
                            }
                        },
                ) {
                    // image
                    Canvas(Modifier.fillMaxSize()) {
                        drawImageFit(image)
                        drawCropChrome(rect)
                    }
                }
            }

            // --- aspect ratio: M3 connected button group (single select) + orientation ---
            val aspectGroupDesc = stringResource(R.string.tool_crop_aspect_group)
            val applyAspect = { a: CropAspect, p: Boolean ->
                aspect = a
                portrait = p
                resolveAspect(a, p, imageAspect)?.let { locked ->
                    rect = largestCenteredAspect(rect, locked, imgW, imgH)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .semantics { contentDescription = aspectGroupDesc; selectableGroup() },
                    horizontalArrangement = Arrangement.spacedBy(CONNECTED_GAP),
                ) {
                    val entries = CropAspect.entries
                    entries.forEachIndexed { i, a ->
                        val label = when (a) {
                            CropAspect.FREE -> stringResource(R.string.tool_crop_aspect_free)
                            CropAspect.ORIGINAL -> stringResource(R.string.tool_crop_aspect_original)
                            else -> a.label(portrait)
                        }
                        ConnectedToggle(
                            label = label,
                            selected = aspect == a,
                            shape = connectedShape(i, entries.size),
                            onClick = { applyAspect(a, portrait) },
                            // The crop surface is always near-black, whatever the theme.
                            selectedContainer = SpektraBrand.onCanvas,
                            selectedContent = SpektraBrand.onCanvasSelected,
                            container = SpektraBrand.onCanvas.copy(alpha = 0.12f),
                            content = SpektraBrand.onCanvas,
                        )
                    }
                }
                // Orientation flips every fixed ratio (4:5 <-> 5:4). Only meaningful for a
                // non-square fixed ratio, so it is hidden otherwise instead of sitting inert.
                if (aspect.isFixed && aspect != CropAspect.SQUARE) {
                    val orientationLabel = stringResource(
                        if (portrait) R.string.tool_crop_portrait else R.string.tool_crop_landscape,
                    )
                    val flipLabel = stringResource(R.string.tool_crop_orientation_flip)
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(SpektraBrand.onCanvas.copy(alpha = 0.12f))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = flipLabel,
                            ) { applyAspect(aspect, !portrait) }
                            .semantics { contentDescription = orientationLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        // A small frame drawn tall or wide: the orientation it will produce.
                        Canvas(Modifier.size(if (portrait) 14.dp else 20.dp, if (portrait) 20.dp else 14.dp)) {
                            drawRoundRect(
                                color = SpektraBrand.onCanvas,
                                cornerRadius = CornerRadius(2.dp.toPx()),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal enum class Handle { NONE, MOVE, TL, TR, BL, BR, L, R, T, B }

/** The platform mouse cursor (android.view.PointerIcon type) for hovering a crop [handle]. */
internal fun cropCursorType(handle: Handle): Int = when (handle) {
    Handle.TL, Handle.BR -> android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
    Handle.TR, Handle.BL -> android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
    Handle.L, Handle.R -> android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
    Handle.T, Handle.B -> android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
    Handle.MOVE -> android.view.PointerIcon.TYPE_GRAB
    Handle.NONE -> android.view.PointerIcon.TYPE_ARROW
}

/** Resolve an aspect preset to a concrete ratio (Original -> image aspect; Free -> null). */
private fun resolveAspect(a: CropAspect, portrait: Boolean, imageAspect: Float): Float? = when (a) {
    CropAspect.FREE -> null
    CropAspect.ORIGINAL -> imageAspect
    else -> a.ratio(portrait)
}

/** Hit-test which handle a press at [pos] (pixels in [canvas]) grabbed, within [tol] px of an edge. */
private fun pickHandle(rect: Rect, pos: Offset, canvas: IntSize, tol: Float): Handle {
    if (canvas.width == 0 || canvas.height == 0) return Handle.MOVE
    val w = canvas.width.toFloat()
    val h = canvas.height.toFloat()
    val lx = rect.left * w; val rx = rect.right * w
    val ty = rect.top * h; val by = rect.bottom * h
    fun near(a: Float, b: Float) = kotlin.math.abs(a - b) <= tol
    val nl = near(pos.x, lx); val nr = near(pos.x, rx)
    val nt = near(pos.y, ty); val nb = near(pos.y, by)
    return when {
        nl && nt -> Handle.TL
        nr && nt -> Handle.TR
        nl && nb -> Handle.BL
        nr && nb -> Handle.BR
        nl -> Handle.L
        nr -> Handle.R
        nt -> Handle.T
        nb -> Handle.B
        pos.x in lx..rx && pos.y in ty..by -> Handle.MOVE
        else -> Handle.MOVE
    }
}

/** Apply a normalized drag (dx, dy in 0..1) to [rect] for the active [handle]. */
private fun applyDrag(
    rect: Rect,
    handle: Handle,
    dx: Float,
    dy: Float,
    lockedAspect: Float?,
    imgW: Float,
    imgH: Float,
): Rect {
    val minSize = 0.05f
    var l = rect.left; var t = rect.top; var r = rect.right; var b = rect.bottom
    when (handle) {
        Handle.MOVE -> {
            val w = r - l; val h = b - t
            l = (l + dx).coerceIn(0f, 1f - w); r = l + w
            t = (t + dy).coerceIn(0f, 1f - h); b = t + h
        }
        Handle.L -> l = (l + dx).coerceIn(0f, r - minSize)
        Handle.R -> r = (r + dx).coerceIn(l + minSize, 1f)
        Handle.T -> t = (t + dy).coerceIn(0f, b - minSize)
        Handle.B -> b = (b + dy).coerceIn(t + minSize, 1f)
        Handle.TL -> { l = (l + dx).coerceIn(0f, r - minSize); t = (t + dy).coerceIn(0f, b - minSize) }
        Handle.TR -> { r = (r + dx).coerceIn(l + minSize, 1f); t = (t + dy).coerceIn(0f, b - minSize) }
        Handle.BL -> { l = (l + dx).coerceIn(0f, r - minSize); b = (b + dy).coerceIn(t + minSize, 1f) }
        Handle.BR -> { r = (r + dx).coerceIn(l + minSize, 1f); b = (b + dy).coerceIn(t + minSize, 1f) }
        Handle.NONE -> {}
    }
    var out = Rect(l, t, r, b)
    if (lockedAspect != null && handle != Handle.MOVE && handle != Handle.NONE) {
        out = constrainToAspect(out, lockedAspect, imgW, imgH, anchor = handle)
    }
    return out
}

/**
 * Re-fit [rect] to a target display aspect ratio (W:H in *pixels*). Because the
 * rectangle is stored in normalized image coords, the pixel aspect of the box is
 * (w * imgW) / (h * imgH); we adjust height to satisfy it, keeping it in bounds.
 */
internal fun constrainToAspect(
    rect: Rect,
    aspect: Float,
    imgW: Float,
    imgH: Float,
    anchor: Handle = Handle.BR,
): Rect {
    val w = rect.right - rect.left
    // desired normalized height so the pixel aspect (w*imgW)/(nh*imgH) == aspect.
    // NOT clamped to 1 yet: clamping first hid an overflow from the availH check below
    // whenever the fixed edge was the image's top (availH == 1), so a full-frame crop on a
    // landscape photo ignored 1:1 or any portrait lock and stayed at the photo's own aspect.
    var nh = (w * imgW / aspect) / imgH
    var nw = w
    // The corner OPPOSITE the dragged handle stays fixed and the box grows toward the
    // handle: a left/top edge drag keeps the right/bottom edge, and vice versa. (The old
    // code always kept the top-left corner, so TL/TR drags moved the wrong edges.)
    val leftFixed = anchor != Handle.TL && anchor != Handle.BL && anchor != Handle.L
    val topFixed = anchor != Handle.TL && anchor != Handle.TR && anchor != Handle.T
    // Clamp the height to the room past the fixed edge, shrinking width to keep the aspect.
    val availH = (if (topFixed) 1f - rect.top else rect.bottom).coerceAtLeast(0.05f)
    if (nh > availH) {
        nh = availH
        nw = ((nh * imgH * aspect) / imgW).coerceIn(0.05f, 1f)
    }
    nh = nh.coerceIn(0.05f, 1f)
    var l = if (leftFixed) rect.left else rect.right - nw
    var t = if (topFixed) rect.top else rect.bottom - nh
    // Shift back into [0,1] without resizing.
    if (l < 0f) l = 0f
    if (t < 0f) t = 0f
    if (l + nw > 1f) l = (1f - nw).coerceAtLeast(0f)
    if (t + nh > 1f) t = (1f - nh).coerceAtLeast(0f)
    return Rect(l, t, (l + nw).coerceAtMost(1f), (t + nh).coerceAtMost(1f))
}

/**
 * Picking a ratio chip: the LARGEST box of [aspect] that fits the image, centred on the
 * current box's centre and shifted only as far as needed to stay inside the image.
 *
 * Chips used to go through [constrainToAspect], which keeps the top-left corner and starts
 * from the current box, so the box stuck to the top-left and every ratio shrank from the
 * last one (1:1 after 3:4 was not the largest square). Handle drags still use
 * [constrainToAspect], where keeping the opposite corner is the point.
 */
internal fun largestCenteredAspect(rect: Rect, aspect: Float, imgW: Float, imgH: Float): Rect {
    var nw = 1f
    var nh = (imgW / aspect) / imgH
    if (nh > 1f) {
        nh = 1f
        nw = ((imgH * aspect) / imgW).coerceIn(0.05f, 1f)
    }
    val cx = (rect.left + rect.right) / 2f
    val cy = (rect.top + rect.bottom) / 2f
    val l = (cx - nw / 2f).coerceIn(0f, 1f - nw)
    val t = (cy - nh / 2f).coerceIn(0f, 1f - nh)
    return Rect(l, t, l + nw, t + nh)
}

/** Draw the ImageBitmap to fill the whole DrawScope (the Box already has the aspect). */
private fun DrawScope.drawImageFit(image: ImageBitmap) {
    drawImage(
        image = image,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        dstOffset = IntOffset(0, 0),
    )
}

/** Dim outside the crop, draw the rule-of-thirds grid, the border and corner handles. */
private fun DrawScope.drawCropChrome(rect: Rect) {
    val w = size.width; val h = size.height
    val l = rect.left * w; val t = rect.top * h
    val r = rect.right * w; val b = rect.bottom * h
    val scrim = SpektraBrand.scrim.copy(alpha = 0.55f)
    // Dim the four exterior bands.
    drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(w, t))
    drawRect(scrim, topLeft = Offset(0f, b), size = Size(w, h - b))
    drawRect(scrim, topLeft = Offset(0f, t), size = Size(l, b - t))
    drawRect(scrim, topLeft = Offset(r, t), size = Size(w - r, b - t))
    // Rule-of-thirds grid.
    val grid = SpektraBrand.onCanvas.copy(alpha = 0.45f)
    val cw = (r - l); val ch = (b - t)
    for (i in 1..2) {
        val gx = l + cw * i / 3f
        drawLine(grid, Offset(gx, t), Offset(gx, b), strokeWidth = 1.5f)
        val gy = t + ch * i / 3f
        drawLine(grid, Offset(l, gy), Offset(r, gy), strokeWidth = 1.5f)
    }
    // Border.
    drawRect(
        SpektraBrand.onCanvas, topLeft = Offset(l, t), size = Size(cw, ch),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f),
    )
    // Corner handles (short L brackets).
    val hc = SpektraBrand.onCanvas; val len = 26f; val sw = 5f
    fun corner(cx: Float, cy: Float, sx: Int, sy: Int) {
        drawLine(hc, Offset(cx, cy), Offset(cx + len * sx, cy), strokeWidth = sw)
        drawLine(hc, Offset(cx, cy), Offset(cx, cy + len * sy), strokeWidth = sw)
    }
    corner(l, t, 1, 1); corner(r, t, -1, 1); corner(l, b, 1, -1); corner(r, b, -1, -1)
}
