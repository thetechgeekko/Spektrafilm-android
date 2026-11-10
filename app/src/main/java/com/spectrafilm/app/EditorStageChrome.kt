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
 * The mode and section are DERIVED from the active tool (Category.stage / .mode), so the bottom
 * bar, the section tabs and the panel can never disagree, and closing the panel closes the mode.
 * 2026-11-08 regroup: modes Looks / Edit / Crop / Masks, Edit split into sections by what each
 * changes (Light, Color, Film, Print, Effects, Detail, Output, Lab).
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What tapping [mode] in the bottom bar does: open it, or close the panel when it is the open
 * one. Edit reopens the tool last shown there ([lastEdit]) so the section you were working in
 * is where you come back to; the others open on their first tool.
 */
internal fun modeTapTarget(mode: EditorMode, active: Category?, lastEdit: Category? = null): Category? = when {
    active?.mode == mode -> null
    mode == EditorMode.EDIT -> lastEdit?.takeIf { it.mode == EditorMode.EDIT }
        ?: stagesOf(EditorMode.EDIT).first().let { categoriesOf(it).first() }
    else -> stagesOf(mode).first().let { categoriesOf(it).first() }
}

/**
 * The editor's bottom bar (compact and medium windows): Looks, Edit, Crop and Masks. The
 * selected mode is a filled pill with icon and label; the others show their icon with the name
 * as tooltip and accessibility label. Tapping the open mode folds the panel away, leaving the
 * photo and this row. Crop is a tab too ([cropSelected]); it has no Category, so it is wired
 * through [onCrop], which the editor uses to open it or, when open, to leave it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StageRow(
    active: Category?,
    dirty: Set<Category>,
    onOpen: (Category?) -> Unit,
    onCrop: (() -> Unit)?,
    modifier: Modifier = Modifier,
    lastEdit: Category? = null,
    cropSelected: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val modifiedState = stringResource(R.string.editor_category_modified)
    Surface(color = colors.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .helpSpot(LocalQuickHelp.current, HelpSpot.MODES)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val items = buildList<Any> {
                add(EditorMode.LOOKS); add(EditorMode.EDIT)
                if (onCrop != null) add(CROP_ACTION)
                add(EditorMode.MASKS)
            }
            items.forEach { item ->
                val mode = item as? EditorMode
                val selected = if (mode != null) !cropSelected && active?.mode == mode else cropSelected
                val modified = mode != null && Category.entries.any { it.mode == mode && it in dirty }
                val label = stringResource(mode?.labelRes ?: R.string.tool_crop_title)
                val hint = stringResource(mode?.hintRes ?: R.string.tool_crop_title)
                val fill by animateColorAsState(
                    if (selected) colors.secondaryContainer else Color.Transparent, RAIL_PAINT, label = "modeFill",
                )
                TextTooltip(hint, modifier = if (selected) Modifier.weight(1f, fill = false) else Modifier) {
                    Row(
                        Modifier
                            .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                            .heightIn(min = 48.dp)
                            .widthIn(min = 48.dp)
                            .clip(CircleShape)
                            .background(fill)
                            .then(
                                if (mode != null) {
                                    Modifier.selectable(selected = selected, role = Role.Tab) {
                                        onOpen(modeTapTarget(mode, active, lastEdit))
                                    }
                                } else {
                                    Modifier.selectable(selected = selected, role = Role.Tab) { onCrop?.invoke() }
                                },
                            )
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
                                if (mode != null) modeIcon(mode) else SpectraIcons.Crop,
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
 * The name over one tool in Edit's stacked list, with that tool's reset beside it when it holds
 * edits. A heading for TalkBack, so a long section can be skimmed tool by tool.
 */
@Composable
internal fun ToolHeader(tool: Category, onReset: (() -> Unit)?, modifier: Modifier = Modifier) {
    val title = stringResource(tool.labelRes)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            categoryIcon(tool),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onReset != null) {
            // Shows "Reset"; TalkBack hears "Reset <tool>", since several of these share a list.
            val resetLabel = stringResource(R.string.editor_reset_category, title)
            TextButton(
                onClick = onReset,
                modifier = Modifier.semantics { contentDescription = resetLabel },
            ) { Text(stringResource(R.string.editor_reset_short)) }
        }
    }
}

/** Marker for the Crop action in the bottom bar's item list. */
private object CROP_ACTION

/**
 * Edit's sections (Light, Color, Film, ...) as scrollable tabs at the top of the panel. A dot
 * marks a section holding edits. Tapping a section shows its tools stacked below.
 */
@Composable
internal fun SectionTabs(
    sections: List<Stage>,
    active: Stage,
    dirty: Set<Category>,
    onSelect: (Stage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = sections.indexOf(active).coerceAtLeast(0)
    val modifiedState = stringResource(R.string.editor_category_modified)
    PrimaryScrollableTabRow(
        selectedTabIndex = index,
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
        edgePadding = 12.dp,
        divider = {},
    ) {
        sections.forEach { section ->
            val modified = categoriesOf(section).any { it in dirty }
            Tab(
                selected = section == active,
                onClick = { if (section != active) onSelect(section) },
                modifier = if (modified) Modifier.semantics { stateDescription = modifiedState } else Modifier,
                text = {
                    BadgedBox(badge = { if (modified) Badge(Modifier.size(6.dp)) }) {
                        Text(
                            stringResource(section.labelRes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (section == active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                },
            )
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
 * The modes as an M3 navigation rail (expanded windows), with Export as the rail's FAB and
 * Crop as the item for the Crop tab. Tapping the open mode closes the supporting pane, as on the phone.
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
    onCrop: (() -> Unit)? = null,
    lastEdit: Category? = null,
    /** Share sits right under the Export FAB, the rail's form of the top bar's pill. */
    onShare: (() -> Unit)? = null,
    cropSelected: Boolean = false,
) {
    val modifiedState = stringResource(R.string.editor_category_modified)
    NavigationRail(
        modifier = modifier.fillMaxHeight().helpSpot(LocalQuickHelp.current, HelpSpot.MODES),
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            if (exportVisible) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CONNECTED_GAP * 2),
                ) {
                    ExportFab(exporting = exporting, onClick = onExport)
                    if (onShare != null) ExportShareSmallFab(exporting = exporting, onClick = onShare)
                }
            }
            Spacer(Modifier.height(12.dp))
        },
    ) {
        // Scrolls: on a landscape phone (under 480dp tall, still routed to this layout) the
        // FAB and four labelled items can be taller than the window.
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val railItem: @Composable (EditorMode) -> Unit = { mode ->
                val modified = Category.entries.any { it.mode == mode && it in dirty }
                NavigationRailItem(
                    selected = !cropSelected && active?.mode == mode,
                    onClick = { onOpen(modeTapTarget(mode, active, lastEdit)) },
                    icon = {
                        BadgedBox(badge = { if (modified) Badge() }) {
                            Icon(modeIcon(mode), contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(mode.labelRes), maxLines = 1) },
                    modifier = if (modified) Modifier.semantics { stateDescription = modifiedState } else Modifier,
                )
            }
            railItem(EditorMode.LOOKS)
            railItem(EditorMode.EDIT)
            if (onCrop != null) {
                NavigationRailItem(
                    selected = cropSelected,
                    onClick = onCrop,
                    icon = { Icon(SpectraIcons.Crop, contentDescription = null) },
                    label = { Text(stringResource(R.string.tool_crop_title), maxLines = 1) },
                )
            }
            railItem(EditorMode.MASKS)
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

