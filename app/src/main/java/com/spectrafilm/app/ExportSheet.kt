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

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
    // Saved export presets: their names, the one the sheet's state matches (null = custom), and
    // the three things you can do with them. Defaults keep the sheet usable without presets.
    presetNames: List<String> = emptyList(),
    selectedPreset: String? = null,
    onSelectPreset: (String) -> Unit = {},
    onSavePreset: (String) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    /** Current editor preview, for the film-border thumbnail. Read only, never recycled here. */
    previewBitmap: Bitmap? = null,
    /** The frame caption as worded for the open photo, or null (none, or no single photo). */
    frameCaption: String? = null,
    /**
     * Apply this edit and these export settings to more photos (batch export), or null when
     * that is not possible right now. The sheet only offers it; the caller picks the photos.
     */
    onBatchExport: (() -> Unit)? = null,
    /**
     * Export the open photo and one more as a half-frame pair (HalfFramePair.kt), or null when
     * that is not possible right now. The caller picks the second photo.
     */
    onHalfFramePair: (() -> Unit)? = null,
    /**
     * Non-null: the sheet exports this many photos from the Library, each with its own saved
     * edit (LibraryExport.kt). Their colour space is part of each edit, so the sheet hides the
     * Colour section, the scene-linear format (it exports the decode, not an edit) and the
     * split button's menu of single-photo variants.
     */
    photoCount: Int? = null,
    /** False draws the same body on a plain surface: for previews and screenshot tests (#456). */
    inSheet: Boolean = true,
) {
    var savingPreset by remember { mutableStateOf(false) }
    var managingPresets by remember { mutableStateOf(false) }
    // Dropdown's `display` is a plain (non-composable) lambda, so stringResource cannot go inside
    // it. LocalResources is the lint-approved read and, unlike LocalContext.current.getString,
    // recomposes when the configuration changes (locale, font scale).
    val resources = LocalResources.current
    ExportSheetFrame(inSheet, onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The app runs edge-to-edge, and `adjustResize` alone does not lift Compose
                // content above the keyboard: without this the IME covers the file-name field
                // -- the one control in the app a user must type into to name an export.
                // Order matters: imePadding has to come BEFORE verticalScroll, or the scroll
                // viewport is sized without the inset and the field stays underneath.
                // ModalBottomSheet already handles the navigation-bar inset itself.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val isSceneLinear = options.format == ExportFormat.SCENE_LINEAR_TIFF
            val manyPhotos = photoCount != null
            // Header: what is being saved (the photo) and how (one summary line), so the
            // choices below read as optional changes rather than a form to fill in.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (previewBitmap != null && !previewBitmap.isRecycled) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (photoCount != null) {
                            pluralStringResource(R.plurals.library_export_title, photoCount, photoCount)
                        } else {
                            stringResource(R.string.tool_export_title)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    val summary = buildList {
                        add(resources.getString(options.format.labelRes()))
                        if (!options.format.isHighBitDepth()) add(resources.getString(options.size.labelRes))
                        if (!isSceneLinear && !manyPhotos) add(resources.getString(colorSpace.labelRes()))
                    }.joinToString(" · ")
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (manyPhotos) {
                Text(
                    stringResource(R.string.library_export_own_edits),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // --- Actions ---
            // The sheet's one action comes first, under the summary: with the remembered
            // settings, export is open sheet -> Export. Swipe down or tap outside to cancel
            // (M3 modal bottom sheet), so there is no Cancel button competing with it.
            if (manyPhotos) {
                Button(
                    onClick = onExport,
                    enabled = !isSceneLinear,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(SpectraIcons.Export, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tool_export_action))
                }
            } else Row(modifier = Modifier.fillMaxWidth()) {
                // Split button: the action, plus a menu of its variants (save these settings as a
                // preset, manage presets). The two halves share one row and read as one control.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Button(
                        onClick = onExport,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                    ) {
                        Icon(SpectraIcons.Export, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tool_export_action))
                    }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        Button(
                            onClick = { menu = true },
                            shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.heightIn(min = 56.dp).semantics {
                                contentDescription = resources.getString(R.string.tool_export_more_options)
                            },
                        ) {
                            Icon(SpectraIcons.ChevronDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_export_preset_save)) },
                                onClick = { menu = false; savingPreset = true },
                            )
                            if (presetNames.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tool_export_preset_manage)) },
                                    onClick = { menu = false; managingPresets = true },
                                )
                            }
                            // Shown disabled rather than hidden when this photo or format
                            // cannot use them, so the menu does not change shape under the
                            // user (m3-design-language, menus: disabled, not removed).
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_export_batch)) },
                                enabled = onBatchExport != null && !isSceneLinear,
                                onClick = { menu = false; onBatchExport?.invoke() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_export_half_frame_pair)) },
                                enabled = onHalfFramePair != null,
                                onClick = { menu = false; onHalfFramePair?.invoke() },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Saved presets: one tap sets every choice below ---
            if (presetNames.isNotEmpty()) {
                val customLabel = stringResource(R.string.tool_export_preset_custom)
                Dropdown(
                    label = stringResource(R.string.tool_export_preset),
                    selected = selectedPreset,
                    options = listOf<String?>(null) + presetNames,
                    display = { it ?: customLabel },
                    onSelect = { name -> if (name != null) onSelectPreset(name) },
                )
            }

            // --- Format (+ format-specific quality) ---
            Dropdown(
                label = stringResource(R.string.tool_export_format),
                selected = options.format,
                options = ExportFormat.entries.filter { !manyPhotos || it != ExportFormat.SCENE_LINEAR_TIFF },
                display = { resources.getString(it.labelRes()) },
                onSelect = { onOptionsChange(options.copy(format = it)) },
            )
            if (options.format == ExportFormat.JPEG || options.format == ExportFormat.ULTRA_HDR) {
                IntSlider(
                    label = stringResource(R.string.tool_export_quality),
                    value = options.jpegQuality,
                    range = 10..100,
                    default = 90,
                    onValueChange = { onOptionsChange(options.copy(jpegQuality = it)) },
                )
            }
            if (isSceneLinear) {
                Text(
                    stringResource(R.string.tool_export_scene_linear_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // --- Dimensions (post-render downscale, like Lightroom) ---
            Text(
                stringResource(R.string.tool_export_size_heading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (options.format.isHighBitDepth()) {
                Text(
                    stringResource(R.string.tool_export_high_bit_depth_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Dropdown(
                    label = stringResource(R.string.tool_export_dimensions),
                    selected = options.size,
                    options = ExportSize.entries.toList(),
                    display = { resources.getString(it.labelRes) },
                    onSelect = { onOptionsChange(options.copy(size = it)) },
                )
                if (options.size == ExportSize.CUSTOM) {
                    OutlinedTextField(
                        value = if (options.customLongEdge <= 0) "" else options.customLongEdge.toString(),
                        onValueChange = { v ->
                            onOptionsChange(options.copy(customLongEdge = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0))
                        },
                        label = { Text(stringResource(R.string.tool_export_long_edge)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.tool_export_custom_clamp,
                            ExportOptions.MIN_CUSTOM_EDGE,
                            ExportOptions.MAX_CUSTOM_EDGE,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Output sharpening: applied after the resize, so it belongs with Dimensions and
                // hides with it for the high-bit-depth formats that never get it. Four options:
                // a single-select connected row, not a dropdown (M3: menus are for long lists).
                Text(
                    stringResource(R.string.tool_export_sharpen),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // At large text sizes four segments cannot hold "Standard" on a phone (#456: cut
                // to "Stan" at 2x), so the group becomes two connected rows of two.
                val choices = OutputSharpen.entries
                val perRow = if (LocalDensity.current.fontScale >= 1.5f) 2 else choices.size
                Column(
                    Modifier.fillMaxWidth().semantics { selectableGroup() },
                    verticalArrangement = Arrangement.spacedBy(CONNECTED_GAP),
                ) {
                    choices.chunked(perRow).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(CONNECTED_GAP),
                        ) {
                            row.forEachIndexed { index, choice ->
                                ConnectedToggle(
                                    label = stringResource(choice.labelRes),
                                    selected = options.sharpen == choice,
                                    shape = connectedShape(index, row.size),
                                    onClick = { onOptionsChange(options.copy(sharpen = choice)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Film border (around the photo; Bitmap formats only) ---
            FilmBorderSection(
                options = options,
                onOptionsChange = onOptionsChange,
                previewBitmap = previewBitmap,
                caption = frameCaption,
                captionKnown = !manyPhotos,
            )

            if (!isSceneLinear && !manyPhotos) {
                HorizontalDivider()

                // --- Colour ---
                Dropdown(
                    label = stringResource(R.string.tool_export_color_space),
                    selected = colorSpace,
                    options = ColorSpace.entries.toList(),
                    display = { resources.getString(it.labelRes()) },
                    onSelect = onColorSpaceChange,
                )
                SwitchRow(
                    label = stringResource(R.string.tool_export_cctf),
                    checked = cctf,
                    onCheckedChange = onCctfChange,
                    tooltip = stringResource(R.string.tool_export_cctf_help),
                )
                // Soft proof: what the preview, rendered in this space, says about the file.
                ExportProofSection(
                    previewBitmap = previewBitmap,
                    colorSpace = colorSpace,
                    cctf = cctf,
                    spaceName = resources.getString(colorSpace.labelRes()),
                    format = options.format,
                    sharpened = options.effectiveSharpen() != OutputSharpen.NONE,
                )
            }

            HorizontalDivider()

            // --- Naming + metadata ---
            OutlinedTextField(
                value = options.customName,
                onValueChange = { onOptionsChange(options.copy(customName = it)) },
                label = {
                    // One line: a wrapped label overlaps the outline at large text (#456).
                    Text(
                        stringResource(R.string.tool_export_file_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                placeholder = { Text(stringResource(R.string.tool_export_file_name_placeholder)) },
                supportingText = { Text(stringResource(R.string.tool_export_file_name_tokens)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!isSceneLinear) {
                SwitchRow(
                    label = stringResource(R.string.tool_export_include_gps),
                    checked = keepGps,
                    onCheckedChange = onKeepGpsChange,
                    tooltip = stringResource(R.string.tool_export_include_gps_help),
                )
            }

            if (savingPreset) {
                var name by remember { mutableStateOf(selectedPreset ?: "") }
                val cleaned = ExportPresetCodec.cleanName(name)
                AlertDialog(
                    onDismissRequest = { savingPreset = false },
                    title = { Text(stringResource(R.string.tool_export_preset_save_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { if (it.length <= ExportPresetCodec.MAX_NAME_CHARS) name = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.tool_export_preset_name)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (presetNames.any { it.equals(cleaned, ignoreCase = true) }) {
                                Text(
                                    stringResource(R.string.tool_export_preset_overwrite),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { savingPreset = false; onSavePreset(cleaned) },
                            enabled = cleaned.isNotEmpty(),
                        ) { Text(stringResource(R.string.tool_export_preset_save_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { savingPreset = false }) { Text(stringResource(R.string.tool_cancel)) }
                    },
                )
            }

            if (managingPresets) {
                AlertDialog(
                    onDismissRequest = { managingPresets = false },
                    title = { Text(stringResource(R.string.tool_export_preset_manage)) },
                    text = {
                        // AlertDialog bounds the text slot to the window; scroll inside it
                        // so all 30 presets stay reachable on a phone.
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            for (preset in presetNames) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(preset, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onDeletePreset(preset) }) {
                                        Text(stringResource(R.string.tool_export_preset_delete))
                                    }
                                }
                            }
                            if (presetNames.isEmpty()) {
                                Text(stringResource(R.string.tool_export_preset_none))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { managingPresets = false }) { Text(stringResource(R.string.tool_done)) }
                    },
                )
            }

            Text(
                stringResource(R.string.tool_export_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** The modal sheet in the app; a plain surface where a window cannot open (layoutlib). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheetFrame(inSheet: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (inSheet) {
        ModalBottomSheet(onDismissRequest = onDismiss, content = content)
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(content = content) }
    }
}

@Composable
private fun FilmBorderSection(
    options: ExportOptions,
    onOptionsChange: (ExportOptions) -> Unit,
    previewBitmap: Bitmap?,
    caption: String?,
    captionKnown: Boolean,
) {
    Text(
        stringResource(R.string.tool_export_border_heading),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    if (options.format.isHighBitDepth() || options.format == ExportFormat.SCENE_LINEAR_TIFF) {
        Text(
            stringResource(R.string.tool_export_border_high_bit_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // The Frame tab's controls, so a library export (no editor) can frame its photos too.
    FrameControls(
        spec = options.frameSpec,
        onChange = { onOptionsChange(options.withFrame(it)) },
        preview = previewBitmap,
        frameless = false,
        caption = caption,
        captionKnown = captionKnown,
    )
    if (options.frameSpec.isNone) return
    Text(
        stringResource(R.string.tool_export_border_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // A small framed copy of the current preview, painted by the same code as the export, so
    // what the sheet shows is what the file gets. Painted off the main thread under a preview
    // read lease, not in composition (#270); the old thumbnail stays up until the new one is ready.
    rememberFramedPreview(previewBitmap, options.frameSpec, BORDER_THUMB_EDGE, caption)?.let { framed ->
        Image(
            bitmap = framed.asImageBitmap(),
            contentDescription = stringResource(
                R.string.tool_export_border_preview_cd,
                stringResource(options.border.labelRes),
            ),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp),
        )
    }
}

/** Long edge of the border thumbnail in the export sheet. */
private const val BORDER_THUMB_EDGE = 640

/** Localised display name for an [ExportFormat] (the enum's `display` stays as the log/diagnostic id). */
@StringRes
private fun ExportFormat.labelRes(): Int = when (this) {
    ExportFormat.PNG -> R.string.tool_export_format_png
    ExportFormat.JPEG -> R.string.tool_export_format_jpeg
    ExportFormat.ULTRA_HDR -> R.string.tool_export_format_ultra_hdr
    ExportFormat.TIFF -> R.string.tool_export_format_tiff
    ExportFormat.PNG16 -> R.string.tool_export_format_png16
    ExportFormat.TIFF32F -> R.string.tool_export_format_tiff32f
    ExportFormat.SCENE_LINEAR_TIFF -> R.string.tool_export_format_scene_linear_tiff
}

/** Friendly label for an output [ColorSpace] in the export sheet. */
@StringRes
private fun ColorSpace.labelRes(): Int = when (this) {
    ColorSpace.SRGB -> R.string.tool_color_space_srgb
    ColorSpace.ADOBE_RGB -> R.string.tool_color_space_adobe_rgb
    ColorSpace.PROPHOTO -> R.string.tool_color_space_prophoto
    ColorSpace.REC2020 -> R.string.tool_color_space_rec2020
    ColorSpace.ACES2065_1 -> R.string.tool_color_space_aces2065_1
    ColorSpace.LINEAR_SRGB -> R.string.tool_color_space_linear_srgb
}
