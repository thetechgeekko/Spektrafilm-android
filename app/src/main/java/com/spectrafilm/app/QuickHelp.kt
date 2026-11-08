/*
 * Spektrafilm for Android — quick help ("Show me"). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The editor's manual, kept light: one bottom sheet of short tips, each with a "Show me"
 * button that closes the sheet and pulses the real control on screen. Controls opt in with
 * [helpSpot]; a control that is not on screen right now (the export pill before there is a
 * photo, the film strip outside Looks) simply has no "Show me". The full written guide stays
 * in Settings, linked from the bottom of the sheet.
 */
package com.spectrafilm.app

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The editor controls a tip can point at. */
enum class HelpSpot { LIBRARY, MODES, FILMS, COMPARE, UNDO, EXPORT }

/**
 * Where each tagged control is (window coordinates), whether the sheet is open, and which
 * control is pulsing. One instance per activity, provided through [LocalQuickHelp].
 */
@Stable
class QuickHelpState {
    internal val spots = mutableStateMapOf<HelpSpot, Rect>()
    var sheetOpen by mutableStateOf(false)
    internal var pulsing by mutableStateOf<HelpSpot?>(null)

    fun open() { sheetOpen = true }

    internal fun showMe(spot: HelpSpot) {
        sheetOpen = false
        pulsing = spot
    }
}

/** Null outside the app root (previews, tests): [helpSpot] then tags nothing. */
val LocalQuickHelp = staticCompositionLocalOf<QuickHelpState?> { null }

/** Tags this control as [spot], so a "Show me" tip can find and pulse it. */
fun Modifier.helpSpot(state: QuickHelpState?, spot: HelpSpot): Modifier =
    if (state == null) this else this then HelpSpotElement(state, spot)

private data class HelpSpotElement(val state: QuickHelpState, val spot: HelpSpot) :
    ModifierNodeElement<HelpSpotNode>() {
    override fun create() = HelpSpotNode(state, spot)
    override fun update(node: HelpSpotNode) {
        if (node.spot != spot || node.state !== state) {
            node.forget()
            node.state = state
            node.spot = spot
        }
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "helpSpot"
        properties["spot"] = spot
    }
}

private class HelpSpotNode(var state: QuickHelpState, var spot: HelpSpot) :
    Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        // boundsInWindow is clipped by every scrolling parent: a control scrolled out of its
        // panel is attached but invisible, so it has no "Show me" until it scrolls back.
        val visible = coordinates.boundsInWindow()
        if (visible.width > 0f && visible.height > 0f) state.spots[spot] = visible else forget()
    }

    // A control that leaves the screen takes its "Show me" with it.
    override fun onDetach() = forget()

    fun forget() { state.spots.remove(spot) }
}

private class HelpTip(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val icon: ImageVector,
    val spot: HelpSpot?,
)

private val TIPS = listOf(
    HelpTip(R.string.help_library_title, R.string.help_library_body, SpectraIcons.SourceImage, HelpSpot.LIBRARY),
    HelpTip(R.string.help_modes_title, R.string.help_modes_body, SpectraIcons.StageLook, HelpSpot.MODES),
    HelpTip(R.string.help_films_title, R.string.help_films_body, SpectraIcons.Simulation, HelpSpot.FILMS),
    HelpTip(R.string.help_compare_title, R.string.help_compare_body, SpectraIcons.Compare, HelpSpot.COMPARE),
    HelpTip(R.string.help_undo_title, R.string.help_undo_body, SpectraIcons.Undo, HelpSpot.UNDO),
    HelpTip(R.string.help_save_title, R.string.help_save_body, SpectraIcons.Export, HelpSpot.EXPORT),
    HelpTip(R.string.help_zoom_title, R.string.help_zoom_body, SpectraIcons.Loupe, null),
)

/**
 * The quick-help sheet: a heading, one list row per tip (icon, title, one line, "Show me"
 * when the control is on screen) and a link to the full guide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickHelpSheet(state: QuickHelpState, onOpenGuide: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Animate the sheet away before acting, so the pulse is not drawn under it.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    ModalBottomSheet(onDismissRequest = { state.sheetOpen = false }, sheetState = sheetState) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.help_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 8.dp)
                    .semantics { heading() },
            )
            TIPS.forEach { tip ->
                val shown = tip.spot?.takeIf { it in state.spots }
                ListItem(
                    headlineContent = { Text(stringResource(tip.title)) },
                    supportingContent = { Text(stringResource(tip.body)) },
                    leadingContent = {
                        Icon(tip.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = if (shown != null) {
                        {
                            TextButton(onClick = { closeThen { state.showMe(shown) } }) {
                                Text(stringResource(R.string.help_show_me))
                            }
                        }
                    } else {
                        null
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            TextButton(
                onClick = { closeThen { state.sheetOpen = false; onOpenGuide() } },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.help_full_guide)) }
        }
    }
}

/** How long a "Show me" ring pulses before it fades away. */
internal const val HELP_PULSE_MS = 2400

/**
 * Draws the pulsing ring around the control picked by "Show me". Draw-only: it takes no
 * touches, so the user can tap the control straight away. Reduced motion shows a still ring
 * for the same time.
 */
@Composable
fun QuickHelpPulse(state: QuickHelpState) {
    val spot = state.pulsing ?: return
    val target = state.spots[spot]
    val progress = remember(spot) { Animatable(0f) }
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    LaunchedEffect(spot) {
        if (reduceMotion) {
            kotlinx.coroutines.delay(HELP_PULSE_MS.toLong())
        } else {
            progress.animateTo(1f, tween(HELP_PULSE_MS, easing = LinearEasing))
        }
        state.pulsing = null
    }
    if (target == null) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ring = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val box = target.translate(-origin)
        val pad = 6.dp.toPx()
        val stroke = 3.dp.toPx()
        // Three beats: each grows a ring out of the control and fades it.
        val beats = 3
        val t = progress.value * beats
        val beat = t - t.toInt()
        val grow = if (reduceMotion) 0f else beat * 12.dp.toPx()
        val alpha = if (reduceMotion) 1f else 1f - beat
        val inflated = box.inflate(pad + grow)
        val radius = minOf(inflated.height, inflated.width) / 2f
        drawRoundRect(
            color = ring.copy(alpha = alpha),
            topLeft = inflated.topLeft,
            size = Size(inflated.width, inflated.height),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
        // A steady ring on the control itself, so it stays marked between beats.
        val steady = box.inflate(pad)
        val r = minOf(steady.height, steady.width) / 2f
        drawRoundRect(
            color = ring,
            topLeft = steady.topLeft,
            size = Size(steady.width, steady.height),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = stroke),
        )
    }
}
