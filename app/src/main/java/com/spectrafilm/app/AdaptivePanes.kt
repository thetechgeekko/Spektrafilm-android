/*
 * Spektrafilm for Android -- the M3 list-detail pane layout shared by Settings and the
 * Library's albums. GPLv3. Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Whether a list-detail destination (Settings, an open album) shows both panes: from the M3
 * expanded width class up, the same breakpoint the editor docks its side pane at. Medium
 * windows stay single-pane, because the detail pages would be squeezed under 480dp beside a
 * 360dp list.
 */
internal fun listDetailTwoPane(widthDp: Float): Boolean =
    windowSizeClass(widthDp, heightDp = 1f).isExpandedWidth()

/**
 * The material3-adaptive [ListDetailPaneScaffold] with both panes shown (#457): [list] in a
 * fixed leading pane [listWidthDp] wide (the M3 360dp / 412dp supporting width, see
 * [sidePaneWidthDp]), [detail] filling the rest, a divider between them.
 *
 * Only for the two-pane case: callers keep their single-pane path, where each detail page is
 * a navigation destination of its own, so Back and deep links behave as before. The pane
 * count is therefore pinned here rather than derived again from the window, which keeps the
 * layout and the caller's [listDetailTwoPane] decision from disagreeing.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ListDetailPanes(
    listWidthDp: Float,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListDetailPaneScaffold(
        directive = PaneScaffoldDirective.Default.copy(
            maxHorizontalPartitions = 2,
            horizontalPartitionSpacerSize = 0.dp,
            defaultPanePreferredWidth = listWidthDp.dp,
        ),
        value = TWO_PANES,
        listPane = { AnimatedPane { list() } },
        detailPane = {
            AnimatedPane {
                Row(Modifier.fillMaxSize()) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
                }
            }
        },
        modifier = modifier,
    )
}

/** List (secondary) and detail (primary) both expanded; no extra pane. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val TWO_PANES = ThreePaneScaffoldValue(
    primary = PaneAdaptedValue.Expanded,
    secondary = PaneAdaptedValue.Expanded,
    tertiary = PaneAdaptedValue.Hidden,
)
