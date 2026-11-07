/*
 * Spektrafilm for Android — editor category rail. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The bottom stage/category rail, its chips and the export FAB. Stateless; split out of
 * EditorChrome.kt (#273).
 */
package com.spectrafilm.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The editor parameter navigation: five darkroom stages, with the selected stage's
 * groups on a second row above them.
 *
 * This replaced a single scrolling LazyRow of all fourteen [Category] chips. At 72dp a
 * chip that row ran past 1000dp against a 360dp screen, so most of the app's controls
 * were reachable only by scrolling a bar that gave no sign of how much lay off-screen,
 * ordered by the enum rather than by the photographic process. Five fixed chips fit any
 * phone without scrolling, and the second row is never longer than six.
 *
 * The stage is DERIVED from the active category rather than held as separate state, so
 * the two rows cannot disagree and closing the panel closes the stage.
 */
@Composable
internal fun CategoryBar(
    active: Category?,
    dirty: Set<Category>,
    onSelect: (Category) -> Unit,
    exportVisible: Boolean = false,
    exporting: Boolean = false,
    onExport: () -> Unit = {},
) {
    val activeStage = active?.stage
    val subItems = remember(activeStage) { activeStage?.let { categoriesOf(it) }.orEmpty() }

    Surface(
        color = SpectraIcons.nearBlackCanvas,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Second row: the groups inside the open stage. A stage holding a single
            // group shows no row at all -- there is nothing to choose, and a one-item
            // row would read as a dead control.
            if (subItems.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    itemsIndexed(subItems) { _, cat ->
                        SubCategoryChip(
                            category = cat,
                            selected = cat == active,
                            modified = cat in dirty,
                            onClick = { onSelect(cat) },
                        )
                    }
                }
                HorizontalDivider(
                    color = SpektraBrand.onCanvas.copy(alpha = 0.10f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            // M3 floating toolbar: the five stages in a pill, with the screen's one primary
            // action -- Export -- as the FAB paired beside it. The five stages share the
            // pill's width equally, so none is squeezed. Where the FAB beside them would
            // leave a stage under the 48dp touch target (very narrow windows, or a side
            // column), the FAB moves to its own row at the trailing edge above the toolbar.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fabBeside = !exportVisible ||
                (maxWidth - TOOLBAR_SIDE_PADDING * 2 - TOOLBAR_GAP - EXPORT_FAB_SIZE - TOOLBAR_INNER_PADDING * 2) / Stage.entries.size >= 48.dp
            Column(Modifier.fillMaxWidth()) {
            if (exportVisible && !fabBeside) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) { ExportFab(exporting = exporting, onClick = onExport) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = TOOLBAR_SIDE_PADDING, end = TOOLBAR_SIDE_PADDING, top = 6.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(TOOLBAR_GAP, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 420.dp)
                    .clip(CircleShape)
                    .background(SpektraBrand.onCanvas.copy(alpha = 0.10f))
                    .padding(TOOLBAR_INNER_PADDING)
                    .selectableGroup(),
            ) {
                Stage.entries.forEach { stage ->
                    val members = categoriesOf(stage)
                    StageItem(
                        modifier = Modifier.weight(1f),
                        stage = stage,
                        selected = stage == activeStage,
                        // A stage is "doing something" when any group inside it is.
                        modified = members.any { it in dirty },
                        onClick = {
                            if (stage == activeStage) {
                                // Tapping the open stage closes the panel, exactly as
                                // tapping the active category chip always did.
                                active?.let(onSelect)
                            } else {
                                members.firstOrNull()?.let(onSelect)
                            }
                        },
                    )
                }
            }
            if (exportVisible && fabBeside) ExportFab(exporting = exporting, onClick = onExport)
            }
            }
            }
        }
    }
}

private val TOOLBAR_SIDE_PADDING = 12.dp
private val TOOLBAR_GAP = 8.dp
private val TOOLBAR_INNER_PADDING = 4.dp
private val EXPORT_FAB_SIZE = 56.dp

/** One of the five stage chips on the bottom row. */
@Composable
internal fun StageItem(
    stage: Stage,
    selected: Boolean,
    modified: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isSelected = selected
    val modifiedState = stringResource(R.string.editor_category_modified)
    // Motion here is deliberately confined to paint and to a width INSIDE the chip.
    // Nothing animates the rail's height: the preview is a weight(1f) sibling, and an
    // animated rail height would re-measure it every frame -- the churn that forced the
    // GPU preview off and jolted the CPU one (see PanelOverlay's note).
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, RAIL_PRESS, label = "stagePress")
    val tint by animateColorAsState(
        if (selected) accent else SpektraBrand.onCanvas.copy(alpha = 0.78f), RAIL_PAINT, label = "stageTint",
    )
    val fill by animateColorAsState(
        if (selected) accent.copy(alpha = 0.18f) else Color.Transparent, RAIL_PAINT, label = "stageFill",
    )
    val pill by animateDpAsState(if (selected) 20.dp else 0.dp, RAIL_PILL, label = "stagePill")
    TextTooltip(stringResource(stage.hintRes)) {
        Column(
            modifier
                .scale(press)
                .clip(CircleShape)
                .background(fill)
                .clickableNoRipple(interaction, onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = isSelected
                    if (modified) stateDescription = modifiedState
                }
                // 2dp, not more: on a 360dp phone with Export beside the toolbar each stage
                // is ~53dp wide, and translated labels (フィルム, Película) need nearly all of
                // it. Labels must not truncate (m3-design-language, navigation bar items).
                .padding(horizontal = 2.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = stageIcon(stage),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
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
                stringResource(stage.labelRes),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = tint,
            )
            // The indicator grows from the centre rather than appearing, so moving
            // between stages reads as one mark travelling along the rail.
            Box(
                Modifier
                    .size(width = pill, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
        }
    }
}

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

/**
 * A group chip on the second row: a round icon button.
 *
 * Two earlier cuts were wrong about width. Reusing [CategoryItem] put six 72dp stacked
 * icon-over-label cells in the row -- 432dp against a 360dp screen, still overflowing,
 * the exact fault this redesign existed to remove. Text-only pills were shorter but only
 * fixed it vertically: "Simulation" is a wide word and the sixth chip still started past
 * the edge. Six icon buttons is roughly 250dp, so the row finally fits outright on the
 * narrowest phone, with no scrolling at all.
 *
 * The name is not lost, only moved: the wrapping [TextTooltip] carries the category hint
 * on long-press, and `contentDescription` carries the label itself, so TalkBack still
 * announces "Grain" rather than an unnamed button. That matters here precisely because
 * the glyph alone is doing the identifying.
 */
@Composable
internal fun SubCategoryChip(
    category: Category,
    selected: Boolean,
    modified: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isSelected = selected
    val label = stringResource(category.labelRes)
    val modifiedState = stringResource(R.string.editor_category_modified)
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, RAIL_PRESS, label = "groupPress")
    val tint by animateColorAsState(
        if (selected) accent else SpektraBrand.onCanvas.copy(alpha = 0.82f), RAIL_PAINT, label = "groupTint",
    )
    val fill by animateColorAsState(
        if (selected) accent.copy(alpha = 0.22f) else SpektraBrand.onCanvas.copy(alpha = 0.07f),
        RAIL_PAINT, label = "groupFill",
    )
    TextTooltip(categoryHint(category)) {
        Box(
            Modifier
                .scale(press)
                .size(40.dp)
                .clip(CircleShape)
                .background(fill)
                .clickableNoRipple(interaction, onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = isSelected
                    // The glyph is the only visible identifier, so the name lives here.
                    contentDescription = label
                    if (modified) stateDescription = modifiedState
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (modified) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
    }
}

/** Stage icons, reusing the vector that best represents each stage's contents. */
internal fun stageIcon(stage: Stage) = when (stage) {
    Stage.LOOK -> SpectraIcons.Presets
    Stage.FILM -> SpectraIcons.Simulation
    Stage.PRINT -> SpectraIcons.Preflash
    Stage.SCAN -> SpectraIcons.Glare
    Stage.FINISH -> SpectraIcons.ToneCurve
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
                tint = if (selected) accent else SpektraBrand.onCanvas.copy(alpha = 0.78f),
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
            color = if (selected) accent else SpektraBrand.onCanvas.copy(alpha = 0.78f),
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
        Category.SIMULATION -> R.string.editor_hint_simulation
        Category.INPUT -> R.string.editor_hint_input
        Category.RAW_WB -> R.string.editor_hint_raw_wb
        Category.GRAIN -> R.string.editor_hint_grain
        Category.HALATION -> R.string.editor_hint_halation
        Category.GLARE -> R.string.editor_hint_glare
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
    Category.RAW_WB -> SpectraIcons.ImportRaw
    Category.GRAIN -> SpectraIcons.Grain
    Category.PREFLASH -> SpectraIcons.Preflash
    Category.CONTRAST_MASK -> SpectraIcons.ContrastMask
    Category.HALATION -> SpectraIcons.Halation
    Category.COUPLERS -> SpectraIcons.Couplers
    Category.GLARE -> SpectraIcons.Glare
    Category.EXPERIMENTAL -> SpectraIcons.Experimental
    Category.TONE_CURVE -> SpectraIcons.ToneCurve
    Category.MASKS -> SpectraIcons.Masks
    Category.DISPLAY -> SpectraIcons.Display
    Category.PRESETS -> SpectraIcons.Presets
    Category.SOURCE -> SpectraIcons.SourceImage
}
