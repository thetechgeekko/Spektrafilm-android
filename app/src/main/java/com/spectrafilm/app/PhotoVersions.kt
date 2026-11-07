/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Named versions of one photo: several complete looks of the SAME source ("Portra, warm",
 * "Ektar, cropped square") saved side by side, any of which can be put back on the photo.
 *
 * A version is a full params snapshot in the preset schema ([Presets.toJsonString] /
 * [Presets.decode]), so a version, a preset and a recipe all decode the same way and there is
 * no parallel params model to drift. Unlike a preset it keeps the crop and masks: it belongs to
 * this photo. Versions are keyed by [Recipes.keyFor] of the source URI, exactly like the
 * auto-saved recipe, and the demo image (no key) has none.
 *
 * Storage follows docs/adr/0002: one app-private JSON document per photo, written through
 * [AtomicJsonStore] (AtomicFile), read-modify-write under the document's path lock. A document
 * that cannot be parsed is quarantined, never silently overwritten.
 */
package com.spectrafilm.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** One saved look of a photo. [paramsJson] is a [Presets.toJsonString] payload. */
internal data class PhotoVersion(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val paramsJson: String,
    /** The photo's manual rotation when saved (0/90/180/270): crop and masks are relative to it. */
    val rotationDegrees: Int = 0,
)

/** The on-disk document: `{"schema":..., "version":1, "sourceKey":..., "versions":[...]}`. */
internal object PhotoVersionCodec {
    const val SCHEMA = "spektrafilm.photo-versions"
    const val VERSION = 1
    const val MAX_NAME_CHARS = 64
    const val MAX_VERSIONS = 50

    fun encode(sourceKey: String, versions: List<PhotoVersion>): String {
        val list = JSONArray()
        for (v in versions) {
            list.put(
                JSONObject()
                    .put("id", v.id)
                    .put("name", v.name)
                    .put("createdAt", v.createdAtMillis)
                    .put("updatedAt", v.updatedAtMillis)
                    .put("params", JSONObject(v.paramsJson))
                    .put("rotation", v.rotationDegrees),
            )
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("sourceKey", sourceKey)
            .put("versions", list)
            .toString()
    }

    /** Throws on anything that is not a well-formed document for [expectedKey]. */
    fun decode(text: String, expectedKey: String): List<PhotoVersion> {
        val root = JSONObject(text)
        require(root.optString("schema") == SCHEMA) { "not a photo-versions document" }
        val version = root.optInt("version", -1)
        require(version == VERSION) { "unsupported photo-versions version $version" }
        require(root.optString("sourceKey") == expectedKey) { "photo-versions key mismatch" }
        val list = root.getJSONArray("versions")
        return (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            PhotoVersion(
                id = o.getString("id").also { require(it.isNotBlank()) { "blank version id" } },
                name = cleanName(o.getString("name")),
                createdAtMillis = o.getLong("createdAt"),
                updatedAtMillis = o.optLong("updatedAt", o.getLong("createdAt")),
                paramsJson = o.getJSONObject("params").toString(),
                rotationDegrees = SourceRotation.fromDegrees(o.optInt("rotation", 0)).degrees,
            )
        }
    }

    /** Trim, collapse to one line and bound a user-typed name. */
    fun cleanName(raw: String): String =
        AtomicJsonStore.truncateUtf16Safely(
            raw.replace(Regex("\\s+"), " ").trim(),
            MAX_NAME_CHARS,
        )

    /** "Version N" with the smallest N not already taken (case-insensitive). */
    fun defaultName(existing: List<PhotoVersion>, prefix: String = "Version"): String {
        val taken = existing.map { it.name.lowercase() }.toSet()
        var n = existing.size + 1
        while ("$prefix $n".lowercase() in taken) n++
        return "$prefix $n"
    }
}

internal sealed interface VersionListResult {
    data class Loaded(val versions: List<PhotoVersion>) : VersionListResult

    /** The document was unreadable and moved aside; the photo now has no versions. */
    data class CorruptQuarantined(val reason: String) : VersionListResult
    data class Failed(val reason: String) : VersionListResult
}

/** Per-photo version storage. Every call does blocking file IO: call it off the main thread. */
internal interface VersionRepository {
    fun list(key: String): VersionListResult

    /** Saves [paramsJson] as a new version (newest first). Fails when the photo is full. */
    fun add(key: String, name: String, paramsJson: String, rotationDegrees: Int = 0): PhotoVersion

    fun rename(key: String, id: String, name: String)

    /** Overwrites a version's look with [paramsJson] and [rotationDegrees], keeping its name. */
    fun replaceParams(key: String, id: String, paramsJson: String, rotationDegrees: Int = 0)

    /** Removes a version and returns it (for Undo), or null when it was already gone. */
    fun delete(key: String, id: String): PhotoVersion?

    /** Puts a deleted version back where its timestamps place it. */
    fun restore(key: String, version: PhotoVersion)
}

internal class VersionLimitException(message: String) : IOException(message)

/** [VersionRepository] over `<dir>/<key>.json`. */
internal class FileVersionRepository(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : VersionRepository {

    private fun file(key: String): File {
        RecipeDocumentCodec.validateKey(key)
        return File(dir, "$key.json")
    }

    override fun list(key: String): VersionListResult {
        val target = file(key)
        return AtomicJsonStore.withPathLock(target) {
            try {
                VersionListResult.Loaded(readLocked(key, target))
            } catch (failure: IOException) {
                VersionListResult.Failed(failure.message ?: "versions read failed")
            } catch (failure: Exception) {
                val reason = failure.message ?: failure.javaClass.simpleName
                runCatching { AtomicJsonStore.quarantine(target) }
                    .fold(
                        onSuccess = { VersionListResult.CorruptQuarantined(reason) },
                        onFailure = { VersionListResult.Failed(reason) },
                    )
            }
        }
    }

    override fun add(key: String, name: String, paramsJson: String, rotationDegrees: Int): PhotoVersion {
        // Parse now so a malformed payload fails here, not on the next read of the document.
        JSONObject(paramsJson)
        return mutate(key) { current ->
            if (current.size >= PhotoVersionCodec.MAX_VERSIONS) {
                throw VersionLimitException("a photo keeps at most ${PhotoVersionCodec.MAX_VERSIONS} versions")
            }
            val now = clock()
            val cleaned = PhotoVersionCodec.cleanName(name)
            val created = PhotoVersion(
                id = UUID.randomUUID().toString(),
                name = cleaned.ifEmpty { PhotoVersionCodec.defaultName(current) },
                createdAtMillis = now,
                updatedAtMillis = now,
                paramsJson = paramsJson,
                rotationDegrees = SourceRotation.fromDegrees(rotationDegrees).degrees,
            )
            (listOf(created) + current) to created
        }
    }

    override fun rename(key: String, id: String, name: String) {
        val cleaned = PhotoVersionCodec.cleanName(name)
        if (cleaned.isEmpty()) return
        mutate(key) { current ->
            current.map { if (it.id == id) it.copy(name = cleaned, updatedAtMillis = clock()) else it } to Unit
        }
    }

    override fun replaceParams(key: String, id: String, paramsJson: String, rotationDegrees: Int) {
        JSONObject(paramsJson)
        val rotation = SourceRotation.fromDegrees(rotationDegrees).degrees
        mutate(key) { current ->
            current.map {
                if (it.id == id) {
                    it.copy(paramsJson = paramsJson, rotationDegrees = rotation, updatedAtMillis = clock())
                } else {
                    it
                }
            } to Unit
        }
    }

    override fun delete(key: String, id: String): PhotoVersion? = mutate(key) { current ->
        val gone = current.firstOrNull { it.id == id }
        current.filterNot { it.id == id } to gone
    }

    override fun restore(key: String, version: PhotoVersion) {
        mutate(key) { current ->
            if (current.any { it.id == version.id }) return@mutate current to Unit
            (current + version).sortedByDescending { it.createdAtMillis } to Unit
        }
    }

    /** Read-modify-write under the document's path lock. An empty list deletes the file. */
    private fun <T> mutate(key: String, change: (List<PhotoVersion>) -> Pair<List<PhotoVersion>, T>): T {
        val target = file(key)
        return AtomicJsonStore.withPathLock(target) {
            // A corrupt document throws here and is NOT overwritten: list() quarantines it.
            val (next, result) = change(readLocked(key, target))
            if (next.isEmpty()) {
                AtomicJsonStore.delete(target)
            } else {
                AtomicJsonStore.writeUtf8(target, PhotoVersionCodec.encode(key, next), MAX_BYTES)
            }
            result
        }
    }

    private fun readLocked(key: String, target: File): List<PhotoVersion> {
        if (!target.isFile) return emptyList()
        return PhotoVersionCodec.decode(AtomicJsonStore.readUtf8(target, MAX_BYTES), key)
    }

    companion object {
        /** Fifty full looks with masks fit comfortably; a runaway document does not. */
        const val MAX_BYTES: Int = 16 * 1024 * 1024
    }
}

internal data class VersionsUiState(
    /** [Recipes.keyFor] of the open photo; null for the demo image (versions unavailable). */
    val key: String? = null,
    val versions: List<PhotoVersion> = emptyList(),
    val loading: Boolean = false,
) {
    val available: Boolean get() = key != null
}

/** One-shot outcomes the editor turns into a status line or an Undo snackbar. */
internal sealed interface VersionEvent {
    data class Saved(val version: PhotoVersion) : VersionEvent
    data class Updated(val name: String) : VersionEvent
    /** [key] is the photo it was deleted from; Undo restores it THERE, whatever is open now. */
    data class Deleted(val key: String, val version: PhotoVersion) : VersionEvent
    data class Failed(val reason: String) : VersionEvent

    /** The photo's versions file was unreadable and moved aside. */
    data class Quarantined(val reason: String) : VersionEvent
}

/**
 * The open photo's versions. The editor [bind]s it to the source key; every repository call
 * runs on [io]. Applying a version is the editor's job (it owns the live [ParamsState]): it
 * decodes [PhotoVersion.paramsJson] exactly as Paste Settings does, so Undo covers it.
 */
internal class VersionsViewModel(
    private val repository: VersionRepository,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VersionsUiState())
    val uiState: StateFlow<VersionsUiState> = _uiState.asStateFlow()

    /** Called with each finished operation's outcome; set by the editor. */
    var onEvent: (VersionEvent) -> Unit = {}

    fun bind(key: String?) {
        if (key == _uiState.value.key && !_uiState.value.loading && key != null) {
            refresh()
            return
        }
        _uiState.value = VersionsUiState(key = key, loading = key != null)
        if (key != null) refresh()
    }

    // Reads and their publication run one at a time, in call order, so an older read of the
    // file can never land after a newer one.
    private val refreshes = Mutex()

    private fun refresh() {
        val key = _uiState.value.key ?: return
        viewModelScope.launch { refreshes.withLock { refreshLocked(key) } }
    }

    private suspend fun refreshLocked(key: String) {
        val result = withContext(io) { repository.list(key) }
        if (_uiState.value.key != key) return
        when (result) {
            is VersionListResult.Loaded ->
                _uiState.update { it.copy(versions = result.versions, loading = false) }
            is VersionListResult.CorruptQuarantined -> {
                _uiState.update { it.copy(versions = emptyList(), loading = false) }
                onEvent(VersionEvent.Quarantined(result.reason))
            }
            is VersionListResult.Failed -> {
                _uiState.update { it.copy(loading = false) }
                onEvent(VersionEvent.Failed(result.reason))
            }
        }
    }

    private fun run(block: (String) -> VersionEvent?) {
        val key = _uiState.value.key ?: return
        viewModelScope.launch {
            val event = withContext(io) {
                runCatching { block(key) }.getOrElse { VersionEvent.Failed(it.message ?: it.javaClass.simpleName) }
            }
            if (_uiState.value.key != key) return@launch
            refresh()
            event?.let(onEvent)
        }
    }

    fun save(name: String, paramsJson: String, rotationDegrees: Int) = run { key ->
        VersionEvent.Saved(repository.add(key, name, paramsJson, rotationDegrees))
    }

    fun rename(id: String, name: String) = run { key -> repository.rename(key, id, name); null }

    fun replaceWithCurrent(version: PhotoVersion, paramsJson: String, rotationDegrees: Int) = run { key ->
        repository.replaceParams(key, version.id, paramsJson, rotationDegrees)
        VersionEvent.Updated(version.name)
    }

    fun delete(id: String) = run { key -> repository.delete(key, id)?.let { VersionEvent.Deleted(key, it) } }

    /**
     * Undo a delete. Takes the photo's [key] from the [VersionEvent.Deleted] rather than the
     * currently bound photo: the snackbar can outlive a switch to another photo, and restoring
     * into that one would move a crop and masks onto the wrong frame.
     */
    fun restore(key: String, version: PhotoVersion) {
        viewModelScope.launch {
            val failure = withContext(io) {
                runCatching { repository.restore(key, version) }.exceptionOrNull()
            }
            if (_uiState.value.key != key) return@launch
            refresh()
            failure?.let { onEvent(VersionEvent.Failed(it.message ?: it.javaClass.simpleName)) }
        }
    }
}
