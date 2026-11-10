/*
 * Spektrafilm for Android — the crop tool's straighten ruler. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A horizontal ruler of degree ticks that scrolls under a fixed centre needle: drag it
 * sideways and the angle under the needle is the straighten angle. Ticks every degree, a
 * longer one every 5 and a labelled one every 15; a haptic tick at each whole degree, a
 * sticky detent at level (0), and a double-tap that levels the photo.
 */
package com.spectrafilm.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Ruler spacing: one degree of angle per this much finger travel. */
internal const val STRAIGHTEN_DP_PER_DEGREE = 12f

/** The ruler's resolution. */
internal const val STRAIGHTEN_STEP = 0.1f

/** Within this of level, a dragged angle sticks to exactly 0. */
internal const val STRAIGHTEN_DETENT = 0.25f

/**
 * The angle a ruler drag reaches: the ruler moves WITH the finger, so dragging left brings
 * larger angles under the needle. Clamped, snapped to [STRAIGHTEN_STEP], with the level detent.
 */
internal fun straightenRulerValue(start: Float, dxPx: Float, pxPerDegree: Float): Float {
    if (pxPerDegree <= 0f) return clampStraighten(start)
    val raw = clampStraighten(start - dxPx / pxPerDegree)
    if (abs(raw) <= STRAIGHTEN_DETENT) return 0f
    val snapped = (raw / STRAIGHTEN_STEP).roundToInt() * STRAIGHTEN_STEP
    return clampStraighten(snapped)
}

/** "+2.5°", "−12.0°", "0.0°" (a real minus sign; the digits read the same in every locale). */
internal fun formatStraighten(deg: Float): String {
    val v = (deg * 10f).roundToInt() / 10f
    val body = "%.1f".format(java.util.Locale.ROOT, abs(v))
    return when {
        v > 0f -> "+$body°"
        v < 0f -> "−$body°"
        else -> "$body°"
    }
}

@Composable
internal fun StraightenRuler(
    degrees: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    // The ticks and label. White on the always-dark canvas; the crop panel passes its theme's
    // on-surface-variant so the ruler reads on a light panel too.
    tint: Color = SpektraBrand.onCanvas,
    // The fixed needle; the crop panel passes the theme's primary (amber).
    needle: Color = SpektraBrand.onCanvas,
) {
    val view = LocalView.current
    val current by rememberUpdatedState(degrees)
    val change by rememberUpdatedState(onChange)
    val title = stringResource(R.string.tool_crop_straighten)
    val desc = stringResource(R.string.tool_crop_straighten_desc)
    val resetLabel = stringResource(R.string.tool_crop_straighten_reset)
    val valueText = formatStraighten(degrees)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$title  $valueText",
            color = if (degrees == 0f) tint.copy(alpha = 0.7f) else tint,
            style = MaterialTheme.typography.labelLarge,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(top = 6.dp)
                .semantics {
                    contentDescription = desc
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        degrees,
                        -STRAIGHTEN_MAX_DEGREES..STRAIGHTEN_MAX_DEGREES,
                        steps = 0,
                    )
                    setProgress { target ->
                        change(clampStraighten((target / STRAIGHTEN_STEP).roundToInt() * STRAIGHTEN_STEP))
                        true
                    }
                    customActions = listOf(CustomAccessibilityAction(resetLabel) { change(0f); true })
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (current != 0f) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        change(0f)
                    })
                }
                .pointerInput(Unit) {
                    val pxPerDeg = STRAIGHTEN_DP_PER_DEGREE.dp.toPx()
                    var start = 0f
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { start = current; dx = 0f },
                    ) { input, amount ->
                        input.consume()
                        dx += amount
                        val before = current
                        val next = straightenRulerValue(start, dx, pxPerDeg)
                        if (next != before) {
                            // A tick per whole degree crossed, and on landing in the level detent.
                            if (floor(next) != floor(before) || (next == 0f) != (before == 0f)) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            change(next)
                        }
                    }
                },
        ) {
            val pxPerDeg = STRAIGHTEN_DP_PER_DEGREE.dp.toPx()
            val cx = size.width / 2f
            val base = size.height
            val minor = tint.copy(alpha = 0.35f)
            val major = tint.copy(alpha = 0.7f)
            val halfSpan = cx / pxPerDeg
            val first = ceil(degrees - halfSpan).toInt().coerceAtLeast(-STRAIGHTEN_MAX_DEGREES.toInt())
            val last = floor(degrees + halfSpan).toInt().coerceAtMost(STRAIGHTEN_MAX_DEGREES.toInt())
            for (d in first..last) {
                val x = cx + (d - degrees) * pxPerDeg
                // Fade toward the edges so the ruler reads as a dial.
                val fade = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)
                val len = when {
                    d % 15 == 0 -> base * 0.62f
                    d % 5 == 0 -> base * 0.42f
                    else -> base * 0.24f
                }
                val color = (if (d % 5 == 0) major else minor).let { it.copy(alpha = it.alpha * fade) }
                drawLine(color, Offset(x, base - len), Offset(x, base), strokeWidth = 1.5.dp.toPx())
                if (d == 0) {
                    drawCircle(major.copy(alpha = major.alpha * fade), 2.dp.toPx(), Offset(x, base - len - 5.dp.toPx()))
                }
            }
            // The fixed needle.
            drawLine(
                needle,
                Offset(cx, 0f),
                Offset(cx, base),
                strokeWidth = 2.5.dp.toPx(),
            )
            drawCircle(
                needle,
                3.dp.toPx(),
                Offset(cx, 0f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}
