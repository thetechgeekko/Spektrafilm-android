/*
 * Spektrafilm for Android — the Crop tab. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Crop is one of the editor's tabs, like Looks, Edit and Masks: the top bar, the photo's place
 * and the bottom bar stay where they are, the crop frame is drawn on the photo itself
 * ([CropCanvas]) and the panel below holds the straighten ruler, the ratio buttons and
 * Rotate / Orientation / Reset ([CropPanelContent]). There is no Cancel or Done: the draft
 * ([CropDraft]) is written to the recipe when the user leaves the tab (another tab, Crop again,
 * Back, Export), which the editor's history records as ONE undo step, so Undo is the way back.
 * It replaced a full-screen overlay that drew a 92% black scrim over the whole editor.
 *
 * While the tab is open the preview is rendered WITHOUT the engine crop (EditorPreviewEffects),
 * so [CropCanvas] is handed the whole frame; the editor shows the canvas only once that
 * crop-free render is the one on screen.
 *
 * On commit the selected rectangle is translated into the engine's existing crop
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
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
    R16_9(16, 9),
    // The panoramic film gates (upstream spektrafilm PR #38's film-format presets): the 24x65 mm
    // 35 mm panorama camera frame, and 6x17 roll film (56x168 mm).
    R65_24(65, 24),
    R3_1(3, 1);

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


/** What leaving the Crop tab writes to the recipe: the engine crop fields and the straighten angle. */
internal data class CropCommit(
    val crop: Boolean,
    val center: Pair<Float, Float>,
    val size: Pair<Float, Float>,
    val straighten: Float,
)

/**
 * The Crop tab's in-progress edit over one crop-free preview of [imgW] x [imgH] pixels, seeded
 * from the recipe's committed crop. Nothing reaches the recipe until [commit]; [touched] says
 * whether the user changed anything, so leaving the tab untouched records no undo step (the
 * seed's float round trip would otherwise look like an edit).
 */
@Stable
internal class CropDraft(
    val imgW: Float,
    val imgH: Float,
    /** The committed crop this draft was seeded from (kept for the #139 probe). */
    val initialCrop: Boolean,
    val initialCenter: Pair<Float, Float>,
    val initialSize: Pair<Float, Float>,
    initialStraighten: Float,
) {
    val maxDim = max(imgW, imgH)
    val imageAspect = imgW / imgH

    /** The angle the preview is already rendered at; the canvas draws only the difference. */
    val renderedStraighten = clampStraighten(initialStraighten)

    /** The crop rectangle in NORMALIZED IMAGE COORDS: left/top/right/bottom in 0..1 of (W, H). */
    var rect by mutableStateOf(seedRect(initialCrop, initialCenter, initialSize))
        private set
    var straighten by mutableFloatStateOf(renderedStraighten)
        private set
    var aspect by mutableStateOf(CropAspect.FREE)
        private set

    /** Fixed ratios follow the photo's own orientation by default; the toggle flips them. */
    var portrait by mutableStateOf(imgH > imgW)
        private set
    var touched by mutableStateOf(false)
        private set

    /** The locked W:H of the picked ratio, or null for Free. */
    val lockedAspect: Float? get() = resolveAspect(aspect, portrait, imageAspect)

    /** True when the frame, the angle or the ratio differs from an uncropped, level photo. */
    val isReset: Boolean
        get() = rect == FULL_FRAME && straighten == 0f && aspect == CropAspect.FREE

    fun drag(handle: Handle, dx: Float, dy: Float) {
        val next = applyDrag(rect, handle, dx, dy, lockedAspect, imgW, imgH)
        if (next != rect) {
            rect = next
            touched = true
        }
    }

    fun straightenTo(degrees: Float) {
        if (degrees != straighten) {
            straighten = degrees
            touched = true
        }
    }

    fun pickAspect(a: CropAspect, p: Boolean = portrait) {
        aspect = a
        portrait = p
        resolveAspect(a, p, imageAspect)?.let { locked ->
            val next = largestCenteredAspect(rect, locked, imgW, imgH)
            if (next != rect) {
                rect = next
                touched = true
            }
        }
    }

    fun reset() {
        if (isReset) return
        rect = FULL_FRAME
        straighten = 0f
        aspect = CropAspect.FREE
        touched = true
    }

    /** The recipe values for this draft. A full-frame rectangle turns the crop off. */
    fun commit(): CropCommit {
        val r = rect
        val w = (r.right - r.left).coerceIn(0f, 1f)
        val h = (r.bottom - r.top).coerceIn(0f, 1f)
        val isFull = r.left <= 0.001f && r.top <= 0.001f && r.right >= 0.999f && r.bottom >= 0.999f
        if (isFull) return CropCommit(false, 0.5f to 0.5f, initialSize, straighten)
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        // size in fractions of the LONG side (see file header).
        return CropCommit(true, cx to cy, (w * imgW / maxDim) to (h * imgH / maxDim), straighten)
    }

    private fun seedRect(crop: Boolean, center: Pair<Float, Float>, size: Pair<Float, Float>): Rect {
        if (!crop) return FULL_FRAME
        // Invert the engine mapping to recover the normalized rectangle.
        val halfW = (size.first * maxDim / imgW) / 2f
        val halfH = (size.second * maxDim / imgH) / 2f
        return Rect(
            left = (center.first - halfW).coerceIn(0f, 1f),
            top = (center.second - halfH).coerceIn(0f, 1f),
            right = (center.first + halfW).coerceIn(0f, 1f),
            bottom = (center.second + halfH).coerceIn(0f, 1f),
        )
    }

    companion object {
        val FULL_FRAME = Rect(0f, 0f, 1f, 1f)
    }
}

/**
 * The draft for [bitmap] (a crop-free preview) and the recipe's committed crop. A new draft is
 * seeded whenever the committed values or the frame size change: an Undo while the tab is open,
 * a 90-degree rotation (the editor commits the draft first, so nothing set is lost), a new photo. Bitmap identity is deliberately not a key, since every
 * re-render publishes a new bitmap of the same frame.
 */
@Composable
internal fun rememberCropDraft(
    bitmap: Bitmap,
    crop: Boolean,
    center: Pair<Float, Float>,
    size: Pair<Float, Float>,
    straighten: Float,
    /** Bumped by the editor to discard the pending draft (Undo of an uncommitted crop edit). */
    epoch: Int = 0,
): CropDraft {
    val w = bitmap.width
    val h = bitmap.height
    return remember(w, h, crop, center, size, straighten, epoch) {
        CropDraft(w.toFloat().coerceAtLeast(1f), h.toFloat().coerceAtLeast(1f), crop, center, size, straighten)
    }
}

/**
 * The photo with the crop frame on it, filling the editor's photo area. [bitmap] is the
 * crop-free preview at [CropDraft.renderedStraighten]; a straighten drag turns and zooms it to
 * cover the frame (the same largest same-aspect cut the decode makes) until the exact image
 * arrives with the re-render after the tab is left. [bottomInset] keeps it above the panel.
 */
@Composable
internal fun CropCanvas(
    bitmap: Bitmap,
    draft: CropDraft,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val image: ImageBitmap = rememberLeasedImage(bitmap) ?: return

    // Fixed #139 evidence is gated inside the bridge and is a no-op for normal app sessions. The
    // effect runs only after this draft has been seeded from the committed recipe.
    LaunchedEffect(draft) {
        editorProbe.publishCropDraftInitialized(
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            initialCrop = draft.initialCrop,
            initialCenter = draft.initialCenter,
            initialSize = draft.initialSize,
            actualLeft = draft.rect.left,
            actualTop = draft.rect.top,
            actualRight = draft.rect.right,
            actualBottom = draft.rect.bottom,
        )
    }

    // Pixel size of the laid-out image (set by onSizeChanged on the image Box). Used to convert
    // drag pixels <-> normalized coords.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // The handle grabbed at drag-start. Mutated from the gesture callbacks, never read during
    // composition.
    val handleRef = remember { mutableStateOf(Handle.NONE) }
    // The rule-of-thirds grid shows while a handle is held, as in every phone crop tool.
    var dragging by remember { mutableStateOf(false) }
    // Mouse/trackpad (DeX, ChromeOS, desktop windows): the handle under the cursor, so the
    // cursor shows what a drag from here will do, like any desktop crop tool.
    var hoverHandle by remember { mutableStateOf(Handle.NONE) }
    val canvasDesc = stringResource(R.string.tool_crop_canvas_desc)
    val resetLabel = stringResource(R.string.tool_crop_reset)

    Box(
        modifier
            .fillMaxSize()
            .padding(bottom = bottomInset)
            // #255: keep the rectangle's side edges, and so all four corners, out of the system
            // back-gesture zones, or a side or corner drag is taken by the system as Back.
            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .aspectRatio(draft.imageAspect)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it }
                // Drag-only canvas: a screen reader can at least reset to the full frame; the
                // ratio buttons in the panel remain the accessible way to constrain it.
                .semantics {
                    contentDescription = canvasDesc
                    customActions = listOf(
                        CustomAccessibilityAction(resetLabel) { draft.reset(); true },
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
                                    pickHandle(draft.rect, pos, canvasSize, tol)
                                else -> continue
                            }
                        }
                    }
                }
                .pointerHoverIcon(PointerIcon(cropCursorType(hoverHandle)))
                .pointerInput(draft, canvasSize) {
                    // 24 dp either side of an edge = a 48 dp grab band (the Material touch target).
                    val tol = 24.dp.toPx()
                    detectDragGestures(
                        onDragStart = { pos ->
                            handleRef.value = pickHandle(draft.rect, pos, canvasSize, tol)
                            dragging = true
                        },
                        onDragEnd = { handleRef.value = Handle.NONE; dragging = false },
                        onDragCancel = { handleRef.value = Handle.NONE; dragging = false },
                    ) { change, drag ->
                        change.consume()
                        val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                        val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                        draft.drag(handleRef.value, drag.x / w, drag.y / h)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val delta = draft.straighten - draft.renderedStraighten
                if (delta == 0f) {
                    drawImageFit(image)
                } else {
                    val cover = (1.0 / straightenScale(bitmap.width, bitmap.height, delta)).toFloat()
                    val pivot = center
                    withTransform({
                        rotate(delta, pivot)
                        scale(cover, cover, pivot)
                    }) { drawImageFit(image) }
                }
                drawCropChrome(draft.rect, grid = dragging || delta != 0f, fineGrid = delta != 0f)
            }
        }
    }
}

/**
 * The Crop tab's panel body: the straighten ruler, the ratios as an M3 connected button group,
 * and Rotate / Orientation / Reset. Theme colours throughout (it sits on the editor's own panel,
 * not on the dark canvas). A null [draft] means the crop-free preview is still rendering.
 */
@Composable
internal fun ColumnScope.CropPanelContent(
    draft: CropDraft?,
    onRotate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (draft == null) {
        Box(Modifier.fillMaxWidth().height(CROP_PANEL_LOADING_HEIGHT), contentAlignment = Alignment.Center) {
            SpectraLoadingIndicator()
        }
        return
    }
    StraightenRuler(
        degrees = draft.straighten,
        onChange = draft::straightenTo,
        tint = colors.onSurfaceVariant,
        needle = colors.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp),
    )

    // --- aspect ratio: M3 connected button group (single select), theme colours ---
    val aspectGroupDesc = stringResource(R.string.tool_crop_aspect_group)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = aspectGroupDesc; selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(CONNECTED_GAP),
    ) {
        val entries = CropAspect.entries
        entries.forEachIndexed { i, a ->
            val label = when (a) {
                CropAspect.FREE -> stringResource(R.string.tool_crop_aspect_free)
                CropAspect.ORIGINAL -> stringResource(R.string.tool_crop_aspect_original)
                else -> a.label(draft.portrait)
            }
            ConnectedToggle(
                label = label,
                selected = draft.aspect == a,
                shape = connectedShape(i, entries.size),
                onClick = { draft.pickAspect(a) },
            )
        }
    }

    // --- Rotate / Orientation / Reset ---
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CropToolButton(
            label = stringResource(R.string.editor_history_rotate),
            description = stringResource(R.string.tool_crop_rotate),
            icon = SpectraIcons.Rotate,
            onClick = onRotate,
        )
        // Orientation flips every fixed ratio (4:5 <-> 5:4), so it only does something for a
        // non-square fixed ratio. It stays in place, disabled, so the row never reflows.
        val orientable = draft.aspect.isFixed && draft.aspect != CropAspect.SQUARE
        CropToolButton(
            label = stringResource(
                if (draft.portrait) R.string.tool_crop_orient_portrait else R.string.tool_crop_orient_landscape,
            ),
            description = stringResource(
                if (draft.portrait) R.string.tool_crop_portrait else R.string.tool_crop_landscape,
            ),
            enabled = orientable,
            onClick = { draft.pickAspect(draft.aspect, !draft.portrait) },
        ) { tint ->
            // A small frame drawn tall or wide: the orientation the ratios are in.
            Canvas(Modifier.size(if (draft.portrait) 14.dp else 20.dp, if (draft.portrait) 20.dp else 14.dp)) {
                drawRoundRect(
                    color = tint,
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        CropToolButton(
            label = stringResource(R.string.editor_reset_short),
            description = stringResource(R.string.tool_crop_reset),
            icon = SpectraIcons.Reset,
            enabled = !draft.isReset,
            onClick = draft::reset,
        )
    }
}

/** Height the panel keeps while the crop-free preview renders, so it does not jump on arrival. */
private val CROP_PANEL_LOADING_HEIGHT = 168.dp

/** One labelled tonal icon button in the crop panel's bottom row. */
@Composable
private fun CropToolButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    glyph: (@Composable (tint: Color) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.widthIn(min = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextTooltip(description) {
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                val tint = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = 0.38f)
                if (icon != null) Icon(icon, contentDescription = null) else glyph?.invoke(tint)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/** Dim outside the crop, draw the rule-of-thirds grid (while [grid]), the border and corner handles. */
private fun DrawScope.drawCropChrome(rect: Rect, grid: Boolean, fineGrid: Boolean = false) {
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
    val gridColor = SpektraBrand.onCanvas.copy(alpha = 0.45f)
    val cw = (r - l); val ch = (b - t)
    // While straightening, a finer grid to line horizons and verticals up against.
    if (fineGrid) {
        val fine = SpektraBrand.onCanvas.copy(alpha = 0.22f)
        for (i in 1 until 9) {
            if (i % 3 == 0) continue
            val gx = l + cw * i / 9f
            drawLine(fine, Offset(gx, t), Offset(gx, b), strokeWidth = 1f)
            val gy = t + ch * i / 9f
            drawLine(fine, Offset(l, gy), Offset(r, gy), strokeWidth = 1f)
        }
    }
    if (grid) for (i in 1..2) {
        val gx = l + cw * i / 3f
        drawLine(gridColor, Offset(gx, t), Offset(gx, b), strokeWidth = 1.5f)
        val gy = t + ch * i / 3f
        drawLine(gridColor, Offset(l, gy), Offset(r, gy), strokeWidth = 1.5f)
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

/**
 * The Crop tab's panel in the editor's own panel style: the floating bottom panel on compact and
 * medium windows (surfaceContainer, 28dp top corners, as AdjustmentPanel), or the docked side
 * pane at [sideWidth] on expanded ones (surfaceContainerLow, as SidePanel).
 */
@Composable
internal fun CropPanel(
    modifier: Modifier = Modifier,
    sideWidth: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val title = stringResource(R.string.tool_crop_title)
    val panelTitle = stringResource(R.string.editor_panel_title, title)
    val colors = MaterialTheme.colorScheme
    if (sideWidth == null) {
        Surface(
            color = colors.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = modifier.fillMaxWidth().semantics { paneTitle = panelTitle },
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) { content() }
        }
    } else {
        Surface(
            color = colors.surfaceContainerLow,
            modifier = modifier.width(sideWidth).fillMaxHeight().semantics { paneTitle = panelTitle },
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                        .semantics { heading() },
                )
                content()
            }
        }
    }
}
