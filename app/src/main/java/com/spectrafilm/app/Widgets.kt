/*
 * Spektrafilm for Android — UI widgets. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Self-contained Material 3 composables: rounded "preference" setting cards, a
 * collapsible section header, switch rows, sub-tabs, help sheets and connected
 * toggles.
 * Sliders live in Sliders.kt, dropdowns in Dropdowns.kt and the auto-exposure
 * control in AutoExposureControl.kt (#273).
 */
package com.spectrafilm.app

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What a slider is currently reading, for the floating readout over the preview. */
data class SliderReadout(
    val label: String,
    val value: String,
    /** Set when the readout may offer "tap to type an exact value" once the drag ends. */
    val edit: SliderReadoutEdit? = null,
)

/** What tapping the floating readout needs to set the slider it came from. */
class SliderReadoutEdit(
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val decimals: Int,
    val current: Float,
    /** False once the slider is gone or shows another value: the edit is stale. */
    val isCurrent: () -> Boolean,
    /**
     * The control's value now, or null once it has left the screen. Lets a drag on the photo
     * keep adjusting the last control after its own drag ended (PhotoScrub).
     */
    val live: () -> Float? = { null },
    /**
     * Whether the control is still on screen, as State, one object per control: the editor
     * observes it so a photo drag is only offered while there is a control to adjust.
     */
    val alive: androidx.compose.runtime.State<Boolean>? = null,
    /** Moves the value without the release step [set] runs; [set] still ends the move. */
    val move: ((Float) -> Unit)? = null,
    val set: (Float) -> Unit,
) {
    /**
     * This edit as it would read had the control's own drag ended at [value]: current and
     * staleness follow [value], everything else is shared.
     */
    fun rebased(value: Float): SliderReadoutEdit {
        val tol = maxOf(step / 2f, (range.endInclusive - range.start) * 1e-4f)
        return SliderReadoutEdit(
            range, step, decimals, value,
            isCurrent = { live()?.let { kotlin.math.abs(it - value) <= tol } == true },
            live = live,
            alive = alive,
            move = move,
            set = set,
        )
    }
}

/**
 * Reports slider drag begin/end to the editor (Lightroom's ICBSliderTrackingBegin/End): [onChange]
 * fires on each drag frame, [onFinished] on release. The editor uses this to render a fast live
 * DRAFT only while a slider is actively dragged, then the crisp full pass on release — so a discrete
 * edit (switch/dropdown) skips the draft and goes straight to the crisp render. Provided via
 * [LocalSliderInteraction]; the default is a no-op so a slider still works with no provider.
 *
 * [onChange] also carries the value being set. The editor fades the panel almost away during a
 * drag so the photograph underneath is visible — which is only usable if the number moves
 * somewhere you can still see it, so it floats over the preview instead. Every slider in the app
 * funnels through EnhancedSlider, so reporting it there covers all of them.
 */
class SliderInteraction(
    val onChange: (SliderReadout?) -> Unit = {},
    val onFinished: () -> Unit = {},
    /**
     * A second finger came down on (true) or lifted from (false) a slider row. The editor uses
     * it to peek at clipping on the preview while the first finger keeps adjusting.
     */
    val onMultiTouch: (Boolean) -> Unit = {},
)

/**
 * Report when two or more pointers are down inside this element, without consuming anything:
 * the slider underneath keeps tracking its first finger exactly as before. Observed in the
 * Initial pass so a child that consumes the drag cannot hide the second finger from us.
 */
internal fun Modifier.multiTouchPeek(onMultiTouch: (Boolean) -> Unit): Modifier =
    pointerInput(onMultiTouch) {
        awaitPointerEventScope {
            var active = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val down = event.changes.count { it.pressed }
                    val now = down >= 2
                    if (now != active) {
                        active = now
                        onMultiTouch(now)
                    }
                }
            } finally {
                // Leaving composition mid-gesture must not leave the peek stuck on.
                if (active) onMultiTouch(false)
            }
        }
    }

/** CompositionLocal carrying the active [SliderInteraction]; defaults to a no-op. */
val LocalSliderInteraction = staticCompositionLocalOf { SliderInteraction() }

/**
 * Wraps arbitrary [content] in a Material3 [TooltipBox] that surfaces [text] on
 * long-press (and hover, on devices that report it). Reusable so every clickable
 * control in the editor can expose its purpose without re-plumbing the boilerplate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
        modifier = modifier,
    ) { content() }
}

/**
 * A collapsible section: a rounded card with a header row
 * (title + optional enabled-switch + chevron) that expands/collapses its content.
 */
@Composable
fun SectionCard(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabledSwitch: Boolean? = null,
    onEnabledChange: ((Boolean) -> Unit)? = null,
    help: ParamHelp? = null,
    content: @Composable () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    if (LocalFlatSections.current) {
        FlatSection(title, modifier, enabledSwitch, onEnabledChange, help, onHelp = { showHelp = true }, content)
        if (help != null && showHelp) {
            HelpSheet(help) { showHelp = false }
        }
        return
    }
    // Editor panels sit on surfaceContainer, so a nested group steps up one container level.
    ContentCard(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        padded = false,
    ) {
        val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
        // The header is one TalkBack node: "<title>, Expanded/Collapsed, heading, button".
        // The help badge and the enable switch stay separate, individually focusable nodes.
        val expandedState = stringResource(
            if (expanded) R.string.widget_state_expanded else R.string.widget_state_collapsed,
        )
        val toggleLabel = stringResource(
            if (expanded) R.string.widget_section_collapse else R.string.widget_section_expand,
        )
        val enableDescription = stringResource(R.string.widget_section_enable_switch, title)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // A state layer on press, clipped by the card (m3-design-language,
                // foundations: every interactive element shows press feedback).
                .clickable(onClickLabel = toggleLabel, role = Role.Button) {
                    onExpandedChange(!expanded)
                }
                .semantics {
                    heading()
                    stateDescription = expandedState
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (help != null) {
                HelpBadge(title) { showHelp = true }
                Spacer(Modifier.width(8.dp))
            }
            if (enabledSwitch != null && onEnabledChange != null) {
                Switch(
                    checked = enabledSwitch,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics { contentDescription = enableDescription },
                )
                Spacer(Modifier.width(8.dp))
            }
            Chevron(modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
    if (help != null && showHelp) {
        HelpSheet(help) { showHelp = false }
    }
}

/**
 * True inside an editor tool sheet or side pane, whose header already names the open group
 * (its tab, or its title). There a [SectionCard] at the top of the panel would be the third
 * copy of that name, in a card whose only content is the whole panel and whose chevron could
 * collapse the panel to nothing. m3-design-language, containment / Cards: "Don't force
 * content into cards when spacing, headers or dividers would do."
 *
 * Only the OUTERMOST section flattens: [FlatSection] resets this to false for its content, so a
 * collapsible group nested inside a panel keeps its card.
 */
internal val LocalFlatSections = staticCompositionLocalOf { false }

/**
 * [SectionCard] inside a sheet: no container, no title, no chevron, content always shown.
 * What the card header carried besides its title survives as a row: the group's on/off
 * switch becomes a labelled switch row (m3-design-language, Switches: "always paired with
 * an inline label"; whole row toggles), and the help badge sits at its end.
 */
@Composable
private fun FlatSection(
    title: String,
    modifier: Modifier,
    enabledSwitch: Boolean?,
    onEnabledChange: ((Boolean) -> Unit)?,
    help: ParamHelp?,
    onHelp: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val hasSwitch = enabledSwitch != null && onEnabledChange != null
        if (hasSwitch || help != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(
                        if (hasSwitch) {
                            Modifier.toggleable(
                                value = enabledSwitch == true,
                                role = Role.Switch,
                                onValueChange = { onEnabledChange?.invoke(it) },
                            )
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasSwitch) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (help != null) {
                    HelpBadge(title, onHelp)
                    if (hasSwitch) Spacer(Modifier.width(8.dp))
                }
                if (hasSwitch) {
                    // The row is the toggle (role Switch, labelled by the title); the Switch
                    // itself only draws, so TalkBack hears one control, not two.
                    Switch(checked = enabledSwitch == true, onCheckedChange = null)
                }
            }
        }
        CompositionLocalProvider(LocalFlatSections provides false) { content() }
    }
}

/**
 * A small "?" affordance for a [SectionCard] header, drawn with [SpectraIcons.Help]; a long-press
 * tooltip + semantics label name the section so it is discoverable and accessible. [onClick] opens
 * the section's [HelpSheet].
 *
 * The vector already contains its own circle, so this draws no border of its own — it used to
 * draw a `border` ring around a typographic "?", which meant the ring's weight came from a
 * BorderStroke and the glyph's from the type scale, and neither matched the icon set.
 */
@Composable
private fun HelpBadge(title: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.widget_help_badge_description, title)
    val showHelpLabel = stringResource(R.string.widget_help_badge_action)
    TextTooltip(stringResource(R.string.widget_help_badge_tooltip, title)) {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(26.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = showHelpLabel, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SpectraIcons.Help,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * A bottom-sheet "help sheet" carrying a control's plain-language [ParamHelp]: a headline, a
 * one-line summary and a fuller explanation, with the GPLv3 spektrafilm attribution. Content is
 * scrollable so long bodies fit on short screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(help: ParamHelp, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(help.title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(help.summary),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(help.body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.help_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * A disclosure control that splits a dense section into a "basic" set (always shown) and an
 * "advanced" set (revealed on demand). Pure presentation: the hidden controls keep their state and
 * the engine still receives every param, so toggling this changes nothing about the render.
 */
@Composable
fun AdvancedToggle(advanced: Boolean, onToggle: (Boolean) -> Unit) {
    // The same chevron, and the same rotation, as SectionCard's own disclosure — this is the
    // second kind of "there is more below" control in the editor and it read as a plain
    // sentence, with nothing to say it opened anything.
    val rotation by animateFloatAsState(if (advanced) 180f else 0f, label = "advancedChevron")
    TextButton(
        onClick = { onToggle(!advanced) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (advanced) R.string.widget_advanced_hide else R.string.widget_advanced_show,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Chevron(modifier = Modifier.rotate(rotation).size(18.dp))
    }
}

/** A simple downward chevron drawn on a Canvas (avoids a material-icons dependency). */
@Composable
private fun Chevron(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawLine(color, Offset(w * 0.30f, h * 0.40f), Offset(w * 0.50f, h * 0.62f),
            strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.50f, h * 0.62f), Offset(w * 0.70f, h * 0.40f),
            strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** A switch row (label + helper tooltip + trailing switch). */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tooltip: String? = null,
) {
    // The whole row toggles and is ONE TalkBack node (label + tooltip + state); the inner Switch
    // is display-only (onCheckedChange = null), so the row keeps its own >= 48 dp height.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (tooltip != null) {
                Text(
                    tooltip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A one-of-N switcher inside a tool panel (tone-curve channel, mask 1/2/3...), drawn as an M3
 * connected button group: m3-design-language, components-actions "connected button group for
 * view/option switching" (it replaced segmented buttons and pill tabs). Segments hug their
 * labels and the row scrolls when there are more than fit; selection is owned by the caller.
 */
@Composable
fun SubTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CONNECTED_GAP),
    ) {
        tabs.forEachIndexed { i, label ->
            ConnectedToggle(
                label = label,
                selected = i == selected,
                shape = connectedShape(i, tabs.size),
                onClick = { onSelect(i) },
                modifier = Modifier.widthIn(min = 56.dp),
            )
        }
    }
}

/**
 * The app's card: an M3 filled card on [MaterialTheme.colorScheme.surfaceContainer] with
 * 16dp corners (the same outer radius as the grouped Settings and Photo info lists, so every
 * contained block in the app reads as one family). [padded] = false leaves the inside to the
 * caller (a full-bleed clickable header). Pass [color] = surfaceContainerHigh inside a sheet,
 * whose own background is already surfaceContainerLow.
 */
@Composable
internal fun ContentCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    padded: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        color = color,
    ) {
        Column(
            if (padded) Modifier.fillMaxWidth().padding(16.dp) else Modifier.fillMaxWidth(),
            verticalArrangement = if (padded) Arrangement.spacedBy(8.dp) else Arrangement.Top,
            content = content,
        )
    }
}

internal val CARD_SHAPE = RoundedCornerShape(16.dp)

/**
 * A small "not yet active" badge + helper line for parameters that are present in the
 * UI (forward-compatible) but have no engine effect yet. Wrap the inert controls in a
 * [GatedBlock] to dim them and append this note, so users aren't misled (repo issue #6).
 */
@Composable
fun NotYetActiveNote(
    detail: String = stringResource(R.string.widget_not_yet_active_detail),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Text(
                stringResource(R.string.widget_not_yet_active_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Visually gates [content] for parameters that don't do anything yet: shows a
 * [NotYetActiveNote] header and dims the controls (still interactive, so values are
 * retained/forward-compatible, but clearly marked as inert).
 */
@Composable
fun GatedBlock(
    note: String = stringResource(R.string.widget_not_yet_active_detail),
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        NotYetActiveNote(note)
        Spacer(Modifier.width(0.dp))
        Column(
            modifier = Modifier.fillMaxWidth().alpha(0.55f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

/**
 * The app's indeterminate wait indicator: Material 3 Expressive's shape-morphing
 * [LoadingIndicator] (#294) in place of the plain circular spinner, for waits that fill a
 * screen or a canvas (startup, the export render, the magnifier, curve loading). Inline
 * waits inside a button keep the small circular spinner, which fits a text line.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpectraLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    androidx.compose.material3.LoadingIndicator(modifier = modifier, color = color)
}

/**
 * #466: the in-flight export indicator for the Export button / FAB. Determinate (the M3
 * Expressive loading indicator morphing with the stage-weighted fraction, ExportProgress.kt)
 * once the running export has reported a stage; the indeterminate indicator before that and
 * for exports that report no stages. Reads the process-owned runtime directly, like the
 * foreground notification, so both always show the same number.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExportProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val fraction by ExportWorkRuntime.progress.collectAsStateWithLifecycle()
    if (fraction > 0f) {
        androidx.compose.material3.LoadingIndicator(progress = { fraction }, modifier = modifier, color = color)
    } else {
        SpectraLoadingIndicator(modifier, color)
    }
}

/** Gap between the segments of an M3 connected button group. */
internal val CONNECTED_GAP = 2.dp
private val CONNECTED_OUTER = 20.dp // full: half of the 40dp segment height
private val CONNECTED_INNER = 8.dp // M3 small connected-group inner corner

/**
 * Shape of segment [index] of [count] in an M3 connected button group (the replacement for
 * segmented buttons): full outer corners on the ends, small inner corners between segments.
 * Lay segments out in a Row with `Arrangement.spacedBy(CONNECTED_GAP)` and give the Row
 * `semantics { selectableGroup() }`.
 */
internal fun connectedShape(index: Int, count: Int): RoundedCornerShape {
    val start = if (index == 0) CONNECTED_OUTER else CONNECTED_INNER
    val end = if (index == count - 1) CONNECTED_OUTER else CONNECTED_INNER
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}

/**
 * One single-select segment of an M3 connected button group: 40dp tall inside a 48dp touch
 * target, radio role. Theme colours by default (selected = primary, otherwise
 * surfaceContainerHighest); pass explicit colours on a fixed-dark surface such as crop.
 */
@Composable
internal fun ConnectedToggle(
    label: String,
    selected: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedContainer: Color = MaterialTheme.colorScheme.primary,
    selectedContent: Color = MaterialTheme.colorScheme.onPrimary,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .widthIn(min = 48.dp)
                .clip(shape)
                .background(if (selected) selectedContainer else container)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) selectedContent else content,
                maxLines = 1,
                // Ellipsize rather than cut the glyphs off mid-letter (#456).
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
