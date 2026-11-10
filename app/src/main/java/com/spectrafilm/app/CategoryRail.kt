/*
 * Spektrafilm for Android — editor category rail. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The export FAB and the stage/category icons and hints. The stage row, tool row and
 * stage rail themselves live in EditorStageChrome.kt. Stateless; split out of
 * EditorChrome.kt (#273).
 */
package com.spectrafilm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The editor's one primary action. M3: a FAB is for the constructive main action, is hidden
 * rather than disabled when unavailable, and may sit beside a floating toolbar. While an
 * export runs it shows the loading indicator in place of its icon and ignores taps.
 */
@Composable
internal fun ExportFab(exporting: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.editor_action_export)
    val busyLabel = stringResource(R.string.editor_exporting)
    TextTooltip(stringResource(R.string.editor_tooltip_export_gallery)) {
        FloatingActionButton(
            onClick = { if (!exporting) onClick() },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .helpSpot(LocalQuickHelp.current, HelpSpot.EXPORT)
                .semantics { contentDescription = if (exporting) busyLabel else label },
        ) {
            if (exporting) {
                ExportProgressIndicator(
                    Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(SpectraIcons.Export, contentDescription = null)
            }
        }
    }
}

/**
 * Share beside the rail's Export FAB: exports with the last-used choices and opens the share
 * sheet (or shares the file just saved). Same rules as the FAB: never disabled, inert while
 * an export runs.
 */
@Composable
internal fun ExportShareSmallFab(exporting: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.editor_export_share)
    TextTooltip(label) {
        SmallFloatingActionButton(
            onClick = { if (!exporting) onClick() },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Icon(SpectraIcons.Share, contentDescription = null)
        }
    }
}

/** Bottom-bar icons for the editor's modes. */
internal fun modeIcon(mode: EditorMode) = when (mode) {
    EditorMode.LOOKS -> SpectraIcons.StageLook
    EditorMode.EDIT -> SpectraIcons.StageFinish
    EditorMode.MASKS -> SpectraIcons.Masks
}

@Composable
internal fun CategoryItem(
    category: Category,
    selected: Boolean,
    modified: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Local copy: inside `semantics {}` the receiver's `selected` property would shadow
    // the parameter.
    val isSelected = selected
    // The dot is colour-only on screen, so it has to exist in the a11y tree too.
    val modifiedState = stringResource(R.string.editor_category_modified)
    TextTooltip(categoryHint(category)) {
    Column(
        Modifier
            .width(72.dp)
            .scale(if (pressed) 0.92f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickableNoRipple(interaction, onClick)
            // One tab node: icon + label merged, selection exposed (not colour-only).
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = isSelected
                if (modified) stateDescription = modifiedState
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = categoryIcon(category),
                // Decorative: the visible label text below carries the name.
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            // "This category is doing something to the photograph." The chips encoded
            // only selection before, so the one thing a 14-entry bar most needed to
            // say -- which groups are live -- was the one thing it never said.
            if (modified) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-3).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
        Text(
            stringResource(category.labelRes),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // sliding pill indicator
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(width = if (selected) 20.dp else 0.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) accent else Color.Transparent),
        )
    }
    }
}

/** One-line tooltip description for each editor tool. */
@Composable
internal fun categoryHint(c: Category) = stringResource(
    when (c) {
        Category.SOURCE -> R.string.editor_hint_source
        Category.PRESETS -> R.string.editor_hint_presets
        Category.SIMULATION -> R.string.editor_hint_film_stock
        Category.PAPER -> R.string.editor_hint_paper
        Category.EXPOSURE -> R.string.editor_hint_exposure
        Category.TONE_CURVE -> R.string.editor_hint_tone_curve
        Category.SCENE_LATITUDE -> R.string.editor_hint_scene_latitude
        Category.CONTRAST_MASK -> R.string.editor_hint_contrast_mask
        Category.RAW_WB -> R.string.editor_hint_raw_wb
        Category.SAT_VIB -> R.string.editor_hint_sat_vib
        Category.PRINT_COLOR -> R.string.editor_hint_print_color
        Category.COUPLERS -> R.string.editor_hint_couplers
        Category.FILM_FORMAT -> R.string.editor_hint_film_format
        Category.GRAIN -> R.string.editor_hint_grain
        Category.HALATION -> R.string.editor_hint_halation
        Category.CAMERA_DIFFUSION -> R.string.editor_hint_camera_diffusion
        Category.PRINT_EXPOSURE -> R.string.editor_hint_print_exposure
        Category.PREFLASH -> R.string.editor_hint_preflash
        Category.ENLARGER -> R.string.editor_hint_enlarger
        Category.GLARE -> R.string.editor_hint_glare
        Category.LENS_BLUR -> R.string.editor_hint_lens_blur
        Category.DATE_STAMP -> R.string.editor_hint_date_stamp
        Category.SCANNER -> R.string.editor_hint_scanner
        Category.RAW_CLEANUP -> R.string.editor_hint_raw_cleanup
        Category.FRAME -> R.string.editor_hint_frame
        Category.OUTPUT -> R.string.editor_hint_output
        Category.DISPLAY -> R.string.editor_hint_display
        Category.INPUT -> R.string.editor_hint_input
        Category.EXPERIMENTAL -> R.string.editor_hint_experimental
        Category.MASKS -> R.string.editor_hint_masks
    },
)

internal fun categoryIcon(c: Category) = when (c) {
    Category.SIMULATION -> SpectraIcons.Simulation
    Category.PAPER -> SpectraIcons.Paper
    Category.PRESETS -> SpectraIcons.Presets
    Category.SOURCE -> SpectraIcons.Source
    Category.EXPOSURE -> MaterialSymbols.Exposure
    Category.TONE_CURVE -> SpectraIcons.ToneCurve
    Category.SCENE_LATITUDE -> MaterialSymbols.Tune
    Category.CONTRAST_MASK -> SpectraIcons.ContrastMask
    Category.RAW_WB -> SpectraIcons.WhiteBalance
    Category.SAT_VIB -> MaterialSymbols.Palette
    Category.PRINT_COLOR -> MaterialSymbols.Colorize
    Category.COUPLERS -> SpectraIcons.Couplers
    Category.FILM_FORMAT -> MaterialSymbols.AspectRatio
    Category.GRAIN -> SpectraIcons.Grain
    Category.HALATION -> SpectraIcons.Halation
    Category.CAMERA_DIFFUSION -> MaterialSymbols.BlurOn
    Category.PRINT_EXPOSURE -> MaterialSymbols.Print
    Category.PREFLASH -> SpectraIcons.Preflash
    Category.ENLARGER -> MaterialSymbols.ZoomIn
    Category.GLARE -> SpectraIcons.Glare
    Category.LENS_BLUR -> MaterialSymbols.BlurOn
    Category.DATE_STAMP -> MaterialSymbols.CameraRoll
    Category.SCANNER -> MaterialSymbols.DocumentScanner
    Category.RAW_CLEANUP -> MaterialSymbols.RawOn
    Category.FRAME -> MaterialSymbols.FilterFrames
    Category.OUTPUT -> SpectraIcons.Output
    Category.DISPLAY -> SpectraIcons.Display
    Category.INPUT -> SpectraIcons.Input
    Category.EXPERIMENTAL -> SpectraIcons.Experimental
    Category.MASKS -> SpectraIcons.Masks
}
