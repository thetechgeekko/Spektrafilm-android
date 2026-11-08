/*
 * Spektrafilm for Android -- the Film & look strip: every film stock as a live tile. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Choosing a film is the first thing almost everyone does with a new photo, and through the
 * picker it took three taps and a scroll (open the browser, find the stock, tap it, then the
 * sheet closes). The strip puts the user's own photo rendered through each stock at the top of
 * the Film tab, so trying a look is one tap and comparing looks needs no tap at all.
 *
 * Layout is the M3 multi-browse carousel (the Carousel composable, Akshay 2026-11-08): one
 * large tile, then medium and small ones that grow as they scroll in, 28dp (extraLarge)
 * corners, and the stock's name on the tile, fading out on the small ones. The stock picker
 * field right below it opens the full vertical browser, the carousel's "show all" (every item
 * reachable without horizontal scrolling).
 *
 * Tiles share [StockPreviewViewModel] with the browser sheet, under one extra rule: the strip
 * renders only while the interactive preview is idle and no slider is held ([paused]), so a
 * tile never competes with the render the user is waiting for.
 */
package com.spectrafilm.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest

/** Height of the carousel, and so of every tile. Leaves the photo most of a compact panel. */
private val STRIP_HEIGHT_DP = 128.dp

/** Width the large tile aims for; the carousel fits medium and small tiles around it. */
private val STRIP_LARGE_DP = 168.dp

/** Tiles this many around the large one are asked for when rendering (re)starts. */
private const val STRIP_REQUEST_AHEAD = 4

/** Stocks in the order the browser lists them: the strip is the same catalog, sideways. */
internal fun stripStocks(groups: List<DropdownGroup>): List<DropdownOption> =
    groups.flatMap { it.options }

/** Index of [selectedId] in [stocks], or 0 when it is not there (a custom or unknown id). */
internal fun stripSelectedIndex(stocks: List<DropdownOption>, selectedId: String): Int =
    stocks.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

/**
 * A multi-browse carousel of every stock in [groups], each a tile of the user's photo through
 * that stock. [sourceKey] identifies the photo (a new one starts a new rendering session);
 * [paused] holds rendering while the interactive preview is busy.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val latestStocks by rememberUpdatedState(stocks)
    val state = rememberCarouselState(initialItem = stripSelectedIndex(stocks, selectedId)) { latestStocks.size }
    LaunchedEffect(sourceKey, paused) {
        if (paused) return@LaunchedEffect
        try {
            host.open(owner)
            // Ask again for the tiles on screen (the large one and those after it) whenever the
            // carousel settles on another one: a request made before the photo was decoded
            // (the strip opens with a new photo, before its first render) was dropped, and the
            // per-tile request below does not rerun for a tile that stayed composed.
            snapshotFlow { state.currentItem }.collectLatest { first ->
                for (i in first until first + STRIP_REQUEST_AHEAD) {
                    latestStocks.getOrNull(i)?.let { host.request(it.id) }
                }
            }
            awaitCancellation()
        } finally {
            host.close(owner)
        }
    }
    val previews by previewHost.previews.collectAsStateWithLifecycle()

    // A pick from the browser (or Undo) brings that stock into the large slot.
    LaunchedEffect(selectedId) {
        val index = stripSelectedIndex(stocks, selectedId)
        if (state.currentItem != index) state.animateScrollToItem(index)
    }

    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = STRIP_LARGE_DP,
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT_DP)
            .selectableGroup(),
        itemSpacing = 8.dp,
    ) { index ->
        val stock = stocks[index]
        // The carousel composes only the tiles on screen, so this asks for the visible ones.
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarouselItemScope.StripTile(
    stock: DropdownOption,
    selected: Boolean,
    preview: ImageBitmap?,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val colors = MaterialTheme.colorScheme
    val info = carouselItemDrawInfo
    Box(
        modifier = Modifier
            .fillMaxSize()
            .maskClip(shape)
            .then(if (selected) Modifier.maskBorder(BorderStroke(3.dp, colors.primary), shape) else Modifier)
            .background(colors.surfaceContainerHighest)
            // A small tile shows no name, so the tile itself carries it for TalkBack.
            .semantics { contentDescription = stock.label }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // The name sits on the tile over a scrim and fades out as the tile shrinks toward the
        // small keyline, where it could not be read anyway.
        Text(
            stock.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = SpektraBrand.onCanvas,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                // Already the tile's description; read once, not twice.
                .clearAndSetSemantics {}
                .graphicsLayer {
                    val range = (info.maxSize - info.minSize).coerceAtLeast(1f)
                    alpha = ((info.size - info.minSize) / range * 2f - 0.6f).coerceIn(0f, 1f)
                }
                .background(Brush.verticalGradient(listOf(Color.Transparent, SpektraBrand.scrim.copy(alpha = 0.6f))))
                .padding(start = 14.dp, end = 10.dp, top = 18.dp, bottom = 10.dp),
        )
    }
}
