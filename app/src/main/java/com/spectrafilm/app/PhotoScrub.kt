/*
 * Spektrafilm for Android — drag sideways on the photo to adjust the last control. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * The editor's side of a sideways drag on the photo: [start] says whether there is a control
 * to adjust (and captures its value), [drag] gets the finger's travel past the lock point and
 * the photo's width, [end] settles the change as one history step.
 */
/**
 * How long after a control (or a photo drag) is released the photo drag stays armed. Past it,
 * a sideways drag on the photo goes to the next photo again.
 */
internal const val PHOTO_SCRUB_ARM_MS = 3_000L

internal class PhotoScrub(
    val start: () -> Boolean,
    val drag: (dx: Float, widthPx: Float) -> Unit,
    val end: () -> Unit,
)

/**
 * The value a photo drag of [dx] px reaches from [start]: the photo's full width sweeps the
 * whole range, snapped to [step] and clamped, so it reads like a long slider laid over the photo.
 */
internal fun scrubValue(
    start: Float,
    dx: Float,
    widthPx: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
): Float = snap(instrumentDragValue(start, dx, widthPx, range, fine = false), range, step)

/**
 * A one-finger sideways drag on the photo, seen before the photo's own zoom and tap handlers,
 * as [photoSwipeGesture] is: it takes the gesture once it is clearly horizontal, then consumes
 * it. A second finger hands the gesture back (pinch to zoom) and ends the drag where it is.
 */
internal fun Modifier.photoScrubGesture(
    scrub: PhotoScrub?,
    enabled: Boolean,
    /** A touch that starts where this is true is not a scrub (e.g. on the strip). */
    ignoreDownAt: (Offset) -> Boolean = { false },
): Modifier = if (scrub == null || !enabled) this else pointerInput(scrub) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (ignoreDownAt(down.position)) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        var base = 0f
        var locked = false
        val slop = viewConfiguration.touchSlop
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                // Held past the long-press timeout before moving: the photo's own long press
                // (peek at the original) owns this touch, so a later sideways move must not
                // start changing the value under it (Codex on #545).
                if (!locked && change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) {
                    return@awaitEachGesture
                }
                if (!locked && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    // Nothing to adjust (no control yet, or it left the screen): leave the
                    // gesture to the photo.
                    if (!scrub.start()) return@awaitEachGesture
                    locked = true
                    base = dx
                }
                if (locked) {
                    scrub.drag(dx - base, size.width.toFloat())
                    change.consume()
                }
                if (!change.pressed) break
            }
        } finally {
            // Also on cancellation (the preview left composition mid-drag).
            if (locked) scrub.end()
        }
    }
}
