/*
 * Spektrafilm for Android — foldable postures for the editor layout. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A half-folded device is two screens at an angle, and a control or a photo laid across the
 * hinge is split in two. M3 large-screen guidance: in tabletop posture (hinge horizontal) put
 * the content on the upper half and the controls on the lower; in book posture (hinge
 * vertical) keep each pane on one side of the hinge. The rules here are pure functions over
 * dp, unit-tested on the JVM; [rememberFoldPosture] is the one place that reads the device.
 */
package com.spectrafilm.app

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** Where a hinge sits, in dp relative to the editor's layout box. */
internal sealed interface FoldPosture {
    /** No hinge across the box, or a fully opened one: lay out by size alone. */
    data object Flat : FoldPosture

    /** Hinge horizontal and half open: photo above [top], controls below [bottom]. */
    data class Tabletop(val top: Float, val bottom: Float) : FoldPosture

    /** Hinge vertical (half open or a physical gap): panes either side of [left]..[right]. */
    data class Book(val left: Float, val right: Float) : FoldPosture
}

/** A folding feature as plain values, so [foldPosture] needs no Android types. */
internal data class HingeGeometry(
    val horizontal: Boolean,
    val halfOpened: Boolean,
    val separating: Boolean,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The posture to lay out for, given the [hinge] (dp, relative to a box [boxW] x [boxH]).
 *
 * Tabletop needs the device half open: a flat horizontal fold is just a tall screen. A
 * vertical hinge counts when half open or when it physically separates the display (a
 * dual-screen device), since either way content across it is split. A hinge that does not
 * cross the box — the editor box starts below the app bar, so a hinge above it is outside —
 * is ignored.
 */
internal fun foldPosture(hinge: HingeGeometry?, boxW: Float, boxH: Float): FoldPosture {
    if (hinge == null) return FoldPosture.Flat
    return if (hinge.horizontal) {
        val crosses = hinge.top > 0f && hinge.bottom < boxH
        if (hinge.halfOpened && crosses) FoldPosture.Tabletop(hinge.top, hinge.bottom) else FoldPosture.Flat
    } else {
        val crosses = hinge.left > 0f && hinge.right < boxW
        if ((hinge.halfOpened || hinge.separating) && crosses) {
            FoldPosture.Book(hinge.left, hinge.right)
        } else {
            FoldPosture.Flat
        }
    }
}

/**
 * Pane mode with the posture taken into account. Tabletop always docks the tools below the
 * hinge, even on a window wide enough for a side pane: the lower half is the part the user
 * holds and touches, the upper half the part they look at. Book posture always uses the side
 * pane so the photo and the tools each get one screen.
 */
internal fun editorPaneMode(widthDp: Float, heightDp: Float, posture: FoldPosture): EditorPaneMode =
    when (posture) {
        is FoldPosture.Tabletop -> EditorPaneMode.BOTTOM_SHEET
        is FoldPosture.Book -> EditorPaneMode.SIDE_PANE
        FoldPosture.Flat -> editorPaneMode(widthDp, heightDp)
    }

/**
 * Side pane width in book posture: exactly the screen on the pane's side of the hinge, so no
 * slider straddles it. The pane sits at the END of the row, which is the right screen in
 * left-to-right layouts and the left one in right-to-left. Falls back to the size-class width
 * when that screen is too narrow for a slider row, or when there is no hinge.
 */
internal fun sidePaneWidthDp(widthDp: Float, posture: FoldPosture, rtl: Boolean): Float {
    if (!isBookHingeAligned(posture, widthDp, rtl)) return sidePaneWidthDp(widthDp)
    posture as FoldPosture.Book
    return if (rtl) posture.left else widthDp - posture.right
}

/**
 * True when the editor splits at the hinge: book posture with the end screen wide enough for
 * the tools. Then the photo keeps to its own screen even with no panel open, so it never
 * straddles the fold.
 */
internal fun isBookHingeAligned(posture: FoldPosture, widthDp: Float, rtl: Boolean): Boolean {
    if (posture !is FoldPosture.Book) return false
    val endScreen = if (rtl) posture.left else widthDp - posture.right
    return endScreen >= PANE_MIN_DP
}

/**
 * Width of the physical hinge gap to leave empty between the photo and the side pane in book
 * posture, so neither draws under it. Zero for a seamless fold, outside book posture, or when
 * [sidePaneWidthDp] fell back to the size-class width (the pane is not hinge-aligned then).
 */
internal fun bookHingeGapDp(posture: FoldPosture, widthDp: Float, rtl: Boolean): Float =
    if (isBookHingeAligned(posture, widthDp, rtl)) {
        posture as FoldPosture.Book
        (posture.right - posture.left).coerceAtLeast(0f)
    } else {
        0f
    }

/**
 * Photo height in tabletop posture: everything above the hinge's top edge (the hinge strip
 * itself, on a device with a physical gap, is not display). Null when not in tabletop.
 */
internal fun tabletopPhotoHeightDp(posture: FoldPosture): Float? =
    (posture as? FoldPosture.Tabletop)?.top?.coerceAtLeast(0f)

/**
 * Panel height in tabletop posture: the whole lower half, so the photo above fills the upper
 * one. Null when not in tabletop (the panel keeps its compact/tall drag heights).
 */
internal fun tabletopPanelHeightDp(posture: FoldPosture, boxH: Float): Float? =
    (posture as? FoldPosture.Tabletop)?.let { (boxH - it.bottom).coerceAtLeast(0f) }

/**
 * The window's folding feature, as reported by Jetpack WindowManager, or null on a device
 * without one (and in previews and tests, where there is no activity). Bounds are in window
 * pixels; the caller maps them into its own box.
 */
@Composable
internal fun rememberFoldingFeature(): State<FoldingFeature?> {
    val activity: Activity? = LocalActivity.current
    return produceState<FoldingFeature?>(null, activity) {
        if (activity == null) return@produceState
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        }
    }
}

/**
 * Map a [feature] (window pixels) into a box whose top-left is at [boxLeftPx], [boxTopPx] in
 * the window, in dp at [density].
 */
internal fun FoldingFeature.toHinge(boxLeftPx: Float, boxTopPx: Float, density: Float): HingeGeometry {
    val b = bounds
    return HingeGeometry(
        horizontal = orientation == FoldingFeature.Orientation.HORIZONTAL,
        halfOpened = state == FoldingFeature.State.HALF_OPENED,
        separating = isSeparating,
        left = (b.left - boxLeftPx) / density,
        top = (b.top - boxTopPx) / density,
        right = (b.right - boxLeftPx) / density,
        bottom = (b.bottom - boxTopPx) / density,
    )
}
