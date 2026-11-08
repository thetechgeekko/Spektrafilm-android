/*
 * Spektrafilm for Android — instrument controls. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Purpose-drawn alternatives to the plain slider, from the owner's instrument catalogue:
 * a rotary knob, a vertical fader and a gradient bar. They follow the same interaction
 * contract as EnhancedSlider, so the editor treats them the same way:
 *  - a drag reports through LocalSliderInteraction (live HUD, chrome fades), and the
 *    release settles one named history step;
 *  - the HUD lingers and can be tapped to type a value (rememberReadoutEdit);
 *  - the value sticks to the default with a haptic tick, and the range ends tick too;
 *  - a double-tap resets to the default;
 *  - a second finger resting on the screen drags at quarter speed (fine control; phones
 *    have no Shift key).
 * TalkBack sees each one as a slider: range info, set-progress and a reset action.
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

/**
 * Which instrument draws the value. The gesture follows: knob, fader, pill and arc gauge drag up, the
 * horizontal kinds (gradient bar, meters, lens grid) sideways. METER_HIGH shades what lies
 * above the value (a white level clips the brighter tones), METER_LOW what lies below it.
 */
internal enum class InstrumentKind {
    KNOB, FADER, PILL, ARC_GAUGE, GRAD_BAR, METER_HIGH, METER_LOW, LENS_GRID,

    /** A dial that fills from the bottom like a level, with the caption's initial in it (fillKnob). */
    FILL_KNOB,

    /** A chirp whose swing grows with the value (wave): contrast, read as "how hard the tones swing". */
    WAVE;

    val vertical: Boolean
        get() = this == KNOB || this == FADER || this == PILL || this == ARC_GAUGE || this == FILL_KNOB
}

/** Reference frame the lens grid draws the current format against, in mm (35 mm film). */
internal const val LENS_GRID_REFERENCE_MM = 35f

/** Finger travel, in dp, that sweeps the whole range at normal speed. */
internal const val INSTRUMENT_TRAVEL_DP = 180f

/** Speed while a second finger rests on the screen. */
internal const val INSTRUMENT_FINE_FACTOR = 0.25f

/**
 * The value a drag reaches: [start] plus [deltaPx] of finger travel, where [travelPx]
 * sweeps the whole range ([fine] slows it to a quarter), clamped to [range]. Snapping
 * and the default detent are applied by the caller, on the result.
 */
internal fun instrumentDragValue(
    start: Float,
    deltaPx: Float,
    travelPx: Float,
    range: ClosedFloatingPointRange<Float>,
    fine: Boolean,
): Float {
    if (travelPx <= 0f) return start.coerceIn(range.start, range.endInclusive)
    val width = range.endInclusive - range.start
    val speed = if (fine) INSTRUMENT_FINE_FACTOR else 1f
    return (start + deltaPx / travelPx * width * speed).coerceIn(range.start, range.endInclusive)
}

/** Where [value] sits in [range], 0..1. */
internal fun instrumentFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val width = range.endInclusive - range.start
    return if (width <= 0f) 0f else ((value - range.start) / width).coerceIn(0f, 1f)
}

/** The colour of a gradient bar at [f] (0..1), interpolated between its evenly spaced stops. */
internal fun gradientAt(stops: List<Color>, f: Float): Color {
    if (stops.isEmpty()) return Color.Unspecified
    if (stops.size == 1) return stops[0]
    val x = f.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], x - i)
}

/** Cool to warm, for a warmth (temperature) bar. */
internal val WARMTH_STOPS get() = SpektraBrand.warmthStops

/** Green to magenta, for a tint bar. */
internal val TINT_STOPS get() = SpektraBrand.tintStops

/**
 * One instrument: the drawn control with its label and value under it (the bar puts them
 * on one line above). See the file header for the interaction contract.
 */
@Composable
internal fun Instrument(
    kind: InstrumentKind,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    decimals: Int = 2,
    default: Float? = null,
    gradient: List<Color> = WARMTH_STOPS,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color? = null,
    caption: String = label,
    // What a FILL_KNOB shows in its middle; the caption's initial when not given.
    glyph: String? = null,
) {
    val view = LocalView.current
    val interaction = LocalSliderInteraction.current
    val hint = rememberUpdatedState(LocalInstrumentHint.current)
    val formatted = formatValue(value, decimals)
    val modified = isModifiedFromDefault(value, default, range, step)
    val readoutEdit = rememberReadoutEdit(value, range, step, decimals, onValueChange, onValueChangeFinished)
    val latestValue = rememberUpdatedState(value)
    val latestChange = rememberUpdatedState(onValueChange)
    val latestFinished = rememberUpdatedState(onValueChangeFinished)
    val latestLabel = rememberUpdatedState(label)
    val snappedDefault = default?.let { snap(it, range, step) }
    var dragging by remember { mutableStateOf(false) }
    val travelPx = with(LocalDensity.current) { INSTRUMENT_TRAVEL_DP.dp.toPx() }

    val reset: (() -> Unit)? = snappedDefault?.takeIf { modified }?.let { d ->
        {
            onValueChange(d)
            onValueChangeFinished?.invoke()
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    val latestReset = rememberUpdatedState(reset)
    val resetLabel = stringResource(R.string.widget_value_pill_reset_default, label)
    val typeLabel = stringResource(R.string.editor_hud_type_value)
    var typing by remember { mutableStateOf(false) }
    val steps = if (step > 0f) (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0) else 0

    val gesture = Modifier
        // A second finger also shows the clipping peek, as it does on the sliders.
        .multiTouchPeek(interaction.onMultiTouch)
        // Double-tap resets. Declared first so it sees the pointer before the drag does; it
        // gives up as soon as the drag detector consumes past touch slop.
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { latestReset.value?.invoke() }) }
        .pointerInput(kind, range, step, snappedDefault) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val vertical = kind.vertical
                // Waiting for slop on OUR axis leaves the other axis to the parent: a knob row
                // or the panel can still scroll past a knob dragged the wrong way.
                var overSlop = 0f
                val slop: PointerInputChange? = if (vertical) {
                    awaitVerticalTouchSlopOrCancellation(down.id) { change, o -> change.consume(); overSlop = o }
                } else {
                    awaitHorizontalTouchSlopOrCancellation(down.id) { change, o -> change.consume(); overSlop = o }
                }
                if (slop == null) return@awaitEachGesture
                hint.value(latestLabel.value)
                dragging = true
                var raw = latestValue.value
                var zone = detentZone(raw, snappedDefault, range)
                fun apply(deltaPx: Float, fine: Boolean) {
                    raw = instrumentDragValue(raw, deltaPx, travelPx, range, fine)
                    val v = detent(snap(raw, range, step), snappedDefault, range)
                    val z = detentZone(v, snappedDefault, range)
                    if (z != SliderZone.FREE && z != zone) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    zone = z
                    latestChange.value(v)
                    interaction.onChange(SliderReadout(latestLabel.value, formatValue(v, decimals), readoutEdit(v)))
                }
                // The travel past touch slop counts, so the value does not lag the finger.
                apply(if (vertical) -overSlop else overSlop, false)
                val onMove: (PointerInputChange) -> Unit = { change ->
                    val d = change.position - change.previousPosition
                    val fine = currentEvent.changes.count { it.pressed } >= 2
                    apply(if (vertical) -d.y else d.x, fine)
                    change.consume()
                }
                // finally: the instrument can leave composition mid-drag (another finger closes
                // the panel), which cancels this coroutine. The editor's drag state must still
                // end, or the chrome stays faded and the HUD never settles.
                var completed = false
                try {
                    completed = if (vertical) verticalDrag(slop.id, onMove) else horizontalDrag(slop.id, onMove)
                } finally {
                    dragging = false
                    latestFinished.value?.invoke()
                    if (completed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    interaction.onFinished()
                }
            }
        }
        // Cleared, not merged: the label and value Texts underneath would otherwise be read
        // a second time after the slider description.
        .clearAndSetSemantics {
            contentDescription = label
            stateDescription = formatted
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range.start, range.endInclusive), range, steps)
            setProgress { target ->
                latestChange.value(snap(target, range, step))
                latestFinished.value?.invoke()
                true
            }
            // Typing an exact value is a custom action, so it is reachable without a drag
            // (TalkBack adjusts by setProgress and never opens the HUD).
            customActions = listOfNotNull(
                CustomAccessibilityAction(typeLabel) { typing = true; true },
                reset?.let { r -> CustomAccessibilityAction(resetLabel) { r(); true } },
            )
        }

    val f = instrumentFraction(value, range)
    val anchor = instrumentFraction(sliderAnchor(default, range), range)
    // An EQ band is a fader in its channel's colour.
    val active = accent ?: MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurface
    val valueColor = if (modified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    when (kind) {
        InstrumentKind.GRAD_BAR, InstrumentKind.METER_HIGH, InstrumentKind.METER_LOW,
        InstrumentKind.LENS_GRID, InstrumentKind.WAVE -> Column(modifier.fillMaxWidth().then(gesture)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(formatted, style = MaterialTheme.typography.labelMedium, color = valueColor)
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(
                        when (kind) {
                            InstrumentKind.LENS_GRID -> 72.dp
                            InstrumentKind.WAVE -> 56.dp
                            else -> 40.dp
                        },
                    ),
            ) {
                val pad = 12.dp.toPx()
                val y = size.height / 2f
                val w = size.width - 2 * pad
                when (kind) {
                    InstrumentKind.GRAD_BAR -> {
                        val barH = 14.dp.toPx()
                        drawRoundRect(
                            brush = Brush.horizontalGradient(gradient, startX = pad, endX = pad + w),
                            topLeft = Offset(pad, y - barH / 2),
                            size = Size(w, barH),
                            cornerRadius = CornerRadius(barH / 2),
                        )
                        // The neutral mark, so "how far from neutral" reads without the number.
                        val ax = pad + w * anchor
                        drawLine(idle, Offset(ax, y + barH / 2 + 3.dp.toPx()), Offset(ax, y + barH / 2 + 8.dp.toPx()), 1.5.dp.toPx())
                        val hx = pad + w * f
                        val r = 10.dp.toPx()
                        drawCircle(gradientAt(gradient, f), r, Offset(hx, y))
                        drawCircle(if (dragging) active else ink, r, Offset(hx, y), style = Stroke(2.5.dp.toPx()))
                    }
                    InstrumentKind.WAVE -> {
                        // A chirp, tight on the right like a tone ramp squeezed by contrast; its
                        // swing grows with the value, with a needle on the value itself.
                        drawLine(idle, Offset(pad, y), Offset(pad + w, y), 1.dp.toPx())
                        val swing = 3.dp.toPx() + f * (size.height / 2 - 6.dp.toPx())
                        val path = androidx.compose.ui.graphics.Path()
                        val samples = 96
                        for (j in 0..samples) {
                            val t = j / samples.toFloat()
                            val px = pad + w * t
                            val py = y - sin(t * t * 28f) * swing
                            if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(path, active, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                        val ax = pad + w * anchor
                        drawLine(idle, Offset(ax, size.height - 6.dp.toPx()), Offset(ax, size.height - 1.dp.toPx()), 1.5.dp.toPx())
                        val hx = pad + w * f
                        drawLine(if (dragging) active else ink, Offset(hx, 2.dp.toPx()), Offset(hx, size.height - 2.dp.toPx()),
                            2.dp.toPx(), cap = StrokeCap.Round)
                    }
                    InstrumentKind.METER_HIGH, InstrumentKind.METER_LOW -> {
                        // A level meter: a dark-to-light ramp with the part the level cuts off
                        // shaded, and a needle on the level itself.
                        val barH = 16.dp.toPx()
                        val top = y - barH / 2
                        val corner = CornerRadius(4.dp.toPx())
                        drawRoundRect(
                            brush = Brush.horizontalGradient(SpektraBrand.levelRamp, startX = pad, endX = pad + w),
                            topLeft = Offset(pad, top),
                            size = Size(w, barH),
                            cornerRadius = corner,
                        )
                        val vx = pad + w * f
                        val (sx, sw) = if (kind == InstrumentKind.METER_HIGH) vx to (pad + w - vx) else pad to (vx - pad)
                        if (sw > 0f) drawRect(active.copy(alpha = 0.30f), Offset(sx, top), Size(sw, barH))
                        drawRoundRect(idle, Offset(pad, top), Size(w, barH), corner, style = Stroke(1.dp.toPx()))
                        for (i in 0..4) {
                            val tx = pad + w * i / 4f
                            drawLine(idle, Offset(tx, top + barH + 2.dp.toPx()), Offset(tx, top + barH + 6.dp.toPx()), 1.dp.toPx())
                        }
                        drawLine(
                            if (dragging) active else ink,
                            Offset(vx, top - 4.dp.toPx()),
                            Offset(vx, top + barH + 4.dp.toPx()),
                            2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    else -> {
                        // Lens grid: the current frame (3:2) drawn to scale against a 35 mm
                        // reference frame, so a bigger format reads as a bigger negative.
                        val h = size.height - 8.dp.toPx()
                        val scale = minOf(w, h * 1.5f) / range.endInclusive
                        val c = Offset(size.width / 2, size.height / 2)
                        fun frame(mm: Float, color: Color, stroke: Float) {
                            val fw = (mm * scale).coerceAtLeast(2.dp.toPx())
                            val fh = fw * 2f / 3f
                            drawRect(color, Offset(c.x - fw / 2, c.y - fh / 2), Size(fw, fh), style = Stroke(stroke))
                        }
                        frame(LENS_GRID_REFERENCE_MM, idle, 1.dp.toPx())
                        frame(value, if (dragging) active else ink, 2.dp.toPx())
                        // A rule of thirds inside the current frame.
                        val fw = (value * scale).coerceAtLeast(2.dp.toPx())
                        val fh = fw * 2f / 3f
                        for (i in 1..2) {
                            val gx = c.x - fw / 2 + fw * i / 3f
                            val gy = c.y - fh / 2 + fh * i / 3f
                            drawLine(idle, Offset(gx, c.y - fh / 2), Offset(gx, c.y + fh / 2), 0.5.dp.toPx())
                            drawLine(idle, Offset(c.x - fw / 2, gy), Offset(c.x + fw / 2, gy), 0.5.dp.toPx())
                        }
                    }
                }
            }
        }
        InstrumentKind.KNOB, InstrumentKind.FADER, InstrumentKind.PILL, InstrumentKind.ARC_GAUGE,
        InstrumentKind.FILL_KNOB -> Column(
            modifier.width(76.dp).then(gesture),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (kind == InstrumentKind.KNOB) {
                Canvas(Modifier.size(64.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.minDimension / 2 - 12.dp.toPx()
                    val a0 = 0.75f * PI.toFloat()
                    val sweep = 1.5f * PI.toFloat()
                    // A ring of ticks, lit from the anchor to the value.
                    val lo = minOf(anchor, f)
                    val hi = maxOf(anchor, f)
                    for (i in 0..20) {
                        val t = i / 20f
                        val a = a0 + sweep * t
                        val lit = t >= lo - 1e-4f && t <= hi + 1e-4f && hi - lo > 1e-4f
                        val inner = r + 4.dp.toPx()
                        val outer = r + (if (i % 5 == 0) 11.dp else 8.dp).toPx()
                        drawLine(
                            if (lit) active else idle,
                            Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                            Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                            1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    drawCircle(body, r, c)
                    val a = a0 + sweep * f
                    drawLine(
                        if (dragging) active else ink,
                        Offset(c.x + cos(a) * (r - 13.dp.toPx()), c.y + sin(a) * (r - 13.dp.toPx())),
                        Offset(c.x + cos(a) * (r - 3.dp.toPx()), c.y + sin(a) * (r - 3.dp.toPx())),
                        2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            } else if (kind == InstrumentKind.FILL_KNOB) {
                // Fill knob (instrument catalogue: fillKnob): a dial that fills from the
                // bottom like a level, with the control's initial in the middle.
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.matchParentSize()) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val r = size.minDimension / 2 - 6.dp.toPx()
                        drawCircle(body, r, c)
                        val disc = androidx.compose.ui.graphics.Path().apply {
                            addOval(androidx.compose.ui.geometry.Rect(c, r))
                        }
                        clipPath(disc) {
                            val top = c.y + r - 2 * r * f
                            drawRect((if (dragging) active else active.copy(alpha = 0.55f)), Offset(c.x - r, top), Size(2 * r, c.y + r - top))
                        }
                        drawCircle(idle, r, c, style = Stroke(1.dp.toPx()))
                    }
                    Text(
                        glyph ?: caption.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = ink,
                    )
                }
            } else if (kind == InstrumentKind.ARC_GAUGE) {
                // Arc gauge (instrument catalogue: arcGauge): a half-dial lit from the anchor
                // to the value, with a needle, for an overall strength.
                Canvas(Modifier.size(width = 72.dp, height = 48.dp)) {
                    val stroke = 6.dp.toPx()
                    val r = minOf(size.width / 2, size.height) - stroke
                    val c = Offset(size.width / 2, size.height - 2.dp.toPx())
                    val tl = Offset(c.x - r, c.y - r)
                    val sz = Size(2 * r, 2 * r)
                    drawArc(idle, 180f, 180f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                    val lo = minOf(anchor, f)
                    val hi = maxOf(anchor, f)
                    if (hi - lo > 1e-4f) {
                        drawArc(active, 180f + 180f * lo, 180f * (hi - lo), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    val a = PI.toFloat() * (1f + f)
                    drawLine(
                        if (dragging) active else ink,
                        c,
                        Offset(c.x + cos(a) * (r - 6.dp.toPx()), c.y + sin(a) * (r - 6.dp.toPx())),
                        2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(if (dragging) active else ink, 3.dp.toPx(), c)
                }
            } else if (kind == InstrumentKind.PILL) {
                // The exposure pill (instrument catalogue: pill): a wide capsule that fills
                // from its neutral point, like light poured into it, with a level bar.
                Canvas(Modifier.size(width = 48.dp, height = 112.dp)) {
                    val pw = size.width - 8.dp.toPx()
                    val x0 = 4.dp.toPx()
                    val top = 4.dp.toPx()
                    val bot = size.height - 4.dp.toPx()
                    val corner = CornerRadius(pw / 2)
                    drawRoundRect(body, Offset(x0, top), Size(pw, bot - top), corner)
                    val yAt = { t: Float -> bot - (bot - top) * t }
                    val y0 = yAt(maxOf(anchor, f))
                    val y1 = yAt(minOf(anchor, f))
                    clipPath(
                        androidx.compose.ui.graphics.Path().apply {
                            addRoundRect(androidx.compose.ui.geometry.RoundRect(x0, top, x0 + pw, bot, corner))
                        },
                    ) {
                        drawRect(active.copy(alpha = 0.55f), Offset(x0, y0), Size(pw, y1 - y0))
                    }
                    drawRoundRect(idle, Offset(x0, top), Size(pw, bot - top), corner, style = Stroke(1.dp.toPx()))
                    val ty = yAt(f)
                    drawRoundRect(
                        if (dragging) active else ink,
                        Offset(x0 + 6.dp.toPx(), ty - 2.dp.toPx()),
                        Size(pw - 12.dp.toPx(), 4.dp.toPx()),
                        CornerRadius(2.dp.toPx()),
                    )
                }
            } else {
                Canvas(Modifier.size(width = 40.dp, height = 112.dp)) {
                    val cw = 22.dp.toPx()
                    val x = size.width / 2
                    val top = 4.dp.toPx()
                    val bot = size.height - 4.dp.toPx()
                    drawRoundRect(body, Offset(x - cw / 2, top), Size(cw, bot - top), CornerRadius(cw / 2))
                    val yAt = { t: Float -> bot - (bot - top) * t }
                    val y0 = yAt(maxOf(anchor, f))
                    val y1 = yAt(minOf(anchor, f))
                    drawRoundRect(active, Offset(x - 3.dp.toPx(), y0), Size(6.dp.toPx(), y1 - y0), CornerRadius(3.dp.toPx()))
                    val ty = yAt(f)
                    val th = 12.dp.toPx()
                    drawRoundRect(
                        if (dragging) active else ink,
                        Offset(x - cw / 2 + 1.dp.toPx(), ty - th / 2),
                        Size(cw - 2.dp.toPx(), th),
                        CornerRadius(5.dp.toPx()),
                    )
                }
            }
            Text(
                // The short name under the instrument; TalkBack and the HUD keep the full label.
                caption,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatted, style = MaterialTheme.typography.labelMedium, color = valueColor)
        }
    }
    if (typing) {
        NumericEntryDialog(
            label = label,
            initial = formatted,
            range = range,
            step = step,
            decimals = decimals,
            onDismiss = { typing = false },
            onConfirm = { v ->
                onValueChange(v)
                onValueChangeFinished?.invoke()
                typing = false
            },
        )
    }
}

/** Reports which instrument in a row was last touched, so the row can show its hint. */
internal val LocalInstrumentHint = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * A row of knobs or faders. A row has no room for every control's tooltip, so it shows the
 * hint of the one last touched ([hints] maps label to hint).
 */
@Composable
internal fun InstrumentRow(
    hints: Map<String, String> = emptyMap(),
    content: @Composable RowScope.() -> Unit,
) {
    var touched by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalInstrumentHint provides { touched = it }) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
                content = content,
            )
            touched?.let { hints[it] }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
