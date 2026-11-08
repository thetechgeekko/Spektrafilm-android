/*
 * Spektrafilm for Android -- the Library's Gallery tab: the phone's own photos. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Read from MediaStore with READ_MEDIA_IMAGES (READ_EXTERNAL_STORAGE up to Android 12). On
 * Android 14 the user may allow only a selection of photos (READ_MEDIA_VISUAL_USER_SELECTED);
 * MediaStore then returns just those, and the tab says so and offers to change the selection.
 */
package com.spectrafilm.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How much of the phone's photos the app may read. */
internal enum class GalleryAccess { FULL, PARTIAL, NONE }

/** One listing: the frames, and the shapes MediaStore already knows (no file is opened). */
internal data class GalleryListing(val entries: List<LibraryEntry>, val aspects: Map<String, Float>)

internal fun interface GalleryLister {
    /** Blocking; called on the IO dispatcher. */
    fun list(): GalleryListing
}

internal data class GalleryUiState(
    val access: GalleryAccess = GalleryAccess.NONE,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val entries: List<LibraryEntry> = emptyList(),
)

internal object PhoneGallery {
    // Both columns exist since API 1 under ImageColumns and since 29 under MediaColumns too;
    // named once here so neither deprecation nor an ambiguous inherited constant gets in.
    private const val COLUMN_DATE_TAKEN = "datetaken"
    private const val COLUMN_ORIENTATION = "orientation"

    /** More than any phone roll a grid should hold in memory at once. */
    const val MAX_FRAMES = 20_000

    /** The permissions to ask for: Android 14 adds the partial "selected photos" one. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): GalleryAccess {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) ->
                GalleryAccess.FULL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryAccess.PARTIAL
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) ->
                GalleryAccess.FULL
            else -> GalleryAccess.NONE
        }
    }

    /**
     * A MediaStore photo the app can read again after a restart: an external-volume item while
     * the app holds a photos permission. Not a photo picker URI (content://media/picker/...),
     * whose grant is per pick. Partial access counts too: the editor then keeps the photo's own
     * URI (and so one recipe for it whatever the access), and if the user later drops it from
     * the selection, restoring asks for the photo again like any revoked source.
     */
    fun isReadable(context: Context, uri: String): Boolean =
        uri.startsWith("content://media/external") && access(context) != GalleryAccess.NONE

    /** Width over height as shown, from MediaStore's size and rotation; null when unknown. */
    internal fun aspectOf(width: Int, height: Int, orientation: Int): Float? {
        if (width <= 0 || height <= 0) return null
        val turned = orientation % 180 != 0
        return if (turned) height.toFloat() / width else width.toFloat() / height
    }

    fun androidLister(context: Context): GalleryLister {
        val app = context.applicationContext
        return GalleryLister {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                COLUMN_DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                COLUMN_ORIENTATION,
            )
            val rows = ArrayList<LibraryEntry>()
            val aspects = HashMap<String, Float>()
            app.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val taken = c.getColumnIndexOrThrow(COLUMN_DATE_TAKEN)
                val modified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val orientation = c.getColumnIndexOrThrow(COLUMN_ORIENTATION)
                while (c.moveToNext() && rows.size < MAX_FRAMES) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString()
                    // Date taken (ms) when the camera wrote one, else the file's date (s).
                    val ms = if (!c.isNull(taken) && c.getLong(taken) > 0L) c.getLong(taken) else c.getLong(modified) * 1000L
                    rows += LibraryEntry(
                        documentUri = uri,
                        name = c.getString(name) ?: uri.substringAfterLast('/'),
                        mimeType = c.getString(mime),
                        modifiedMs = ms,
                        sizeBytes = if (c.isNull(size)) -1L else c.getLong(size),
                    )
                    aspectOf(c.getInt(width), c.getInt(height), c.getInt(orientation))?.let { aspects[uri] = it }
                }
            }
            GalleryListing(libraryEntriesOf(rows), aspects)
        }
    }
}

/**
 * The Gallery tab's state: what access the app has, and the phone's photos when it has any.
 * Selection works as in the folder grid. [access] is read again on every [refresh], since the
 * user can change it in system settings at any time.
 */
internal class GalleryViewModel(
    private val lister: GalleryLister,
    private val access: () -> GalleryAccess,
    private val io: CoroutineDispatcher,
    private val hasEdit: (documentUri: String) -> Boolean = { false },
) : ViewModel() {
    private val _state = MutableStateFlow(GalleryUiState(access = runCatching(access).getOrDefault(GalleryAccess.NONE)))
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private val _aspects = MutableStateFlow<Map<String, Float>>(emptyMap())
    val aspects: StateFlow<Map<String, Float>> = _aspects.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private var loadJob: Job? = null

    fun refresh() {
        val now = runCatching(access).getOrDefault(GalleryAccess.NONE)
        if (now == GalleryAccess.NONE) {
            loadJob?.cancel()
            _selection.value = emptySet()
            _aspects.value = emptyMap()
            _state.value = GalleryUiState(access = now)
            return
        }
        _state.value = _state.value.copy(access = now, loading = true, failed = false)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val listing = withContext(io) {
                runCatching {
                    val raw = lister.list()
                    raw.copy(
                        entries = raw.entries.map { entry ->
                            val edited = runCatching { hasEdit(entry.documentUri) }.getOrDefault(false)
                            if (edited) entry.copy(edited = true) else entry
                        },
                    )
                }
            }.onFailure { Diag.w("gallery listing failed: ${it.message}") }.getOrNull()
            if (listing == null) {
                _state.value = GalleryUiState(access = now, failed = true)
                return@launch
            }
            _aspects.value = listing.aspects
            val present = listing.entries.mapTo(HashSet()) { it.documentUri }
            _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in present }
            _state.value = GalleryUiState(access = now, entries = listing.entries)
        }
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
}
