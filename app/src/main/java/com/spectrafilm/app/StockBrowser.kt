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
 * Each card also shows the user's own photo through that stock, rendered by
 * [StockPreviewViewModel] while the sheet is open. Those tiles were held back until their
 * cost was measured; StockPreviews.kt has the numbers and the rules that keep them off the
 * interactive renderer. Without a [StockPreviewHost] (no photo yet) the cards stay text-only.
 */
package com.spectrafilm.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.runtime.rememberUpdatedState
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
internal fun StockPickerField(
    label: String,
    selectedId: String,
    groups: List<DropdownGroup>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    previewHost: StockPreviewHost? = null,
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
        shape = CARD_SHAPE,
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
            previewHost = previewHost,
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
    previewHost: StockPreviewHost?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Tiles render only while this sheet is up: no slider can be dragged under a modal
    // sheet, so the previews never compete with an interactive render.
    // The host is rebuilt on every editor recomposition; key the session to the sheet's own
    // lifetime and always talk to the latest host.
    val host by rememberUpdatedState(previewHost)
    val owner = remember { Any() }
    LaunchedEffect(Unit) { host?.open?.invoke(owner) }
    DisposableEffect(Unit) { onDispose { host?.close?.invoke(owner) } }
    val previews = previewHost?.previews?.collectAsStateWithLifecycle()
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
                groups.forEachIndexed { gi, g ->
                    if (g.title.isNotBlank()) add(StockRow.Header(g.title, "h$gi"))
                    // Group index in the key: one stock may sit in more than one group.
                    g.options.forEachIndexed { i, o -> add(StockRow.Item(o, i, g.options.size, "i$gi:${o.id}")) }
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
            // Stocks of one family are one grouped list (2dp seams, 16dp outer corners), the
            // same segmented list as Settings and Photo info.
            verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
        ) {
            items(
                rows,
                key = { row -> row.key },
                contentType = { row -> if (row is StockRow.Header) 0 else 1 },
            ) { row ->
                when (row) {
                    is StockRow.Header -> Text(
                        row.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 16.dp, bottom = 8.dp, start = 4.dp)
                            .semantics { heading() },
                    )
                    is StockRow.Item -> {
                        if (previewHost != null) {
                            // LazyColumn composes only cards on screen, so this asks for the
                            // visible tiles, newest scroll position first.
                            LaunchedEffect(row.option.id) { host?.request?.invoke(row.option.id) }
                        }
                        StockCard(
                            option = row.option,
                            selected = row.option.id == selectedId,
                            onClick = { onSelect(row.option.id) },
                            shape = segmentShape(row.index, row.count),
                            showPreview = previewHost != null,
                            preview = previews?.value?.get(row.option.id),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface StockRow {
    /** Stable LazyColumn key, unique across the whole flattened list. */
    val key: String

    data class Header(val title: String, override val key: String) : StockRow
    data class Item(val option: DropdownOption, val index: Int, val count: Int, override val key: String) : StockRow
}

@Composable
private fun StockCard(
    option: DropdownOption,
    selected: Boolean,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    showPreview: Boolean = false,
    preview: ImageBitmap? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val selectedLabel = stringResource(R.string.stock_browser_selected)
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            // One node per stock: name, spec and character note read as a single item, and the
            // selection is exposed as state rather than only as a fill colour.
            .semantics(mergeDescendants = true) {
                this.role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showPreview) StockPreviewTile(preview)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge)
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

/**
 * The photo through one stock. A fixed square, so cards never reflow when a tile lands; a
 * plain themed tile while it renders, with no per-tile spinner (28 spinners read as broken).
 * Decorative for accessibility: the card already reads the stock's name and note.
 */
@Composable
private fun StockPreviewTile(preview: ImageBitmap?) {
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .size(72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
    ) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
