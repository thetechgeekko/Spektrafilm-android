/*
 * Spektrafilm for Android — editor window-size layout rules. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Where the tool panel goes for a given window, following the M3 layout guidance the
 * redesign is built on: window size classes by width (compact < 600dp, medium 600-839dp,
 * expanded 840-1199dp, large >= 1200dp), the photo as the main pane and tools as a
 * supporting pane. Pure functions over dp sizes so the rules are unit-tested on the JVM.
 */
package com.spectrafilm.app

/** Where the editor hosts the open tool panel. */
internal enum class EditorPaneMode {
    /** Floating standard bottom sheet over the photo (compact and medium windows). */
    BOTTOM_SHEET,

    /** Docked supporting pane beside the photo (expanded+ windows, landscape phones). */
    SIDE_PANE,
}

internal const val MEDIUM_WIDTH_DP = 600f
internal const val EXPANDED_WIDTH_DP = 840f
internal const val LARGE_WIDTH_DP = 1200f

/** M3 compact height class: below this a bottom sheet would leave almost no photo. */
internal const val COMPACT_HEIGHT_DP = 480f

/** M3 fixed supporting pane: 360dp in expanded windows, 412dp in large and extra-large. */
internal const val PANE_EXPANDED_DP = 360f
internal const val PANE_LARGE_DP = 412f

/** Narrowest side pane that still fits a labelled slider row comfortably. */
internal const val PANE_MIN_DP = 280f

/** Share of a landscape phone's width the side pane takes before clamping. */
internal const val PANE_LANDSCAPE_FRACTION = 0.34f

/** M3 bottom sheet maximum width; wider windows centre the sheet. */
internal const val SHEET_MAX_WIDTH_DP = 640f

/**
 * Expanded and wider windows always get the side pane. A landscape phone (compact height,
 * wider than tall) gets it too: a bottom sheet there would cover most of a ~360dp-tall
 * photo. Medium windows in portrait (tablet portrait, unfolded foldable) keep the bottom
 * sheet, capped at [SHEET_MAX_WIDTH_DP], because M3 keeps medium windows single-pane.
 */
internal fun editorPaneMode(widthDp: Float, heightDp: Float): EditorPaneMode = when {
    widthDp >= EXPANDED_WIDTH_DP -> EditorPaneMode.SIDE_PANE
    heightDp < COMPACT_HEIGHT_DP && widthDp > heightDp -> EditorPaneMode.SIDE_PANE
    else -> EditorPaneMode.BOTTOM_SHEET
}

/** Side pane width for a window [widthDp] wide (only meaningful in [EditorPaneMode.SIDE_PANE]). */
internal fun sidePaneWidthDp(widthDp: Float): Float = when {
    widthDp >= LARGE_WIDTH_DP -> PANE_LARGE_DP
    widthDp >= EXPANDED_WIDTH_DP -> PANE_EXPANDED_DP
    else -> (widthDp * PANE_LANDSCAPE_FRACTION).coerceIn(PANE_MIN_DP, PANE_EXPANDED_DP)
}
