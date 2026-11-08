/*
 * Spektrafilm for Android — swipe between the frames of a Library folder. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One frame a swipe in the editor can open. URIs are strings so this stays JVM-testable. */
internal data class BrowseFrame(val uri: String, val raw: Boolean)

/**
 * The Library folder the photo in the editor was opened from, in grid order, so a sideways
 * swipe on the photo can open the next or previous frame. Set when the Library opens a frame;
 * a photo from the picker, a share or a restore is not in it, so it simply has no neighbours.
 * Process-wide and in memory only: after process death swipe is off until the Library opens
 * a frame again.
 */
internal object PhotoBrowse {
    @Volatile
    var frames: List<BrowseFrame> = emptyList()

    /**
     * Working copies the editor opened in place of a browsed frame (a Gallery photo under
     * Android 14's partial access is not durably readable, so the editor copies it), mapped
     * back to that frame, so swiping still finds its neighbours.
     */
    private val origins = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [copy] stands in for [original]. */
    fun rememberOrigin(copy: String, original: String) {
        if (copy != original) origins[copy] = original
    }

    private fun browsed(currentUri: String?): String? = currentUri?.let { origins[it] ?: it }

    /** The frame [step] places from [currentUri] (+1 next, -1 previous), or null. */
    fun neighbour(currentUri: String?, step: Int): BrowseFrame? = neighbourIn(frames, browsed(currentUri), step)

    /** Where [currentUri] sits in [frames], or -1 when it is not in the folder. */
    fun indexOf(currentUri: String?): Int {
        val uri = browsed(currentUri) ?: return -1
        return frames.indexOfFirst { it.uri == uri }
    }
}

/** [PhotoBrowse.neighbour] over an explicit list. Pure: unit-tested. */
internal fun neighbourIn(frames: List<BrowseFrame>, currentUri: String?, step: Int): BrowseFrame? {
    if (currentUri == null) return null
    val i = frames.indexOfFirst { it.uri == currentUri }
    if (i < 0) return null
    return frames.getOrNull(i + step)
}

/** Where a swipe gesture ends. Pure: unit-tested. */
internal object PhotoSwipeRules {
    /** Fraction of the width past which a released swipe opens the neighbour. */
    const val COMMIT_FRACTION = 0.6f

    /** Gap between the photo and its neighbour while swiping, in dp. */
    const val GAP_DP = 16f

    /** A swipe toward a missing neighbour moves this fraction of the finger's travel. */
    const val EDGE_RESISTANCE = 1f / 3f

    /** How much the photo shrinks at the end of the swipe (Material carousel style). */
    const val MIN_SCALE = 0.9f

    /** Corner radius at the end of the swipe, in dp (the carousel's large shape). */
    const val MAX_CORNER_DP = 28f

    /** Content drift inside the moving frame, as a fraction of the width (parallax). */
    const val PARALLAX = 0.2f

    /**
     * The step to commit for a finger that travelled [dx] px and left at [velocityX] px/s on
     * a [width] px view, or 0 to spring back. Left (negative dx) opens the next frame.
     */
    fun commitStep(
        dx: Float,
        velocityX: Float,
        width: Float,
        flingPxPerSec: Float,
        slopPx: Float,
        hasNext: Boolean,
        hasPrevious: Boolean,
    ): Int {
        if (width <= 0f || dx == 0f) return 0
        val step = if (dx < 0f) 1 else -1
        val available = if (step > 0) hasNext else hasPrevious
        if (!available) return 0
        val farEnough = abs(dx) > width * COMMIT_FRACTION
        val flung = abs(velocityX) > flingPxPerSec && velocityX * dx > 0f && abs(dx) > slopPx
        return if (farEnough || flung) step else 0
    }

    /** What the photo shows for a finger at [dx]: resisted toward a missing neighbour. */
    fun shownOffset(dx: Float, hasNext: Boolean, hasPrevious: Boolean): Float {
        val available = if (dx < 0f) hasNext else hasPrevious
        return if (available) dx else dx * EDGE_RESISTANCE
    }

    /** Swipe progress 0..1 for a shown offset on a [width] px view. */
    fun progress(shown: Float, width: Float): Float =
        if (width <= 0f) 0f else min(1f, abs(shown) / width)

    /** Scale of the outgoing photo at [progress] (the incoming one uses 1 - progress). */
    fun scaleAt(progress: Float): Float = 1f - (1f - MIN_SCALE) * progress.coerceIn(0f, 1f)

    /** Corner radius in dp at [progress]: reaches the full radius a quarter of the way. */
    fun cornerDpAt(progress: Float): Float = MAX_CORNER_DP * min(1f, max(0f, progress) * 4f)
}
