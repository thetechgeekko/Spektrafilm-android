/*
 * Spektrafilm for Android — the folder strip under the photo, in the photo's film look. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A row of the Library folder the photo was opened from (PhotoBrowse), shown on demand from
 * the frame counter at the top of the canvas. Tap a frame to open it; the strip follows the
 * swipe. Each frame is drawn with the CURRENT photo's settings (stock, paper, exposure,
 * colour), so the whole roll can be judged in this look before opening anything.
 *
 * Layout follows the M3 multi-browse carousel (fixed-height items, a large item for the open
 * frame, small items around it, 8dp gaps, rounded corners). A LazyRow rather than the
 * Carousel composable, for the same reason as FilmStockStrip: the strip must keep the open
 * frame in view, which LazyListState does with a stable API.
 *
 * Looks render one at a time, only while the interactive preview is idle and no control is
 * held ([FolderStripModel.paused]), from the small grid thumbnails, so a strip never competes
 * with the render the user is waiting for.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Long edge a strip frame's look is rendered at, in pixels. */
internal const val FOLDER_STRIP_LOOK_EDGE = 160

/**
 * Everything the strip shows and does. [lookKey] changes whenever the photo's settings do,
 * so a frame re-renders in the new look; [paused] holds new renders back while the
 * interactive preview is busy or a control is held.
 */
internal class FolderStripModel(
    val frames: List<BrowseFrame>,
    val currentIndex: Int,
    val looks: FolderStripLooks?,
    val lookKey: Int,
    val paused: Boolean,
    /** Where grid thumbnails are read from disk. */
    val io: CoroutineDispatcher,
    val onOpen: (index: Int) -> Unit,
)

/** Which strip item to scroll to so the open frame sits second from the left. Pure. */
internal fun folderStripScrollTarget(currentIndex: Int): Int = max(0, currentIndex - 1)

/**
 * Renders strip frames in the open photo's look, one at a time, and keeps the results. Keyed
 * by frame and [FolderStripModel.lookKey], so a settings change re-renders and an unchanged
 * look is reused.
 */
internal class FolderStripLooks(
    private val context: Context,
    private val engine: SpektraEngine,
    private val state: ParamsState,
    private val default: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private val cache = object : LruCache<String, Bitmap>(LOOK_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun key(uri: String, lookKey: Int) = "$lookKey|$uri"

    fun cached(uri: String, lookKey: Int): Bitmap? = cache.get(key(uri, lookKey))

    /** The frame's look for [lookKey], rendered now if not cached. Null on failure. */
    suspend fun render(uri: String, thumbnail: Bitmap, lookKey: Int): Bitmap? = lock.withLock {
        cached(uri, lookKey)?.let { return@withLock it }
        val look = PlaceholderLook.render(
            context, engine, thumbnail, state, default, maxEdge = FOLDER_STRIP_LOOK_EDGE,
        ) ?: return@withLock null
        // Kept even when the frame that asked has gone: it is the right look for this key,
        // and the next request for it is then free.
        cache.put(key(uri, lookKey), look.bitmap)
        val active = currentCoroutineContext().isActive
        PlaceholderLook.report(look, published = active, cancelled = !active)
        look.bitmap
    }

    private companion object {
        const val LOOK_CACHE_BYTES = 8 * 1024 * 1024
    }
}

/** The "7 / 24" counter at the top of the canvas; a tap shows or hides the strip. */
@Composable
internal fun FolderStripToggle(
    position: Int,
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val desc = stringResource(
        if (open) R.string.editor_folder_strip_hide else R.string.editor_folder_strip_show,
    )
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(50),
        color = if (open) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
        },
        contentColor = if (open) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier
            // 48dp touch target around a compact pill.
            .size(width = 72.dp, height = 48.dp)
            .padding(vertical = 8.dp)
            .semantics { contentDescription = desc },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.editor_folder_strip_counter, position, count),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The strip itself: the folder in grid order, the open frame large and outlined. */
@Composable
internal fun FolderStrip(model: FolderStripModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = folderStripScrollTarget(model.currentIndex),
    )
    LaunchedEffect(model.currentIndex) {
        listState.animateScrollToItem(folderStripScrollTarget(model.currentIndex))
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .selectableGroup(),
    ) {
        itemsIndexed(model.frames, key = { _, f -> f.uri }) { index, frame ->
            FolderStripFrame(
                frame = frame,
                index = index,
                count = model.frames.size,
                selected = index == model.currentIndex,
                looks = model.looks,
                lookKey = model.lookKey,
                paused = model.paused,
                io = model.io,
                onOpen = { model.onOpen(index) },
            )
        }
    }
}

@Composable
private fun FolderStripFrame(
    frame: BrowseFrame,
    index: Int,
    count: Int,
    selected: Boolean,
    looks: FolderStripLooks?,
    lookKey: Int,
    paused: Boolean,
    io: CoroutineDispatcher,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val thumb by produceState(LibraryThumbnails.cached(frame.uri), frame.uri) {
        if (value == null) {
            value = withContext(io) {
                runCatching { LibraryThumbnails.load(context, frame.uri, frame.raw) }.getOrNull()
            }
        }
    }
    // Keeps the last look while a newer one renders, so a settings change never flashes the
    // plain thumbnail.
    val look by produceState(looks?.cached(frame.uri, lookKey), frame.uri, lookKey, paused, thumb) {
        looks?.cached(frame.uri, lookKey)?.let { value = it; return@produceState }
        val t = thumb ?: return@produceState
        if (paused || looks == null) return@produceState
        looks.render(frame.uri, t, lookKey)?.let { value = it }
    }
    val shown = (look ?: thumb)?.takeIf { !it.isRecycled }
    val shape = RoundedCornerShape(if (selected) 16.dp else 12.dp)
    val desc = stringResource(R.string.editor_folder_strip_frame, index + 1, count)
    Box(
        Modifier
            .size(width = if (selected) LARGE_ITEM_WIDTH else SMALL_ITEM_WIDTH, height = STRIP_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (selected) {
                    Modifier.border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        shape,
                    )
                } else {
                    Modifier
                },
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onOpen)
            .semantics { contentDescription = desc },
    ) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val STRIP_HEIGHT = 64.dp
private val LARGE_ITEM_WIDTH = 96.dp
private val SMALL_ITEM_WIDTH = 44.dp
