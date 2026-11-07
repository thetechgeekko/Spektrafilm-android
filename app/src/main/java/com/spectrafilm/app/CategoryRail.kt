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
            modifier = Modifier.semantics { contentDescription = if (exporting) busyLabel else label },
        ) {
            if (exporting) {
                SpectraLoadingIndicator(
                    Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(SpectraIcons.Export, contentDescription = null)
            }
        }
    }
}

/** Stage icons, reusing the vector that best represents each stage's contents. */
internal fun stageIcon(stage: Stage) = when (stage) {
    Stage.LOOK -> SpectraIcons.StageLook
    Stage.FILM -> SpectraIcons.StageFilm
    Stage.PRINT -> SpectraIcons.StagePrint
    Stage.SCAN -> SpectraIcons.StageScan
    Stage.FINISH -> SpectraIcons.StageFinish
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

/** One-line tooltip description for each editor category. */
@Composable
internal fun categoryHint(c: Category) = stringResource(
    when (c) {
        Category.SOURCE -> R.string.editor_hint_source
        Category.PRESETS -> R.string.editor_hint_presets
        Category.SIMULATION -> R.string.editor_hint_film_stock
        Category.INPUT -> R.string.editor_hint_input
        Category.RAW_WB -> R.string.editor_hint_raw_wb
        Category.GRAIN -> R.string.editor_hint_grain
        Category.HALATION -> R.string.editor_hint_halation
        Category.GLARE -> R.string.editor_hint_glare
        Category.PAPER -> R.string.editor_hint_paper
        Category.SCANNER -> R.string.editor_hint_scanner
        Category.OUTPUT -> R.string.editor_hint_output
        Category.COUPLERS -> R.string.editor_hint_couplers
        Category.PREFLASH -> R.string.editor_hint_preflash
        Category.CONTRAST_MASK -> R.string.editor_hint_contrast_mask
        Category.EXPERIMENTAL -> R.string.editor_hint_experimental
        Category.TONE_CURVE -> R.string.editor_hint_tone_curve
        Category.MASKS -> R.string.editor_hint_masks
        Category.DISPLAY -> R.string.editor_hint_display
    },
)

internal fun categoryIcon(c: Category) = when (c) {
    Category.SIMULATION -> SpectraIcons.Simulation
    Category.INPUT -> SpectraIcons.Input
    Category.RAW_WB -> SpectraIcons.WhiteBalance
    Category.GRAIN -> SpectraIcons.Grain
    Category.PREFLASH -> SpectraIcons.Preflash
    Category.CONTRAST_MASK -> SpectraIcons.ContrastMask
    Category.HALATION -> SpectraIcons.Halation
    Category.COUPLERS -> SpectraIcons.Couplers
    Category.GLARE -> SpectraIcons.Glare
    Category.PAPER -> SpectraIcons.Paper
    Category.SCANNER -> SpectraIcons.StageScan
    Category.OUTPUT -> SpectraIcons.Output
    Category.EXPERIMENTAL -> SpectraIcons.Experimental
    Category.TONE_CURVE -> SpectraIcons.ToneCurve
    Category.MASKS -> SpectraIcons.Masks
    Category.DISPLAY -> SpectraIcons.Display
    Category.PRESETS -> SpectraIcons.Presets
    Category.SOURCE -> SpectraIcons.Source
}
