/*
 * Spektrafilm for Android — the edit history list (hold Undo). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Every state in the session's history, newest at the top, each named by what changed to
 * reach it. The live state is marked; states above it are the redo branch and are shown
 * dimmed, because a new edit would discard them. Tapping a row walks undo or redo to it.
 *
 * [timeline] and [current] are taken once when the sheet opens ([historyTimeline]); the
 * sheet closes on a pick, so they never go stale under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditHistorySheet(
    timeline: List<EditSnapshot>,
    current: Int,
    onPick: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val labels = remember(timeline) {
        timeline.indices.map { i -> if (i == 0) null else describeStep(timeline[i - 1], timeline[i]) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Newest first, and opened scrolled to the live state rather than to a redo branch tip.
    val rows = timeline.indices.reversed().toList()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (rows.indexOf(current) - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.editor_history_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 8.dp)
                    .semantics { heading() },
            )
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(rows, key = { _, i -> i }) { _, i ->
                    val isCurrent = i == current
                    val ahead = i > current
                    ListItem(
                        headlineContent = {
                            Text(
                                historyLabelText(labels[i]),
                                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                                color = if (ahead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = if (isCurrent) {
                            { Text(stringResource(R.string.editor_history_current)) }
                        } else null,
                        colors = ListItemDefaults.colors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier
                            .semantics { selected = isCurrent }
                            .clickable(enabled = !isCurrent) { onPick(i) },
                    )
                }
            }
        }
    }
}
