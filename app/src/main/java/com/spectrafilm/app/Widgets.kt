/*
 * Spektrafilm for Android — UI widgets. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Self-contained Material3 composables styled after Image Toolbox: rounded
 * "preference" setting cards, a collapsible section header, switch rows, sub-tabs,
 * help sheets and connected toggles. No external dependency on Image Toolbox.
 * Sliders live in Sliders.kt, dropdowns in Dropdowns.kt and the auto-exposure
 * control in AutoExposureControl.kt (#273).
 */
package com.spectrafilm.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp

/** What a slider is currently reading, for the floating readout over the preview. */
data class SliderReadout(val label: String, val value: String)

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
 * An Image-Toolbox-style collapsible section: a rounded card with a header row
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
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
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
                .clickableNoRipple(onClickLabel = toggleLabel, role = Role.Button) {
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

/** Convenience clickable without a ripple import dependency cost. */
@Composable
private fun Modifier.clickableNoRipple(
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier =
    this.then(
        Modifier.clickableImpl(onClickLabel, role, onClick),
    )

@Composable
private fun Modifier.clickableImpl(onClickLabel: String?, role: Role?, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        ),
    )
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
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
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
 * A compact, horizontally-scrolling sub-tab strip for splitting one tool panel into groups
 * (Lightroom-style, e.g. Film / Print / Scanner / Output). The selected tab is a filled
 * primary pill; selection state is owned by the caller.
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { i, label ->
            val active = i == selected
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .selectable(selected = active, role = Role.Tab) { onSelect(i) },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

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
            )
        }
    }
}
