/*
 * Spektrafilm for Android -- Library albums: named sets of frames. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * An album only lists photos; the files stay where they are. Each item remembers the folder
 * grant it is read through, so a frame added from one Library folder still opens after the
 * Library moves to another, and the grant sweep (SourceAccessCoordinator) keeps that folder's
 * grant while any album needs it. The document is one small JSON file written atomically.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One frame in an album: enough to draw and open it without listing its folder again. */
internal data class AlbumItem(
    val uri: String,
    val name: String,
    val mimeType: String?,
    val modifiedMs: Long,
    val sizeBytes: Long,
    /** The folder grant the item is read through, or null when it holds its own grant. */
    val treeUri: String?,
) {
    fun toEntry(edited: Boolean = false) = LibraryEntry(uri, name, mimeType, modifiedMs, sizeBytes, edited)

    companion object {
        fun of(entry: LibraryEntry, treeUri: String?) =
            AlbumItem(entry.documentUri, entry.name, entry.mimeType, entry.modifiedMs, entry.sizeBytes, treeUri)
    }
}

internal data class Album(
    val id: String,
    val name: String,
    val createdMs: Long,
    val items: List<AlbumItem> = emptyList(),
    /** The item shown on the album's card; null or a removed item means the newest one. */
    val coverUri: String? = null,
) {
    /** The cover item: the chosen one while it is still in the album, else the newest. */
    val cover: AlbumItem?
        get() = items.firstOrNull { it.uri == coverUri } ?: items.maxByOrNull { it.modifiedMs }
}

/** Pure album edits, so every rule is tested on the JVM. */
internal object AlbumOps {
    const val MAX_NAME_CHARS = 60
    const val MAX_ITEMS = 4_000

    /** Across every album, so the document stays inside [AlbumCodec.MAX_BYTES]. */
    const val MAX_TOTAL_ITEMS = 10_000
    const val MAX_ALBUMS = 500

    /** Trimmed, single-line and bounded; blank stays blank so the caller can refuse it. */
    fun cleanName(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(MAX_NAME_CHARS).trim()

    /** Room left under [MAX_TOTAL_ITEMS]. */
    fun spareItems(albums: List<Album>): Int = (MAX_TOTAL_ITEMS - albums.sumOf { it.items.size }).coerceAtLeast(0)

    fun create(albums: List<Album>, id: String, name: String, nowMs: Long, items: List<AlbumItem> = emptyList()): List<Album> {
        if (albums.size >= MAX_ALBUMS) return albums
        return albums + Album(id, cleanName(name), nowMs, dedupe(items).take(minOf(MAX_ITEMS, spareItems(albums))))
    }

    fun rename(albums: List<Album>, id: String, name: String): List<Album> {
        val clean = cleanName(name)
        if (clean.isEmpty()) return albums
        return albums.map { if (it.id == id) it.copy(name = clean) else it }
    }

    fun delete(albums: List<Album>, id: String) = albums.filterNot { it.id == id }

    /** Puts a deleted album back where it was (Undo). */
    fun restore(albums: List<Album>, album: Album, index: Int): List<Album> {
        if (albums.any { it.id == album.id }) return albums
        return albums.toMutableList().apply { add(index.coerceIn(0, size), album) }
    }

    /** Adds items not already in the album, keeping the album's order and its size bound. */
    fun add(albums: List<Album>, id: String, items: List<AlbumItem>): List<Album> = albums.map { album ->
        if (album.id != id) return@map album
        val present = album.items.mapTo(HashSet()) { it.uri }
        val room = minOf(MAX_ITEMS - album.items.size, spareItems(albums)).coerceAtLeast(0)
        album.copy(items = album.items + dedupe(items).filter { it.uri !in present }.take(room))
    }

    fun remove(albums: List<Album>, id: String, uris: Set<String>): List<Album> = albums.map { album ->
        if (album.id != id) return@map album
        album.copy(
            items = album.items.filterNot { it.uri in uris },
            coverUri = album.coverUri?.takeUnless { it in uris },
        )
    }

    fun setCover(albums: List<Album>, id: String, uri: String): List<Album> = albums.map { album ->
        if (album.id == id && album.items.any { it.uri == uri }) album.copy(coverUri = uri) else album
    }

    /** Every grant an album needs kept: the folders its items are read through, and the rest. */
    fun retainedUris(albums: List<Album>): Set<String> =
        albums.flatMapTo(HashSet()) { album -> album.items.map { it.treeUri ?: it.uri } }

    /** "Album 3" for the first free number, so a quick New album needs no typing. */
    fun defaultName(albums: List<Album>, word: String): String {
        val taken = albums.mapTo(HashSet()) { it.name }
        var n = albums.size + 1
        while ("$word $n" in taken) n++
        return "$word $n"
    }

    private fun dedupe(items: List<AlbumItem>) = items.distinctBy { it.uri }
}

/** The albums document: version 1. Unknown fields are ignored; a bad item is skipped. */
internal object AlbumCodec {
    const val MAX_BYTES = 8 * 1024 * 1024

    /** Room for [AlbumOps.MAX_TOTAL_ITEMS] items of eight nodes each, with the defaults' other bounds. */
    private val LIMITS = JsonStructureLimits(maxNodes = 120_000, maxInputChars = MAX_BYTES)

    fun encode(albums: List<Album>): String = JSONObject()
        .put("version", 1)
        .put(
            "albums",
            JSONArray().apply {
                albums.forEach { album ->
                    put(
                        JSONObject()
                            .put("id", album.id)
                            .put("name", album.name)
                            .put("created", album.createdMs)
                            .putOpt("cover", album.coverUri)
                            .put(
                                "items",
                                JSONArray().apply {
                                    album.items.forEach { item ->
                                        put(
                                            JSONObject()
                                                .put("uri", item.uri)
                                                .put("name", item.name)
                                                .putOpt("mime", item.mimeType)
                                                .put("modified", item.modifiedMs)
                                                .put("size", item.sizeBytes)
                                                .putOpt("tree", item.treeUri),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
        .toString()

    fun decode(text: String): List<Album> {
        val root = AtomicJsonStore.parseObject(text, LIMITS)
        val version = root.optInt("version", 0)
        if (version != 1) throw IllegalArgumentException("unknown albums version $version")
        val list = root.optJSONArray("albums") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val items = o.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    val it = arr.optJSONObject(j) ?: return@mapNotNull null
                    val uri = it.optString("uri").takeIf { u -> u.startsWith("content://") } ?: return@mapNotNull null
                    AlbumItem(
                        uri = uri,
                        name = it.optString("name", uri.substringAfterLast('/')),
                        mimeType = it.optString("mime").takeIf { m -> m.isNotEmpty() },
                        modifiedMs = it.optLong("modified", 0L),
                        sizeBytes = it.optLong("size", -1L),
                        treeUri = it.optString("tree").takeIf { t -> t.startsWith("content://") },
                    )
                }
            }.orEmpty()
            Album(
                id = id,
                name = AlbumOps.cleanName(o.optString("name")).ifEmpty { id },
                createdMs = o.optLong("created", 0L),
                items = items.distinctBy { it.uri },
                coverUri = o.optString("cover").takeIf { it.isNotEmpty() },
            )
        }.distinctBy { it.id }
    }
}

/** Where albums live. Blocking; called on the IO dispatcher. */
internal interface AlbumStore {
    fun load(): List<Album>
    fun save(albums: List<Album>)
}

/** filesDir/albums.json; a damaged file is moved aside (never overwritten) and reads as empty. */
internal class FileAlbumStore(private val file: File) : AlbumStore {
    override fun load(): List<Album> = AtomicJsonStore.withPathLock(file) {
        if (!file.isFile) return@withPathLock emptyList()
        val text = AtomicJsonStore.readUtf8(file, AlbumCodec.MAX_BYTES)
        try {
            AlbumCodec.decode(text)
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            AtomicJsonStore.quarantine(file)
            emptyList()
        }
    }

    override fun save(albums: List<Album>) = AtomicJsonStore.withPathLock(file) {
        if (albums.isEmpty()) {
            AtomicJsonStore.delete(file)
            Unit
        } else {
            AtomicJsonStore.writeUtf8(file, AlbumCodec.encode(albums), AlbumCodec.MAX_BYTES)
        }
    }

    companion object {
        fun of(context: Context) = FileAlbumStore(File(context.applicationContext.filesDir, "albums.json"))

        /** For the grant sweep: what albums need kept. Unreadable means keep everything. */
        fun retainedUris(context: Context): Set<String> = AlbumOps.retainedUris(of(context).load())
    }
}

/**
 * The albums, for the Library's Albums tab and album view. Every store call runs on [io], one
 * at a time under [lock], so two quick edits land in order.
 */
internal class AlbumsViewModel(
    private val store: AlbumStore,
    private val io: CoroutineDispatcher,
    private val hasEdit: (documentUri: String) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    aspectReader: LibraryAspectReader = LibraryAspectReader { null },
    /**
     * Where edits run. This ViewModel is scoped to the Library's navigation entry and cleared
     * when the user leaves; an edit shown on screen must still be saved, so the app passes a
     * process-wide scope, as for the Library's own writes. Null (tests) keeps [viewModelScope].
     */
    writes: CoroutineScope? = null,
    /**
     * Held by every load and save, across every Library visit: a visit that starts while the
     * last one's edits are still saving reads the file only after they land.
     */
    private val lock: Mutex = Mutex(),
) : ViewModel() {
    private val writeScope: CoroutineScope = writes ?: viewModelScope
    private val aspectTracker = LibraryAspects(viewModelScope, io, aspectReader)

    /** Frame shapes of the open album, for justified rows. */
    val aspects: StateFlow<Map<String, Float>> = aspectTracker.aspects

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())

    /** Frames selected inside the open album. */
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** False after a failed read: saving then would overwrite albums that are only unreadable for now. */
    private var loaded = false

    init {
        viewModelScope.launch {
            lock.withLock {
                withContext(io) { runCatching { store.load() } }
                    .onSuccess { _albums.value = it; loaded = true }
                    .onFailure { Diag.w("albums load failed: ${it.message}") }
            }
        }
    }

    // Grants of frames an album drops are not released here: Undo must still be able to
    // read them, and the next grant reconciliation releases whatever no album retains.
    private fun edit(change: (List<Album>) -> List<Album>) =
        writeScope.launch {
            lock.withLock {
                if (!loaded) return@withLock
                val next = change(_albums.value)
                if (next == _albums.value) return@withLock
                _albums.value = next
                withContext(io) {
                    runCatching { store.save(next) }.onFailure { Diag.w("albums save failed: ${it.message}") }
                }
            }
        }

    /**
     * A new album holding [items] (maybe none). [onDone] gets its id and how many items it took
     * (the album and total bounds can cut a big selection short), or a null id when no album
     * could be made (too many albums, or the albums file is unreadable this session).
     */
    fun create(name: String, items: List<AlbumItem> = emptyList(), onDone: (id: String?, added: Int) -> Unit = { _, _ -> }) {
        val id = newId()
        val clean = AlbumOps.cleanName(name)
        var made: Album? = null
        edit { albums ->
            AlbumOps.create(albums, id, clean.ifEmpty { id }, now(), items).also { next ->
                made = next.firstOrNull { it.id == id }
            }
        }.invokeOnCompletion { if (it == null) onDone(made?.id, made?.items?.size ?: 0) }
    }

    fun rename(id: String, name: String) = edit { AlbumOps.rename(it, id, name) }

    /** Deletes the album and returns what Undo needs, or null when there is no such album. */
    fun delete(id: String): Pair<Album, Int>? {
        val index = _albums.value.indexOfFirst { it.id == id }
        if (index < 0) return null
        val album = _albums.value[index]
        edit { AlbumOps.delete(it, id) }
        return album to index
    }

    fun undoDelete(album: Album, index: Int) = edit { AlbumOps.restore(it, album, index) }

    /** Adds [items]; [onDone] gets how many were new and fitted (0 when the album is full). */
    fun add(id: String, items: List<AlbumItem>, onDone: (added: Int) -> Unit = {}) {
        var added = 0
        edit { albums ->
            val before = albums.firstOrNull { it.id == id }?.items?.size ?: 0
            AlbumOps.add(albums, id, items).also { next ->
                added = (next.firstOrNull { it.id == id }?.items?.size ?: 0) - before
            }
        }.invokeOnCompletion { if (it == null) onDone(added) }
    }

    fun removeSelected(id: String): Int {
        val picked = _selection.value
        if (picked.isEmpty()) return 0
        _selection.value = emptySet()
        edit { AlbumOps.remove(it, id, picked) }
        return picked.size
    }

    fun setCover(id: String, uri: String) {
        _selection.value = emptySet()
        edit { AlbumOps.setCover(it, id, uri) }
    }

    fun toggleSelected(uri: String) {
        _selection.value = _selection.value.let { if (uri in it) it - uri else it + uri }
    }

    fun selectAll(uris: Collection<String>) { _selection.value = LinkedHashSet(uris) }

    fun selectRange(shown: List<LibraryEntry>, anchor: String, to: String, base: Set<String>) {
        _selection.value = selectionRange(shown, anchor, to, base)
    }

    fun toggleDay(uris: Collection<String>) {
        val current = _selection.value
        _selection.value = if (uris.all { it in current }) current - uris.toSet() else current + uris
    }

    fun clearSelection() { _selection.value = emptySet() }

    /** The album's frames as Library entries, with Edited marks read on [io]; measures their shapes. */
    suspend fun entriesOf(album: Album): List<LibraryEntry> = withContext(io) {
        album.items.map { item -> item.toEntry(edited = runCatching { hasEdit(item.uri) }.getOrDefault(false)) }
    }.also(aspectTracker::measure)
}
