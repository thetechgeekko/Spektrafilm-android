/*
 * Spektrafilm for Android — Settings screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * App-level settings persisted via AppSettings (SharedPreferences): default output
 * color space, preview resolution, default film/print profiles, export format +
 * quality, theme, a "Show onboarding again" action, a "Report an issue" entry (and a
 * "View issues" link), and rows for How to use, About and Diagnostics. Stateless over the persisted values:
 * it renders [SettingsUiState] and reports edits as [SettingsAction]s to
 * [SettingsViewModel], which writes them to AppSettings. Theme and dynamic colour
 * re-apply immediately (the activity reads them from the same state); the render
 * defaults (preview size, default profiles, output space) apply on the next launch.
 */
package com.spectrafilm.app

import androidx.compose.ui.graphics.vector.ImageVector
import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.ColorSpace

/**
 * The Settings UI. [state] is the saved settings; [onAction] reports an edit.
 * [filmGroups]/[printGroups] are the catalog-grouped profile options (may be empty
 * before the engine is ready); [onShowOnboarding] re-launches the welcome flow.
 *
 * Laid out as M3 grouped (segmented) lists: a short heading per group, rows in one
 * rounded container with 2dp gaps. Switches act at once; one-of-many choices open a
 * radio dialog and apply on tap. About, How to use and Diagnostics are rows here, so
 * the editor's overflow menu stays short.
 */
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    filmGroups: List<DropdownGroup>,
    printGroups: List<DropdownGroup>,
    onShowOnboarding: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenHowTo: () -> Unit = {},
    updateUi: UpdateUiState = UpdateUiState(),
    selectedDetail: SettingsDetail? = null,
    onUpdateAction: (UpdateAction) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
) {
    val ctx = LocalContext.current
    val themeLabels = ThemeMode.entries.associateWith { stringResource(it.labelRes) }
    val formatLabels = ExportFormat.entries.associateWith { stringResource(it.labelRes) }

    val opensInBrowser = stringResource(R.string.screen_opens_in_browser)
    // Label only: each link row keeps its own click action (null action merges).
    val linkSemantics = Modifier.semantics { onClick(label = opensInBrowser, action = null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        // --- Appearance ---
        SettingsGroup(
            stringResource(R.string.screen_settings_appearance),
            buildList<@Composable () -> Unit> {
                add {
                    ChoiceRow(
                        title = stringResource(R.string.screen_settings_theme),
                        icon = MaterialSymbols.Contrast,
                        selected = state.theme,
                        options = ThemeMode.entries.toList(),
                        display = { themeLabels.getValue(it) },
                        onSelect = { onAction(SettingsAction.SetTheme(it)) },
                    )
                }
                // Only offered where the platform can actually do it (API 31+).
                if (dynamicColorSupported) add {
                    SettingToggleRow(
                        title = stringResource(R.string.screen_settings_dynamic_color),
                        icon = MaterialSymbols.Palette,
                        note = stringResource(R.string.screen_settings_dynamic_color_note),
                        checked = state.dynamicColor,
                        onCheckedChange = { onAction(SettingsAction.SetDynamicColor(it)) },
                    )
                }
                add {
                    SettingToggleRow(
                        title = stringResource(R.string.screen_settings_full_screen),
                        icon = MaterialSymbols.Fullscreen,
                        note = stringResource(R.string.screen_settings_full_screen_note),
                        checked = state.fullScreenEditing,
                        onCheckedChange = { onAction(SettingsAction.SetFullScreenEditing(it)) },
                    )
                }
                // Per-app language lives in the system (Android 13+); this row only opens it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && appLanguageChoiceAvailable(ctx)) add {
                    NavRow(
                        title = stringResource(R.string.screen_settings_language),
                        icon = MaterialSymbols.Translate,
                        trailing = MaterialSymbols.OpenInNew,
                        supporting = stringResource(R.string.screen_settings_language_note, currentAppLanguageLabel(ctx)),
                        onClick = { ctx.startActivity(appLanguageSettingsIntent(ctx)) },
                    )
                }
            },
        )

        // --- Export (what people change most, so it comes before the render internals) ---
        SettingsGroup(
            stringResource(R.string.screen_settings_export),
            buildList<@Composable () -> Unit> {
                add {
                    ChoiceRow(
                        title = stringResource(R.string.screen_settings_export_format),
                        icon = MaterialSymbols.IosShare,
                        selected = state.exportFormat,
                        options = ExportFormat.entries.toList(),
                        display = { formatLabels.getValue(it) },
                        onSelect = { onAction(SettingsAction.SetExportFormat(it)) },
                    )
                }
                if (state.showsQuality) add {
                    RowBody {
                        SavedOnReleaseSlider(
                            label = stringResource(R.string.screen_settings_jpeg_quality),
                            value = state.exportQuality,
                            range = 1..100,
                            onCommit = { onAction(SettingsAction.SetExportQuality(it)) },
                        )
                    }
                }
                add {
                    SettingToggleRow(
                        title = stringResource(R.string.screen_settings_keep_gps),
                        icon = MaterialSymbols.LocationOff,
                        note = stringResource(R.string.screen_settings_keep_gps_note),
                        checked = state.keepGps,
                        onCheckedChange = { onAction(SettingsAction.SetKeepGps(it)) },
                    )
                }
            },
        )

        // --- Render defaults ---
        SettingsGroup(
            stringResource(R.string.screen_settings_render_defaults),
            buildList<@Composable () -> Unit> {
                add {
                    ChoiceRow(
                        title = stringResource(R.string.screen_settings_default_output_cs),
                        icon = MaterialSymbols.Monitor,
                        selected = state.outputColorSpace,
                        options = ColorSpace.entries.toList(),
                        display = { it.name },
                        onSelect = { onAction(SettingsAction.SetOutputColorSpace(it)) },
                    )
                }
                if (filmGroups.isNotEmpty()) add {
                    RowBody {
                        GroupedDropdown(
                            label = stringResource(R.string.screen_settings_default_film),
                            selectedId = state.defaultFilm.ifEmpty { filmGroups.firstOrNull()?.options?.firstOrNull()?.id ?: "" },
                            groups = filmGroups,
                            onSelect = { onAction(SettingsAction.SetDefaultFilm(it)) },
                        )
                    }
                }
                if (printGroups.isNotEmpty()) add {
                    RowBody {
                        GroupedDropdown(
                            label = stringResource(R.string.screen_settings_default_print),
                            selectedId = state.defaultPrint.ifEmpty { printGroups.firstOrNull()?.options?.firstOrNull()?.id ?: "" },
                            groups = printGroups,
                            onSelect = { onAction(SettingsAction.SetDefaultPrint(it)) },
                        )
                    }
                }
                add {
                    SettingToggleRow(
                        title = stringResource(R.string.screen_settings_preview_auto),
                        icon = MaterialSymbols.AspectRatio,
                        note = stringResource(
                            if (state.previewAuto) R.string.screen_settings_preview_auto_note
                            else R.string.screen_settings_preview_auto_off_note,
                            state.autoEdges.previewEdge,
                        ),
                        checked = state.previewAuto,
                        onCheckedChange = { onAction(SettingsAction.SetPreviewAuto(it)) },
                    )
                }
                if (!state.previewAuto) add {
                    RowBody {
                        SavedOnReleaseSlider(
                            label = stringResource(R.string.screen_settings_preview_max_size),
                            value = state.previewMaxSize,
                            range = 128..1024,
                            onCommit = { onAction(SettingsAction.SetPreviewMaxSize(it)) },
                            tooltip = stringResource(R.string.screen_settings_preview_max_size_tip),
                        )
                    }
                }
                add {
                    SettingToggleRow(
                        title = stringResource(R.string.screen_settings_draft_auto),
                        icon = MaterialSymbols.Tune,
                        note = stringResource(
                            if (state.draftAuto) R.string.screen_settings_draft_auto_note
                            else R.string.screen_settings_draft_auto_off_note,
                            state.autoEdges.draftEdge,
                        ),
                        checked = state.draftAuto,
                        onCheckedChange = { onAction(SettingsAction.SetDraftAuto(it)) },
                    )
                }
                if (!state.draftAuto) add {
                    RowBody {
                        SavedOnReleaseSlider(
                            label = stringResource(R.string.screen_settings_draft_size),
                            value = state.draftMaxPx,
                            range = 128..512,
                            onCommit = { onAction(SettingsAction.SetDraftMaxPx(it)) },
                            tooltip = stringResource(R.string.screen_settings_draft_size_tip),
                        )
                    }
                }
            },
            note = stringResource(R.string.screen_settings_render_defaults_note),
        )

        // --- Updates & diagnostics ---
        val checking = updateUi.status == UpdateStatus.CHECKING
        val updateStatus = when (updateUi.status) {
            UpdateStatus.IDLE -> null
            UpdateStatus.CHECKING -> stringResource(R.string.screen_settings_checking)
            UpdateStatus.FAILED -> stringResource(R.string.screen_settings_update_check_failed)
            UpdateStatus.UP_TO_DATE ->
                stringResource(R.string.screen_settings_up_to_date, updateUi.currentVersion.orEmpty())
            UpdateStatus.AVAILABLE ->
                stringResource(R.string.screen_settings_update_available_tag, updateUi.latestTag.orEmpty())
        }
        SettingsGroup(
            stringResource(R.string.screen_settings_updates_heading),
            buildList<@Composable () -> Unit> {
                add {
                    NavRow(
                        title = stringResource(R.string.screen_settings_check_updates),
                        icon = MaterialSymbols.SystemUpdate,
                        supporting = updateStatus,
                        enabled = !checking,
                        onClick = { onUpdateAction(UpdateAction.CheckNow) },
                        supportingLive = true,
                    )
                }
                if (updateUi.status == UpdateStatus.AVAILABLE && updateUi.offer == null) add {
                    NavRow(
                        title = stringResource(R.string.screen_settings_update_view),
                        icon = MaterialSymbols.SystemUpdate,
                        trailing = MaterialSymbols.ChevronRight,
                        onClick = { onUpdateAction(UpdateAction.ShowOffer) },
                    )
                }
                add {
                    SettingToggleRow(
                        title = stringResource(R.string.screen_settings_auto_update),
                        icon = MaterialSymbols.SystemUpdate,
                        note = stringResource(R.string.screen_settings_auto_update_note),
                        checked = updateUi.autoCheck,
                        onCheckedChange = { onUpdateAction(UpdateAction.SetAutoCheck(it)) },
                    )
                }
                add {
                    NavRow(
                        title = stringResource(R.string.screen_settings_diagnostics_logs),
                        icon = MaterialSymbols.BugReport,
                        trailing = MaterialSymbols.ChevronRight,
                        onClick = onOpenDiagnostics,
                        selected = selectedDetail == SettingsDetail.DIAGNOSTICS,
                    )
                }
            },
        )

        // --- Help & feedback, with About folded in ---
        SettingsGroup(
            stringResource(R.string.screen_settings_help_heading),
            listOf<@Composable () -> Unit>(
                {
                    NavRow(
                        title = stringResource(R.string.screen_how_to_use_app),
                        icon = MaterialSymbols.MenuBook,
                        trailing = MaterialSymbols.ChevronRight,
                        onClick = onOpenHowTo,
                        selected = selectedDetail == SettingsDetail.HOW_TO,
                    )
                },
                {
                    NavRow(
                        title = stringResource(R.string.screen_settings_show_onboarding),
                        icon = MaterialSymbols.Help,
                        onClick = onShowOnboarding,
                    )
                },
                {
                    NavRow(
                        title = stringResource(R.string.screen_report_issue),
                        icon = MaterialSymbols.BugReport,
                        trailing = MaterialSymbols.OpenInNew,
                        onClick = { Links.open(ctx, Links.NEW_ISSUE) },
                        modifier = linkSemantics,
                    )
                },
                {
                    NavRow(
                        title = stringResource(R.string.screen_settings_view_issues),
                        trailing = MaterialSymbols.OpenInNew,
                        onClick = { Links.open(ctx, Links.ISSUES) },
                        modifier = linkSemantics,
                    )
                },
                {
                    NavRow(
                        title = stringResource(R.string.screen_settings_source),
                        trailing = MaterialSymbols.OpenInNew,
                        onClick = { Links.open(ctx, Links.SOURCE) },
                        modifier = linkSemantics,
                    )
                },
                {
                    NavRow(
                        title = stringResource(R.string.screen_about_title),
                        icon = MaterialSymbols.Info,
                        trailing = MaterialSymbols.ChevronRight,
                        supporting = stringResource(
                            R.string.screen_about_version, appVersionName(ctx), appVersionCode(ctx),
                        ),
                        onClick = onOpenAbout,
                        selected = selectedDetail == SettingsDetail.ABOUT,
                    )
                },
            ),
        )
    }
}

/**
 * One M3 grouped (segmented) list: a heading, then [rows] in a shared rounded container
 * split by 2dp gaps. The group's outer corners are large and the inner ones small, so the
 * rows read as one unit without dividers.
 */
@Composable
private fun SettingsGroup(
    title: String,
    rows: List<@Composable () -> Unit>,
    note: String? = null,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
        rows.forEachIndexed { i, row ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = segmentShape(i, rows.size),
                modifier = Modifier.fillMaxWidth(),
            ) { row() }
        }
    }
    if (note != null) {
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
}

internal val SEGMENT_GAP = 2.dp
private val SEGMENT_OUTER = 16.dp
private val SEGMENT_INNER = 4.dp

/** Shape of row [index] of [count] in an M3 grouped list (Settings, Photo info). */
internal fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) SEGMENT_OUTER else SEGMENT_INNER
    val bottom = if (index == count - 1) SEGMENT_OUTER else SEGMENT_INNER
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Padding for a row whose body is a full control (slider, profile picker), not a label. */
@Composable
private fun RowBody(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}

/**
 * A tappable list row: title, optional supporting line, full-row 56dp+ target.
 *
 * [selected] marks the row whose page is open in the detail pane of the two-pane Settings
 * layout (M3 list-detail: the list shows which item the detail belongs to). It is a fill
 * change only, so the row does not move when the selection does.
 */
@Composable
private fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    supportingLive: Boolean = false,
    selected: Boolean = false,
    /** Leading Material Symbol (Pen board, Settings rows). */
    icon: ImageVector? = null,
    /** Trailing hint: chevron for a choice or sub-screen, open-in-new for a link. */
    trailing: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else Modifier,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (selected) this.selected = true }
            .then(modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (supportingLive) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                } else Modifier,
            )
        }
        }
        if (trailing != null) {
            Icon(
                trailing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp).size(24.dp),
            )
        }
    }
}

/** The 24dp leading icon of a Settings row, 16dp before the text (M3 list item). */
@Composable
private fun RowIcon(icon: ImageVector?) {
    if (icon == null) return
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 16.dp).size(24.dp),
    )
}

/**
 * A one-of-many setting: the row shows the current value; tapping opens a radio dialog
 * that applies the choice on tap and closes (M3 settings: no Save step for a single choice).
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    selected: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
) {
    var open by remember { mutableStateOf(false) }
    NavRow(
        title = title,
        supporting = display(selected),
        onClick = { open = true },
        icon = icon,
        trailing = MaterialSymbols.ChevronRight,
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = option == selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(option); open = false },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Text(
                                display(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.editor_cancel)) }
            },
        )
    }
}

/**
 * A labelled switch row. The whole row is the toggle (`Role.Switch`), so TalkBack sees one
 * node — "title, note, switch, on/off" — and the tap target is the full row, not the thumb.
 */
@Composable
private fun SettingToggleRow(
    title: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row owns the toggle; a null callback keeps the Switch from adding a second node.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * An [IntSlider] for a stored setting: the thumb follows the finger, and the value is saved
 * once, when the drag (or typed value, or reset) is committed, instead of once per drag frame.
 * Each save is a DataStore write, so this keeps the settings write queue short and the
 * onStop flush on its fast path (MAD audit fix 1).
 */
@Composable
private fun SavedOnReleaseSlider(
    label: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
    tooltip: String? = null,
) {
    // One draft state for the slider's lifetime, so every callback the slider holds (some
    // outlive recomposition) writes the draft that is on screen. It follows the saved value
    // when that changes from outside, for example the export sheet saving a new quality.
    val draft = remember { mutableIntStateOf(value) }
    LaunchedEffect(value) { draft.intValue = value }
    val saved by rememberUpdatedState(value)
    val commit by rememberUpdatedState(onCommit)
    IntSlider(
        label = label,
        value = draft.intValue,
        range = range,
        onValueChange = { draft.intValue = it },
        tooltip = tooltip,
        onValueChangeFinished = { if (draft.intValue != saved) commit(draft.intValue) },
    )
}
