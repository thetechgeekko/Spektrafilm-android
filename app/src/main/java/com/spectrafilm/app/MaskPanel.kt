/*
 * Spektrafilm for Android — local-adjustment (mask) editor panel. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The user-facing surface of the masking keystone: add a radial mask and limit Exposure / Saturation /
 * Contrast to one area. Edits update [ParamsState.localAdjustments], which `simResultToBitmapGraded`
 * composites on the engine OUTPUT (MaskCompositor) — the film render + parity suite are untouched.
 *
 * v1 is slider-driven (position/size/feather + the adjustment), which is fully verifiable here; the
 * draw-on-the-preview gesture overlay + linear masks come next (gesture feel needs an on-device pass).
 * The panel edits the first component of each mask (masks it creates have exactly one radial); a
 * multi-component mask imported from a recipe still applies fully, the panel just edits its first shape.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectrafilm.app.masks.BlendMode
import com.spectrafilm.app.masks.ColorRange
import com.spectrafilm.app.masks.LocalAdjustment
import com.spectrafilm.app.masks.LuminanceRange
import com.spectrafilm.app.masks.Mask
import com.spectrafilm.app.masks.MaskComponent
import com.spectrafilm.app.masks.TierADelta

@Composable
fun MasksSection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    var selected by remember { mutableIntStateOf(0) }
    val masks = s.localAdjustments

    SectionCard("Masks", expanded, { expanded = it }) {
        Text(
            "Local adjustments: a radial mask limits Exposure / Saturation / Contrast to one area " +
                "(brighten a face, darken a corner). Composited on the final image — the film render is untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            s.localAdjustments = masks + defaultRadialAdjustment()
            selected = masks.size
        }) { Text("+ Add radial mask") }

        if (masks.isEmpty()) return@SectionCard

        val idx = selected.coerceIn(0, masks.lastIndex)
        if (masks.size > 1) {
            SubTabRow(List(masks.size) { "Mask ${it + 1}" }, idx, { selected = it })
        }
        val adj = masks[idx]
        fun set(updated: LocalAdjustment) {
            s.localAdjustments = masks.toMutableList().also { it[idx] = updated }
        }

        // --- Adjustment applied where the mask is opaque (Tier-A, pointwise on the output) ---
        EnhancedSlider("Exposure", adj.delta.exposureEv, -4f..4f,
            { set(adj.copy(delta = adj.delta.copy(exposureEv = it))) },
            step = 0.05f, decimals = 2, default = 0f,
            tooltip = "Brighten / darken inside the mask, in stops (local dodge & burn).")
        EnhancedSlider("Saturation", adj.delta.saturation, -100f..100f,
            { set(adj.copy(delta = adj.delta.copy(saturation = it))) },
            step = 1f, decimals = 0, default = 0f, tooltip = "Colorfulness inside the mask.")
        EnhancedSlider("Contrast", adj.delta.contrast, -100f..100f,
            { set(adj.copy(delta = adj.delta.copy(contrast = it))) },
            step = 1f, decimals = 0, default = 0f, tooltip = "Contrast inside the mask.")

        // --- Shape (radial: position / size / feather) ---
        val comp = adj.mask.components.firstOrNull()
        val radial = comp?.shape as? MaskComponent.Radial
        if (comp != null && radial != null) {
            fun setShape(r: MaskComponent.Radial) =
                set(adj.copy(mask = adj.mask.copy(components = listOf(comp.copy(shape = r)))))
            EnhancedSlider("Position X", radial.cx, 0f..1f, { setShape(radial.copy(cx = it)) },
                step = 0.01f, decimals = 2, default = 0.5f)
            EnhancedSlider("Position Y", radial.cy, 0f..1f, { setShape(radial.copy(cy = it)) },
                step = 0.01f, decimals = 2, default = 0.5f)
            EnhancedSlider("Size", radial.rx, 0.02f..1f, { setShape(radial.copy(rx = it, ry = it)) },
                step = 0.01f, decimals = 2, default = 0.3f, tooltip = "Radius of the mask (fraction of the frame).")
            EnhancedSlider("Feather", radial.feather, 0f..1f, { setShape(radial.copy(feather = it)) },
                step = 0.01f, decimals = 2, default = 0.5f, tooltip = "Softness of the mask edge.")
        }

        SwitchRow("Invert (affect outside)", adj.mask.invert,
            { set(adj.copy(mask = adj.mask.copy(invert = it))) },
            "Apply the adjustment OUTSIDE the mask instead of inside (e.g. darken all but the subject).")
        EnhancedSlider("Mask opacity", adj.mask.opacity, 0f..1f,
            { set(adj.copy(mask = adj.mask.copy(opacity = it))) },
            step = 0.01f, decimals = 2, default = 1f)

        // --- Limit to a tonal range (luminance range mask) ---
        val lum = adj.mask.luminanceRange
        SwitchRow("Limit to tones", lum != null,
            { on -> set(adj.copy(mask = adj.mask.copy(luminanceRange = if (on) LuminanceRange() else null))) },
            "Restrict the adjustment to a brightness range — e.g. only the highlights, or only the shadows.")
        if (lum != null) {
            fun setLum(r: LuminanceRange) = set(adj.copy(mask = adj.mask.copy(luminanceRange = r)))
            EnhancedSlider("Tone min", lum.lumMin, 0f..1f, { setLum(lum.copy(lumMin = it)) },
                step = 0.01f, decimals = 2, default = 0f, tooltip = "Darkest tone the adjustment affects.")
            EnhancedSlider("Tone max", lum.lumMax, 0f..1f, { setLum(lum.copy(lumMax = it)) },
                step = 0.01f, decimals = 2, default = 1f, tooltip = "Brightest tone the adjustment affects.")
            EnhancedSlider("Tone feather", lum.feather, 0.01f..0.5f, { setLum(lum.copy(feather = it)) },
                step = 0.01f, decimals = 2, default = 0.1f, tooltip = "Softness of the tonal range edges.")
            SwitchRow("Invert tones", lum.invert, { setLum(lum.copy(invert = it)) },
                "Affect the tones OUTSIDE the range instead.")
        }

        // --- Limit to a color (color range mask) — "tame the reds, not the skin" ---
        val col = adj.mask.colorRange
        SwitchRow("Limit to a color", col != null,
            { on -> set(adj.copy(mask = adj.mask.copy(colorRange = if (on) ColorRange() else null))) },
            "Restrict the adjustment to one color family — e.g. only the reds, leaving skin untouched.")
        if (col != null) {
            fun setCol(r: ColorRange) = set(adj.copy(mask = adj.mask.copy(colorRange = r)))
            EnhancedSlider("Target red", col.targetR, 0f..1f, { setCol(col.copy(targetR = it)) },
                step = 0.01f, decimals = 2, default = 0.5f, tooltip = "The color to affect — red component.")
            EnhancedSlider("Target green", col.targetG, 0f..1f, { setCol(col.copy(targetG = it)) },
                step = 0.01f, decimals = 2, default = 0.5f, tooltip = "The color to affect — green component.")
            EnhancedSlider("Target blue", col.targetB, 0f..1f, { setCol(col.copy(targetB = it)) },
                step = 0.01f, decimals = 2, default = 0.5f, tooltip = "The color to affect — blue component.")
            EnhancedSlider("Color range", col.tolerance, 0.02f..1f, { setCol(col.copy(tolerance = it)) },
                step = 0.01f, decimals = 2, default = 0.6f, tooltip = "How wide a range of colors counts as a match.")
            EnhancedSlider("Color feather", col.feather, 0.01f..0.5f, { setCol(col.copy(feather = it)) },
                step = 0.01f, decimals = 2, default = 0.1f, tooltip = "Softness of the color-selection edges.")
            SwitchRow("Invert color", col.invert, { setCol(col.copy(invert = it)) },
                "Affect the colors OUTSIDE the range instead.")
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                s.localAdjustments = masks.toMutableList().also { it.removeAt(idx) }
                selected = 0
            }) { Text("Delete mask") }
        }
    }
}

/** A centered radial mask with a no-op adjustment — the starting point the user then dials in. */
private fun defaultRadialAdjustment() = LocalAdjustment(
    Mask(listOf(Mask.Component(BlendMode.ADD, MaskComponent.Radial(0.5f, 0.5f, 0.3f, 0.3f, 0.5f)))),
    TierADelta(),
)
