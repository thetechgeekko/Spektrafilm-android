/*
 * Spektrafilm for Android -- the Library grid, its day headers and cells (split from LibraryScreen.kt for #273). GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One line of the Library's list: a date header, or a row of frames. */
private sealed interface LibraryLine {
    val key: String

    data class Header(val section: LibrarySection) : LibraryLine {
        override val key: String get() = "day:" + (section.epochDay ?: "none") + ":" + section.entries.first().documentUri
    }

    data class Frames(val row: LibraryRow) : LibraryLine {
        override val key: String get() = row.key
    }
}

private val GRID_GAP = 3.dp
private val GRID_SIDE = 4.dp
private val JUSTIFIED_ROW_HEIGHT = 128.dp
private val SQUARE_MIN_CELL = 104.dp

/**
 * The frames as one LazyColumn: sticky day headers when sorted by date, and under each either
 * justified rows (the photo-book look of the Pen board) or the square grid. Rows are packed
 * off the composition's hot path with remember, keyed on what changes them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryGrid(
    entries: List<LibraryEntry>,
    query: LibraryQuery,
    aspects: Map<String, Float>,
    currentUri: String?,
    selection: Set<String>,
    onOpen: (Uri) -> Unit,
    onToggleSelected: (String) -> Unit,
    onSelectRange: (anchor: String, to: String, base: Set<String>) -> Unit = { _, _, _ -> },
    onToggleDay: (List<String>) -> Unit = {},
) {
    val selecting = selection.isNotEmpty()
    val listState = rememberLazyListState()
    val currentSelection by rememberUpdatedState(selection)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth - GRID_SIDE * 2).toPx() }
        val gapPx = with(density) { GRID_GAP.toPx() }
        val rowPx = with(density) { JUSTIFIED_ROW_HEIGHT.toPx() }
        val minCellPx = with(density) { SQUARE_MIN_CELL.toPx() }
        val zone = remember { java.util.TimeZone.getDefault() }
        val lines = remember(entries, query.sort, query.view, aspects, widthPx) {
            val out = ArrayList<LibraryLine>()
            for (section in librarySections(entries, query.sort, zone)) {
                if (query.sort == LibrarySort.DATE) out += LibraryLine.Header(section)
                val rows = when (query.view) {
                    LibraryView.JUSTIFIED -> justifyRows(
                        section.entries,
                        { aspects[it.documentUri] ?: DEFAULT_FRAME_ASPECT },
                        widthPx,
                        rowPx,
                        gapPx,
                    )
                    LibraryView.SQUARE -> squareRows(
                        section.entries,
                        squareColumns(widthPx, minCellPx, gapPx),
                        widthPx,
                        gapPx,
                    )
                }
                rows.mapTo(out) { LibraryLine.Frames(it) }
            }
            out
        }
        // Find the user's place in the shoot: scroll to the frame open in the editor, once per
        // visit, so a Refresh or a reflow does not yank the list back.
        var placed by remember { mutableStateOf(false) }
        LaunchedEffect(lines, currentUri) {
            if (placed || currentUri == null) return@LaunchedEffect
            val index = lines.indexOfFirst { line ->
                line is LibraryLine.Frames && line.row.entries.any { it.documentUri == currentUri }
            }
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index)
            placed = true
        }
        val sidePx = with(density) { GRID_SIDE.toPx() }
        val edgePx = with(density) { 64.dp.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val currentLines by rememberUpdatedState(lines)
        // The frame under a point of the list, from the laid-out rows: no per-cell modifiers.
        // Rows lay out from the right in RTL, so the hit test reads x from that edge there.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val listWidthPx = with(density) { maxWidth.toPx() }
        val frameAt: (Offset) -> String? = { raw ->
            val pos = if (rtl) Offset(listWidthPx - raw.x, raw.y) else raw
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { pos.y >= it.offset && pos.y < it.offset + it.size }
                ?.let { item -> currentLines.firstOrNull { it.key == item.key } as? LibraryLine.Frames }
                ?.row
                ?.let { row ->
                    var x = sidePx
                    row.entries.indices.firstOrNull { i ->
                        val hit = pos.x >= x && pos.x < x + row.widths[i] + gapPx
                        x += row.widths[i] + gapPx
                        hit
                    }?.let { row.entries[it].documentUri }
                }
        }
        // Drag-select (Photos style): hold a frame, then drag across others. The hold itself is
        // the cell's own long-press (which selects the frame, and stays the TalkBack action);
        // this layer only watches, in the Initial pass so it sees the touch before the cell, and
        // takes over the gesture once the hold has lasted long enough. A move before that is a
        // scroll and is left alone. Near the top or bottom edge the list scrolls along.
        var dragPointer by remember { mutableStateOf<Offset?>(null) }
        var dragAnchor by remember { mutableStateOf<String?>(null) }
        var dragBase by remember { mutableStateOf<Set<String>?>(null) }
        var dragLast by remember { mutableStateOf<String?>(null) }
        val dragTo: (Offset) -> Unit = { pos ->
            val anchor = dragAnchor
            val target = frameAt(pos)
            if (anchor != null && target != null && target != dragLast) {
                if (dragBase == null) dragBase = currentSelection
                dragLast = target
                onSelectRange(anchor, target, dragBase ?: emptySet())
            }
        }
        LaunchedEffect(dragPointer != null) {
            while (dragPointer != null) {
                val y = dragPointer?.y ?: break
                val step = when {
                    y < edgePx -> -(edgePx - y) / 3f
                    y > heightPx - edgePx -> (y - (heightPx - edgePx)) / 3f
                    else -> 0f
                }
                if (step != 0f) {
                    listState.scrollBy(step)
                    dragPointer?.let(dragTo)
                }
                kotlinx.coroutines.delay(16)
            }
        }
        // Where the grid sits on screen. A hold that starts select mode swaps the tab row for
        // the selection bar, which moves the grid under a still finger; measured in the grid's
        // own coordinates that reads as a drag. Movement is therefore judged on screen.
        val gridCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
        val onScreen: (Offset) -> Offset = { pos ->
            gridCoords[0]?.takeIf { it.isAttached }?.localToRoot(pos) ?: pos
        }
        val dragSelect = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val start = frameAt(down.position) ?: return@awaitEachGesture
                // Times out (null) when the finger stayed down and still for the long-press
                // timeout; anything else (a lift, a move past slop) ends the wait early.
                val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var ended = false
                    while (!ended) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                        ended = change == null || !change.pressed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    }
                    true
                }
                if (endedEarly != null) return@awaitEachGesture
                // The grid can still be settling under a still finger (rows re-justify as photo
                // shapes load, right after a launch), so the frame under the finger may no longer
                // be the one first touched. The anchor stays the touched frame (its cell keeps the
                // gesture and its long-press is what selected it), and the range extends only once
                // the finger itself has moved: a layout shift alone must not turn the hold into a
                // range across several photos.
                val anchorAt = onScreen(currentEvent.changes.firstOrNull { it.id == down.id }?.position ?: down.position)
                var moved = false
                dragAnchor = start
                dragLast = start
                dragBase = null
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        if (!moved) {
                            moved = (onScreen(change.position) - anchorAt).getDistance() > viewConfiguration.touchSlop
                            if (!moved) continue
                        }
                        dragPointer = change.position
                        val before = dragLast
                        dragTo(change.position)
                        if (dragLast != before) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                } finally {
                    dragPointer = null
                    dragAnchor = null
                    dragBase = null
                    dragLast = null
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { gridCoords[0] = it }
                .then(dragSelect),
            contentPadding = PaddingValues(
                start = GRID_SIDE,
                end = GRID_SIDE,
                // Room under the last row for the Open photo button.
                bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        ) {
            lines.forEach { line ->
                when (line) {
                    is LibraryLine.Header -> stickyHeader(key = line.key, contentType = "header") {
                        LibraryDayHeader(
                            line.section,
                            selecting = selecting,
                            daySelected = line.section.entries.all { it.documentUri in selection },
                            onToggleDay = { onToggleDay(line.section.entries.map { it.documentUri }) },
                        )
                    }
                    is LibraryLine.Frames -> item(key = line.key, contentType = "row") {
                        Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                            line.row.entries.forEachIndexed { i, entry ->
                                val w = with(density) { line.row.widths[i].toDp() }
                                val h = with(density) { line.row.height.toDp() }
                                // Keyed by file: a row named by its first frame can change its later
                                // frames on a filter or reflow, and a cell's thumbnail state must
                                // never carry over to a different file.
                                key(entry.documentUri) {
                                LibraryCell(
                                    entry,
                                    current = entry.documentUri == currentUri,
                                    selected = entry.documentUri in selection,
                                    selecting = selecting,
                                    modifier = Modifier.size(w, h),
                                    // Long-press starts selecting (as in Photos and Files); while
                                    // selecting, a tap toggles instead of opening.
                                    onClick = {
                                        if (selecting) onToggleSelected(entry.documentUri) else onOpen(Uri.parse(entry.documentUri))
                                    },
                                    onLongClick = { onToggleSelected(entry.documentUri) },
                                )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A sticky day header: Today, Yesterday, or the date (with the year only when it is not this
 * year), and how many frames the day holds. Opaque, so rows scroll under it cleanly.
 */
@Composable
private fun LibraryDayHeader(
    section: LibrarySection,
    selecting: Boolean = false,
    daySelected: Boolean = false,
    onToggleDay: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val zone = remember { java.util.TimeZone.getDefault() }
    val today = remember { localEpochDay(System.currentTimeMillis(), zone) }
    val label = when {
        section.undated || section.epochDay == null -> stringResource(R.string.library_day_undated)
        section.epochDay == today -> stringResource(R.string.library_day_today)
        section.epochDay == today - 1 -> stringResource(R.string.library_day_yesterday)
        else -> {
            val ms = section.entries.first().modifiedMs
            val thisYear = java.util.Calendar.getInstance(zone).get(java.util.Calendar.YEAR)
            val year = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
            var flags = android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
                android.text.format.DateUtils.FORMAT_ABBREV_ALL
            flags = flags or if (year == thisYear) {
                android.text.format.DateUtils.FORMAT_NO_YEAR
            } else {
                android.text.format.DateUtils.FORMAT_SHOW_YEAR
            }
            android.text.format.DateUtils.formatDateTime(ctx, ms, flags)
        }
    }
    val count = section.entries.size
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (selecting) {
            TextButton(onClick = onToggleDay) {
                Text(stringResource(if (daySelected) R.string.library_clear_day else R.string.library_select_day))
            }
        } else {
            Text(
                pluralStringResource(R.plurals.library_day_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCell(
    entry: LibraryEntry,
    current: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier.aspectRatio(1f),
) {
    val ctx = LocalContext.current
    val description = if (entry.edited) {
        stringResource(R.string.library_cell_edited_desc, entry.name)
    } else {
        entry.name
    }
    val selectLabel = stringResource(R.string.library_select_action)
    val thumb by produceState<Bitmap?>(LibraryThumbnails.cached(entry.documentUri), entry.documentUri) {
        if (value == null) value = withContext(thumbnailLane) { LibraryThumbnails.load(ctx, entry.documentUri, entry.isRaw) }
    }
    // The kit's photo grid: rounded tiles; a selected tile shrinks inside its cell and takes
    // a filled check, every tile shows an empty check circle while selecting.
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "cellInset")
    val corner by animateDpAsState(if (selected) 16.dp else CELL_CORNER, label = "cellCorner")
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .clip(RoundedCornerShape(CELL_CORNER))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = selectLabel)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                this.selected = selected || current
            },
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(inset)
            .clip(shape)
            .then(
                if (current && !selecting) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(
            SpectraIcons.SourceImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).size(32.dp),
        )
        if (entry.isRaw) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            ) {
                Text(
                    stringResource(R.string.library_badge_raw),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        if (entry.edited) {
            // Mirrors the RAW badge in the opposite corner: a saved edit reopens with the photo.
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Text(
                    stringResource(R.string.library_badge_edited),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
        if (selecting) {
            // Bottom-start, clear of the RAW and Edited badges along the top.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(MaterialTheme.colorScheme.primary)
                        } else {
                            // A light ring alone vanishes on a bright photo and leaves only the
                            // grey fill, which reads as a different state; a dark hairline outside
                            // it keeps the empty circle visible on any thumbnail.
                            Modifier
                                .background(SpektraBrand.scrim.copy(alpha = 0.3f))
                                .border(1.dp, SpektraBrand.scrim.copy(alpha = 0.6f), CircleShape)
                                .padding(1.dp)
                                .border(2.dp, SpektraBrand.onCanvas, CircleShape)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        SpectraIcons.Confirm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
