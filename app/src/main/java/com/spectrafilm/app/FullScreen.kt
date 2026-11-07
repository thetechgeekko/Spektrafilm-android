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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
internal fun Modifier.topBarSafePadding(): Modifier =
    windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))

/**
 * An app bar row that keeps its buttons clear of a camera cutout in the band it occupies.
 *
 * With the status bar showing, the bar sits below it and no cutout reaches it, so this lays
 * out like a plain row: [navigation], [title] filling the middle, [actions] at the end. In
 * full screen the bar moves up into the status strip; then each action that would cover the
 * cutout moves to the free side of it (actions keep their order, nearest the end first), and
 * the title takes whatever room is left before the cutout — or gives way entirely when that
 * room is too small to read.
 */
@Composable
internal fun CutoutAwareBarRow(
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var barTopLeft by remember { mutableStateOf(IntOffset.Zero) }
    // Reading the cutout inset subscribes this composable to inset changes (rotation, fold,
    // bars shown/hidden); the rectangles themselves come from the platform insets.
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    val cutoutLeft = WindowInsets.displayCutout.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
    val rects: List<Rect> = remember(view, cutoutTop, cutoutLeft) {
        ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
    }
    val keepOut = with(density) { 8.dp.roundToPx() }
    val minTitle = with(density) { 72.dp.roundToPx() }

    Layout(
        contents = listOf(navigation, title, actions),
        modifier = modifier.onGloballyPositioned {
            val p = it.positionInWindow()
            barTopLeft = IntOffset(p.x.toInt(), p.y.toInt())
        },
    ) { (navM, titleM, actionM), constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nav = navM.map { it.measure(loose) }
        val acts = actionM.map { it.measure(loose) }
        val height = maxOf(
            constraints.minHeight,
            nav.maxOfOrNull { it.height } ?: 0,
            acts.maxOfOrNull { it.height } ?: 0,
        )

        // Cutouts that reach into this bar, in bar-local x, widened by the keep-out margin.
        val holes = rects
            .filter { r -> r.top < barTopLeft.y + height && r.bottom > barTopLeft.y }
            .map { r -> (r.left - barTopLeft.x - keepOut) to (r.right - barTopLeft.x + keepOut) }
            .filter { (l, r) -> r > 0 && l < width }
            .sortedBy { it.first }

        fun blocked(from: Int, to: Int) = holes.any { (l, r) -> from < r && to > l }

        // Navigation from the start edge, stepping right past any cutout it would cover.
        var x = 0
        val navX = nav.map { p ->
            holes.firstOrNull { (l, r) -> x < r && x + p.width > l }?.let { x = it.second }
            val at = x; x += p.width; at
        }
        val titleStart = x

        // Actions from the end edge, stepping left past any cutout they would cover.
        var end = width
        val actX = IntArray(acts.size)
        for (i in acts.indices.reversed()) {
            val p = acts[i]
            var at = end - p.width
            while (blocked(at, at + p.width)) {
                val hole = holes.last { (l, r) -> at < r && at + p.width > l }
                at = hole.first - p.width
            }
            actX[i] = at.coerceAtLeast(titleStart)
            end = actX[i]
        }

        // The title fills what is left before the first cutout or action.
        val titleEnd = minOf(end, holes.firstOrNull { it.second > titleStart }?.first ?: end)
        val titleWidth = (titleEnd - titleStart).coerceAtLeast(0)
        val showTitle = holes.isEmpty() || titleWidth >= minTitle
        val titleP: List<Placeable> = if (showTitle && titleWidth > 0) {
            titleM.map { it.measure(Constraints(maxWidth = titleWidth, maxHeight = height)) }
        } else {
            emptyList()
        }

        layout(width, height) {
            nav.forEachIndexed { i, p -> p.place(navX[i], (height - p.height) / 2) }
            titleP.forEach { p -> p.place(titleStart, (height - p.height) / 2) }
            acts.forEachIndexed { i, p -> p.place(actX[i], (height - p.height) / 2) }
        }
    }
}
