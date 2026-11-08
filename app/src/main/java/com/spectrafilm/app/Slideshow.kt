/*
 * Spektrafilm for Android -- Library slideshow: the frames on screen, full screen, one after
 * another. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long each frame stays up while playing. */
private const val SLIDE_MS = 4_000L

/** Screen-sized frames for the slideshow: a few kept, so stepping back and forth is instant. */
internal object SlideshowFrames {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(uri: String): Bitmap? = cache.get(uri)

    /**
     * Blocking; call on an IO dispatcher. A RAW shows the camera's embedded JPEG (what the
     * Library's thumbnails use, larger); any other photo is decoded subsampled to about [edge]
     * on its long side and turned upright from its EXIF orientation.
     */
    fun load(context: Context, uri: String, raw: Boolean, edge: Int): Bitmap? {
        cache.get(uri)?.let { return it }
        val parsed = Uri.parse(uri)
        val bitmap = (if (raw) camera(context, parsed, edge) else decoded(context, parsed, edge))
            ?: LibraryThumbnails.load(context, uri, raw)
            ?: return null
        cache.put(uri, bitmap)
        return bitmap
    }

    private fun camera(context: Context, uri: Uri, edge: Int): Bitmap? {
        val loaded = RawCameraPreview.load(context, uri, edge) ?: return null
        return OpenPlaceholder.applyTransform(
            loaded.bitmap,
            placeholderTransform(loaded.orientation, SourceRotation.NONE),
            maxEdge = edge,
        )
    }

    private fun decoded(context: Context, uri: Uri, edge: Int): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
            inSampleSize = jpegSampleSize(bounds.outWidth, bounds.outHeight, edge)
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        val orientation = resolver.openInputStream(uri)?.use {
            ExifOrientation.fromExif(
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
        } ?: ExifOrientation.fromExif(ExifInterface.ORIENTATION_NORMAL)
        OpenPlaceholder.applyTransform(bitmap, placeholderTransform(orientation, SourceRotation.NONE), maxEdge = edge)
    }.getOrNull()
}

/**
 * The slideshow over [frames], starting at [start]: plays on its own, swipes by hand, and a
 * tap shows or hides the controls. Keeps the screen on while it is up. Back closes it.
 */
@Composable
internal fun LibrarySlideshow(frames: List<LibraryEntry>, start: Int = 0, onClose: () -> Unit) {
    if (frames.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, frames.lastIndex)) { frames.size }
    var playing by rememberSaveable { mutableStateOf(true) }
    var controls by rememberSaveable { mutableStateOf(true) }
    // One loop for the whole show rather than an effect keyed on the page: a drag that
    // interrupts the auto-advance (or settles back on the same page) changes no key, and an
    // effect keyed on currentPage would cancel its own scroll halfway. Each round waits for
    // the pager to be idle, then for SLIDE_MS of no touching, then advances; any drag in
    // between restarts the round, so a manual swipe also gets a full SLIDE_MS.
    LaunchedEffect(playing, frames.size) {
        if (!playing || frames.size < 2) return@LaunchedEffect
        while (true) {
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            val touched = withTimeoutOrNull(SLIDE_MS) { snapshotFlow { pager.isScrollInProgress }.first { it } }
            if (touched != null) continue
            try {
                pager.animateScrollToPage((pager.settledPage + 1) % frames.size)
            } catch (e: CancellationException) {
                // A drag took the pager mid-animation; only stop if the show itself closed.
                ensureActive()
            }
        }
    }
    // Controls fade away on their own once the show is running.
    LaunchedEffect(controls, playing) {
        if (controls && playing) {
            delay(SLIDE_MS)
            controls = false
        }
    }
    val edge = remember(ctx) {
        val metrics = ctx.resources.displayMetrics
        maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(1024, 2560)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(SpektraBrand.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controls = !controls
            },
    ) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val entry = frames[page]
            val bitmap by produceState(SlideshowFrames.cached(entry.documentUri), entry.documentUri) {
                // A reused page keeps its last value when the key changes; start from this frame.
                value = SlideshowFrames.cached(entry.documentUri)
                if (value == null) {
                    value = withContext(thumbnailLane) {
                        SlideshowFrames.load(ctx, entry.documentUri, entry.isRaw, edge)
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = entry.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: SpectraLoadingIndicator(color = SpektraBrand.onCanvas)
            }
        }
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SpektraBrand.scrim.copy(alpha = 0.45f))
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(SpectraIcons.Cancel, contentDescription = stringResource(R.string.screen_close), tint = SpektraBrand.onCanvas)
                }
                Text(
                    frames[pager.currentPage].name,
                    color = SpektraBrand.onCanvas,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.slideshow_position, pager.currentPage + 1, frames.size),
                    color = SpektraBrand.onCanvas,
                    style = MaterialTheme.typography.labelLarge,
                )
                FilledTonalIconButton(onClick = { playing = !playing }) {
                    Icon(
                        if (playing) SpectraIcons.Pause else SpectraIcons.Play,
                        contentDescription = stringResource(if (playing) R.string.slideshow_pause else R.string.slideshow_play),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
