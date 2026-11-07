/*
 * Spektrafilm for Android -- the Film & look strip: every film stock as a live tile. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Choosing a film is the first thing almost everyone does with a new photo, and through the
 * picker it took three taps and a scroll (open the browser, find the stock, tap it, then the
 * sheet closes). The strip puts the user's own photo rendered through each stock at the top of
 * the Film tab, so trying a look is one tap and comparing looks needs no tap at all.
 *
 * Layout follows the M3 uncontained carousel (m3-design-language skill, components-containment
 * "Carousel"): fixed-size items, 8dp gaps, 28dp (extraLarge) corners, free scrolling, and a way
 * to see every item without horizontal scrolling -- the stock picker field right below it opens
 * the full vertical browser. A LazyRow rather than the Carousel composable, because the strip
 * must scroll the selected stock into view and LazyListState does that with a stable API.
 *
 * Tiles share [StockPreviewViewModel] with the browser sheet, under one extra rule: the strip
 * renders only while the interactive preview is idle and no slider is held ([paused]), so a
 * tile never competes with the render the user is waiting for.
 */
package com.spectrafilm.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.awaitCancellation

/** Edge of one strip tile. Small enough that four show on a compact panel. */
private val STRIP_TILE_DP = 88.dp

/** Stocks in the order the browser lists them: the strip is the same catalog, sideways. */
internal fun stripStocks(groups: List<DropdownGroup>): List<DropdownOption> =
    groups.flatMap { it.options }

/** Index of [selectedId] in [stocks], or 0 when it is not there (a custom or unknown id). */
internal fun stripSelectedIndex(stocks: List<DropdownOption>, selectedId: String): Int =
    stocks.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

/**
 * A row of every stock in [groups], each a tile of the user's photo through that stock.
 * [sourceKey] identifies the photo (a new one starts a new rendering session); [paused] holds
 * rendering while the interactive preview is busy.
 */
@Composable
internal fun FilmStockStrip(
    selectedId: String,
    groups: List<DropdownGroup>,
    previewHost: StockPreviewHost,
    sourceKey: Any?,
    paused: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stocks = remember(groups) { stripStocks(groups) }
    if (stocks.isEmpty()) return
    val host by rememberUpdatedState(previewHost)
    // Rebinding on every idle moment is what keeps the tiles live: the session key carries the
    // current params, so a settled edit re-renders them, and an unchanged look is a no-op.
    // The strip is one owner of the shared view model, so the browser sheet closing over it
    // does not stop its tiles; each open is paired with its own close.
    val owner = remember { Any() }
    LaunchedEffect(sourceKey, paused) {
        if (paused) return@LaunchedEffect
        try {
            host.open(owner)
            awaitCancellation()
        } finally {
            host.close(owner)
        }
    }
    val previews by previewHost.previews.collectAsStateWithLifecycle()

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = stripSelectedIndex(stocks, selectedId))
    LaunchedEffect(selectedId) {
        val index = stripSelectedIndex(stocks, selectedId)
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == index && it.offset >= 0 &&
            it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fullyVisible) listState.animateScrollToItem(index)
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(stocks, key = { _, s -> s.id }) { _, stock ->
            // LazyRow composes only the tiles on screen, so this asks for the visible ones.
            LaunchedEffect(stock.id) { host.request(stock.id) }
            // A settled edit rebinds the session, which clears every finished tile until it
            // re-renders; keep showing the previous one meanwhile so the strip never blinks.
            // A new photo drops it, since the old photo's tile would be wrong, not just stale.
            val fresh = previews[stock.id]
            var last by remember(stock.id, sourceKey) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(fresh) { if (fresh != null) last = fresh }
            StripTile(
                stock = stock,
                selected = stock.id == selectedId,
                preview = fresh ?: last,
                onClick = { onSelect(stock.id) },
            )
        }
    }
}

@Composable
private fun StripTile(
    stock: DropdownOption,
    selected: Boolean,
    preview: ImageBitmap?,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(STRIP_TILE_DP)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(STRIP_TILE_DP)
                .clip(shape)
                .background(colors.surfaceContainerHighest)
                .then(
                    if (selected) Modifier.border(BorderStroke(3.dp, colors.primary), shape) else Modifier,
                ),
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    // The label below names the stock; the tile itself is decoration.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Text(
            stock.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
