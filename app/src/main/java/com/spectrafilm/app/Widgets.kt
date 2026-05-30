/*
 * SpectraFilm for Android — UI widgets. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Self-contained Material3 composables styled after Image Toolbox: rounded
 * "preference" setting cards, a collapsible section header, an enhanced slider
 * with an inline value label, RGB-triple / pair controls, switch rows and enum
 * dropdowns. No external dependency on Image Toolbox.
 */
package com.spectrafilm.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

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
 * An [IconButton] that shows a long-press tooltip carrying [tooltip] and uses the
 * same string as the icon's contentDescription. Drop-in replacement for the
 * repetitive IconButton + Icon + TooltipBox pattern across the editor chrome.
 *
 * [tint] defaults to the inherited content colour so it works on both the dark
 * preview scrim (pass Color.White) and on tonal surfaces (omit for the default).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    contentDescription: String = tooltip,
) {
    TextTooltip(text = tooltip) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
            )
        }
    }
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
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableNoRipple { onExpandedChange(!expanded) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (enabledSwitch != null && onEnabledChange != null) {
                Switch(checked = enabledSwitch, onCheckedChange = onEnabledChange)
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
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickableImpl(onClick),
    )

@Composable
private fun Modifier.clickableImpl(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        ),
    )
}

/**
 * An enhanced single-value slider: a labelled row with the current value shown in a
 * pill, snapping to [step] and clamped to [range]. [tooltip] is rendered as helper text.
 */
@Composable
fun EnhancedSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    decimals: Int = 2,
    tooltip: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ValuePill(formatValue(value, decimals))
        }
        val steps = if (step > 0f) {
            (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
        } else 0
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onValueChange(snap(it, range, step)) },
            valueRange = range,
            steps = steps,
        )
        if (tooltip != null) {
            Text(
                tooltip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ValuePill(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** A linked RGB-triple control: a label plus three sliders sharing one range/step. */
@Composable
fun TripleSlider(
    label: String,
    value: Triple<Float, Float, Float>,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Triple<Float, Float, Float>) -> Unit,
    step: Float = 0f,
    decimals: Int = 2,
    tooltip: String? = null,
    componentLabels: Triple<String, String, String> = Triple("R", "G", "B"),
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (tooltip != null) {
            Text(
                tooltip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EnhancedSlider(componentLabels.first, value.first, range, step = step, decimals = decimals,
            onValueChange = { onValueChange(value.copy(first = it)) })
        EnhancedSlider(componentLabels.second, value.second, range, step = step, decimals = decimals,
            onValueChange = { onValueChange(value.copy(second = it)) })
        EnhancedSlider(componentLabels.third, value.third, range, step = step, decimals = decimals,
            onValueChange = { onValueChange(value.copy(third = it)) })
    }
}

/** A linked pair control: a label plus two sliders. */
@Composable
fun PairSlider(
    label: String,
    value: Pair<Float, Float>,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Pair<Float, Float>) -> Unit,
    step: Float = 0f,
    decimals: Int = 2,
    tooltip: String? = null,
    componentLabels: Pair<String, String> = "1" to "2",
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (tooltip != null) {
            Text(
                tooltip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EnhancedSlider(componentLabels.first, value.first, range, step = step, decimals = decimals,
            onValueChange = { onValueChange(value.copy(first = it)) })
        EnhancedSlider(componentLabels.second, value.second, range, step = step, decimals = decimals,
            onValueChange = { onValueChange(value.copy(second = it)) })
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** An integer slider. */
@Composable
fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    tooltip: String? = null,
) {
    EnhancedSlider(
        label = label,
        value = value.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        step = 1f,
        decimals = 0,
        tooltip = tooltip,
        onValueChange = { onValueChange(it.roundToInt()) },
    )
}

/** A read-only exposed dropdown for a list of options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    selected: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = display(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(display(opt)) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

/** One selectable option in a grouped dropdown: a stable id plus its display label. */
data class DropdownOption(val id: String, val label: String)

/** A titled group of [DropdownOption]s (e.g. a stock category) for a grouped dropdown. */
data class DropdownGroup(val title: String, val options: List<DropdownOption>)

/** Convert StockCatalog [ProfileOption]s into ordered [DropdownGroup]s, preserving order. */
fun List<ProfileOption>.toGroups(): List<DropdownGroup> {
    val byGroup = LinkedHashMap<String, MutableList<DropdownOption>>()
    for (o in this) byGroup.getOrPut(o.groupTitle) { mutableListOf() }.add(DropdownOption(o.id, o.label))
    return byGroup.map { (title, opts) -> DropdownGroup(title, opts) }
}

/**
 * A read-only exposed dropdown whose options are organized under non-selectable group
 * headers. The field shows the label of the currently selected id (falling back to the
 * raw id when it isn't among the options). Used for built-in presets and for the
 * catalog-grouped film/print profile pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedDropdown(
    label: String,
    selectedId: String,
    groups: List<DropdownGroup>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = remember(selectedId, groups) {
        groups.firstNotNullOfOrNull { g -> g.options.firstOrNull { it.id == selectedId }?.label } ?: selectedId
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groups.forEachIndexed { gi, group ->
                if (group.title.isNotBlank()) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = if (gi == 0) 8.dp else 12.dp, bottom = 4.dp),
                    )
                }
                group.options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.label) },
                        onClick = { onSelect(opt.id); expanded = false },
                    )
                }
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
    detail: String = "These controls are wired for a future engine update and have no effect yet.",
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Text(
                "not yet active",
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
    note: String = "These controls are wired for a future engine update and have no effect yet.",
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

private fun snap(v: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (step <= 0f) return v.coerceIn(range.start, range.endInclusive)
    val snapped = range.start + (((v - range.start) / step).roundToInt()) * step
    return snapped.coerceIn(range.start, range.endInclusive)
}

private fun formatValue(v: Float, decimals: Int): String =
    if (decimals <= 0) v.roundToInt().toString() else "%.${decimals}f".format(v)

// ---------------------------------------------------------------------------
// AutoExposureControl — Lightroom-mobile style expandable metering control
// ---------------------------------------------------------------------------

/**
 * Converts a snake_case method id to a human-readable Title Case label.
 * e.g. "center_weighted" -> "Center Weighted"
 */
private fun meteringMethodLabel(id: String): String =
    id.split('_').joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercaseChar() }
    }

/**
 * A small drag-handle bar drawn via Canvas — the grabber affordance at the
 * top of the expanded metering list.
 */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(modifier = modifier.size(width = 32.dp, height = 4.dp)) {
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
    }
}

/**
 * Adaptive popup position provider for the metering-method panel.
 *
 * Placement logic:
 *   - If there is enough room ABOVE the anchor (anchorBounds.top >= popupContentSize.height),
 *     place the panel so its bottom edge meets the anchor's top edge (grows upward).
 *   - Otherwise, place the panel so its top edge meets the anchor's bottom edge (grows downward).
 *   - Horizontally, align the panel's left edge to the anchor's left edge.
 *   - Both x and y are clamped to stay fully within the window bounds.
 *
 * The [placeAbove] callback lets the composable know which direction was chosen so
 * the drag-handle can be positioned on the correct edge.
 */
private class MeteringPopupPositionProvider(
    private val onPlacedAbove: (Boolean) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val placeAbove = anchorBounds.top >= popupContentSize.height
        onPlacedAbove(placeAbove)

        val y = if (placeAbove) {
            // Bottom of popup aligns with top of anchor.
            anchorBounds.top - popupContentSize.height
        } else {
            // Top of popup aligns with bottom of anchor.
            anchorBounds.bottom
        }

        val x = anchorBounds.left

        // Clamp so the panel never goes off-screen.
        val clampedX = x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val clampedY = y.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))

        return IntOffset(clampedX, clampedY)
    }
}

/**
 * Lightroom-mobile-style expandable auto-exposure / metering-method control.
 *
 * Collapsed state: a single "Auto" button (OutlinedButton when off, filled Button
 * showing the active method name when on).
 *
 * Expanded state: an elevated Surface rendered in a [Popup] anchored to the Auto
 * button. The popup positions itself ABOVE the button when there is room, or BELOW
 * when the button is near the top of the screen (adaptive vertical anchoring).
 * Tapping outside the popup or pressing Back dismisses it (focusable = true).
 * A drag handle and a swipe gesture also collapse it. Swiping up on the
 * (collapsed) button expands it.
 *
 * State ownership: [autoExposure] / [autoExposureMethod] are owned by the caller
 * (ParamsState fields); expand/collapse is local [remember] state.
 */
@Composable
fun AutoExposureControl(
    autoExposure: Boolean,
    autoExposureMethod: String,
    methods: List<String>,
    onAutoExposureChange: (Boolean) -> Unit,
    onMethodChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // Whether the popup was last placed above the button (drives drag direction logic).
    var popupIsAbove by remember { mutableStateOf(true) }

    // Accumulated vertical drag on the button (swipe-up to expand).
    var buttonDragAccum by remember { mutableFloatStateOf(0f) }
    // Accumulated vertical drag on the list panel (swipe-down to collapse).
    var listDragAccum by remember { mutableFloatStateOf(0f) }
    // Threshold (px) for swipe recognition — ~40dp worth at most densities.
    val swipeThresholdPx = 80f

    // Human-readable label for the current method.
    val currentLabel = meteringMethodLabel(autoExposureMethod)

    val collapse = { expanded = false }

    // The popup position provider is remembered so it is stable across recompositions.
    val positionProvider = remember {
        MeteringPopupPositionProvider(onPlacedAbove = { popupIsAbove = it })
    }

    // ----- Popup containing the metering-method panel -----
    // Placed outside the normal layout flow; the anchor Box below provides the bounds.
    // focusable = true enables tap-outside-to-dismiss and back-press dismissal.
    if (expanded) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = collapse,
            properties = PopupProperties(focusable = true),
        ) {
            // Animate on first appearance: slide in from the direction of the button.
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    // If above → slide up (negative = from below the panel's final position).
                    // If below → slide down (positive).
                    initialOffsetY = { fullHeight ->
                        if (popupIsAbove) fullHeight else -fullHeight
                    },
                    animationSpec = tween(220),
                ) + fadeIn(animationSpec = tween(220)),
            ) {
                Surface(
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        // Swipe gesture on the panel to collapse.
                        // When above: swipe DOWN (positive dragAmount) collapses.
                        // When below: swipe UP (negative dragAmount) collapses.
                        .pointerInput(popupIsAbove) {
                            detectVerticalDragGestures(
                                onDragStart = { listDragAccum = 0f },
                                onDragEnd = { listDragAccum = 0f },
                                onDragCancel = { listDragAccum = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    listDragAccum += dragAmount
                                    val shouldCollapse = if (popupIsAbove) {
                                        listDragAccum > swipeThresholdPx   // swipe down
                                    } else {
                                        listDragAccum < -swipeThresholdPx  // swipe up
                                    }
                                    if (shouldCollapse) {
                                        expanded = false
                                        listDragAccum = 0f
                                    }
                                },
                            )
                        },
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier.wrapContentSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        // Drag handle — placed on the edge nearest the button:
                        //   • panel above button → handle at the BOTTOM of the panel.
                        //   • panel below button → handle at the TOP of the panel.
                        // When above we render the main content first, then the handle.
                        if (!popupIsAbove) {
                            // Below: handle at top.
                            Box(
                                modifier = Modifier
                                    .wrapContentSize()
                                    .padding(top = 8.dp, bottom = 4.dp)
                                    .align(Alignment.CenterHorizontally),
                                contentAlignment = Alignment.Center,
                            ) {
                                DragHandle()
                            }
                        }

                        if (popupIsAbove) {
                            Text(
                                "Metering method",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        } else {
                            Text(
                                "Metering method",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // "Off / Manual" entry — always at the top.
                        MeteringMethodRow(
                            label = "Off / Manual",
                            isSelected = !autoExposure,
                            onClick = {
                                onAutoExposureChange(false)
                                expanded = false
                            },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        // All metering methods.
                        methods.forEachIndexed { index, method ->
                            MeteringMethodRow(
                                label = meteringMethodLabel(method),
                                isSelected = autoExposure && autoExposureMethod == method,
                                onClick = {
                                    onAutoExposureChange(true)
                                    onMethodChange(method)
                                    // Keep the list open so the user sees the selection.
                                    // Dismissed via swipe, tap-outside, or back-press.
                                },
                            )
                            if (index < methods.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                )
                            }
                        }

                        if (popupIsAbove) {
                            // Above: handle at bottom — nearest the button.
                            Box(
                                modifier = Modifier
                                    .wrapContentSize()
                                    .padding(top = 4.dp, bottom = 8.dp)
                                    .align(Alignment.CenterHorizontally),
                                contentAlignment = Alignment.Center,
                            ) {
                                DragHandle()
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    // ----- Collapsed "Auto" button (stays in normal layout flow) -----
    // This Box is the popup anchor — the Popup() sibling above is positioned relative to it.
    // Tap: if off → turn on and expand. If on → toggle list visibility.
    // Swipe up: expand the list.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { buttonDragAccum = 0f },
                    onDragEnd = { buttonDragAccum = 0f },
                    onDragCancel = { buttonDragAccum = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        buttonDragAccum += dragAmount
                        // Negative dragAmount = upward swipe → expand.
                        if (buttonDragAccum < -swipeThresholdPx) {
                            if (!autoExposure) onAutoExposureChange(true)
                            expanded = true
                            buttonDragAccum = 0f
                        }
                    },
                )
            },
    ) {
        TextTooltip(
            "Auto-exposure: tap to choose a metering pattern, or swipe up to expand. " +
                "When off, exposure is fully manual.",
        ) {
        if (autoExposure) {
            // ON: filled/accent button showing active method name.
            Button(
                onClick = {
                    // Tap while on: toggle the list panel.
                    expanded = !expanded
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    "Auto  ·  $currentLabel",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } else {
            // OFF: outlined button, subtitle "Manual exposure".
            Column(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        // Tap while off: turn on and expand.
                        onAutoExposureChange(true)
                        expanded = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Auto")
                }
                Text(
                    "Manual exposure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
        }
        }
    }
}

/**
 * A single row in the metering-method list: label + trailing checkmark when selected.
 */
@Composable
private fun MeteringMethodRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = if (isSelected) {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (isSelected) primaryColor else onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            // Checkmark drawn via Canvas — no material-icons dependency.
            val checkColor = primaryColor
            Canvas(modifier = Modifier.size(20.dp)) {
                val w = size.width
                val h = size.height
                val stroke = w * 0.10f
                drawLine(
                    color = checkColor,
                    start = Offset(w * 0.18f, h * 0.52f),
                    end = Offset(w * 0.42f, h * 0.76f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = checkColor,
                    start = Offset(w * 0.42f, h * 0.76f),
                    end = Offset(w * 0.82f, h * 0.28f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
