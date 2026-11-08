/*
 * Spektrafilm for Android — slider controls. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * EnhancedSlider and its Pair/Triple/Int variants, fine-drag, the numeric-entry dialog and the
 * gesture-exclusion strips. Split out of Widgets.kt (#273).
 */
package com.spectrafilm.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity

/**
 * An enhanced single-value slider: a labelled row with the current value shown in a
 * pill, snapping to [step] and clamped to [range]. [tooltip] is rendered as helper text.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    default: Float? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val interaction = LocalSliderInteraction.current
    var editing by remember { mutableStateOf(false) }
    val formatted = formatValue(value, decimals)
    // Which detent zone the last drag frame was in, so the haptic tick fires on arrival only.
    val lastZone = remember { mutableStateOf(SliderZone.FREE) }
    val readoutEdit = rememberReadoutEdit(value, range, step, decimals, onValueChange, onValueChangeFinished)
    Column(modifier.fillMaxWidth().multiTouchPeek(interaction.onMultiTouch)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            // The value pill is interactive (Lightroom-style): single-tap to type an exact
            // value, double-tap to reset to the parameter's neutral default. Typing works on
            // every slider; reset is offered only when a [default] is supplied and the value
            // isn't already there. Both give a tick of haptic feedback. The double-tap has no
            // TalkBack equivalent, so the reset is also exposed as a custom accessibility action.
            // Marking a changed parameter is what makes the double-tap reset discoverable:
            // the marker appears exactly when the reset exists, so the two are one affordance.
            val modified = isModifiedFromDefault(value, default, range, step)
            val resetDefault = default?.takeIf { modified }
            val reset: (() -> Unit)? = resetDefault?.let { dv ->
                {
                    onValueChange(snap(dv, range, step))
                    onValueChangeFinished?.invoke()
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            val pillDescription = stringResource(
                if (modified) {
                    R.string.widget_value_pill_description_modified
                } else {
                    R.string.widget_value_pill_description
                },
                label, formatted,
            )
            val enterLabel = stringResource(R.string.widget_value_pill_enter_exact, label)
            val resetLabel = stringResource(R.string.widget_value_pill_reset_default, label)
            ValuePill(
                formatted,
                modified = modified,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    // Sideways drag = fine adjust. Declared before the click handling so the
                    // drag detector sees the pointer first; it waits for touch slop, so a tap
                    // (type a value) and a double-tap (reset) still reach combinedClickable.
                    .fineDragValue(
                        value = value,
                        range = range,
                        step = step,
                        onValueChange = {
                            onValueChange(it)
                            interaction.onChange(SliderReadout(label, formatValue(it, decimals), readoutEdit(it)))
                        },
                        onSettled = {
                            onValueChangeFinished?.invoke()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            interaction.onFinished()
                        },
                    )
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = enterLabel,
                        role = Role.Button,
                        onClick = { editing = true },
                        onDoubleClick = reset,
                    )
                    .semantics {
                        contentDescription = pillDescription
                        if (reset != null) {
                            customActions = listOf(CustomAccessibilityAction(resetLabel) { reset(); true })
                        }
                    },
            )
        }
        val steps = if (step > 0f) {
            (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
        } else 0
        val endExclusionPx = with(LocalDensity.current) { SLIDER_END_EXCLUSION.toPx() }
        // Lightroom-style tactile feedback: a light tick when a drag settles, so an
        // adjustment "lands" physically. Fired on release (not per-frame) to stay subtle.
        // The state-based Slider is the overload that takes a custom track. Its value is
        // driven from [value] every composition; with onValueChange set, a drag reports
        // through the callback instead of moving the state itself, so the parameter stays
        // the single source of truth (as with the value-based overload).
        val clamped = value.coerceIn(range.start, range.endInclusive)
        val sliderState = remember(range, steps) { SliderState(clamped, steps, range) }
        if (sliderState.value != clamped) sliderState.value = clamped
        Slider(
            state = sliderState,
            onValueChange = {
                // Report the SNAPPED value, not the raw track position: the readout has to say
                // what the engine will actually receive. Near the neutral default the value
                // sticks to it (a detent), so "back to zero" is a feel, not a hunt.
                val snapped = detent(snap(it, range, step), default?.let { d -> snap(d, range, step) }, range)
                val zone = detentZone(snapped, default?.let { d -> snap(d, range, step) }, range)
                // One tick on arriving at the default or at either end of the range.
                if (zone != SliderZone.FREE && zone != lastZone.value) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                lastZone.value = zone
                onValueChange(snapped)
                interaction.onChange(SliderReadout(label, formatValue(snapped, decimals), readoutEdit(snapped)))
            },
            onValueChangeFinished = {
                onValueChangeFinished?.invoke()
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                interaction.onFinished()
            },
            // M3 Expressive track: the fill grows from the parameter's neutral point, so a
            // +/- control (exposure, tint, saturation) reads as "how far from neutral and in
            // which direction" instead of a bar that is half full at zero.
            track = {
                AnchoredSliderTrack(
                    value = clamped,
                    range = range,
                    anchor = sliderAnchor(default, range),
                )
            },
            // Announce "<label>, <value>" rather than a bare percentage. The semantics come
            // FIRST and MERGING: a node's (touch) bounds come from its outermost merging
            // semantics modifier, and the slider's own progressSemantics merges, so a plain
            // semantics block here still reported 44dp. Placed outside heightIn, this one
            // reports the 48dp target. The M3 Expressive slider itself
            // lays out at its 44dp thumb height (#455: measured 44.2dp on device); its drag
            // input already gets Compose's 48dp minimum touch area, this makes the
            // accessibility node say so too. The extra height is centred: the track stays put.
            modifier = Modifier
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = formatted
                }
                .heightIn(min = 48.dp)
                .sliderEndsGestureExclusion(endExclusionPx),
        )
        if (tooltip != null) {
            Text(
                tooltip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (editing) {
            NumericEntryDialog(
                label = label,
                initial = formatValue(value, decimals),
                range = range,
                step = step,
                decimals = decimals,
                onDismiss = { editing = false },
                onConfirm = {
                    onValueChange(it)
                    onValueChangeFinished?.invoke()
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                },
            )
        }
    }
}

/**
 * The value readout beside a slider's label.
 *
 * [modified] marks a parameter sitting away from its neutral default. The rail already shows
 * which CATEGORY holds edits, but inside a twenty-slider panel like Couplers or Grain there
 * was nothing at the parameter tier — "which of these have I touched" was unanswerable
 * without knowing every neutral by heart.
 *
 * The marker is a fill swap plus a hairline border, NOT a size or padding change: the pill
 * sits in a Row with the label, and a pill that changed width when a value left its default
 * would shift the label on every edit.
 */
@Composable
private fun ValuePill(text: String, modifier: Modifier = Modifier, modified: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (modified) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        border = if (modified) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (modified) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
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
    componentLabels: Triple<String, String, String> = Triple(
        stringResource(R.string.widget_component_r),
        stringResource(R.string.widget_component_g),
        stringResource(R.string.widget_component_b),
    ),
    default: Triple<Float, Float, Float>? = null,
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
            default = default?.first, onValueChange = { onValueChange(value.copy(first = it)) })
        EnhancedSlider(componentLabels.second, value.second, range, step = step, decimals = decimals,
            default = default?.second, onValueChange = { onValueChange(value.copy(second = it)) })
        EnhancedSlider(componentLabels.third, value.third, range, step = step, decimals = decimals,
            default = default?.third, onValueChange = { onValueChange(value.copy(third = it)) })
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
    componentLabels: Pair<String, String> =
        stringResource(R.string.widget_component_1) to stringResource(R.string.widget_component_2),
    default: Pair<Float, Float>? = null,
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
            default = default?.first, onValueChange = { onValueChange(value.copy(first = it)) })
        EnhancedSlider(componentLabels.second, value.second, range, step = step, decimals = decimals,
            default = default?.second, onValueChange = { onValueChange(value.copy(second = it)) })
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
    default: Int? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    EnhancedSlider(
        label = label,
        value = value.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        step = 1f,
        decimals = 0,
        tooltip = tooltip,
        default = default?.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        onValueChangeFinished = onValueChangeFinished,
    )
}

/**
 * Drag the value pill sideways to adjust at a tenth of the slider's sensitivity.
 *
 * A slider track is roughly 320dp wide and spans the whole range, so one finger resolves
 * about 1/300 of that range at best -- a fingertip cannot be placed more precisely than the
 * 48dp target Material specifies exists for it. Several parameters here need finer than
 * that, and the only existing answer was to tap the pill and type a number, which is exact
 * but breaks the loop of watching the photograph while you adjust.
 *
 * This maps a full 320dp drag to a TENTH of the range, so ~32dp of travel is 1% of the
 * parameter -- fine enough to nudge, coarse enough to stay usable. It is deliberately on
 * the PILL rather than the track: the track keeps its familiar absolute behaviour, and the
 * drag detector waits for touch slop, so tap-to-type and double-tap-to-reset still work.
 *
 * TalkBack users are not served by a drag; numeric entry is already the exact path for
 * them, and it stays.
 */
/**
 * Is this parameter away from its neutral default?
 *
 * Compared through [snap], not with a bare `!=`. Every interactive write path already snaps,
 * so a value the user set round-trips exactly — but a value DECODED from a preset or a
 * restored session does not have to, and a default that lands one ULP away would leave a
 * parameter marked as changed forever with no way to clear it. Snapping both sides puts the
 * comparison on the grid the control actually works in.
 *
 * A parameter with no declared default is never marked: absence of a neutral is not evidence
 * of neutrality.
 */
internal fun isModifiedFromDefault(
    value: Float,
    default: Float?,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
): Boolean {
    if (default == null) return false
    return snap(default, range, step) != snap(value, range, step)
}

@Composable
private fun Modifier.fineDragValue(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    onSettled: () -> Unit,
): Modifier {
    val density = LocalDensity.current
    val span = range.endInclusive - range.start
    // Accumulate in value space so sub-step drags are not lost to repeated rounding.
    val pending = remember { mutableFloatStateOf(0f) }
    // Where the drag started, so a CANCELLED drag can be put back.
    val origin = remember { mutableFloatStateOf(0f) }
    val latest = rememberUpdatedState(value)
    // The gesture block below outlives recompositions (it restarts only on range, step or
    // density), so read the callbacks through state: a caller that swaps its lambdas, such
    // as a settings slider whose draft state is replaced after a save, must get the new ones.
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestOnSettled = rememberUpdatedState(onSettled)
    return this.pointerInput(range, step, density) {
        if (span <= 0f) return@pointerInput
        detectHorizontalDragGestures(
            onDragStart = {
                pending.floatValue = latest.value
                origin.floatValue = latest.value
            },
            onDragEnd = { latestOnSettled.value() },
            // A cancel means something else took the gesture — most often the panel's own
            // vertical scroll claiming a diagonal drag. The value has already been moved by
            // every frame up to that point, so committing it silently sets a parameter the
            // user was not adjusting. Put it back.
            onDragCancel = {
                latestOnValueChange.value(snap(origin.floatValue, range, step))
                latestOnSettled.value()
            },
        ) { change, dragAmount ->
            change.consume()
            val trackPx = with(density) { FINE_DRAG_REFERENCE_TRACK.toPx() }
            pending.floatValue = fineDragAdvance(
                pending.floatValue, dragAmount, span, trackPx, range,
            )
            latestOnValueChange.value(snap(pending.floatValue, range, step))
        }
    }
}

/** The track width the fine-drag sensitivity is defined against; see [fineDragAdvance]. */
internal val FINE_DRAG_REFERENCE_TRACK = 320.dp

/** Divisor on slider sensitivity for a fine drag: a full track travel moves a tenth. */
internal const val FINE_DRAG_DIVISOR = 10f

/**
 * Advance an accumulated fine-drag value by [dragPx] of horizontal travel.
 *
 * Pure so the sensitivity contract can be asserted without a device: dragging one reference
 * track width ([FINE_DRAG_REFERENCE_TRACK]) moves the value by span / [FINE_DRAG_DIVISOR].
 * Accumulating in value space (rather than re-deriving from the snapped result each frame)
 * is what stops sub-step drags being rounded away to nothing on every event.
 */
internal fun fineDragAdvance(
    current: Float,
    dragPx: Float,
    span: Float,
    trackPx: Float,
    range: ClosedFloatingPointRange<Float>,
): Float {
    if (span <= 0f || trackPx <= 0f) return current.coerceIn(range.start, range.endInclusive)
    val perPx = span / FINE_DRAG_DIVISOR / trackPx
    return (current + dragPx * perPx).coerceIn(range.start, range.endInclusive)
}

internal fun snap(v: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (step <= 0f) return v.coerceIn(range.start, range.endInclusive)
    val snapped = range.start + (((v - range.start) / step).roundToInt()) * step
    return snapped.coerceIn(range.start, range.endInclusive)
}

internal fun formatValue(v: Float, decimals: Int): String =
    if (decimals <= 0) v.roundToInt().toString() else "%.${decimals}f".format(v)

/**
 * Parse a user-typed slider value into a snapped, in-range [Float], or null when the text
 * isn't a finite number. Tolerates surrounding whitespace, a leading '+', and a comma
 * decimal separator; clamps to [range] and snaps to [step] via the same [snap] the drag
 * path uses, so keyboard entry and dragging land on identical values. Pure (no Android
 * deps) so it is unit-tested in SliderInputTest.
 */
internal fun parseSliderInput(
    text: String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
): Float? {
    val v = text.trim().removePrefix("+").replace(',', '.').toFloatOrNull() ?: return null
    if (v.isNaN() || v.isInfinite()) return null
    return snap(v, range, step)
}

/**
 * A small dialog for typing an exact slider value — Lightroom's "tap the number" affordance.
 * Confirms only a parseable, in-range number (the Set button disables otherwise); a "±"
 * toggle covers negative ranges, where the numeric IME often omits a minus key.
 */
@Composable
internal fun NumericEntryDialog(
    label: String,
    initial: String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    decimals: Int,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val parsed = parseSliderInput(text, range, step)
    // [parsed] clamps out-of-range input (Set still works, landing on the bound), so the range
    // hint is flagged as an error both for unparseable text and for a typed value outside the
    // range — the latter would otherwise be clamped silently.
    val typed = text.trim().removePrefix("+").replace(',', '.').toFloatOrNull()
    val outOfRange = typed != null && (typed < range.start || typed > range.endInclusive)
    val isError = text.isNotBlank() && (parsed == null || outOfRange)
    val rangeHint = stringResource(
        R.string.widget_numeric_entry_range_hint,
        formatValue(range.start, decimals),
        formatValue(range.endInclusive, decimals),
    )
    val signDescription = stringResource(R.string.widget_numeric_entry_sign_toggle)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label, modifier = Modifier.semantics { heading() }) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.widget_numeric_entry_field_label)) },
                        isError = isError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    if (range.start < 0f) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                text = when {
                                    text.startsWith("-") -> text.removePrefix("-")
                                    text.isBlank() -> "-"
                                    else -> "-$text"
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = signDescription },
                        ) { Text("±") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    rangeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { if (isError) error(rangeHint) },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onConfirm); onDismiss() }) {
                Text(stringResource(R.string.widget_numeric_entry_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.widget_numeric_entry_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// AutoExposureControl — Lightroom-mobile style expandable metering control
// ---------------------------------------------------------------------------

/** Width of each slider end that is shielded from the system back gesture. */
private val SLIDER_END_EXCLUSION = 48.dp

/**
 * Keep the system back gesture off the two ends of a slider track.
 *
 * Panel sliders run almost edge to edge, so a thumb at either end of its range sits inside
 * the gesture-navigation back zone. Grabbing it there was read as Back: the preset amount
 * slider rests at 100% on the far right, and dragging it closed the Presets panel instead
 * of changing the amount. Only the two thumb-sized ends are excluded, not the whole track,
 * because Android honours at most 200dp of exclusion per screen edge and a panel shows
 * several sliders at once.
 */
private fun Modifier.sliderEndsGestureExclusion(stripPx: Float): Modifier =
    this
        .systemGestureExclusion { c ->
            Rect(0f, 0f, minOf(stripPx, c.size.width.toFloat()), c.size.height.toFloat())
        }
        .systemGestureExclusion { c ->
            val w = c.size.width.toFloat()
            Rect(maxOf(0f, w - stripPx), 0f, w, c.size.height.toFloat())
        }

/**
 * Where a slider's fill starts: the neutral [default] when it sits inside the range (a
 * centred +/- slider), otherwise the range start (an ordinary 0..max slider).
 */
internal fun sliderAnchor(default: Float?, range: ClosedFloatingPointRange<Float>): Float =
    default?.takeIf { it > range.start && it < range.endInclusive } ?: range.start

/** Fraction 0..1 of [v] along [range]; 0 for an empty range. */
internal fun sliderFraction(v: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return if (span <= 0f) 0f else ((v - range.start) / span).coerceIn(0f, 1f)
}

/**
 * M3 Expressive slider track (16dp, fully rounded): inactive surfaceContainerHighest, active
 * primary from [anchor] to [value], and a small stop mark at a centred anchor so neutral is
 * findable by eye. Drawn in one Canvas so dragging redraws without relayout.
 */
@Composable
private fun AnchoredSliderTrack(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    anchor: Float,
) {
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val active = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onSurfaceVariant
    val centred = anchor > range.start
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(SLIDER_TRACK_HEIGHT),
    ) {
        val h = size.height
        val r = CornerRadius(h / 2f)
        drawRoundRect(color = inactive, cornerRadius = r)
        // The Slider mirrors thumb and drag in RTL, so the track must mirror too.
        val rtl = layoutDirection == LayoutDirection.Rtl
        fun x(f: Float) = (if (rtl) 1f - f else f) * size.width
        val a = x(sliderFraction(anchor, range))
        val v = x(sliderFraction(value, range))
        val left = minOf(a, v)
        val right = maxOf(a, v)
        if (right - left > 0.5f) {
            drawRoundRect(
                color = active,
                topLeft = Offset(left, 0f),
                size = Size(right - left, h),
                cornerRadius = r,
            )
        }
        if (centred) {
            val d = 4.dp.toPx()
            drawCircle(color = mark, radius = d / 2f, center = Offset(a, h / 2f))
        }
    }
}

private val SLIDER_TRACK_HEIGHT = 16.dp

/** Where a slider value sits, for the haptic ticks: free, on its default, or at an end. */
internal enum class SliderZone { FREE, DEFAULT, END }

/** Fraction of the range around the default that snaps onto it while dragging. */
internal const val DETENT_FRACTION = 0.015f

/**
 * The sticky detent: a dragged value within [DETENT_FRACTION] of the range from [default]
 * lands exactly on it. A slider without a default, or whose default is outside the range,
 * has no detent.
 */
internal fun detent(value: Float, default: Float?, range: ClosedFloatingPointRange<Float>): Float {
    if (default == null || default < range.start || default > range.endInclusive) return value
    val width = range.endInclusive - range.start
    return if (kotlin.math.abs(value - default) <= width * DETENT_FRACTION) default else value
}

/** Which [SliderZone] a (detented) value is in. */
internal fun detentZone(value: Float, default: Float?, range: ClosedFloatingPointRange<Float>): SliderZone = when {
    default != null && value == default -> SliderZone.DEFAULT
    value <= range.start || value >= range.endInclusive -> SliderZone.END
    else -> SliderZone.FREE
}

/**
 * What the floating readout needs to offer "tap to type" after the finger lifts, for one
 * control. The setter goes through the LATEST callbacks, because a caller's callback
 * captures its state (Masks rebuilds the whole mask list from what it closed over), so an
 * old callback would write back stale state. The edit is only offered while the control is
 * still composed and still shows the value the readout reported; a slot reused for another
 * target (another mask selected) or removed fails that and the HUD closes.
 */
@Composable
internal fun rememberReadoutEdit(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    decimals: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?,
): (Float) -> SliderReadoutEdit {
    val latestChange = rememberUpdatedState(onValueChange)
    val latestFinished = rememberUpdatedState(onValueChangeFinished)
    val latestValue = rememberUpdatedState(value)
    // State, not a plain flag: the editor observes it to offer the photo drag (PhotoScrub)
    // only while this control is on screen.
    val alive = remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        alive.value = true
        onDispose { alive.value = false }
    }
    return { current ->
        SliderReadoutEdit(
            range, step, decimals, current,
            isCurrent = {
                val tol = maxOf(step / 2f, (range.endInclusive - range.start) * 1e-4f)
                alive.value && kotlin.math.abs(latestValue.value - current) <= tol
            },
            live = { if (alive.value) latestValue.value else null },
            alive = alive,
            move = { v -> if (alive.value) latestChange.value(snap(v, range, step)) },
        ) { v ->
            latestChange.value(snap(v, range, step))
            latestFinished.value?.invoke()
        }
    }
}
