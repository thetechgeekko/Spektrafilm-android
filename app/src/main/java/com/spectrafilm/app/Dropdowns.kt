/*
 * Spektrafilm for Android — dropdown pickers. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Plain and grouped dropdowns and the profile-option grouping. Split out of Widgets.kt (#273).
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A read-only exposed dropdown for a list of options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    selected: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = display(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(display(opt)) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

/** One selectable option in a grouped dropdown: a stable id plus its display label. */
data class DropdownOption(
    val id: String,
    val label: String,
    /** Optional spec line rendered under [label] (e.g. "ISO 400 · Tungsten"). */
    val spec: String = "",
    /** Optional one-sentence character note rendered under [spec]. */
    val summary: String = "",
)

/** A titled group of [DropdownOption]s (e.g. a stock category) for a grouped dropdown. */
data class DropdownGroup(val title: String, val options: List<DropdownOption>)

/** Convert StockCatalog [ProfileOption]s into ordered [DropdownGroup]s, preserving order. */
fun List<ProfileOption>.toGroups(): List<DropdownGroup> {
    val byGroup = LinkedHashMap<String, MutableList<DropdownOption>>()
    for (o in this) {
        byGroup.getOrPut(o.groupTitle) { mutableListOf() }
            .add(DropdownOption(o.id, o.label, o.spec, o.summary))
    }
    return byGroup.map { (title, opts) -> DropdownGroup(title, opts) }
}

/**
 * A read-only exposed dropdown whose options are organized under non-selectable group
 * headers. The field shows the label of the currently selected id (falling back to the
 * raw id when it isn't among the options). Used for built-in presets and for the
 * catalog-grouped film/print profile pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedDropdown(
    label: String,
    selectedId: String,
    groups: List<DropdownGroup>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = remember(selectedId, groups) {
        groups.firstNotNullOfOrNull { g -> g.options.firstOrNull { it.id == selectedId }?.label } ?: selectedId
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groups.forEachIndexed { gi, group ->
                if (group.title.isNotBlank()) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 16.dp, top = if (gi == 0) 8.dp else 12.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                group.options.forEach { opt ->
                    val detail = opt.spec.takeIf { it.isNotBlank() }
                    val note = opt.summary.takeIf { it.isNotBlank() }
                    DropdownMenuItem(
                        text = {
                            // Merged so TalkBack reads "Kodak Portra 400, ISO 400, Daylight,
                            // <character note>" as one item rather than three fragments.
                            Column(Modifier.semantics(mergeDescendants = true) {}) {
                                Text(opt.label)
                                if (detail != null) {
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (note != null) {
                                    Text(
                                        note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                }
                            }
                        },
                        onClick = { onSelect(opt.id); expanded = false },
                    )
                }
            }
        }
    }
}
