/*
 * Spektrafilm for Android -- the photo Library: one folder of RAWs and photos. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The rikiros fork added a RAW folder browser so a shoot can be worked through without going
 * back to the system picker for every frame (credited in the PR). The implementation here is
 * our own: the folder is an OpenDocumentTree grant (no storage permission, works for SD cards
 * and cloud providers), the listing is a plain DocumentsContract query, and opening a frame
 * goes through the same dispatch as a shared or picked file.
 */
package com.spectrafilm.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import com.spectrafilm.libraw.RawFormats
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** One openable file in the Library folder. URIs are strings so this stays JVM-testable. */
internal data class LibraryEntry(
    val documentUri: String,
    val name: String,
    val mimeType: String?,
    val modifiedMs: Long,
    val sizeBytes: Long,
    /** A saved edit (recipe) exists for this frame, so reopening it restores the edit. */
    val edited: Boolean = false,
) {
    val isRaw: Boolean get() = detectSourceKind(name, mimeType) == SourceFileKind.RAW
}

/**
 * Keeps what the editor can open (stills and camera RAW; never video, .mcraw, folders or
 * sidecars) and orders it newest first, like a camera roll. Ties fall back to the name so the
 * grid does not reshuffle between refreshes.
 */
internal fun libraryEntriesOf(rows: List<LibraryEntry>): List<LibraryEntry> = rows
    .filter { row ->
        if (row.mimeType == "vnd.android.document/directory") return@filter false
        when (detectSourceKind(row.name, row.mimeType)) {
            SourceFileKind.PHOTO -> true
            // Listing is stricter than opening: an unknown file opened on purpose goes to the
            // RAW path (see detectSourceKind), but a folder also holds sidecars, .xmp, .txt and
            // the like, which must not show up as frames. So a RAW frame needs a known RAW
            // extension or a RAW MIME type (a provider may hide the extension).
            SourceFileKind.RAW -> RawFormats.isRawFileName(row.name) || RawFormats.isRawMimeType(row.mimeType)
            SourceFileKind.VIDEO, SourceFileKind.MCRAW -> false
        }
    }
    .sortedWith(compareByDescending<LibraryEntry> { it.modifiedMs }.thenBy { it.name.lowercase(Locale.ROOT) })

/**
 * Marks the entries that already carry a saved edit. A lookup that throws counts as not
 * edited: the badge is a hint, and a failed check must not hide the frame.
 */
internal fun markEdited(entries: List<LibraryEntry>, hasEdit: (documentUri: String) -> Boolean): List<LibraryEntry> =
    entries.map { entry ->
        val edited = runCatching { hasEdit(entry.documentUri) }.getOrDefault(false)
        if (edited == entry.edited) entry else entry.copy(edited = edited)
    }

/** How the Library orders its frames. */
internal enum class LibrarySort { DATE, NAME, SIZE }

/** Which frames the Library shows; null in [LibraryQuery.filter] shows them all. */
internal enum class LibraryFilter { RAW, PHOTO, EDITED, NOT_EDITED }

/**
 * The user's sort, filter and search over the folder. Sort and filter are remembered across
 * launches ([LibraryQueryStore]); the search text is not, so a returning user never meets a
 * grid silently narrowed by a word typed days ago.
 */
internal data class LibraryQuery(
    val sort: LibrarySort = LibrarySort.DATE,
    /** Newest, Z or largest first. */
    val descending: Boolean = true,
    val filter: LibraryFilter? = null,
    val text: String = "",
    /** Justified rows or the square grid; changes the layout, never which frames show. */
    val view: LibraryView = LibraryView.JUSTIFIED,
) {
    /** Something hides frames: a filter or search words. Sort alone never does. */
    val narrows: Boolean get() = filter != null || text.isNotBlank()

    /** Sort and filter as one stored string; the search text is left out on purpose. */
    fun encode(): String =
        listOf(sort.name, if (descending) "desc" else "asc", filter?.name ?: "ALL", view.name).joinToString(":")

    companion object {
        /** Reads [encode]; anything unknown falls back to the default for that part. */
        fun decode(stored: String?): LibraryQuery {
            val parts = stored?.split(':').orEmpty()
            return LibraryQuery(
                sort = parts.getOrNull(0)?.let { name -> LibrarySort.entries.firstOrNull { it.name == name } } ?: LibrarySort.DATE,
                descending = parts.getOrNull(1) != "asc",
                filter = parts.getOrNull(2)?.let { name -> LibraryFilter.entries.firstOrNull { it.name == name } },
                view = parts.getOrNull(3)?.let { name -> LibraryView.entries.firstOrNull { it.name == name } }
                    ?: LibraryView.JUSTIFIED,
            )
        }
    }
}

/**
 * The frames [query] shows, in its order. Search matches every word of the text against the
 * file name, ignoring case. Ties break on the name, then the URI, so the grid never reshuffles
 * between refreshes. A size the provider did not report (-1) sorts after every known size in
 * both directions.
 */
internal fun applyLibraryQuery(entries: List<LibraryEntry>, query: LibraryQuery): List<LibraryEntry> {
    val words = query.text.lowercase(Locale.ROOT).split(' ', '\t').filter { it.isNotEmpty() }
    val shown = entries.filter { entry ->
        val kept = when (query.filter) {
            null -> true
            LibraryFilter.RAW -> entry.isRaw
            LibraryFilter.PHOTO -> !entry.isRaw
            LibraryFilter.EDITED -> entry.edited
            LibraryFilter.NOT_EDITED -> !entry.edited
        }
        kept && words.all { entry.name.lowercase(Locale.ROOT).contains(it) }
    }
    val byName = compareBy<LibraryEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.documentUri }
    val primary: Comparator<LibraryEntry> = when (query.sort) {
        LibrarySort.DATE -> compareBy { it.modifiedMs }
        LibrarySort.NAME -> byName
        LibrarySort.SIZE -> compareBy { it.sizeBytes }
    }
    val ordered = if (query.descending) primary.reversed() else primary
    val unknownSizeLast = compareBy<LibraryEntry> { query.sort == LibrarySort.SIZE && it.sizeBytes < 0 }
    return shown.sortedWith(unknownSizeLast.then(ordered).then(byName))
}

/**
 * [base] plus every frame between [anchorUri] and [toUri] (inclusive) in [shown] order. An
 * anchor or target no longer shown leaves [base] as it is.
 */
internal fun selectionRange(
    shown: List<LibraryEntry>,
    anchorUri: String,
    toUri: String,
    base: Set<String>,
): Set<String> {
    val from = shown.indexOfFirst { it.documentUri == anchorUri }
    val to = shown.indexOfFirst { it.documentUri == toUri }
    if (from < 0 || to < 0) return base
    val out = LinkedHashSet(base)
    for (i in minOf(from, to)..maxOf(from, to)) out += shown[i].documentUri
    return out
}

/**
 * The share intent's type for [mimes]: the one type they all have, else any image when all
 * are images, else any type at all. A RAW reported as octet-stream widens the batch to any
 * type, so no receiving app is promised an image it cannot read.
 */
internal fun shareMimeType(mimes: List<String?>): String {
    val known = mimes.map { it?.lowercase(Locale.ROOT) ?: "*/*" }
    if (known.isEmpty()) return "*/*"
    if (known.distinct().size == 1) return known.first()
    return if (known.all { it.startsWith("image/") }) "image/*" else "*/*"
}

/** Where the Library's sort and filter are remembered. Production: [AppSettings]. */
internal interface LibraryQueryStore {
    fun load(): LibraryQuery
    fun save(query: LibraryQuery)

    /** Remembers nothing (tests, previews). */
    object None : LibraryQueryStore {
        override fun load() = LibraryQuery()
        override fun save(query: LibraryQuery) = Unit
    }
}

/** What the Library screen draws. */
internal data class LibraryUiState(
    /** The chosen folder tree, or null before the user picked one. */
    val treeUri: String? = null,
    val folderName: String? = null,
    val entries: List<LibraryEntry> = emptyList(),
    val loading: Boolean = false,
    /** The folder could not be read (grant revoked, provider gone, SD card removed). */
    val unreadable: Boolean = false,
)

/** Edit files beside the photos ([LibrarySidecars]). Blocking; called on the IO dispatcher. */
internal interface LibrarySidecarPort {
    /** Imports sidecars of frames that have no edit yet; returns how many. */
    fun importMissing(frames: List<LibraryEntry>, rows: List<LibraryEntry>): Int

    /** Not blocking: production runs it on the recipe FIFO, behind any queued paste or save. */
    suspend fun save(treeUri: String, frames: List<LibraryEntry>, rows: List<LibraryEntry>): LibrarySidecars.SaveResult

    object None : LibrarySidecarPort {
        override fun importMissing(frames: List<LibraryEntry>, rows: List<LibraryEntry>) = 0
        override suspend fun save(treeUri: String, frames: List<LibraryEntry>, rows: List<LibraryEntry>) =
            LibrarySidecars.SaveResult(0, 0)
    }
}

/** Progress of "Save edits beside photos". */
internal sealed interface LibrarySidecarState {
    data object Idle : LibrarySidecarState
    data object Running : LibrarySidecarState
    data class Done(val result: LibrarySidecars.SaveResult) : LibrarySidecarState
}

/** Progress of a Library look paste ([LibraryViewModel.pasteLookFrom]). */
internal sealed interface LibraryPasteState {
    data object Idle : LibraryPasteState
    data class Running(val count: Int) : LibraryPasteState
    data class Done(val result: LookSync.Result) : LibraryPasteState
}

/** Where the chosen folder lives, and its grant. Production: AppSettings + ContentResolver. */
internal interface LibraryFolderStore {
    var treeUri: String?
    /** Take a persisted read grant on [treeUri]; false when the provider refuses. */
    fun takeGrant(treeUri: String): Boolean
    /**
     * Release grants no longer needed after the folder changed: the old folder's, unless the
     * frame open in the editor still reads through it. Blocking; call off the main thread.
     */
    fun releaseUnusedGrants()
}

/**
 * Reads a frame's shape as shown: width / height after its EXIF rotation. Blocking (opens the
 * file's header); null when the format does not say.
 */
internal fun interface LibraryAspectReader {
    fun aspect(entry: LibraryEntry): Float?
}

/**
 * Frame shapes already read, for the whole process: reopening the Library lays out justified
 * rows at once instead of reflowing while headers are read again. Keyed by URI and date, so
 * a replaced file is read afresh. Bounded; cleared rather than evicted, as an overflow only
 * means some shapes are read again.
 */
internal object LibraryAspectCache {
    private const val MAX = 8_000
    private val map = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private fun key(entry: LibraryEntry) = entry.documentUri + "@" + entry.modifiedMs
    fun get(entry: LibraryEntry): Float? = map[key(entry)]
    fun put(entry: LibraryEntry, aspect: Float) {
        if (map.size >= MAX) map.clear()
        map[key(entry)] = aspect
    }
    fun clear() = map.clear()
}

/** Lists a folder tree. Throws when the folder cannot be read. */
internal fun interface LibraryLister {
    fun list(treeUri: String): LibraryListing
}

internal data class LibraryListing(val folderName: String?, val rows: List<LibraryEntry>)

/** One level of [tree]: the folder's own files, not its subfolders. */
internal fun listTree(resolver: ContentResolver, tree: Uri): LibraryListing {
    val treeDocId = DocumentsContract.getTreeDocumentId(tree)
    val folderName = resolver.query(
        DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    val rows = ArrayList<LibraryEntry>()
    val cursor = resolver.query(children, projection, null, null, null)
        ?: error("provider returned no cursor")
    cursor.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            rows += LibraryEntry(
                documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                name = c.getString(1) ?: continue,
                mimeType = c.getString(2),
                modifiedMs = if (c.isNull(3)) 0L else c.getLong(3),
                sizeBytes = if (c.isNull(4)) -1L else c.getLong(4),
            )
        }
    }
    return LibraryListing(folderName, rows)
}

/**
 * Grid thumbnails: the file's embedded EXIF preview first (RAWs and camera JPEGs carry one,
 * and it needs no decode), then the provider's own thumbnail. Kept in a small byte-sized LRU
 * so scrolling back is free.
 */
internal object LibraryThumbnails {
    private const val EDGE_PX = 320
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(uri: String): Bitmap? = cache.get(uri)

    /**
     * Blocking; call on an IO dispatcher. A RAW tries the camera's own embedded JPEG first: it
     * is sharper than the 160 px EXIF thumbnail, and for CR3, 3FR, IIQ and other containers
     * ExifInterface cannot read it is the only embedded image there is.
     */
    fun load(context: Context, uri: String, raw: Boolean): Bitmap? {
        cache.get(uri)?.let { return it }
        val parsed = Uri.parse(uri)
        val bitmap = (if (raw) camera(context, parsed) else null)
            ?: embedded(context, parsed)
            ?: provided(context, parsed)
            ?: resolved(context, parsed)
            ?: return null
        cache.put(uri, bitmap)
        return bitmap
    }

    private fun camera(context: Context, uri: Uri): Bitmap? {
        val loaded = RawCameraPreview.load(context, uri, EDGE_PX) ?: return null
        return OpenPlaceholder.applyTransform(
            loaded.bitmap,
            placeholderTransform(loaded.orientation, SourceRotation.NONE),
        )
    }

    private fun embedded(context: Context, uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            if (!exif.hasThumbnail()) return@use null
            val thumb = exif.thumbnailBitmap ?: return@use null
            val orientation = ExifOrientation.fromExif(
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
            OpenPlaceholder.applyTransform(thumb, placeholderTransform(orientation, SourceRotation.NONE))
        }
    }.getOrNull()

    private fun provided(context: Context, uri: Uri): Bitmap? = runCatching {
        DocumentsContract.getDocumentThumbnail(context.contentResolver, uri, Point(EDGE_PX, EDGE_PX), null)
    }.getOrNull()

    /**
     * MediaStore photos (the Gallery tab, the photo picker) are not documents, so the call
     * above has nothing for them; the resolver's own thumbnail does, already upright.
     */
    private fun resolved(context: Context, uri: Uri): Bitmap? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(EDGE_PX, EDGE_PX), null)
            }.getOrNull()
        } else if (uri.authority == MediaStore.AUTHORITY) {
            sampled(context, uri)
        } else {
            null
        }

    /**
     * Before Android 10 the resolver has no thumbnail call: decode the photo itself, sampled
     * down to about the cell size, and turn it upright from its EXIF orientation. Only for
     * MediaStore photos (camera JPEGs, screenshots), never a RAW, which [camera] covers.
     */
    private fun sampled(context: Context, uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= EDGE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        val orientation = resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        OpenPlaceholder.applyTransform(
            bitmap,
            placeholderTransform(ExifOrientation.fromExif(orientation), SourceRotation.NONE),
        )
    }.getOrNull()
}

/**
 * Frame shapes for justified rows: the cached ones at once, the rest read on [io] in batches
 * so the grid reflows a few times rather than once per frame. A new [measure] replaces the
 * last one. Shared by the folder grid and the album view.
 */
internal class LibraryAspects(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val reader: LibraryAspectReader,
) {
    private val _aspects = MutableStateFlow<Map<String, Float>>(emptyMap())
    val aspects: StateFlow<Map<String, Float>> = _aspects.asStateFlow()
    private var job: Job? = null

    fun measure(entries: List<LibraryEntry>) {
        job?.cancel()
        val known = HashMap<String, Float>()
        val missing = ArrayList<LibraryEntry>()
        for (entry in entries) {
            val cached = LibraryAspectCache.get(entry)
            if (cached != null) known[entry.documentUri] = cached else missing += entry
        }
        _aspects.value = known.toMap()
        if (missing.isEmpty()) return
        job = scope.launch {
            // On screen first: the grid is newest first by default, as is the listing.
            missing.chunked(BATCH).forEach { batch ->
                val read = withContext(io) {
                    batch.mapNotNull { entry ->
                        val aspect = runCatching { reader.aspect(entry) }.getOrNull()
                            ?.takeIf { it.isFinite() && it > 0f }
                            ?: return@mapNotNull null
                        LibraryAspectCache.put(entry, aspect)
                        entry.documentUri to aspect
                    }
                }
                if (read.isNotEmpty()) _aspects.value = _aspects.value + read
            }
        }
    }

    private companion object {
        const val BATCH = 24
    }
}
