/*
 * Spektrafilm for Android — film and paper stock browser. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Choosing a film stock is the single most product-defining act in this app, and until now it
 * was a `GroupedDropdown`: a menu of 28 bare names. `catalog.json` has always carried an ISO,
 * a colour balance and a one-sentence character note for every one of them, and `optionsFor`
 * threw all three away before the picker ever saw them.
 *
 * The dropdown now shows that detail, but a menu is still the wrong shape for this: the rows
 * are cramped, a long list of them is hard to scan, and there is nowhere to say which group a
 * stock belongs to except a header you have already scrolled past. This is the same content in
 * a sheet that can breathe — one card per stock, grouped, with the selected one marked.
 *
 * Deliberately NOT thumbnails of the user's photograph through each stock. That was the
 * ambitious version in the plan, and it is gated on a measurement nobody has taken: 28
 * simulate() runs would contend with the interactive renderer on the same cores. The research
 * pre-authorised this text-rich version as the fallback that still delivers the information
 * win, and it is what ships until the thumbnail cost is measured on a device.
 */
package com.spectrafilm.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The field that stands where the dropdown used to: the current stock, its spec line, and a
 * press target that opens [StockBrowserSheet].
 */
@Composable
fun StockPickerField(
    label: String,
    selectedId: String,
    groups: List<DropdownGroup>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var browsing by remember { mutableStateOf(false) }
    val selected = remember(selectedId, groups) {
        groups.firstNotNullOfOrNull { g -> g.options.firstOrNull { it.id == selectedId } }
    }
    val name = selected?.label ?: selectedId
    val spec = selected?.spec.orEmpty()
    val openLabel = stringResource(R.string.stock_browser_change, label)

    OutlinedButton(
        onClick = { browsing = true },
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
                .semantics(mergeDescendants = true) { role = Role.Button },
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (spec.isNotBlank()) {
                Text(spec, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(openLabel, style = MaterialTheme.typography.labelMedium)
    }

    if (browsing) {
        StockBrowserSheet(
            title = label,
            selectedId = selectedId,
            groups = groups,
            onSelect = { onSelect(it); browsing = false },
            onDismiss = { browsing = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockBrowserSheet(
    title: String,
    selectedId: String,
    groups: List<DropdownGroup>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        // Flattened to one list so the whole catalog scrolls as a single surface rather than
        // a column of independently scrolling groups.
        val rows = remember(groups) {
            buildList {
                for (g in groups) {
                    if (g.title.isNotBlank()) add(StockRow.Header(g.title))
                    for (o in g.options) add(StockRow.Item(o))
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            // The last card has to clear the system navigation, and that is 24dp of gesture
            // handle on this device but 48dp of button bar on another -- a fixed 32dp happens
            // to look fine here and puts the final stock under the nav bar in three-button
            // mode. Take it from the inset. Over-padding the bottom of a scrolling list is
            // invisible; under-padding it hides a row you cannot scroll to.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows) { row ->
                when (row) {
                    is StockRow.Header -> Text(
                        row.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 14.dp, bottom = 2.dp, start = 4.dp)
                            .semantics { heading() },
                    )
                    is StockRow.Item -> StockCard(
                        option = row.option,
                        selected = row.option.id == selectedId,
                        onClick = { onSelect(row.option.id) },
                    )
                }
            }
        }
    }
}

private sealed interface StockRow {
    data class Header(val title: String) : StockRow
    data class Item(val option: DropdownOption) : StockRow
}

@Composable
private fun StockCard(option: DropdownOption, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val selectedLabel = stringResource(R.string.stock_browser_selected)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // One node per stock: name, spec and character note read as a single item, and the
            // selection is exposed as state rather than only as a fill colour.
            .semantics(mergeDescendants = true) {
                this.role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.label, style = MaterialTheme.typography.titleSmall)
                if (option.spec.isNotBlank()) {
                    Text(option.spec, style = MaterialTheme.typography.labelSmall, color = accent)
                }
                if (option.summary.isNotBlank()) {
                    Text(
                        option.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(
                    SpectraIcons.Confirm,
                    contentDescription = selectedLabel,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
