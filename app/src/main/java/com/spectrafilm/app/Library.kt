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
    private val sidecars: LibrarySidecarPort = LibrarySidecarPort.None,
    /**
     * Where writes run: look pastes, sidecar saves and grant releases (#453). The Library's
     * ViewModel is scoped to its navigation entry and cleared when the user leaves, which
     * must not cut a write to their photos short, so the app passes a process-wide scope.
     * Folder listings run there as well, since a pending save waits on one. Null (tests)
     * keeps them on [viewModelScope].
     */
    writes: CoroutineScope? = null,
    /**
     * Held by every look paste and sidecar save for its whole run, across every Library entry
     * (#453). A write outlives the entry that started it, so a save asked for from a later
     * entry waits here for earlier writes and then lists the folder again, choosing frames
     * and sidecar pairings from what those writes left. The app passes one process-wide lock.
     */
    private val writeLock: Mutex = Mutex(),
) : ViewModel() {
    private val writeScope: CoroutineScope = writes ?: viewModelScope
    /** The whole last listing (frames and other files), for pairing frames with sidecars. */
    private var lastRows: List<LibraryEntry> = emptyList()

    /** The folder [lastRows] was listed from. */
    private var lastRowsTree: String? = null

    private val _sidecarSave = MutableStateFlow<LibrarySidecarState>(LibrarySidecarState.Idle)
    val sidecarSave: StateFlow<LibrarySidecarState> = _sidecarSave.asStateFlow()

    /**
     * Set when a save stopped for want of a write grant: the frames it was for (empty = every
     * edited frame). Choosing the same folder again retries it.
     */
    private var saveAfterGrant: Set<String>? = null

    /**
     * Writes the edit of every edited frame (or of the selected ones, while selecting) beside
     * it. Without a write grant the result says so and the screen asks for the folder again.
     */
    fun saveEditsBesidePhotos(only: Set<String> = _selection.value) {
        val tree = store.treeUri ?: return
        if (_sidecarSave.value is LibrarySidecarState.Running) return
        val picked = only
        _sidecarSave.value = LibrarySidecarState.Running
        writeScope.launch {
            // An earlier paste changes which frames are edited, and an earlier save creates the
            // sidecars this one must update rather than create again. Either may belong to an
            // earlier Library entry whose refresh never reaches this one, and may have finished
            // before this save was asked for, so always list the folder again under the lock
            // before choosing frames and rows.
            writeLock.lock()
            try {
                refresh()
                saveLocked(tree, picked)
            } finally {
                writeLock.unlock()
            }
        }
    }

    /** The save itself, under [writeLock]: frames and rows from the settled listing. */
    private suspend fun saveLocked(tree: String, picked: Set<String>) {
        // A refresh can start while we wait on another: settle on the latest one.
        while (true) {
            val job = loadJob ?: break
            job.join()
            if (loadJob === job) break
        }
        // The folder may have changed while we waited. Frames, rows and destination must
        // all come from the folder the save was asked for, or nothing is written.
        if (store.treeUri != tree || _state.value.treeUri != tree || lastRowsTree != tree) {
            _sidecarSave.value = LibrarySidecarState.Idle
            return
        }
        val frames = _state.value.entries.filter { it.edited && (picked.isEmpty() || it.documentUri in picked) }
        val rows = lastRows
        val result = try {
            withContext(io) { sidecars.save(tree, frames, rows) }
        } catch (cancelled: CancellationException) {
            _sidecarSave.value = LibrarySidecarState.Idle
            throw cancelled
        } catch (failure: Exception) {
            Diag.w("sidecar save failed: ${failure.message}")
            LibrarySidecars.SaveResult(saved = 0, failed = frames.size)
        }
        saveAfterGrant = if (result.needsWriteAccess) picked else null
        _sidecarSave.value = LibrarySidecarState.Done(result)
        if (result.saved > 0) {
            _selection.value = emptySet()
            refresh()
        }
    }

    /** The screen showed the outcome of [saveEditsBesidePhotos]. */
    fun sidecarSaveShown() {
        if (_sidecarSave.value is LibrarySidecarState.Done) _sidecarSave.value = LibrarySidecarState.Idle
    }

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
        writeScope.launch {
            writeLock.withLock {
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
        // On the write scope too: a paste or save that outlives this entry joins this
        // listing to pick its frames, and a listing cancelled with the entry would hand it
        // the stale one (#503 review). A finished listing on a cleared entry is harmless.
        loadJob = writeScope.launch {
            val result = try {
                withContext(io) {
                    val listed = lister.list(tree)
                    val frames = libraryEntriesOf(listed.rows)
                    // Edits saved beside photos (another install, another phone) fill frames
                    // that have none here; before the badges are read, so they show at once.
                    runCatching { sidecars.importMissing(frames, listed.rows) }
                        .onFailure { Diag.w("sidecar import failed: ${it.message}") }
                    listed.copy(rows = markEdited(frames, hasEdit)) to listed.rows
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Diag.w("library list failed: ${failure.message}")
                null
            }
            if (store.treeUri != tree) return@launch
            val listing = result?.first
            if (result != null) {
                lastRows = result.second
                lastRowsTree = tree
            }
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
        val retry = saveAfterGrant
        saveAfterGrant = null
        if (retry != null && treeUri == previous) {
            // The user chose the folder again to let Spektrafilm write to it: finish the save
            // they asked for, once the listing it needs has landed.
            writeScope.launch {
                loadJob?.join()
                saveEditsBesidePhotos(retry)
            }
        }
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
        writeScope.launch(io) {
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

                // Keep write too when the picker granted it, so "Save edits beside photos" can
                // write sidecars without asking again; reading never depends on it.
                override fun takeGrant(treeUri: String): Boolean = runCatching {
                    resolver.takePersistableUriPermission(
                        Uri.parse(treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    true
                }.recoverCatching {
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

        fun androidSidecars(context: Context, recipes: RecipeRepository): LibrarySidecarPort {
            val resolver = context.applicationContext.contentResolver
            val keyOf = { uri: String -> Recipes.keyFor(Uri.parse(uri)) }
            return object : LibrarySidecarPort {
                override fun importMissing(frames: List<LibraryEntry>, rows: List<LibraryEntry>): Int =
                    LibrarySidecars.importMissing(
                        recipes,
                        frames,
                        LibrarySidecars.pair(frames, rows),
                        keyOf,
                    ) { LibrarySidecars.readDocument(resolver, it) }

                override suspend fun save(treeUri: String, frames: List<LibraryEntry>, rows: List<LibraryEntry>) =
                    RecipeWorkRuntime.submit {
                        LibrarySidecars.saveAll(
                            recipes,
                            LibrarySidecars.androidFolder(resolver, treeUri),
                            frames,
                            LibrarySidecars.pair(frames, rows),
                            keyOf,
                        )
                    }.await()
            }
        }

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
}
