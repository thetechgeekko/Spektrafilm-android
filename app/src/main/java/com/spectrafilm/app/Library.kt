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
import android.graphics.Point
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Camera RAW containers LibRaw opens. Listing is stricter than opening: an unknown file opened
 * on purpose goes to the RAW path (see [detectSourceKind]), but a folder also holds sidecars,
 * .xmp, .txt and the like, which must not show up as frames.
 */
private val LIBRARY_RAW_EXTENSIONS = setOf(
    "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "raw",
    "pef", "ptx", "srw", "x3f", "3fr", "fff", "iiq", "mos", "mef", "mrw", "erf", "kdc", "dcr",
    "rwl", "gpr",
)

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
            SourceFileKind.RAW -> row.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
                LIBRARY_RAW_EXTENSIONS
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

/** Lists a folder tree. Throws when the folder cannot be read. */
internal fun interface LibraryLister {
    fun list(treeUri: String): LibraryListing
}

internal data class LibraryListing(val folderName: String?, val rows: List<LibraryEntry>)

internal class LibraryViewModel(
    private val store: LibraryFolderStore,
    private val lister: LibraryLister,
    private val io: CoroutineDispatcher,
    /** True when a saved edit exists for a document URI. Blocking; called on [io]. */
    private val hasEdit: (documentUri: String) -> Boolean = { false },
    /** Pastes the look of the photo at the first URI onto the targets. Called on [io]. */
    private val pasteLook: suspend (sourceUri: String, targets: List<LookSync.Target>) -> LookSync.Result =
        { _, _ -> LookSync.Result(0, 0, noSource = true) },
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState(treeUri = store.treeUri))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    private val _selection = MutableStateFlow<Set<String>>(emptySet())

    /** Document URIs the user selected (long-press, then tap) to paste a look onto. */
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _paste = MutableStateFlow<LibraryPasteState>(LibraryPasteState.Idle)
    val paste: StateFlow<LibraryPasteState> = _paste.asStateFlow()

    fun toggleSelected(documentUri: String) {
        _selection.value = _selection.value.let { if (documentUri in it) it - documentUri else it + documentUri }
    }

    fun selectAll() {
        _selection.value = _state.value.entries.mapTo(LinkedHashSet()) { it.documentUri }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /**
     * Gives every selected frame the look of [sourceUri] (the photo open in the editor), keeping
     * each frame's own crop, masks and rotation ([LookSync]). Ends selection and re-reads the
     * folder so the Edited badges catch up.
     */
    fun pasteLookFrom(sourceUri: String?) {
        if (_paste.value is LibraryPasteState.Running) return
        val picked = _selection.value
        val targets = _state.value.entries
            .filter { it.documentUri in picked && it.documentUri != sourceUri }
            .map { LookSync.Target(it.documentUri, it.name) }
        if (sourceUri == null) {
            _paste.value = LibraryPasteState.Done(LookSync.Result(0, 0, noSource = true))
            return
        }
        if (targets.isEmpty()) return
        _paste.value = LibraryPasteState.Running(targets.size)
        viewModelScope.launch {
            val result = try {
                withContext(io) { pasteLook(sourceUri, targets) }
            } catch (cancelled: CancellationException) {
                _paste.value = LibraryPasteState.Idle
                throw cancelled
            } catch (failure: Exception) {
                Diag.w("library paste failed: ${failure.message}")
                LookSync.Result(applied = 0, skipped = targets.size)
            }
            if (!result.noSource) _selection.value = emptySet()
            _paste.value = LibraryPasteState.Done(result)
            if (result.applied > 0) refresh()
        }
    }

    /** The screen showed the outcome of [pasteLookFrom]. */
    fun pasteShown() {
        if (_paste.value is LibraryPasteState.Done) _paste.value = LibraryPasteState.Idle
    }

    /** Re-read the folder. Called when the screen is shown, so new frames appear. */
    fun refresh() {
        val tree = store.treeUri ?: run {
            _state.value = LibraryUiState()
            return
        }
        loadJob?.cancel()
        _state.value = _state.value.copy(treeUri = tree, loading = true, unreadable = false)
        loadJob = viewModelScope.launch {
            val listing = try {
                withContext(io) {
                    val listed = lister.list(tree)
                    listed.copy(rows = markEdited(libraryEntriesOf(listed.rows), hasEdit))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Diag.w("library list failed: ${failure.message}")
                null
            }
            if (store.treeUri != tree) return@launch
            if (listing != null) {
                // A frame that left the folder cannot stay selected.
                val present = listing.rows.mapTo(HashSet()) { it.documentUri }
                _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in present }
            }
            _state.value = if (listing == null) {
                LibraryUiState(treeUri = tree, folderName = _state.value.folderName, unreadable = true)
            } else {
                LibraryUiState(
                    treeUri = tree,
                    folderName = listing.folderName,
                    entries = listing.rows,
                )
            }
        }
    }

    /** The user picked [treeUri] in the system folder picker. */
    fun chooseFolder(treeUri: String) {
        val previous = store.treeUri
        // Record the folder before taking its grant, so a source restore running meanwhile
        // already counts the grant as the Library's and does not release it.
        store.treeUri = treeUri
        _selection.value = emptySet()
        if (!store.takeGrant(treeUri)) {
            // Without a persisted grant the folder would vanish on the next cold start; still
            // show it for this session rather than refusing the pick outright.
            Diag.w("library folder grant not persistable")
        }
        if (previous != null && previous != treeUri) releaseUnused()
        _state.value = LibraryUiState(treeUri = treeUri)
        refresh()
    }

    /** Forget the folder and its grant. */
    fun forgetFolder() {
        store.treeUri ?: return
        loadJob?.cancel()
        store.treeUri = null
        _selection.value = emptySet()
        releaseUnused()
        _state.value = LibraryUiState()
    }

    private fun releaseUnused() {
        viewModelScope.launch(io) {
            runCatching { store.releaseUnusedGrants() }
                .onFailure { Diag.w("library grant release failed: ${it.message}") }
        }
    }

    companion object {
        fun androidStore(context: Context, settings: AppSettings): LibraryFolderStore {
            val resolver = context.applicationContext.contentResolver
            return object : LibraryFolderStore {
                override var treeUri: String?
                    get() = settings.libraryTreeUri
                    set(v) { settings.libraryTreeUri = v }

                override fun takeGrant(treeUri: String): Boolean = runCatching {
                    resolver.takePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                }.getOrDefault(false)

                // The source runtime owns every persisted grant: it keeps the editor's source,
                // the Library folder, and the folder the current source was opened from.
                override fun releaseUnusedGrants() {
                    sourceAccessRuntime(context).coordinator.reconcilePersistedGrants()
                }
            }
        }

        /** A frame is edited when the editor saved a recipe for its URI (see [Recipes.keyFor]). */
        fun androidEditLookup(context: Context): (String) -> Boolean {
            val app = context.applicationContext
            return { uri -> Recipes.exists(app, Recipes.keyFor(Uri.parse(uri))) }
        }

        /**
         * Runs on the recipe FIFO ([RecipeWorkRuntime]), behind any editor save already queued,
         * so the look pasted is the one the editor last saved.
         */
        fun androidLookPaste(recipes: RecipeRepository): suspend (String, List<LookSync.Target>) -> LookSync.Result =
            { source, targets -> RecipeWorkRuntime.submit { LookSync.paste(recipes, source, targets) }.await() }

        fun androidLister(context: Context): LibraryLister {
            val resolver = context.applicationContext.contentResolver
            return LibraryLister { tree -> listTree(resolver, Uri.parse(tree)) }
        }
    }
}

/** One level of [tree]: the folder's own files, not its subfolders. */
private fun listTree(resolver: ContentResolver, tree: Uri): LibraryListing {
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

    /** Blocking; call on an IO dispatcher. */
    fun load(context: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        val parsed = Uri.parse(uri)
        val bitmap = embedded(context, parsed) ?: provided(context, parsed) ?: return null
        cache.put(uri, bitmap)
        return bitmap
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
}
