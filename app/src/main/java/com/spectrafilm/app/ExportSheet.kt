/*
 * Spektrafilm for Android — Lightroom-style export options sheet (§6a/§6b). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A format-aware export bottom sheet modeled on Lightroom mobile's export UI (RE'd from lrmobile:
 * format → format-specific options → dimensions → colour → naming → metadata), replacing the old
 * "export with whatever's in Settings" flow. UI only — the chosen [ExportOptions] drive the
 * post-engine encode in MainActivity, so there is no engine param and no parity impact.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.ColorSpace

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    options: ExportOptions,
    onOptionsChange: (ExportOptions) -> Unit,
    colorSpace: ColorSpace,
    onColorSpaceChange: (ColorSpace) -> Unit,
    cctf: Boolean,
    onCctfChange: (Boolean) -> Unit,
    keepGps: Boolean,
    onKeepGpsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Export", style = MaterialTheme.typography.headlineSmall)
            val isSceneLinear = options.format == ExportFormat.SCENE_LINEAR_TIFF

            // --- Format (+ format-specific quality) ---
            Dropdown(
                label = "Format",
                selected = options.format,
                options = ExportFormat.entries.toList(),
                display = { it.display },
                onSelect = { onOptionsChange(options.copy(format = it)) },
            )
            if (options.format == ExportFormat.JPEG || options.format == ExportFormat.ULTRA_HDR) {
                IntSlider(
                    label = "Quality",
                    value = options.jpegQuality,
                    range = 10..100,
                    default = 90,
                    onValueChange = { onOptionsChange(options.copy(jpegQuality = it)) },
                )
            }
            if (isSceneLinear) {
                Text(
                    "Exports the decoded scene-linear input — the linear RGB before the film " +
                        "simulation — as an untagged 32-bit float TIFF, for grading in another app. " +
                        "Colour space and metadata options don't apply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // --- Dimensions (post-render downscale, like Lightroom) ---
            Text("Size", style = MaterialTheme.typography.titleSmall)
            if (options.format.isHighBitDepth()) {
                Text(
                    "High-bit-depth formats export at full resolution.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Dropdown(
                    label = "Dimensions",
                    selected = options.size,
                    options = ExportSize.entries.toList(),
                    display = { it.label },
                    onSelect = { onOptionsChange(options.copy(size = it)) },
                )
                if (options.size == ExportSize.CUSTOM) {
                    OutlinedTextField(
                        value = if (options.customLongEdge <= 0) "" else options.customLongEdge.toString(),
                        onValueChange = { v ->
                            onOptionsChange(options.copy(customLongEdge = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0))
                        },
                        label = { Text("Long edge (px)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Clamped to ${ExportOptions.MIN_CUSTOM_EDGE}–${ExportOptions.MAX_CUSTOM_EDGE} px; the photo is never enlarged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!isSceneLinear) {
                HorizontalDivider()

                // --- Colour ---
                Dropdown(
                    label = "Color space",
                    selected = colorSpace,
                    options = ColorSpace.entries.toList(),
                    display = { it.label() },
                    onSelect = onColorSpaceChange,
                )
                SwitchRow(
                    label = "Encode color transfer (CCTF)",
                    checked = cctf,
                    onCheckedChange = onCctfChange,
                    tooltip = "On for normal viewing. Off writes scene-linear values (for further grading).",
                )
            }

            HorizontalDivider()

            // --- Naming + metadata ---
            OutlinedTextField(
                value = options.customName,
                onValueChange = { onOptionsChange(options.copy(customName = it)) },
                label = { Text("File name (optional)") },
                placeholder = { Text("Spektrafilm_<timestamp>") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!isSceneLinear) {
                SwitchRow(
                    label = "Include location (GPS)",
                    checked = keepGps,
                    onCheckedChange = onKeepGpsChange,
                    tooltip = "Copy GPS coordinates from the source photo into the exported file.",
                )
            }

            // --- Actions ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export") }
            }

            Text(
                "Film modeling powered by spektrafilm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** Friendly label for an output [ColorSpace] in the export sheet. */
private fun ColorSpace.label(): String = when (this) {
    ColorSpace.SRGB -> "sRGB"
    ColorSpace.ADOBE_RGB -> "Adobe RGB (1998)"
    ColorSpace.PROPHOTO -> "ProPhoto RGB"
    ColorSpace.REC2020 -> "Rec. 2020"
    ColorSpace.ACES2065_1 -> "ACES2065-1"
    ColorSpace.LINEAR_SRGB -> "Linear sRGB"
}
