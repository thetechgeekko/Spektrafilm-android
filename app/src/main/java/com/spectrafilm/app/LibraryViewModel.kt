/*
 * Spektrafilm for Android -- the Library's ViewModel (split from Library.kt for #273). GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
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
    private val queryStore: LibraryQueryStore = LibraryQueryStore.None,
    private val aspectReader: LibraryAspectReader = LibraryAspectReader { null },
) : ViewModel() {
    private val aspectTracker = LibraryAspects(viewModelScope, io, aspectReader)

    /**
     * Known frame shapes by document URI, for justified rows. Filled in the background after
     * each listing, in batches so the grid reflows a few times rather than once per frame.
     */
    val aspects: StateFlow<Map<String, Float>> = aspectTracker.aspects

    private fun measureAspects(entries: List<LibraryEntry>) = aspectTracker.measure(entries)

    private val _query = MutableStateFlow(runCatching { queryStore.load() }.getOrDefault(LibraryQuery()))

    /** Sort, filter and search over the folder ([applyLibraryQuery]). */
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()

    /** Changes the sort, filter or search; sort and filter are remembered. */
    fun setQuery(query: LibraryQuery) {
        val before = _query.value
        if (query == before) return
        _query.value = query
        if (query.encode() != before.encode()) {
            runCatching { queryStore.save(query) }.onFailure { Diag.w("library query save failed: ${it.message}") }
        }
        // A frame the new filter hides cannot stay selected: actions would reach it unseen.
        if (query.narrows) {
            val shown = visibleEntries().mapTo(HashSet()) { it.documentUri }
            _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in shown }
        }
    }

    /** The frames on screen, in grid order: the listing after [query]. */
    fun visibleEntries(): List<LibraryEntry> = applyLibraryQuery(_state.value.entries, _query.value)

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

    /** Selects every frame on screen; frames hidden by a filter or search stay unselected. */
    fun selectAll() {
        _selection.value = visibleEntries().mapTo(LinkedHashSet()) { it.documentUri }
    }

    /**
     * Drag-select: the frames from [anchorUri] to [toUri] in grid order, on top of [base] (the
     * selection when the drag began). Dragging back past a frame unselects it again, as in
     * Photos, because the range is recomputed from the anchor every time.
     */
    fun selectRange(anchorUri: String, toUri: String, base: Set<String>) {
        _selection.value = selectionRange(visibleEntries(), anchorUri, toUri, base)
    }

    /** A day header's Select: selects the day, or clears it when it is all selected already. */
    fun toggleDay(uris: Collection<String>) {
        val current = _selection.value
        _selection.value = if (uris.all { it in current }) current - uris.toSet() else current + uris
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
                // A frame that left the folder, or that the active filter or search now hides
                // (a sidecar import can make an unedited frame edited), cannot stay selected:
                // a paste would reach it unseen.
                val present = applyLibraryQuery(listing.rows, _query.value).mapTo(HashSet()) { it.documentUri }
                _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in present }
            }
            if (listing != null) measureAspects(listing.rows)
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

        /**
         * The shape from the file's EXIF header, read through a seekable descriptor so a RAW
         * is never streamed whole; for a JPEG or HEIC without EXIF size, the decoder's bounds.
         * CR3 and a few other RAWs carry neither and stay at the default 3:2.
         */
        fun androidAspectReader(context: Context): LibraryAspectReader {
            val resolver = context.applicationContext.contentResolver
            return LibraryAspectReader { entry ->
                val uri = Uri.parse(entry.documentUri)
                val fromExif = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (w > 0 && h > 0) exif.rotationDegrees.let { if (it == 90 || it == 270) h.toFloat() / w else w.toFloat() / h } else null
                }
                fromExif ?: if (entry.isRaw) null else resolver.openInputStream(uri)?.use { input ->
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight else null
                }
            }
        }

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

        fun androidQueryStore(settings: AppSettings): LibraryQueryStore = object : LibraryQueryStore {
            override fun load() = LibraryQuery.decode(settings.libraryQuery)
            override fun save(query: LibraryQuery) { settings.libraryQuery = query.encode() }
        }

        fun androidLister(context: Context): LibraryLister {
            val resolver = context.applicationContext.contentResolver
            return LibraryLister { tree -> listTree(resolver, Uri.parse(tree)) }
        }
    }
}

