/*
 * Spektrafilm for Android — the editor's top app bar. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Split out of EditorScreen.kt (#273).
 */
package com.spectrafilm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    /** Hold Undo: the named history list. */
    onShowHistory: () -> Unit,
    /** Hold Compare: true while the finger is down, the unedited photo shows. */
    onPeekOriginal: (Boolean) -> Unit,
    /**
     * A slider is moving: Undo, Redo and Compare fade back so the bar does not compete with
     * the photo. State, read in the draw phase, so a drag does not recompose the bar.
     */
    fading: State<Boolean>,
    onOpenSource: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLibrary: () -> Unit,
    /** Opens the Photo info sheet; null while there is no photo to describe (the demo image). */
    onPhotoInfo: (() -> Unit)?,
    /** A rendered photo is on screen: Crop and the view toggles have something to act on. */
    hasPhoto: Boolean,
    onCrop: () -> Unit,
    compare: Boolean,
    histogram: Boolean,
    clipWarnings: Boolean,
    onToggleCompare: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleClipWarnings: () -> Unit,
    onOpenLoupe: () -> Unit,
    /** The film stock in use, as the bar's title. */
    title: String,
    /** The print paper, or "scanned as positive"; null hides the line. */
    subtitle: String?,
    /**
     * Expanded window: the stage rail carries Export as its FAB, so the bar keeps Crop
     * instead. Compact and medium: Export is the bar's filled button and Crop moves into
     * More, as in the kit's photo editor.
     */
    wide: Boolean,
    exportVisible: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
    /** The last export saved and nothing changed since: the pill shows its check. */
    exportSaved: Boolean,
    onShare: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), Color.Transparent),
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) {
        // In full screen the bar moves up into the status strip; CutoutAwareBarRow keeps every
        // button clear of the front camera (Pen board, "Camera cutout" row).
        CutoutAwareBarRow(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            navigation = {
            // The Library is the way back to the shoot: the bar's navigation slot.
            TextTooltip(stringResource(R.string.library_title)) {
                PressIconButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.helpSpot(LocalQuickHelp.current, HelpSpot.LIBRARY),
                ) {
                    Icon(
                        SpectraIcons.SourceImage, contentDescription = stringResource(R.string.library_title),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            },
            title = {
            // The kit's editor title: what this photo is being made with, the film stock,
            // with the print paper under it. Says something the photo itself cannot.
            Column(
                Modifier
                    .padding(horizontal = 4.dp)
                    .semantics(mergeDescendants = true) { heading() },
            ) {
                Text(
                    title,
                    // Emphasized title (M3 Expressive type): the one heading on the editor.
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            },
            actions = {
            if (wide) {
                TextTooltip(stringResource(R.string.editor_crop_transform_tooltip)) {
                    PressIconButton(onClick = onCrop, enabled = hasPhoto) {
                        Icon(
                            SpectraIcons.Crop, contentDescription = stringResource(R.string.editor_crop_transform),
                            tint = if (hasPhoto) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }
            // Undo / redo — in-session edit history. Disabled (dimmed) when the
            // respective stack is empty; one slider drag = one undo step. Holding Undo lists
            // every step by name. No tooltip on the hold buttons: a tooltip also opens on
            // long press and would land on top of what the hold does.
            val fadeAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (fading.value) BAR_FADE_ALPHA else 1f,
                animationSpec = if (fading.value) PANEL_DIM_OUT_SPEC else PANEL_DIM_IN_SPEC,
                label = "barFade",
            )
            val fade = Modifier.graphicsLayer { alpha = fadeAlpha }
            HoldIconButton(
                onClick = onUndo,
                onHoldStart = onShowHistory,
                holdLabel = stringResource(R.string.editor_history_title),
                enabled = canUndo || canRedo,
                modifier = fade.helpSpot(LocalQuickHelp.current, HelpSpot.UNDO),
            ) {
                Icon(
                    SpectraIcons.Undo, contentDescription = stringResource(R.string.editor_action_undo),
                    tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            Box(fade) {
                TextTooltip(stringResource(R.string.editor_action_redo)) {
                    PressIconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(
                            SpectraIcons.Redo, contentDescription = stringResource(R.string.editor_action_redo),
                            tint = if (canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }
            // Compare: tap for the split view, hold for the original while the finger is down.
            HoldIconButton(
                onClick = onToggleCompare,
                onHoldStart = { onPeekOriginal(true) },
                onHoldEnd = { onPeekOriginal(false) },
                enabled = hasPhoto,
                modifier = fade
                    .helpSpot(LocalQuickHelp.current, HelpSpot.COMPARE)
                    .semantics { toggleableState = ToggleableState(compare) },
            ) {
                Icon(
                    SpectraIcons.Compare, contentDescription = stringResource(R.string.editor_compare),
                    tint = when {
                        !hasPhoto -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        compare -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            // Hidden, not disabled, when there is nothing to export.
            if (!wide && exportVisible) {
                // Whatever the row's fixed buttons leave: Library, Undo, Redo, Compare and More
                // at 48dp each plus the row's 4dp padding. The pill drops its label below that.
                ExportSharePill(
                    exporting = exporting,
                    saved = exportSaved,
                    onExport = onExport,
                    onShare = onShare,
                    modifier = Modifier.helpSpot(LocalQuickHelp.current, HelpSpot.EXPORT),
                    maxWidth = this@BoxWithConstraints.maxWidth - 248.dp,
                )
            }
            // M3 app bars carry one or two essential actions: Export, then Undo and Redo.
            // Opening another photo, the view toggles, Settings and About live behind the
            // overflow menu.
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                TextTooltip(stringResource(R.string.editor_action_more)) {
                    PressIconButton(onClick = { menuOpen = true }) {
                        Icon(
                            SpectraIcons.More,
                            contentDescription = stringResource(R.string.editor_action_more),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_open_photo)) },
                        leadingIcon = { Icon(SpectraIcons.OpenPhoto, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenSource() },
                    )
                    if (!wide) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_crop_transform)) },
                            leadingIcon = { Icon(SpectraIcons.Crop, contentDescription = null) },
                            enabled = hasPhoto,
                            onClick = { menuOpen = false; onCrop() },
                        )
                    }
                    HorizontalDivider()
                    // View toggles, moved off the photo: checkable items with a tick when on
                    // (m3-design-language, Menus: selected items get a checkmark). Each acts
                    // at once on the photo, so the menu closes after a tap. Compare has its
                    // own bar button now (tap: split view, hold: the original).
                    ViewToggleItem(stringResource(R.string.editor_histogram), SpectraIcons.Histogram, histogram, hasPhoto) {
                        menuOpen = false; onToggleHistogram()
                    }
                    ViewToggleItem(stringResource(R.string.editor_menu_clipping), SpectraIcons.Clipping, clipWarnings, hasPhoto) {
                        menuOpen = false; onToggleClipWarnings()
                    }
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_magnifier)) },
                        leadingIcon = { Icon(SpectraIcons.Loupe, contentDescription = null) },
                        enabled = hasPhoto,
                        onClick = { menuOpen = false; onOpenLoupe() },
                    )
                    HorizontalDivider()
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.photo_info_title)) },
                        leadingIcon = { Icon(SpectraIcons.Info, contentDescription = null) },
                        enabled = onPhotoInfo != null,
                        onClick = { menuOpen = false; onPhotoInfo?.invoke() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_action_settings)) },
                        leadingIcon = { Icon(SpectraIcons.Settings, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenSettings() },
                    )
                    val quickHelp = LocalQuickHelp.current
                    if (quickHelp != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.help_menu)) },
                            leadingIcon = { Icon(SpectraIcons.Help, contentDescription = null) },
                            onClick = { menuOpen = false; quickHelp.open() },
                        )
                    }
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_action_about)) },
                        leadingIcon = { Icon(SpectraIcons.Info, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenAbout() },
                    )
                }
            }
            },
        )
    }
}

/** A checkable More-menu item for one of the photo view toggles. */
@Composable
private fun ViewToggleItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = if (checked) {
            { Icon(SpectraIcons.Check, contentDescription = null) }
        } else {
            null
        },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
    )
}

/** How far Undo, Redo and Compare fade while a slider moves (interaction rules). */
internal const val BAR_FADE_ALPHA = 0.25f
