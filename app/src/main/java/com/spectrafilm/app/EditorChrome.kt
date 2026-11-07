/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Editor chrome (#273, third MainActivity split): status pill, scrim and press buttons,
 * slider readout, panel overlay/adjustment/side panels and the category rail. Stateless;
 * moved verbatim out of MainActivity.
 */
package com.spectrafilm.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
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
                            color = Color.White,
                        )
                        Text(
                            pillState.label,
                            fontSize = 12.sp,
                            color = Color.White,
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
                            color = Color.White.copy(alpha = 0.85f),
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

/**
 * 40dp circular control over a ~35% scrim. [toggle] marks a two-state control so the
 * [active] tint (otherwise colour-only) is also announced as "On"/"Off".
 */
@Composable
internal fun CircleScrimButton(
    onClick: () -> Unit,
    active: Boolean = false,
    toggle: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val stateLabel = stringResource(if (active) R.string.editor_state_on else R.string.editor_state_off)
    Box(
        Modifier
            .size(40.dp)
            .scale(if (pressed) 0.9f else 1f)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else Color.Black.copy(alpha = 0.35f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier.semantics {
                if (toggle) stateDescription = stateLabel
            },
        ) { content() }
    }
}

/** A press-scaling icon button used in the top bar. */
@Composable
internal fun PressIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.scale(if (pressed) 0.88f else 1f),
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
internal fun SliderReadoutPill(readout: State<SliderReadout?>) {
    val r = readout.value ?: return
    Surface(
        color = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .padding(top = 6.dp, end = 6.dp)
            // A long parameter name must not grow the pill back across the status pill;
            // the number is the part that has to stay readable, so the name ellipsizes.
            .widthIn(max = 220.dp)
            // Decorative: TalkBack already announces the slider's own value, and a live
            // region here would read every intermediate frame of the drag aloud.
            .clearAndSetSemantics { },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                r.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                r.value,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

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
    // M3 standard bottom sheet: surfaceContainerLow, 28dp (extra-large) top corners.
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics { paneTitle = panelTitle },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxH),
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
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                content()
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val title = stringResource(category.labelRes)
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
                    title,
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
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
                content()
            }
        }
    }
}

/**
 * The editor parameter navigation: five darkroom stages, with the selected stage's
 * groups on a second row above them.
 *
 * This replaced a single scrolling LazyRow of all fourteen [Category] chips. At 72dp a
 * chip that row ran past 1000dp against a 360dp screen, so most of the app's controls
 * were reachable only by scrolling a bar that gave no sign of how much lay off-screen,
 * ordered by the enum rather than by the photographic process. Five fixed chips fit any
 * phone without scrolling, and the second row is never longer than six.
 *
 * The stage is DERIVED from the active category rather than held as separate state, so
 * the two rows cannot disagree and closing the panel closes the stage.
 */
@Composable
internal fun CategoryBar(
    active: Category?,
    dirty: Set<Category>,
    onSelect: (Category) -> Unit,
    exportVisible: Boolean = false,
    exporting: Boolean = false,
    onExport: () -> Unit = {},
) {
    val activeStage = active?.stage
    val subItems = remember(activeStage) { activeStage?.let { categoriesOf(it) }.orEmpty() }

    Surface(
        color = SpectraIcons.nearBlackCanvas,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Second row: the groups inside the open stage. A stage holding a single
            // group shows no row at all -- there is nothing to choose, and a one-item
            // row would read as a dead control.
            if (subItems.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    itemsIndexed(subItems) { _, cat ->
                        SubCategoryChip(
                            category = cat,
                            selected = cat == active,
                            modified = cat in dirty,
                            onClick = { onSelect(cat) },
                        )
                    }
                }
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            // M3 floating toolbar: the five stages in a pill, with the screen's one primary
            // action -- Export -- as the FAB paired beside it. The five stages share the
            // pill's width equally, so none is squeezed. Where the FAB beside them would
            // leave a stage under the 48dp touch target (very narrow windows, or a side
            // column), the FAB moves to its own row at the trailing edge above the toolbar.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fabBeside = !exportVisible ||
                (maxWidth - TOOLBAR_SIDE_PADDING * 2 - TOOLBAR_GAP - EXPORT_FAB_SIZE - TOOLBAR_INNER_PADDING * 2) / Stage.entries.size >= 48.dp
            Column(Modifier.fillMaxWidth()) {
            if (exportVisible && !fabBeside) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) { ExportFab(exporting = exporting, onClick = onExport) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = TOOLBAR_SIDE_PADDING, end = TOOLBAR_SIDE_PADDING, top = 6.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(TOOLBAR_GAP, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 420.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(TOOLBAR_INNER_PADDING)
                    .selectableGroup(),
            ) {
                Stage.entries.forEach { stage ->
                    val members = categoriesOf(stage)
                    StageItem(
                        modifier = Modifier.weight(1f),
                        stage = stage,
                        selected = stage == activeStage,
                        // A stage is "doing something" when any group inside it is.
                        modified = members.any { it in dirty },
                        onClick = {
                            if (stage == activeStage) {
                                // Tapping the open stage closes the panel, exactly as
                                // tapping the active category chip always did.
                                active?.let(onSelect)
                            } else {
                                members.firstOrNull()?.let(onSelect)
                            }
                        },
                    )
                }
            }
            if (exportVisible && fabBeside) ExportFab(exporting = exporting, onClick = onExport)
            }
            }
            }
        }
    }
}

private val TOOLBAR_SIDE_PADDING = 12.dp
private val TOOLBAR_GAP = 8.dp
private val TOOLBAR_INNER_PADDING = 4.dp
private val EXPORT_FAB_SIZE = 56.dp

/** One of the five stage chips on the bottom row. */
@Composable
internal fun StageItem(
    stage: Stage,
    selected: Boolean,
    modified: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isSelected = selected
    val modifiedState = stringResource(R.string.editor_category_modified)
    // Motion here is deliberately confined to paint and to a width INSIDE the chip.
    // Nothing animates the rail's height: the preview is a weight(1f) sibling, and an
    // animated rail height would re-measure it every frame -- the churn that forced the
    // GPU preview off and jolted the CPU one (see PanelOverlay's note).
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, RAIL_PRESS, label = "stagePress")
    val tint by animateColorAsState(
        if (selected) accent else Color.White.copy(alpha = 0.78f), RAIL_PAINT, label = "stageTint",
    )
    val fill by animateColorAsState(
        if (selected) accent.copy(alpha = 0.18f) else Color.Transparent, RAIL_PAINT, label = "stageFill",
    )
    val pill by animateDpAsState(if (selected) 20.dp else 0.dp, RAIL_PILL, label = "stagePill")
    TextTooltip(stringResource(stage.hintRes)) {
        Column(
            modifier
                .scale(press)
                .clip(CircleShape)
                .background(fill)
                .clickableNoRipple(interaction, onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = isSelected
                    if (modified) stateDescription = modifiedState
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = stageIcon(stage),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
                if (modified) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 5.dp, y = (-3).dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
            }
            Text(
                stringResource(stage.labelRes),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = tint,
            )
            // The indicator grows from the centre rather than appearing, so moving
            // between stages reads as one mark travelling along the rail.
            Box(
                Modifier
                    .size(width = pill, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
        }
    }
}

/**
 * The editor's one primary action. M3: a FAB is for the constructive main action, is hidden
 * rather than disabled when unavailable, and may sit beside a floating toolbar. While an
 * export runs it shows the loading indicator in place of its icon and ignores taps.
 */
@Composable
internal fun ExportFab(exporting: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.editor_action_export)
    val busyLabel = stringResource(R.string.editor_exporting)
    TextTooltip(stringResource(R.string.editor_tooltip_export_gallery)) {
        FloatingActionButton(
            onClick = { if (!exporting) onClick() },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.semantics { contentDescription = if (exporting) busyLabel else label },
        ) {
            if (exporting) {
                SpectraLoadingIndicator(
                    Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(SpectraIcons.Export, contentDescription = null)
            }
        }
    }
}

/**
 * A group chip on the second row: a round icon button.
 *
 * Two earlier cuts were wrong about width. Reusing [CategoryItem] put six 72dp stacked
 * icon-over-label cells in the row -- 432dp against a 360dp screen, still overflowing,
 * the exact fault this redesign existed to remove. Text-only pills were shorter but only
 * fixed it vertically: "Simulation" is a wide word and the sixth chip still started past
 * the edge. Six icon buttons is roughly 250dp, so the row finally fits outright on the
 * narrowest phone, with no scrolling at all.
 *
 * The name is not lost, only moved: the wrapping [TextTooltip] carries the category hint
 * on long-press, and `contentDescription` carries the label itself, so TalkBack still
 * announces "Grain" rather than an unnamed button. That matters here precisely because
 * the glyph alone is doing the identifying.
 */
@Composable
internal fun SubCategoryChip(
    category: Category,
    selected: Boolean,
    modified: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isSelected = selected
    val label = stringResource(category.labelRes)
    val modifiedState = stringResource(R.string.editor_category_modified)
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, RAIL_PRESS, label = "groupPress")
    val tint by animateColorAsState(
        if (selected) accent else Color.White.copy(alpha = 0.82f), RAIL_PAINT, label = "groupTint",
    )
    val fill by animateColorAsState(
        if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f),
        RAIL_PAINT, label = "groupFill",
    )
    TextTooltip(categoryHint(category)) {
        Box(
            Modifier
                .scale(press)
                .size(40.dp)
                .clip(CircleShape)
                .background(fill)
                .clickableNoRipple(interaction, onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = isSelected
                    // The glyph is the only visible identifier, so the name lives here.
                    contentDescription = label
                    if (modified) stateDescription = modifiedState
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (modified) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
    }
}

/** Stage icons, reusing the vector that best represents each stage's contents. */
internal fun stageIcon(stage: Stage) = when (stage) {
    Stage.LOOK -> SpectraIcons.Presets
    Stage.FILM -> SpectraIcons.Simulation
    Stage.PRINT -> SpectraIcons.Preflash
    Stage.SCAN -> SpectraIcons.Glare
    Stage.FINISH -> SpectraIcons.ToneCurve
}

@Composable
internal fun CategoryItem(
    category: Category,
    selected: Boolean,
    modified: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Local copy: inside `semantics {}` the receiver's `selected` property would shadow
    // the parameter.
    val isSelected = selected
    // The dot is colour-only on screen, so it has to exist in the a11y tree too.
    val modifiedState = stringResource(R.string.editor_category_modified)
    TextTooltip(categoryHint(category)) {
    Column(
        Modifier
            .width(72.dp)
            .scale(if (pressed) 0.92f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickableNoRipple(interaction, onClick)
            // One tab node: icon + label merged, selection exposed (not colour-only).
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = isSelected
                if (modified) stateDescription = modifiedState
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = categoryIcon(category),
                // Decorative: the visible label text below carries the name.
                contentDescription = null,
                tint = if (selected) accent else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(24.dp),
            )
            // "This category is doing something to the photograph." The chips encoded
            // only selection before, so the one thing a 14-entry bar most needed to
            // say -- which groups are live -- was the one thing it never said.
            if (modified) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-3).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
        Text(
            stringResource(category.labelRes),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) accent else Color.White.copy(alpha = 0.78f),
        )
        // sliding pill indicator
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(width = if (selected) 20.dp else 0.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) accent else Color.Transparent),
        )
    }
    }
}

/** One-line tooltip description for each editor category. */
@Composable
internal fun categoryHint(c: Category) = stringResource(
    when (c) {
        Category.SOURCE -> R.string.editor_hint_source
        Category.PRESETS -> R.string.editor_hint_presets
        Category.SIMULATION -> R.string.editor_hint_simulation
        Category.INPUT -> R.string.editor_hint_input
        Category.RAW_WB -> R.string.editor_hint_raw_wb
        Category.GRAIN -> R.string.editor_hint_grain
        Category.HALATION -> R.string.editor_hint_halation
        Category.GLARE -> R.string.editor_hint_glare
        Category.COUPLERS -> R.string.editor_hint_couplers
        Category.PREFLASH -> R.string.editor_hint_preflash
        Category.CONTRAST_MASK -> R.string.editor_hint_contrast_mask
        Category.EXPERIMENTAL -> R.string.editor_hint_experimental
        Category.TONE_CURVE -> R.string.editor_hint_tone_curve
        Category.MASKS -> R.string.editor_hint_masks
        Category.DISPLAY -> R.string.editor_hint_display
    },
)

internal fun categoryIcon(c: Category) = when (c) {
    Category.SIMULATION -> SpectraIcons.Simulation
    Category.INPUT -> SpectraIcons.Input
    Category.RAW_WB -> SpectraIcons.ImportRaw
    Category.GRAIN -> SpectraIcons.Grain
    Category.PREFLASH -> SpectraIcons.Preflash
    Category.CONTRAST_MASK -> SpectraIcons.ContrastMask
    Category.HALATION -> SpectraIcons.Halation
    Category.COUPLERS -> SpectraIcons.Couplers
    Category.GLARE -> SpectraIcons.Glare
    Category.EXPERIMENTAL -> SpectraIcons.Experimental
    Category.TONE_CURVE -> SpectraIcons.ToneCurve
    Category.MASKS -> SpectraIcons.Masks
    Category.DISPLAY -> SpectraIcons.Display
    Category.PRESETS -> SpectraIcons.Presets
    Category.SOURCE -> SpectraIcons.SourceImage
}

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
