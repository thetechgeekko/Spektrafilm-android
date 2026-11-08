/*
 * Spektrafilm for Android — the open editor panel's body. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen's layout (#273), where it was the panelContent lambda.
 * Looks and Masks show the one picked tool; Edit shows every tool on one sideways rail of
 * instrument cards (ToolRail.kt), and a tool opened from its chip in full, under its own name
 * and reset.
 */
package com.spectrafilm.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

@Composable
internal fun ColumnScope.EditorPanelContent(
    sliderInteraction: SliderInteraction,
    state: ParamsState,
    activeCategory: Category?,
    onActiveCategoryChange: (Category) -> Unit,
    railDetail: Category?,
    onRailDetailChange: (Category?) -> Unit,
    /** Back closes the full tool only while nothing sits over the panel. */
    backEnabled: Boolean,
    dirty: Set<Category>,
    resetCategory: (Category) -> Unit,
    toolShown: (Category) -> Boolean,
    toolContent: @Composable ColumnScope.(Category) -> Unit,
) {
    CompositionLocalProvider(LocalSliderInteraction provides sliderInteraction) {
        val open = activeCategory
        val detail = railDetail
        if (open != null && open.mode == EditorMode.EDIT && detail != null) {
            // Only while nothing sits over the panel: the magnifier, crop and the
            // other full-screen tools own Back while they are open.
            BackHandler(
                enabled = backEnabled,
            ) { onRailDetailChange(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onRailDetailChange(null) }) {
                    Icon(
                        MaterialSymbols.ArrowBack,
                        contentDescription = stringResource(R.string.editor_rail_back_to_tools),
                    )
                }
                ToolHeader(
                    tool = detail,
                    onReset = if (detail in dirty) ({ resetCategory(detail) }) else null,
                    modifier = Modifier.weight(1f),
                )
            }
            toolContent(detail)
        } else if (open != null && open.mode == EditorMode.EDIT) {
            ToolRail(
                tools = stagesOf(EditorMode.EDIT).flatMap { categoriesOf(it) }.filter(toolShown),
                activeStage = open.stage,
                startTool = open,
                dirty = dirty,
                switchOf = { toolSwitch(state, it) },
                onScrolledTo = { stage ->
                    if (activeCategory?.stage != stage) {
                        onActiveCategoryChange(categoriesOf(stage).first(toolShown))
                    }
                },
                onOpenTool = { tool ->
                    onActiveCategoryChange(tool)
                    onRailDetailChange(tool)
                },
            ) { tool -> RailToolContent(state, tool, onOpen = { onActiveCategoryChange(tool); onRailDetailChange(tool) }) }
        } else if (open != null) {
            toolContent(open)
        }
    }
}
