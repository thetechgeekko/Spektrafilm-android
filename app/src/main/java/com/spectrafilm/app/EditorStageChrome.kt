/*
 * Spektrafilm for Android — the editor's stage and tool navigation. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Built from the M3 adaptive kit's photo editor (the "Supporting pane - Editor" screens the
 * owner put in the redesign .pen file and approved on 2026-11-07):
 *
 *  - compact and medium windows: the photo, then one supporting panel at the bottom holding
 *    the picked tool's controls, a row of round tool buttons, and the five stages as a
 *    segmented row (the selected stage shows its label, the others their icon);
 *  - expanded windows: the stages in a navigation rail with Export as its FAB, the photo in
 *    the middle, and the picked tool's controls in a supporting pane with the stage's tools
 *    listed at its top.
 *
 * The stage is still DERIVED from the active category (CategoryRail.kt's rule), so the stage
 * row, the tool row and the panel can never disagree, and closing the panel closes the stage.
 */
package com.spectrafilm.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What tapping [stage] does: open its first group, or close the panel when it is the open one. */
internal fun stageTapTarget(stage: Stage, active: Category?): Category? =
    if (active?.stage == stage) null else categoriesOf(stage).firstOrNull()

/**
 * The five stages as one segmented row along the bottom edge (compact and medium windows).
 * The selected stage is a filled pill with icon and label; the others show their icon, with
 * the name as tooltip and accessibility label. Tapping the open stage folds the panel away,
 * leaving the photo and this row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StageRow(
    active: Category?,
    dirty: Set<Category>,
    onOpen: (Category?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val modifiedState = stringResource(R.string.editor_category_modified)
    Surface(color = colors.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stage.entries.forEach { stage ->
                val selected = active?.stage == stage
                val modified = categoriesOf(stage).any { it in dirty }
                val label = stringResource(stage.labelRes)
                val fill by animateColorAsState(
                    if (selected) colors.secondaryContainer else Color.Transparent, RAIL_PAINT, label = "stageFill",
                )
                // The selected stage takes only what is left after the other four have their
                // 48dp targets (weighted children measure last): a long or enlarged label
                // ellipsizes instead of pushing Scan and Finish off the row.
                TextTooltip(
                    stringResource(stage.hintRes),
                    modifier = if (selected) Modifier.weight(1f, fill = false) else Modifier,
                ) {
                    Row(
                        Modifier
                            // The chosen stage grows to fit its label (fast spatial spring).
                            .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                            .heightIn(min = 48.dp)
                            .widthIn(min = 48.dp)
                            .clip(CircleShape)
                            .background(fill)
                            .selectable(selected = selected, role = Role.Tab) { onOpen(stageTapTarget(stage, active)) }
                            .semantics {
                                contentDescription = label
                                if (modified) stateDescription = modifiedState
                            }
                            .padding(horizontal = if (selected) 16.dp else 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BadgedBox(badge = { if (modified && !selected) Badge(Modifier.size(6.dp)) }) {
                            Icon(
                                stageIcon(stage),
                                contentDescription = null,
                                tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        if (selected) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                color = colors.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The open stage's groups as round tool buttons with a label under each (the kit's edit
 * option segments). Scrolls sideways when a stage has more groups, or longer translated
 * labels, than fit; a group holding edits carries a small badge.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ToolRow(
    tools: List<Category>,
    active: Category,
    dirty: Set<Category>,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tools.size < 2) return
    val colors = MaterialTheme.colorScheme
    val modifiedState = stringResource(R.string.editor_category_modified)
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        tools.forEach { cat ->
            val selected = cat == active
            val modified = cat in dirty
            // M3 Expressive toggle: round when off, square (16dp corners) when chosen.
            val corner by animateDpAsState(
                if (selected) 16.dp else 26.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "toolShape",
            )
            Column(
                Modifier
                    .widthIn(min = 64.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(selected = selected, role = Role.Tab) { if (!selected) onSelect(cat) }
                    .then(if (modified) Modifier.semantics { stateDescription = modifiedState } else Modifier)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BadgedBox(badge = { if (modified) Badge() }) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(corner))
                            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLowest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            categoryIcon(cat),
                            contentDescription = null,
                            tint = if (selected) colors.onSecondaryContainer else colors.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Text(
                    stringResource(cat.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The stages as an M3 navigation rail (expanded windows), with Export as the rail's FAB.
 * Tapping the open stage closes the supporting pane, as on the phone.
 */
@Composable
internal fun StageRail(
    active: Category?,
    dirty: Set<Category>,
    onOpen: (Category?) -> Unit,
    exportVisible: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modifiedState = stringResource(R.string.editor_category_modified)
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            if (exportVisible) ExportFab(exporting = exporting, onClick = onExport)
            Spacer(Modifier.height(12.dp))
        },
    ) {
        // Scrolls: on a landscape phone (under 480dp tall, still routed to this layout) the
        // FAB and five labelled items can be taller than the window.
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        Stage.entries.forEach { stage ->
            val modified = categoriesOf(stage).any { it in dirty }
            NavigationRailItem(
                selected = active?.stage == stage,
                onClick = { onOpen(stageTapTarget(stage, active)) },
                icon = {
                    BadgedBox(badge = { if (modified) Badge() }) {
                        Icon(stageIcon(stage), contentDescription = null)
                    }
                },
                label = { Text(stringResource(stage.labelRes), maxLines = 1) },
                modifier = if (modified) Modifier.semantics { stateDescription = modifiedState } else Modifier,
            )
        }
        }
    }
}

/** The open stage's groups as a list at the top of the supporting pane (expanded windows). */
@Composable
internal fun ToolList(
    tools: List<Category>,
    active: Category,
    dirty: Set<Category>,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tools.size < 2) return
    val modifiedState = stringResource(R.string.editor_category_modified)
    Column(modifier.fillMaxWidth().selectableGroup()) {
        tools.forEach { cat ->
            val modified = cat in dirty
            NavigationDrawerItem(
                label = { Text(stringResource(cat.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(categoryIcon(cat), contentDescription = null) },
                badge = { if (modified) Badge() },
                selected = cat == active,
                onClick = { if (cat != active) onSelect(cat) },
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
                modifier = Modifier
                    .height(48.dp)
                    .then(if (modified) Modifier.semantics { stateDescription = modifiedState } else Modifier),
            )
        }
    }
}

/**
 * The editor's primary action in the top app bar on compact and medium windows (the kit's
 * filled "Export" button). Hidden, not disabled, when there is nothing to export; shows the
 * loading indicator while an export runs and ignores taps.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExportButton(exporting: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.editor_action_export)
    val busyLabel = stringResource(R.string.editor_exporting)
    TextTooltip(stringResource(R.string.editor_tooltip_export_gallery)) {
        // Press morph: full round at rest, squarer while pressed (M3 Expressive button shapes).
        Button(
            onClick = { if (!exporting) onClick() },
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { if (exporting) contentDescription = busyLabel },
        ) {
            if (exporting) {
                ExportProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(SpectraIcons.Export, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}
