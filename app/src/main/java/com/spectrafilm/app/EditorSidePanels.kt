/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Preset, source and export panels (#273, third MainActivity split). Stateless over their
 * parameters; moved verbatim out of MainActivity.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Panels that wrap existing functionality (preset/source) for the new layout
// ---------------------------------------------------------------------------

@Composable
internal fun PresetPanel(
    builtInGroups: Map<String, List<BuiltInPreset>>,
    onApplyBuiltIn: (BuiltInPreset) -> Unit,
    /** The built-in look currently on the photo ("" when the photo has moved on from it). */
    appliedBuiltInId: String = "",
    /** Tiles of the photo through each look; null without a photo (no strip then). */
    previewHost: StockPreviewHost? = null,
    stripSourceKey: Any? = null,
    stripPaused: Boolean = false,
    amount: Float,
    amountEnabled: Boolean,
    onAmountChange: (Float) -> Unit,
    presets: List<String>,
    selected: String,
    name: String,
    onNameChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSave: () -> Unit,
    nameExists: (String) -> Boolean,
    resolveName: (String) -> String,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportSelected: () -> Unit,
    onCopySettings: () -> Unit,
    canPasteSettings: Boolean,
    onPasteSettings: () -> Unit,
    /** Display name of the photo whose edit "Apply previous edit" would use; null when none. */
    previousEditName: String?,
    onApplyPrevious: () -> Unit,
    onExportLut: (size: Int, clf: Boolean) -> Unit,
    bakingLut: Boolean,
    // Versions of the open photo, under the copy/paste row (see VersionsSection).
    versionsContent: @Composable () -> Unit = {},
) {
    if (builtInGroups.isNotEmpty()) {
        Text(
            stringResource(R.string.editor_preset_builtin_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        val all = remember(builtInGroups) { builtInGroups.values.flatten() }
        // GroupedDropdown has always rendered a spec line and a summary under each row
        // (it is what the film/paper pickers use); this panel passed neither, so 27
        // curated looks arrived as 27 bare names. Filling them in costs one map and
        // turns the list into something you can actually choose from.
        val ctx = LocalContext.current
        val positiveLabel = stringResource(R.string.editor_preset_scan_positive)
        val builtInDropdownGroups = remember(builtInGroups, positiveLabel) {
            builtInGroups.map { (g, ps) ->
                DropdownGroup(
                    g,
                    ps.map { p ->
                        DropdownOption(
                            id = p.id,
                            label = p.name,
                            spec = BuiltInPresets.specLine(
                                filmName = StockCatalog.displayName(ctx, p.filmProfile),
                                printName = StockCatalog.displayName(ctx, p.printProfile),
                                scanFilm = p.scanFilm,
                                positiveLabel = positiveLabel,
                            ),
                            summary = p.description,
                        )
                    },
                )
            }
        }
        // One tap applies a look. The strip shows the photo through every look (M3
        // uncontained carousel, as on the Film tab); the list below is its "show all"
        // (carousel a11y: every item reachable without horizontal scrolling) and applies
        // on pick too. There is no separate Apply button any more: the app bar's Undo
        // takes a look back, and the Amount slider below fades it.
        val apply = { id: String -> all.firstOrNull { it.id == id }?.let(onApplyBuiltIn); Unit }
        if (previewHost != null) {
            FilmStockStrip(
                modifier = Modifier.helpSpot(LocalQuickHelp.current, HelpSpot.FILMS),
                selectedId = appliedBuiltInId,
                groups = builtInDropdownGroups,
                previewHost = previewHost,
                sourceKey = stripSourceKey,
                paused = stripPaused,
                onSelect = apply,
            )
        }
        GroupedDropdown(
            label = stringResource(R.string.editor_preset_builtin_label),
            selectedId = appliedBuiltInId,
            groups = builtInDropdownGroups,
            onSelect = apply,
        )
        val current = all.firstOrNull { it.id == appliedBuiltInId }
        if (current?.description?.isNotBlank() == true) {
            Text(
                current.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Divider()
        Text(
            stringResource(R.string.editor_preset_yours_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
    }

    // Lightroom-style preset amount: cross-fade the last-applied preset against the
    // look that preceded it. Shown only once a preset has been applied this session.
    if (amountEnabled) {
        EnhancedSlider(
            label = stringResource(R.string.editor_preset_amount),
            value = amount * 100f,
            range = 0f..100f,
            step = 1f,
            decimals = 0,
            default = 100f,
            tooltip = stringResource(R.string.editor_preset_amount_tooltip),
            onValueChange = { onAmountChange((it / 100f).coerceIn(0f, 1f)) },
        )
        Divider()
    }

    OutlinedTextField(
        value = name, onValueChange = onNameChange,
        label = { Text(stringResource(R.string.editor_preset_name)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // The stored name is not always the typed one (safeName rewrites punctuation), and
    // the save status used to claim otherwise. Say so before the save, not after.
    val storedName = resolveName(name)
    if (name.isNotBlank() && storedName != name.trim()) {
        Text(
            stringResource(R.string.editor_preset_name_resolved, storedName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    var confirmOverwrite by remember { mutableStateOf<String?>(null) }
    Button(
        onClick = {
            // Saving over an existing preset was silent and unrecoverable. Unlike a
            // delete — where the user knows what they are destroying — a collision here
            // can be news to them, so this prevents rather than offers to repair.
            if (name.isNotBlank() && nameExists(name)) confirmOverwrite = storedName else onSave()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ButtonIcon(SpectraIcons.Presets)
        Text(stringResource(R.string.editor_preset_save))
    }
    confirmOverwrite?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmOverwrite = null },
            title = { Text(stringResource(R.string.editor_preset_overwrite_title, target)) },
            text = { Text(stringResource(R.string.editor_preset_overwrite_body)) },
            confirmButton = {
                TextButton(onClick = { confirmOverwrite = null; onSave() }) {
                    Text(stringResource(R.string.editor_preset_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            },
        )
    }
    if (presets.isNotEmpty()) {
        // The dropdown fell back to the first preset for DISPLAY only, while Apply and
        // Delete gate on the selection itself (— `selectedPreset.isNotBlank()`). On a
        // fresh launch that left a named preset on screen beside two buttons that looked
        // armed and silently did nothing until you opened the dropdown and re-picked the
        // very item already showing. Adopt what is displayed as the real selection.
        //
        // Not just the EMPTY case: the selection is restored from the editor session, so
        // it can also name a preset that has since been deleted. The field then showed a
        // preset that no longer exists and every action targeted it. Fall back whenever
        // the selection is not one of the presets actually on disk.
        val effective = selected.takeIf { it in presets } ?: presets.first()
        LaunchedEffect(effective) { if (selected != effective) onSelect(effective) }
        Dropdown(
            label = stringResource(R.string.editor_preset_saved),
            selected = effective,
            options = presets,
            display = { it },
            onSelect = onSelect,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Tonal: Save above is the panel's one filled action (m3-design-language,
            // "one high-emphasis action per screen").
            FilledTonalButton(onClick = onApply, modifier = Modifier.weight(1f)) {
                ButtonIcon(SpectraIcons.Confirm)
                Text(stringResource(R.string.editor_preset_apply))
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.editor_preset_delete)) }
        }
        // Exports the selected preset itself. The "Export current look" button below
        // serialises live editor state, which is a different thing entirely.
        OutlinedButton(onClick = onExportSelected, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.editor_preset_export_selected, effective))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.editor_preset_import_json)) }
        OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.editor_preset_export_share)) }
    }
    // Copy the current edit and paste it onto another image (session clipboard).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopySettings, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.editor_preset_copy_settings)) }
        OutlinedButton(
            onClick = onPasteSettings,
            enabled = canPasteSettings,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.editor_preset_paste_settings)) }
    }
    // The previous photo's look without a Copy first: the same whole-look replace as Paste.
    OutlinedButton(
        onClick = onApplyPrevious,
        enabled = previousEditName != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.editor_preset_apply_previous)) }
    Text(
        if (previousEditName != null) stringResource(R.string.editor_preset_previous_from, previousEditName)
        else stringResource(R.string.editor_preset_previous_none),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Divider()
    versionsContent()
    Divider()
    var lutSize by remember { mutableIntStateOf(33) }
    var lutClf by remember { mutableStateOf(false) }
    Text(stringResource(R.string.editor_lut_size), style = MaterialTheme.typography.bodySmall)
    ConnectedChoiceRow(
        options = listOf(17, 33, 65).map { it to "$it³" },
        selected = lutSize,
        onSelect = { lutSize = it },
    )
    Text(stringResource(R.string.editor_lut_format), style = MaterialTheme.typography.bodySmall)
    ConnectedChoiceRow(
        options = listOf(false to ".cube", true to ".clf"),
        selected = lutClf,
        onSelect = { lutClf = it },
    )
    FilledTonalButton(
        onClick = { onExportLut(lutSize, lutClf) },
        enabled = !bakingLut,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (bakingLut) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.editor_lut_baking))
        } else {
            Text(stringResource(R.string.editor_lut_export, if (lutClf) ".clf" else ".cube", lutSize))
        }
    }
    Text(
        stringResource(R.string.editor_lut_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SourcePanel(
    sourceName: String,
    status: String,
    showHistogram: Boolean,
    onToggleHistogram: () -> Unit,
    hasRecipe: Boolean,
    needsAuthorization: Boolean,
    onReauthorize: () -> Unit,
    onPickPhoto: () -> Unit,
    onOpenRaw: () -> Unit,
    onUseDemo: () -> Unit,
    onResetEdits: () -> Unit,
) {
    Text(stringResource(R.string.editor_source_label, sourceName), style = MaterialTheme.typography.bodySmall)
    Text(status, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (needsAuthorization) {
        Button(onClick = onReauthorize, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.editor_source_choose_again))
        }
        Text(
            stringResource(R.string.editor_source_permission_expired),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPickPhoto, modifier = Modifier.weight(1f)) {
            ButtonIcon(SpectraIcons.OpenPhoto)
            Text(stringResource(R.string.editor_source_pick_photo))
        }
        // Tonal beside the filled Pick photo: two filled buttons side by side read as two
        // equal primaries.
        FilledTonalButton(onClick = onOpenRaw, modifier = Modifier.weight(1f)) {
            ButtonIcon(SpectraIcons.ImportRaw)
            Text(stringResource(R.string.editor_source_open_raw))
        }
    }
    OutlinedButton(onClick = onUseDemo, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.editor_source_use_demo)) }

    FilterChip(
        selected = showHistogram,
        onClick = onToggleHistogram,
        label = { Text(stringResource(R.string.editor_histogram)) },
    )
    Text(
        stringResource(R.string.editor_source_histogram_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (hasRecipe) {
        // The one editor action with no way back: onResetEdits clears the undo history
        // along with the edits, so a mis-tap here cannot be walked back the way every
        // other destructive thing in this screen can. Hence a dialog rather than the
        // Undo snackbar used for preset deletion.
        var confirmReset by remember { mutableStateOf(false) }
        Text(
            stringResource(R.string.editor_source_autosaved),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { confirmReset = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.editor_source_reset_edits))
        }
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text(stringResource(R.string.editor_reset_edits_confirm_title)) },
                text = { Text(stringResource(R.string.editor_reset_edits_confirm_body)) },
                confirmButton = {
                    TextButton(
                        onClick = { confirmReset = false; onResetEdits() },
                    ) {
                        Text(stringResource(R.string.editor_source_reset_edits))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReset = false }) {
                        Text(stringResource(R.string.editor_cancel))
                    }
                },
            )
        }
    }
    Text(
        stringResource(R.string.editor_source_tip),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Film modeling powered by spektrafilm (GPLv3). Preview is downscaled for " +
            "interactivity; export renders at full resolution.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The full-screen render mask. Rendering really is modal: the export reads the live edit
 * state, so this swallows every pointer event until it finishes or is cancelled. The result
 * is NOT shown here: the editor announces it with a Snackbar and the pill's check, and the
 * editor stays usable.
 */
@Composable
internal fun ExportMask(onCancel: () -> Unit, photo: Bitmap? = null) {
    // This overlay swallows every pointer event, so Back is the only system gesture that
    // can reach anything while it is up. Without a handler of its own it would fall
    // through to the editor root, where Back leaves the app -- so a stray Back during a
    // render could send the app away mid-export. Inert while rendering, because Cancel is
    // a deliberate press and not a reflex.
    BackHandler(enabled = true) { }
    // API 33+: the photo develops in a darkroom tray while the export runs (ExportDevelopTray.kt).
    // Null below 33, with animations off or without a preview: the plain scrim and indicator.
    val tray = rememberDevelopTray(photo)
    val darkroom = tray != null
    Box(
        Modifier
            .fillMaxSize()
            .background(if (darkroom) SpektraBrand.darkroomScrim else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (tray != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DevelopTrayImage(
                tray,
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 144.dp),
            )
        }
        Column(
            Modifier
                .then(if (darkroom) Modifier.align(Alignment.BottomCenter).navigationBarsPadding() else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The export's progress as a filling pill, the same one the Export button showed
            // (no spinning circle, user 2026-11-08).
            ExportProgressPill(onDarkroom = darkroom)
            Button(onClick = onCancel) { Text(stringResource(R.string.editor_export_cancel)) }
            Text(
                stringResource(R.string.editor_export_rendering),
                color = if (darkroom) SpektraBrand.onDarkroom else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

