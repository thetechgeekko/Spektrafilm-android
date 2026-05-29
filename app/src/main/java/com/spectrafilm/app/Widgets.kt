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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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

private fun snap(v: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (step <= 0f) return v.coerceIn(range.start, range.endInclusive)
    val snapped = range.start + (((v - range.start) / step).roundToInt()) * step
    return snapped.coerceIn(range.start, range.endInclusive)
}

private fun formatValue(v: Float, decimals: Int): String =
    if (decimals <= 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
