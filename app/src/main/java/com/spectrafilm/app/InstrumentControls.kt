/*
 * Spektrafilm for Android -- Pad2D, StepWave, IconSlider and SunburstToggle instruments (split from Instruments.kt for #273). GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One axis of a [Pad2D]: its own label, range, step, default and setter. */
internal class PadAxis(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val onValueChange: (Float) -> Unit,
    val step: Float = 0f,
    val decimals: Int = 0,
    val default: Float? = null,
)

/**
 * Two parameters on one puck (instrument catalogue: pad2D). The
 * drag is relative, like the other instruments, so a touch never jumps the puck; x and y
 * each have their own default detent and end ticks, the HUD reads both values, a double-tap
 * resets both, and one release is one history step. TalkBack gets each axis as its own
 * slider (two small semantics nodes), since a 2D drag has no screen-reader equivalent.
 */
@Composable
internal fun Pad2D(
    title: String,
    x: PadAxis,
    y: PadAxis,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    // The square's side. The tool rail's cards use a smaller pad than the full settings.
    padSize: androidx.compose.ui.unit.Dp = 132.dp,
    // Draw the pad as a filter wheel (instrument catalogue: wheel) instead of a square: the
    // same relative 2D drag, with [wheelLabels] (x, y) marking the two positive axes.
    wheelLabels: Pair<String, String>? = null,
) {
    val view = LocalView.current
    val interaction = LocalSliderInteraction.current
    val latestX = rememberUpdatedState(x)
    val latestY = rememberUpdatedState(y)
    val latestFinished = rememberUpdatedState(onValueChangeFinished)
    val latestTitle = rememberUpdatedState(title)
    var dragging by remember { mutableStateOf(false) }
    val travelPx = with(LocalDensity.current) { INSTRUMENT_TRAVEL_DP.dp.toPx() }
    fun dx(a: PadAxis) = a.default?.let { snap(it, a.range, a.step) }
    val modified = isModifiedFromDefault(x.value, x.default, x.range, x.step) ||
        isModifiedFromDefault(y.value, y.default, y.range, y.step)
    val reset: (() -> Unit)? = if (modified) {
        {
            dx(x)?.let(x.onValueChange)
            dx(y)?.let(y.onValueChange)
            onValueChangeFinished?.invoke()
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    } else null
    val latestReset = rememberUpdatedState(reset)
    val readout = { xv: Float, yv: Float ->
        "${formatValue(xv, latestX.value.decimals)} / ${formatValue(yv, latestY.value.decimals)}"
    }

    val gesture = Modifier
        // A second finger also shows the clipping peek, as it does on the sliders.
        .multiTouchPeek(interaction.onMultiTouch)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { latestReset.value?.invoke() }) }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var pastSlop = false
                val startX = latestX.value.value
                val startY = latestY.value.value
                var rawX = startX
                var rawY = startY
                val ax = latestX.value
                val ay = latestY.value
                var zoneX = detentZone(rawX, dx(ax), ax.range)
                var zoneY = detentZone(rawY, dx(ay), ay.range)
                var travelled = Offset.Zero
                var movedX = false
                var movedY = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val d = change.position - change.previousPosition
                        travelled += d
                        // An axis counts as moved once the finger has gone a touch slop along
                        // it, so the jitter of a straight vertical drag leaves x alone.
                        if (abs(travelled.x) >= viewConfiguration.touchSlop) movedX = true
                        if (abs(travelled.y) >= viewConfiguration.touchSlop) movedY = true
                        if (!pastSlop) {
                            if (travelled.getDistance() < viewConfiguration.touchSlop) continue
                            pastSlop = true
                            dragging = true
                        }
                        val fine = event.changes.count { it.pressed } >= 2
                        rawX = instrumentDragValue(rawX, d.x, travelPx, ax.range, fine)
                        rawY = instrumentDragValue(rawY, -d.y, travelPx, ay.range, fine)
                        // An axis the finger has not moved keeps its value as it was: a typed
                        // 2 must not fall into the default detent when only the other axis moves.
                        val vx = if (!movedX) startX else detent(snap(rawX, ax.range, ax.step), dx(ax), ax.range)
                        val vy = if (!movedY) startY else detent(snap(rawY, ay.range, ay.step), dx(ay), ay.range)
                        val zx = detentZone(vx, dx(ax), ax.range)
                        val zy = detentZone(vy, dx(ay), ay.range)
                        if ((zx != SliderZone.FREE && zx != zoneX) || (zy != SliderZone.FREE && zy != zoneY)) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        zoneX = zx
                        zoneY = zy
                        if (movedX) latestX.value.onValueChange(vx)
                        if (movedY) latestY.value.onValueChange(vy)
                        interaction.onChange(SliderReadout(latestTitle.value, readout(vx, vy)))
                        change.consume()
                    }
                } finally {
                    // Also on cancellation (the pad left composition mid-drag): the editor's
                    // drag state must end.
                    if (pastSlop) {
                        dragging = false
                        latestFinished.value?.invoke()
                        interaction.onFinished()
                    }
                }
            }
        }

    val fx = instrumentFraction(x.value, x.range)
    val fy = instrumentFraction(y.value, y.range)
    val ax0 = instrumentFraction(sliderAnchor(x.default, x.range), x.range)
    val ay0 = instrumentFraction(sliderAnchor(y.default, y.range), y.range)
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurface
    val valueColor = if (modified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val resetLabel = stringResource(R.string.widget_value_pill_reset_default, title)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Canvas(Modifier.size(padSize).then(gesture).clearAndSetSemantics { }) {
                val pad = 8.dp.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                val c = Offset(size.width / 2, size.height / 2)
                val px: Float
                val py: Float
                if (wheelLabels != null) {
                    // A filter wheel: x points at the right, y up, their complements opposite.
                    val r = minOf(w, h) / 2
                    drawCircle(Brush.sweepGradient(*SpektraBrand.filterWheel, center = c), r, c, alpha = 0.6f)
                    drawCircle(Brush.radialGradient(listOf(body, body.copy(alpha = 0f)), c, r), r, c)
                    drawCircle(idle, r, c, style = Stroke(1.dp.toPx()))
                    drawLine(idle, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
                    drawLine(idle, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
                    // 0.7 keeps the corners of the square value range on the disc.
                    px = c.x + (fx - ax0) * 2 * r * 0.7f
                    py = c.y - (fy - ay0) * 2 * r * 0.7f
                } else {
                    drawRoundRect(body, Offset(pad, pad), Size(w, h), CornerRadius(12.dp.toPx()))
                    // Neutral crosshair through the defaults.
                    val nx = pad + w * ax0
                    val ny = pad + h * (1 - ay0)
                    drawLine(idle, Offset(nx, pad), Offset(nx, pad + h), 1.dp.toPx())
                    drawLine(idle, Offset(pad, ny), Offset(pad + w, ny), 1.dp.toPx())
                    px = pad + w * fx
                    py = pad + h * (1 - fy)
                }
                drawCircle(if (dragging) active else ink, 9.dp.toPx(), Offset(px, py), style = Stroke(2.dp.toPx()))
                drawCircle(if (dragging) active else ink, 3.dp.toPx(), Offset(px, py))
            }
            wheelLabels?.let { (lx, ly) ->
                val tag = MaterialTheme.typography.labelSmall
                val tagColor = MaterialTheme.colorScheme.onSurfaceVariant
                Text(ly, style = tag, color = tagColor, modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                Text(lx, style = tag, color = tagColor, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
            }
            // One accessibility node per axis, laid over the pad: TalkBack swipes adjust them.
            Column(Modifier.matchParentSize()) {
                for (a in listOf(x, y)) {
                    val steps = if (a.step > 0f) (((a.range.endInclusive - a.range.start) / a.step).roundToInt() - 1).coerceAtLeast(0) else 0
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clearAndSetSemantics {
                                contentDescription = a.label
                                stateDescription = formatValue(a.value, a.decimals)
                                progressBarRangeInfo = ProgressBarRangeInfo(a.value.coerceIn(a.range.start, a.range.endInclusive), a.range, steps)
                                setProgress { t ->
                                    a.onValueChange(snap(t, a.range, a.step))
                                    latestFinished.value?.invoke()
                                    true
                                }
                                if (reset != null) customActions = listOf(CustomAccessibilityAction(resetLabel) { reset(); true })
                            },
                    )
                }
            }
        }
        Text(title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        // Each axis value is its own tap target that opens numeric entry: the pad's detent
        // makes values right beside the default unreachable by drag, so typing must stay.
        var typing by remember { mutableStateOf<Int?>(null) }
        val typeLabel = stringResource(R.string.editor_hud_type_value)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(x, y).forEachIndexed { i, a ->
                if (i == 1) Text("/", style = MaterialTheme.typography.labelMedium, color = valueColor)
                Text(
                    formatValue(a.value, a.decimals),
                    style = MaterialTheme.typography.labelMedium,
                    color = valueColor,
                    modifier = Modifier
                        .clickable(onClickLabel = typeLabel) { typing = i }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
        typing?.let { i ->
            val a = if (i == 0) x else y
            NumericEntryDialog(
                label = a.label,
                initial = formatValue(a.value, a.decimals),
                range = a.range,
                step = a.step,
                decimals = a.decimals,
                onDismiss = { typing = null },
                onConfirm = { v ->
                    a.onValueChange(v)
                    onValueChangeFinished?.invoke()
                    typing = null
                },
            )
        }
    }
}

/** The one-line explanation under an instrument (sliders carry theirs in a tooltip). */
@Composable
internal fun InstrumentTooltip(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Finger travel, in dp, from one stop of a [StepWave] to the next. */
internal const val STEP_WAVE_STOP_DP = 36f

/**
 * The stop a sideways drag of [deltaPx] from [start] reaches, one stop per [stopPx], kept
 * within [count] stops.
 */
internal fun stepWaveIndex(start: Int, deltaPx: Float, stopPx: Float, count: Int): Int {
    if (count <= 0) return 0
    val moved = if (stopPx > 0f) (deltaPx / stopPx).toInt() else 0
    return (start + moved).coerceIn(0, count - 1)
}

/**
 * A contrast family as curves (instrument catalogue: wave): one characteristic curve per
 * stop, steeper as the stop rises, with the selected one drawn bold. A sideways drag walks
 * the stops with a tick at each; taps and TalkBack stay with the segmented row the caller
 * puts beside it, so this node is hidden from accessibility.
 */
@Composable
internal fun StepWave(
    title: String,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val interaction = LocalSliderInteraction.current
    val latestSelected = rememberUpdatedState(selected)
    val latestSelect = rememberUpdatedState(onSelect)
    val latestLabels = rememberUpdatedState(labels)
    val latestTitle = rememberUpdatedState(title)
    var dragging by remember { mutableStateOf(false) }
    val stopPx = with(LocalDensity.current) { STEP_WAVE_STOP_DP.dp.toPx() }
    val n = labels.size

    val gesture = Modifier.pointerInput(n) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var overSlop = 0f
            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, o -> change.consume(); overSlop = o }
                ?: return@awaitEachGesture
            dragging = true
            val start = latestSelected.value
            var index = start
            var travelled = overSlop
            fun step() {
                val next = stepWaveIndex(start, travelled, stopPx, latestLabels.value.size)
                if (next != index) {
                    index = next
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    latestSelect.value(next)
                }
                interaction.onChange(SliderReadout(latestTitle.value, latestLabels.value.getOrElse(index) { "" }))
            }
            step()
            try {
                horizontalDrag(slop.id) { change ->
                    travelled += change.position.x - change.previousPosition.x
                    step()
                    change.consume()
                }
            } finally {
                dragging = false
                interaction.onFinished()
            }
        }
    }

    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .then(gesture)
            .clearAndSetSemantics { },
    ) {
        val pad = 12.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val samples = 32
        for (i in 0 until n) {
            // Logistic curves, slope rising with the stop: a picture of "more contrast",
            // not a measured paper curve.
            val k = 3f + 9f * i / (n - 1).coerceAtLeast(1)
            val lo = 1f / (1f + kotlin.math.exp(k * 0.5f))
            val hi = 1f / (1f + kotlin.math.exp(-k * 0.5f))
            val path = androidx.compose.ui.graphics.Path()
            for (j in 0..samples) {
                val t = j / samples.toFloat()
                val v = (1f / (1f + kotlin.math.exp(-k * (t - 0.5f))) - lo) / (hi - lo)
                val px = pad + w * t
                val py = pad + h * (1f - v)
                if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val isSel = i == selected
            drawPath(
                path,
                if (isSel) (if (dragging) active else ink) else idle,
                style = Stroke(if (isSel) 2.5.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * A stepped choice as a slider (instrument catalogue: iconSlider): a track with one dot per
 * option, a thumb on the chosen one and its name above. A sideways drag walks the options
 * with a tick at each, and a tap on the track picks the nearest. TalkBack sees a slider over
 * the option index, announced by the option's name.
 */
@Composable
internal fun IconSlider(
    title: String,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val interaction = LocalSliderInteraction.current
    val latestSelected = rememberUpdatedState(selected)
    val latestSelect = rememberUpdatedState(onSelect)
    val latestLabels = rememberUpdatedState(labels)
    val latestTitle = rememberUpdatedState(title)
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val stopPx = with(density) { STEP_WAVE_STOP_DP.dp.toPx() }
    val trackPadPx = with(density) { ICON_SLIDER_PAD_DP.dp.toPx() }
    val n = labels.size
    val current = selected.coerceIn(0, (n - 1).coerceAtLeast(0))

    val gesture = Modifier
        .pointerInput(n) {
            detectTapGestures { pos ->
                val i = iconSliderIndexAt(pos.x, size.width.toFloat(), trackPadPx, latestLabels.value.size)
                if (i != latestSelected.value) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    latestSelect.value(i)
                }
            }
        }
        .pointerInput(n) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var overSlop = 0f
                val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, o -> change.consume(); overSlop = o }
                    ?: return@awaitEachGesture
                dragging = true
                val start = latestSelected.value
                var index = start
                var travelled = overSlop
                fun step() {
                    val next = stepWaveIndex(start, travelled, stopPx, latestLabels.value.size)
                    if (next != index) {
                        index = next
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        latestSelect.value(next)
                    }
                    interaction.onChange(SliderReadout(latestTitle.value, latestLabels.value.getOrElse(index) { "" }))
                }
                step()
                try {
                    horizontalDrag(slop.id) { change ->
                        travelled += change.position.x - change.previousPosition.x
                        step()
                        change.consume()
                    }
                } finally {
                    dragging = false
                    interaction.onFinished()
                }
            }
        }
        .clearAndSetSemantics {
            contentDescription = title
            stateDescription = labels.getOrElse(current) { "" }
            progressBarRangeInfo = ProgressBarRangeInfo(
                current.toFloat(), 0f..(n - 1).coerceAtLeast(1).toFloat(), (n - 2).coerceAtLeast(0),
            )
            setProgress { t ->
                val i = t.roundToInt().coerceIn(0, (n - 1).coerceAtLeast(0))
                if (i != latestSelected.value) latestSelect.value(i)
                true
            }
        }

    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.then(gesture)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            val y = size.height / 2
            val x0 = trackPadPx
            val x1 = size.width - trackPadPx
            drawLine(idle, Offset(x0, y), Offset(x1, y), 4.dp.toPx(), cap = StrokeCap.Round)
            for (i in 0 until n) {
                val x = iconSliderStopX(i, n, x0, x1)
                drawCircle(if (i == current) active else idle, 3.dp.toPx(), Offset(x, y))
            }
            val tx = iconSliderStopX(current, n, x0, x1)
            drawCircle(if (dragging) active else ink, 10.dp.toPx(), Offset(tx, y))
            drawCircle(body, 4.dp.toPx(), Offset(tx, y))
        }
        Text(
            labels.getOrElse(current) { "" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** Inset, in dp, from each end of an [IconSlider]'s canvas to its first and last stop. */
internal const val ICON_SLIDER_PAD_DP = 14f

/** Where stop [i] of [n] sits on a track from [x0] to [x1]; a single stop sits in the middle. */
internal fun iconSliderStopX(i: Int, n: Int, x0: Float, x1: Float): Float =
    if (n <= 1) (x0 + x1) / 2 else x0 + (x1 - x0) * i / (n - 1)

/** The stop nearest a tap at [x] on a canvas [width] wide, with [pad] inset at both ends. */
internal fun iconSliderIndexAt(x: Float, width: Float, pad: Float, n: Int): Int {
    if (n <= 1) return 0
    val span = width - 2 * pad
    if (span <= 0f) return 0
    return (((x - pad) / span) * (n - 1)).roundToInt().coerceIn(0, n - 1)
}

/** The option of [options] nearest [value], by absolute difference; 0 for an empty list. */
internal fun nearestOptionIndex(options: List<Int>, value: Int): Int =
    options.indices.minByOrNull { abs(options[it] - value) } ?: 0

/**
 * An on/off control drawn as a half sunburst (instrument catalogue: sunburst): the rays
 * light up while it is on. A tap toggles it; TalkBack sees a switch.
 */
@Composable
internal fun SunburstToggle(
    label: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    val onText = stringResource(R.string.editor_rail_tool_on)
    val offText = stringResource(R.string.editor_rail_tool_off)
    Column(
        modifier
            .width(76.dp)
            .toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onToggle(it)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(width = 72.dp, height = 48.dp)) {
            val c = Offset(size.width / 2, size.height - 4.dp.toPx())
            val r = minOf(size.width / 2, size.height) - 4.dp.toPx()
            val rays = 13
            for (i in 0 until rays) {
                val a = PI.toFloat() * (1f + i / (rays - 1).toFloat())
                drawLine(
                    if (on) active else idle,
                    Offset(c.x + cos(a) * 14.dp.toPx(), c.y + sin(a) * 14.dp.toPx()),
                    Offset(c.x + cos(a) * r, c.y + sin(a) * r),
                    (if (on) 2.dp else 1.5.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }
            drawArc(if (on) active else idle, 180f, 180f, true, Offset(c.x - 9.dp.toPx(), c.y - 9.dp.toPx()), Size(18.dp.toPx(), 18.dp.toPx()))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (on) onText else offText,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
