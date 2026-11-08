/*
 * Spektrafilm for Android — full screen editing and camera-cutout-aware chrome. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Two platform features the M3 skill does not cover (tagged "Outside the skill" on the Pen
 * board): hiding the system bars while editing, and laying the app bar out around the front
 * camera cutout once the status bar is gone.
 */
package com.spectrafilm.app

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Rect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * While composed with [enabled], hides the status and navigation bars. A swipe in from an
 * edge shows them for a moment (transient bars), so nothing becomes unreachable. Leaving
 * composition, or turning [enabled] off, brings them back.
 */
@Composable
internal fun ImmersiveSystemBars(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Top padding for overlays that own the top edge (crop, masks, pixel sampler): the status
 * bar when it shows, and the camera cutout's band when it is hidden, so a control never sits
 * under the camera.
 */
@Composable
internal fun topBarSafeInsets(): WindowInsets =
    WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)

/**
 * Where the front camera cutouts are, for one app bar: [rects] in window coordinates and the
 * bar's own top-left in the window (tracked by [track]). Created once per bar with
 * [rememberCutoutBand] so the bar's owner can plan what fits beside the cutout (see
 * [placeBesideCutout]) with the same numbers [CutoutAwareBarRow] lays out with.
 */
@Stable
internal class CutoutBand(internal val keepOutPx: Int) {
    internal var rects: List<Rect> by mutableStateOf(emptyList())
    internal var barTopLeft: IntOffset by mutableStateOf(IntOffset.Zero)

    /** Records where the bar sits in the window. */
    val track: Modifier = Modifier.onGloballyPositioned {
        val p = it.positionInWindow()
        barTopLeft = IntOffset(p.x.toInt(), p.y.toInt())
    }

    /** Cutouts that reach into a bar [width] wide and [height] tall (see [cutoutHolesInBar]). */
    fun holes(width: Int, height: Int, rtl: Boolean): List<Pair<Int, Int>> =
        cutoutHolesInBar(rects, barTopLeft.x, barTopLeft.y, width, height, keepOutPx, rtl)
}

/** A [CutoutBand] that follows the window's cutout (rotation, fold, bars shown/hidden). */
@Composable
internal fun rememberCutoutBand(): CutoutBand {
    val view = LocalView.current
    val density = LocalDensity.current
    val band = remember(density) { CutoutBand(with(density) { 8.dp.roundToPx() }) }
    // Reading the cutout inset subscribes the caller to inset changes; the rectangles
    // themselves come from the platform insets.
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    val cutoutLeft = WindowInsets.displayCutout.getLeft(density, LayoutDirection.Ltr)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val rects = remember(view, cutoutTop, cutoutLeft, statusTop) {
        ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
            .filter { it.width() > 0 && it.height() > 0 }
    }
    if (band.rects != rects) band.rects = rects
    return band
}

/**
 * The cutouts in [rects] (window coordinates) that reach into a bar at ([barX], [barY]) of
 * [width] x [height], as bar-local LOGICAL x ranges (start edge = 0, mirrored in RTL so one
 * walk works both ways), widened by [keepOut] on each side and sorted from the start edge.
 */
internal fun cutoutHolesInBar(
    rects: List<Rect>,
    barX: Int,
    barY: Int,
    width: Int,
    height: Int,
    keepOut: Int,
    rtl: Boolean,
): List<Pair<Int, Int>> = rects
    .filter { r -> r.top < barY + height && r.bottom > barY }
    .map { r ->
        val l = r.left - barX - keepOut
        val rr = r.right - barX + keepOut
        if (rtl) (width - rr) to (width - l) else l to rr
    }
    .filter { (l, r) -> r > 0 && l < width }
    .sortedBy { it.first }

/** Where [placeBesideCutout] put each item, in bar-local logical x. */
internal class BarPlacement(
    val startX: IntArray,
    val endX: IntArray,
    /** Where the first end-side item starts; the title may run up to here. */
    val end: Int,
    /** Where the last start-side item ends; the title starts here. */
    val titleStart: Int,
) {
    /** No item covers a cutout or another item. */
    val fits: Boolean get() = end >= titleStart
}

/**
 * Lays out a bar row around cutout [holes] (from [cutoutHolesInBar]): [startWidths] from the
 * start edge and [endWidths] from the end edge (kept in order, nearest the end first), each
 * item stepping past any cutout it would cover. Pure, so callers can ask "does this set of
 * buttons fit beside the camera?" before composing them.
 */
internal fun placeBesideCutout(
    width: Int,
    holes: List<Pair<Int, Int>>,
    startWidths: List<Int>,
    endWidths: List<Int>,
): BarPlacement {
    fun blocked(from: Int, to: Int) = holes.any { (l, r) -> from < r && to > l }
    var x = 0
    val startX = IntArray(startWidths.size)
    startWidths.forEachIndexed { i, w ->
        holes.firstOrNull { (l, r) -> x < r && x + w > l }?.let { x = it.second }
        startX[i] = x
        x += w
    }
    var end = width
    val endX = IntArray(endWidths.size)
    for (i in endWidths.indices.reversed()) {
        val w = endWidths[i]
        var at = end - w
        while (blocked(at, at + w)) {
            at = holes.last { (l, r) -> at < r && at + w > l }.first - w
        }
        endX[i] = at
        end = at
    }
    return BarPlacement(startX, endX, end, x)
}

/**
 * An app bar row that keeps its buttons clear of a camera cutout in the band it occupies.
 *
 * With the status bar showing, the bar sits below it and no cutout reaches it, so this lays
 * out like a plain row: [navigation], [title] filling the middle, [actions] at the end. In
 * full screen the bar moves up into the status strip; then each action that would cover the
 * cutout moves to the free side of it (actions keep their order, nearest the end first), and
 * the title takes whatever room is left before the cutout — or gives way entirely when that
 * room is too small to read. Only when the buttons cannot fit beside the cutout at all does
 * the row drop below it; a caller that wants to stay in the strip plans with [band] and
 * [placeBesideCutout] and trims its actions first.
 */
@Composable
internal fun CutoutAwareBarRow(
    modifier: Modifier = Modifier,
    band: CutoutBand = rememberCutoutBand(),
    navigation: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val minTitle = with(density) { 72.dp.roundToPx() }

    Layout(
        contents = listOf(navigation, title, actions),
        modifier = modifier.then(band.track),
    ) { (navM, titleM, actionM), constraints ->
        val width = constraints.maxWidth
        val rtl = layoutDirection == LayoutDirection.Rtl
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nav = navM.map { it.measure(loose) }
        val acts = actionM.map { it.measure(loose) }
        val baseRowHeight = maxOf(
            constraints.minHeight,
            nav.maxOfOrNull { it.height } ?: 0,
            acts.maxOfOrNull { it.height } ?: 0,
        )

        val holes = band.holes(width, baseRowHeight, rtl)
        val plan = placeBesideCutout(width, holes, nav.map { it.width }, acts.map { it.width })
        var titleStart = plan.titleStart
        var end = plan.end
        val actX = plan.endX

        // Not enough room beside the cutout (narrow window, wide notch): rather than let
        // buttons overlap each other or the camera, drop the whole row below the cutout and lay
        // it out plainly there.
        var top = 0
        val fits = holes.isEmpty() || plan.fits
        if (!fits) {
            val barY = band.barTopLeft.y
            top = band.rects
                .filter { r -> r.top < barY + baseRowHeight && r.bottom > barY }
                .maxOf { it.bottom - barY } + band.keepOutPx
            titleStart = nav.sumOf { it.width }
            end = width
            for (i in acts.indices.reversed()) { end -= acts[i].width; actX[i] = end }
        }
        val navPositions = if (fits) plan.startX.toList() else run {
            var a = 0
            nav.map { p -> val at = a; a += p.width; at }
        }

        // The title fills what is left before the first cutout or action.
        val laneHoles = if (fits) holes else emptyList()
        val titleEnd = minOf(end, laneHoles.firstOrNull { it.second > titleStart }?.first ?: end)
        val titleWidth = (titleEnd - titleStart).coerceAtLeast(0)
        val showTitle = laneHoles.isEmpty() || titleWidth >= minTitle
        val titleP: List<Placeable> = if (showTitle && titleWidth > 0) {
            // Height is not capped at the button row: at large font scales the two-line title
            // is taller than 64dp, and capping it cut the subtitle's glyphs in half (#456).
            titleM.map { it.measure(Constraints(maxWidth = titleWidth, maxHeight = constraints.maxHeight)) }
        } else {
            emptyList()
        }
        val rowHeight = maxOf(baseRowHeight, titleP.maxOfOrNull { it.height } ?: 0)

        // placeRelative mirrors in RTL, matching the logical coordinates above.
        layout(width, top + rowHeight) {
            nav.forEachIndexed { i, p -> p.placeRelative(navPositions[i], top + (rowHeight - p.height) / 2) }
            titleP.forEach { p -> p.placeRelative(titleStart, top + (rowHeight - p.height) / 2) }
            acts.forEachIndexed { i, p -> p.placeRelative(actX[i], top + (rowHeight - p.height) / 2) }
        }
    }
}
