/*
 * Spektrafilm for Android — sideways swipe on the photo to the next or previous frame. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * What a swipe on the photo can open: the neighbours' thumbnails (shown while they slide in)
 * and which way there is anything at all. [onCommit] opens the frame [step] places away.
 */
internal class PhotoSwipeNeighbours(
    /** The photo these are the neighbours of: a swipe's hold ends only once this changes. */
    val sourceKey: String?,
    val next: Bitmap?,
    val previous: Bitmap?,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val onCommit: (step: Int) -> Unit,
)

/**
 * A one-finger sideways drag, seen before the photo's own zoom and tap handlers. It takes the
 * gesture only once it is clearly horizontal (past the touch slop and 1.5x the vertical
 * travel), and then consumes it so the zoom/peek/tap handlers below stand down. A second
 * finger hands the gesture back (pinch to zoom), springing the swipe back.
 *
 * [onDrag] gets the finger's total horizontal travel; [onEnd] its travel and release
 * velocity (px/s), or a null velocity when a second finger cancelled the swipe.
 */
internal fun Modifier.photoSwipeGesture(
    enabled: Boolean,
    /** A touch that starts where this is true is not a photo swipe (e.g. on the strip). */
    ignoreDownAt: (Offset) -> Boolean = { false },
    onStart: () -> Unit,
    onDrag: (dx: Float) -> Unit,
    onEnd: (dx: Float, velocityX: Float?) -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (ignoreDownAt(down.position)) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        var locked = false
        var cancelled = false
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) cancelled = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!cancelled) {
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!locked && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    locked = true
                    onStart()
                }
                if (locked) {
                    onDrag(dx)
                    change.consume()
                }
            } else if (locked) {
                // Pinch took over: leave the rest of the gesture to the zoom handler.
                break
            }
            if (!change.pressed) break
        }
        if (locked) onEnd(dx, if (cancelled) null else tracker.calculateVelocity().x)
    }
}

/**
 * The settings a photo opens with, as the editor's recipe restore applies them: its saved
 * recipe when it reads cleanly, otherwise the defaults a first open gets ([applyDefaults], the
 * user's default settings over a fresh state). Unit-tested.
 */
internal fun openingStateFor(
    recipe: RecipeDocument?,
    applyDefaults: (ParamsState) -> Unit,
): ParamsState {
    val s = ParamsState()
    if (recipe != null) {
        Presets.decode(recipe.params, s)
    } else {
        applyDefaults(s)
    }
    return s
}

/**
 * The bitmap a neighbour slides in with: its grid thumbnail at once, then (once the
 * interactive preview is idle) that thumbnail in the neighbour's OWN look, the settings
 * [openingState] says it will open with. So the photo that lands already looks like itself
 * instead of wearing the open photo's look and jumping to its own a moment later.
 */
@Composable
internal fun rememberNeighbourBitmap(
    frame: BrowseFrame?,
    context: Context,
    engine: SpektraEngine?,
    openingState: suspend (uri: String) -> ParamsState,
    busy: () -> Boolean,
    io: CoroutineDispatcher,
    default: CoroutineDispatcher,
): Bitmap? {
    val thumb by produceState(frame?.let { LibraryThumbnails.cached(it.uri) }, frame) {
        // produceState keeps its value across key changes: start from the new neighbour.
        value = frame?.let { LibraryThumbnails.cached(it.uri) }
        val f = frame ?: return@produceState
        if (value == null) value = withContext(io) {
            runCatching { LibraryThumbnails.load(context, f.uri, f.raw) }.getOrNull()
        }
    }
    // (uri, look) so a look never outlives the frame it was rendered for.
    val look by produceState<Pair<String, Bitmap>?>(null, frame, thumb, engine) {
        if (value?.first != frame?.uri) value = null
        val f = frame ?: return@produceState
        val t = thumb ?: return@produceState
        val e = engine ?: return@produceState
        val own = openingState(f.uri)
        // Let the render the user is waiting for go first.
        snapshotFlow { busy() }.first { !it }
        val rendered = PlaceholderLook.render(context, e, t, own, default) ?: return@produceState
        val active = isActive
        if (active) value = f.uri to rendered.bitmap
        PlaceholderLook.report(rendered, published = active, cancelled = !active)
    }
    return look?.takeIf { it.first == frame?.uri }?.second ?: thumb
}

