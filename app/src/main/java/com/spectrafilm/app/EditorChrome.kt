/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Editor chrome (#273, third MainActivity split): status pill, press buttons,
 * slider readout and panel overlay/adjustment/side panels. Stateless; moved verbatim out of
 * MainActivity. The category rail lives in CategoryRail.kt.
 */
package com.spectrafilm.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * A compact status pill overlaid in the top-start corner of the preview canvas.
 *
 * Priority: exporting > decoding > rendering > error > idle (last render time or "Ready").
 * Fades in when visible; the idle state auto-fades after 2 s using [animateFloatAsState].
 * Shows a small [SpectraLoadingIndicator] while busy.
 */
@Composable
internal fun StatusPill(
    exporting: Boolean,
    rendering: Boolean,
    decoding: Boolean,
    lastRenderMs: Long?,
    renderErr: String?,
    modifier: Modifier = Modifier,
) {
    // Derive a single status from the priority chain.
    val pillState: PillState = when {
        exporting -> PillState.Busy(stringResource(R.string.editor_exporting))
        decoding  -> PillState.Busy(stringResource(R.string.editor_decoding))
        rendering -> PillState.Busy(stringResource(R.string.editor_rendering))
        renderErr != null -> PillState.Error(renderErr)
        lastRenderMs != null -> PillState.Done(lastRenderMs)
        else -> PillState.Hidden
    }

    // For the idle "Rendered in X ms" state we auto-fade after 2 s.
    var showIdle by remember { mutableStateOf(false) }
    LaunchedEffect(lastRenderMs, rendering, exporting, decoding, renderErr) {
        if (pillState is PillState.Done) {
            showIdle = true
            delay(2000)
            showIdle = false
        } else {
            showIdle = false
        }
    }

    val visible = pillState is PillState.Busy ||
        pillState is PillState.Error ||
        (pillState is PillState.Done && showIdle)

    val pillDesc = when (pillState) {
        is PillState.Busy  -> pillState.label
        is PillState.Error -> stringResource(R.string.editor_pill_render_error, pillState.message)
        is PillState.Done  -> stringResource(R.string.editor_pill_rendered_in, pillState.ms)
        PillState.Hidden   -> ""
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(180)),
        exit  = fadeOut(animationSpec = androidx.compose.animation.core.tween(400)),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SpektraBrand.scrim.copy(alpha = 0.45f),
            contentColor = SpektraBrand.onCanvas,
            modifier = Modifier
                .wrapContentSize()
                // One merged node: the spinner + text read as a single polite live status.
                .semantics(mergeDescendants = true) {
                    contentDescription = pillDesc
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (pillState) {
                    is PillState.Busy -> {
                        SpectraLoadingIndicator(
                            modifier = Modifier.size(18.dp),
                            color = SpektraBrand.onCanvas,
                        )
                        Text(
                            pillState.label,
                            fontSize = 12.sp,
                            color = SpektraBrand.onCanvas,
                        )
                    }
                    is PillState.Error -> {
                        Text(
                            stringResource(R.string.editor_pill_error, pillState.message),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.errorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    is PillState.Done -> {
                        Text(
                            stringResource(R.string.editor_pill_rendered_in_text, pillState.ms),
                            fontSize = 12.sp,
                            color = SpektraBrand.onCanvas.copy(alpha = 0.85f),
                        )
                    }
                    PillState.Hidden -> { /* not shown */ }
                }
            }
        }
    }
}

/** Internal sealed hierarchy for the status-pill priority logic. */
private sealed interface PillState {
    data class Busy(val label: String) : PillState
    data class Error(val message: String) : PillState
    data class Done(val ms: Long) : PillState
    data object Hidden : PillState
}

/** A press-scaling icon button used in the top bar. */
@Composable
internal fun PressIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.scale(if (pressed) 0.88f else 1f),
    ) { content() }
}

/**
 * An icon button with a second gesture on hold (interaction rules: hold Compare for the
 * original, hold Undo for the history). A tap runs [onClick]; once the press outlasts the
 * long-press timeout [onHoldStart] runs with a haptic tick, and [onHoldEnd] runs when that
 * same finger lifts. [holdLabel] names the hold for TalkBack's long-click action; a long
 * click with no finger down (TalkBack) runs start and end together.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HoldIconButton(
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    modifier: Modifier = Modifier,
    onHoldEnd: () -> Unit = {},
    holdLabel: String? = null,
    clickLabel: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // combinedClickable, not raw pointer gestures, so the button keeps keyboard / D-pad
    // focus and activation, the ripple and the long-press haptic. The hold's END is read
    // from the same interaction source: the Release (or Cancel) of the press that held.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val holdEnd = rememberUpdatedState(onHoldEnd)
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { i ->
            if ((i is androidx.compose.foundation.interaction.PressInteraction.Release ||
                    i is androidx.compose.foundation.interaction.PressInteraction.Cancel) && held
            ) {
                held = false
                holdEnd.value()
            }
        }
    }
    // The button can leave composition mid-hold (the bar hides); the hold must still end,
    // or the original would stay on screen with no finger down.
    DisposableEffect(Unit) {
        onDispose { if (held) { held = false; holdEnd.value() } }
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .scale(if (pressed) 0.88f else 1f)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = clickLabel,
                onLongClickLabel = holdLabel,
                onLongClick = {
                    if (pressed) {
                        held = true
                        onHoldStart()
                    } else {
                        // An accessibility long-click has no finger to lift: run the whole
                        // hold at once (history opens; a peek has nothing to show).
                        onHoldStart()
                        onHoldEnd()
                    }
                },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * How far the panel fades while a slider is moving, and how long each direction takes.
 *
 * Asymmetric on purpose. Getting out of the way has to feel like a consequence of your
 * finger, so it is fast; coming back is not urgent and a snap back to full opacity reads as
 * a flicker, so it is slower. 0.20 leaves the controls faintly legible — enough to see that
 * the track is still under your thumb — without the panel competing with the photograph.
 */
internal val PANEL_DIM_ALPHA = 0.20f
internal val PANEL_DIM_OUT_SPEC = tween<Float>(durationMillis = 110)
internal val PANEL_DIM_IN_SPEC = tween<Float>(durationMillis = 240)

/**
 * The value of the slider under your finger, floating over the photograph.
 *
 * This exists because of the fade: with the panel at 0.20 you can no longer read its value
 * pill, and an adjustment you cannot put a number on is a worse tool, not a better-looking
 * one. So the number moves to where you are already looking.
 *
 * [readout] is read HERE and nowhere else. A drag writes it on every frame, so this small
 * composable is the entire recomposition cost of the feature.
 */
@Composable
internal fun SliderReadoutPill(
    readout: MutableState<SliderReadout?>,
    interacting: State<Boolean>,
) {
    // The HUD outlives the drag by HUD_LINGER_MS so the last value can be read, and tapped:
    // a tap opens the numeric entry for that same control. A new drag restarts the clock
    // (the key changes with every frame of it), and the effect only clears a value it saw
    // while the finger was up, so it can never blank a readout mid-drag.
    val r = readout.value ?: return
    val dragging = interacting.value
    var typing by remember { mutableStateOf<SliderReadoutEdit?>(null) }
    LaunchedEffect(r, dragging, typing) {
        if (!dragging && typing == null) {
            delay(HUD_LINGER_MS)
            if (readout.value === r) readout.value = null
        }
    }
    val edit = r.edit
    val tapLabel = stringResource(R.string.editor_hud_type_value)
    // A stale edit (its slider gone or retargeted since) closes the HUD instead of typing.
    fun openTyping() {
        if (edit == null || dragging) return
        if (edit.isCurrent()) typing = edit else readout.value = null
    }
    Surface(
        color = SpektraBrand.scrim.copy(alpha = 0.62f),
        shape = RoundedCornerShape(14.dp),
        onClick = { openTyping() },
        enabled = edit != null && !dragging,
        modifier = Modifier
            .padding(top = 6.dp, end = 6.dp)
            // A long parameter name must not grow the pill back across the status pill;
            // the number is the part that has to stay readable, so the name ellipsizes.
            .widthIn(max = 220.dp)
            // While dragging it is decorative: TalkBack already announces the slider's own
            // value, and a live region would read every intermediate frame aloud. Once the
            // finger is up it is a button that types the value.
            .clearAndSetSemantics {
                if (edit != null && !dragging) {
                    contentDescription = "${r.label} ${r.value}"
                    role = Role.Button
                    onClick(label = tapLabel) { openTyping(); true }
                }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                r.label,
                style = MaterialTheme.typography.labelSmall,
                color = SpektraBrand.onCanvas.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                r.value,
                style = MaterialTheme.typography.headlineSmall,
                color = SpektraBrand.onCanvas,
                maxLines = 1,
            )
        }
    }
    typing?.let { e ->
        NumericEntryDialog(
            label = r.label,
            initial = formatValue(e.current, e.decimals),
            range = e.range,
            step = e.step,
            decimals = e.decimals,
            onDismiss = { typing = null },
            onConfirm = { v ->
                if (e.isCurrent()) e.set(v)
                typing = null
                readout.value = null
            },
        )
    }
}

/** How long the slider HUD stays up after the finger lifts (interaction rule: 1.4 s). */
internal const val HUD_LINGER_MS = 1400L

/**
 * The adjustment panel as a floating bottom overlay inside the preview Box. Extracting it into a
 * BoxScope extension (a) lets it use Modifier.align to pin to the preview's bottom and (b) breaks
 * the enclosing Column's receiver chain, so AnimatedVisibility resolves to the top-level overload
 * instead of ColumnScope's (which would re-measure the column). The slide/fade reads as a bottom
 * sheet rising over the now constant-height preview, so opening a panel no longer resizes it.
 */
@Composable
internal fun BoxScope.PanelOverlay(
    visible: Boolean,
    dimming: State<Boolean>,
    content: @Composable () -> Unit,
) {
    // Lightroom's tool tray gets out of the way while you drag, and the reason is that the
    // panel covers the bottom third of the photograph — exactly the part you are trying to
    // judge. It fades to a ghost on the first movement and returns on release.
    //
    // `dimming` arrives as State and is read HERE rather than at the call site, so the flip
    // recomposes this small composable instead of the editor around it. The animated alpha
    // is then read inside the graphicsLayer LAMBDA, which runs in the draw phase, so no
    // frame of the fade re-measures anything. That matters: the preview is a weight(1f)
    // sibling, and a per-frame remeasure of it is what forced the GPU preview off once
    // already (the note under the call site).
    val alpha = animateFloatAsState(
        targetValue = if (dimming.value) PANEL_DIM_ALPHA else 1f,
        animationSpec = if (dimming.value) PANEL_DIM_OUT_SPEC else PANEL_DIM_IN_SPEC,
        label = "panelDim",
    )
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .graphicsLayer { this.alpha = alpha.value },
        enter = expandVertically(animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        )) + fadeIn(),
        exit = shrinkVertically(animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
        )) + fadeOut(),
    ) { content() }
}

/** The adjustment panel body: drag-handle header (swipe down to dismiss) + scrolling content. */
@Composable
internal fun AdjustmentPanel(
    category: Category?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Non-null only when this category actually holds edits. The editor had a reset for one
    // slider (double-tap its pill) and a reset for everything, and nothing in between — so
    // backing out of one exploratory panel meant remembering and reversing a dozen sliders
    // by hand.
    onResetCategory: (() -> Unit)? = null,
    // Two heights: compact (the default, most of the photo stays in view) and tall (for the
    // twenty-slider panels). Drag the handle up for tall, down for compact, and down again
    // to close. Hoisted so the choice survives closing and reopening the panel.
    tall: Boolean = false,
    onTallChange: (Boolean) -> Unit = {},
    // Tabletop posture: the panel fills the screen half below the hinge, overriding the
    // compact/tall heights (FoldPosture.kt). Null everywhere else.
    fixedHeight: Dp? = null,
    // The open stage's groups, shown as tabs under the drag handle. One group or none: the
    // handle shows the title instead, as before.
    tabs: List<Category> = emptyList(),
    dirty: Set<Category> = emptySet(),
    onSelectTab: (Category) -> Unit = {},
    // Edit's sections (Light, Color, ...), shown as tabs at the top; their tools are stacked
    // in [content]. Empty for Looks and Masks.
    sections: List<Stage> = emptyList(),
    onSelectSection: (Stage) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenH = localConfigurationHeightDp()
    val maxH by animateDpAsState(
        (screenH * if (tall) PANEL_TALL_FRACTION else PANEL_COMPACT_FRACTION).dp,
        label = "panelMaxHeight",
    )
    val expandLabel = stringResource(R.string.editor_panel_expand)
    val collapseLabel = stringResource(R.string.editor_panel_collapse)
    val title = category?.let { stringResource(it.labelRes) } ?: ""
    val panelTitle = stringResource(R.string.editor_panel_title, title)
    val closeLabel = stringResource(R.string.editor_close_panel_named, title)
    val closeAction = stringResource(R.string.editor_close)
    // The kit's supporting panel: surfaceContainer (the stage row under it uses the same
    // colour, so the two read as one panel), 28dp (extra-large) top corners.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics { paneTitle = panelTitle },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier.heightIn(max = maxH)),
        ) {
            // drag-handle header: swipe DOWN to dismiss. The drag has no accessible
            // equivalent, so the header is also exposed as a "Close <category> panel"
            // button via a semantics-only click action (touch behaviour unchanged).
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp) // #181: it is a button for TalkBack; 46dp natural
                    .pointerInput(category, tall) {
                        // Decide on release from the whole drag, not per sample: one
                        // gesture moves exactly one step (tall -> compact -> closed).
                        var total = 0f
                        val threshold = PANEL_DRAG_STEP.toPx()
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                when {
                                    total < -threshold -> onTallChange(true)
                                    total > threshold && tall -> onTallChange(false)
                                    total > threshold -> onDismiss()
                                }
                            },
                        ) { _, dragAmount -> total += dragAmount }
                    }
                    // M3: a sheet's drag handle must also work without dragging. A tap
                    // cycles compact <-> tall; closing stays on drag-down, Back and the
                    // accessibility click below.
                    .pointerInput(tall) { detectTapGestures { onTallChange(!tall) } }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = closeLabel
                        onClick(label = closeAction) { onDismiss(); true }
                        // The drag has no accessible equivalent; offer the height step too.
                        customActions = listOf(
                            if (tall) {
                                CustomAccessibilityAction(collapseLabel) { onTallChange(false); true }
                            } else {
                                CustomAccessibilityAction(expandLabel) { onTallChange(true); true }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                    if (tabs.size > 1 || sections.size > 1) {
                        // The tabs name the open group; a title here would repeat it.
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
            }
            // Outside the handle's merged semantics node, so each section tab stays its own
            // TalkBack stop.
            if (sections.size > 1 && category != null) {
                SectionTabs(
                    sections = sections,
                    active = category.stage,
                    dirty = dirty,
                    onSelect = onSelectSection,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // A new section starts at its top, not wherever the last one was scrolled to.
            // Reset by an effect, not by key(stage) { rememberScrollState() }: that keyed
            // group sat among the panel's node-emitting siblings and opening Edit crashed in
            // LayoutNode.onChildRemoved (null child) on device.
            val scroll = rememberScrollState()
            LaunchedEffect(category?.stage) { scroll.scrollTo(0) }
            Column(
                Modifier
                    .fillMaxWidth()
                    // fill = false: a short panel stays short, a long one scrolls while the
                    // tool row below it stays in view.
                    .weight(1f, fill = false)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Deliberately here and not in the drag-handle header above: that header is
                // one merged semantics node carrying its own "close panel" click action, and
                // a nested button inside it would be unreachable to TalkBack.
                if (onResetCategory != null) {
                    TextButton(
                        onClick = onResetCategory,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.editor_reset_category, title))
                    }
                }
                CompositionLocalProvider(LocalFlatSections provides true) { content() }
            }
            // The kit's supporting panel: the picked tool's controls, then the stage's tools
            // as round buttons. Outside the handle's merged semantics node, so each tool
            // stays its own TalkBack stop rather than being swallowed by "close panel".
            if (tabs.size > 1 && category != null) {
                ToolRow(
                    tools = tabs,
                    active = category,
                    dirty = dirty,
                    onSelect = onSelectTab,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * Wide-layout counterpart of [AdjustmentPanel]: the same [content], docked as a fixed-width
 * side column beside the preview instead of floating over it. No height cap (the column
 * scrolls) and a plain title + close button instead of the drag handle.
 */
@Composable
internal fun SidePanel(
    category: Category,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onResetCategory: (() -> Unit)? = null,
    // M3 fixed supporting pane: 360dp expanded, 412dp large; narrower on a landscape phone.
    width: Dp = PANE_EXPANDED_DP.dp,
    tabs: List<Category> = emptyList(),
    dirty: Set<Category> = emptySet(),
    onSelectTab: (Category) -> Unit = {},
    sections: List<Stage> = emptyList(),
    onSelectSection: (Stage) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val title = stringResource(category.labelRes)
    // The heading names the mode; the tool list or section tabs under it name the group.
    val heading = if (tabs.size > 1 || sections.size > 1) stringResource(category.mode.labelRes) else title
    val panelTitle = stringResource(R.string.editor_panel_title, title)
    val closeLabel = stringResource(R.string.editor_close_panel_named, title)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .semantics { paneTitle = panelTitle },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                TextTooltip(stringResource(R.string.editor_close_panel)) {
                    IconButton(onClick = onDismiss) {
                        Icon(SpectraIcons.Cancel, contentDescription = closeLabel)
                    }
                }
            }
            if (sections.size > 1) {
                SectionTabs(
                    sections = sections,
                    active = category.stage,
                    dirty = dirty,
                    onSelect = onSelectSection,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (tabs.size > 1 && localConfigurationHeightDp() < SIDE_PANE_LIST_MIN_HEIGHT_DP) {
                // Short window (a landscape phone): six 48dp list rows would leave the
                // controls no height at all, so the tools go in one scrolling row instead.
                ToolRow(
                    tools = tabs,
                    active = category,
                    dirty = dirty,
                    onSelect = onSelectTab,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (tabs.size > 1) {
                ToolList(
                    tools = tabs,
                    active = category,
                    dirty = dirty,
                    onSelect = onSelectTab,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val sideScroll = rememberScrollState()
            LaunchedEffect(category.stage) { sideScroll.scrollTo(0) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(sideScroll)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onResetCategory != null) {
                    TextButton(
                        onClick = onResetCategory,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.editor_reset_category, title))
                    }
                }
                CompositionLocalProvider(LocalFlatSections provides true) { content() }
            }
        }
    }
}

/**
 * Below this window height the side pane lists its tools as a row, not a column: the Film
 * stage's six list rows alone are 288dp.
 */
private const val SIDE_PANE_LIST_MIN_HEIGHT_DP = 600

/** Compact adjustment-panel height, as a fraction of the screen: most of the photo stays visible. */
private const val PANEL_COMPACT_FRACTION = 0.38f

/** Tall adjustment-panel height, for the long panels (Couplers, Grain, Masks). */
private const val PANEL_TALL_FRACTION = 0.66f

/** How far the panel handle must travel before a drag counts as one height step. */
private val PANEL_DRAG_STEP = 24.dp

/**
 * Predictive-back feedback for the tool sheet / side pane: while the Back gesture is in
 * progress the panel shrinks slightly toward its bottom edge and drifts down, so the user
 * sees what Back will close before committing (M3: sheets scale toward the gesture).
 */
internal fun Modifier.predictiveBackShrink(progress: Float): Modifier =
    if (progress <= 0f) this else graphicsLayer {
        val p = progress.coerceIn(0f, 1f)
        val s = 1f - PREDICTIVE_BACK_SCALE * p
        scaleX = s
        scaleY = s
        translationY = PREDICTIVE_BACK_DRIFT.toPx() * p
        transformOrigin = TransformOrigin(0.5f, 1f)
    }

private const val PREDICTIVE_BACK_SCALE = 0.06f
private val PREDICTIVE_BACK_DRIFT = 16.dp
