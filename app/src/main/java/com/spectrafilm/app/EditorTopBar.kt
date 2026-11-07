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
internal fun MainActivity.EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
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
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(SpektraBrand.scrim.copy(alpha = 0.55f), Color.Transparent),
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Open / pick a source photo (was an app-exit bug previously).
            TextTooltip(stringResource(R.string.editor_open_photo)) {
                PressIconButton(onClick = onOpenSource) {
                    Icon(
                        SpectraIcons.OpenPhoto, contentDescription = stringResource(R.string.editor_open_photo),
                        tint = SpektraBrand.onCanvas,
                    )
                }
            }
            // The Library sits beside Open: both are ways to open a photo, and working
            // through a shoot frame by frame should be one tap away.
            TextTooltip(stringResource(R.string.library_title)) {
                PressIconButton(onClick = onOpenLibrary) {
                    Icon(
                        SpectraIcons.SourceImage, contentDescription = stringResource(R.string.library_title),
                        tint = SpektraBrand.onCanvas,
                    )
                }
            }
            // No title. The editor is a canvas (m3-design-language, ux-flows §3, Map /
            // canvas: full-bleed content), and the app name in the middle of the bar on
            // every photo said nothing the launcher had not. The space holds Crop instead,
            // which used to float on the photo itself.
            Spacer(Modifier.weight(1f))
            TextTooltip(stringResource(R.string.editor_crop_transform_tooltip)) {
                PressIconButton(onClick = onCrop, enabled = hasPhoto) {
                    Icon(
                        SpectraIcons.Crop, contentDescription = stringResource(R.string.editor_crop_transform),
                        tint = if (hasPhoto) SpektraBrand.onCanvas else SpektraBrand.onCanvas.copy(alpha = 0.4f),
                    )
                }
            }
            // Undo / redo — in-session edit history. Disabled (dimmed) when the
            // respective stack is empty; one slider drag = one undo step.
            TextTooltip(stringResource(R.string.editor_action_undo)) {
                PressIconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        SpectraIcons.Undo, contentDescription = stringResource(R.string.editor_action_undo),
                        tint = if (canUndo) SpektraBrand.onCanvas else SpektraBrand.onCanvas.copy(alpha = 0.4f),
                    )
                }
            }
            TextTooltip(stringResource(R.string.editor_action_redo)) {
                PressIconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        SpectraIcons.Redo, contentDescription = stringResource(R.string.editor_action_redo),
                        tint = if (canRedo) SpektraBrand.onCanvas else SpektraBrand.onCanvas.copy(alpha = 0.4f),
                    )
                }
            }
            // M3 app bars carry one or two essential actions; Undo and Redo are this
            // screen's. Export moved to the FAB beside the stage toolbar, and Settings /
            // About live behind the overflow menu.
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                TextTooltip(stringResource(R.string.editor_action_more)) {
                    PressIconButton(onClick = { menuOpen = true }) {
                        Icon(
                            SpectraIcons.More,
                            contentDescription = stringResource(R.string.editor_action_more),
                            tint = SpektraBrand.onCanvas,
                        )
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    // View toggles, moved off the photo: checkable items with a tick when on
                    // (m3-design-language, Menus: selected items get a checkmark). Each acts
                    // at once on the photo, so the menu closes after a tap. Compare also
                    // works without the menu: touch and hold the photo.
                    ViewToggleItem(stringResource(R.string.editor_compare), SpectraIcons.Display, compare, hasPhoto) {
                        menuOpen = false; onToggleCompare()
                    }
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
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_action_about)) },
                        leadingIcon = { Icon(SpectraIcons.Help, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenAbout() },
                    )
                }
            }
        }
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
