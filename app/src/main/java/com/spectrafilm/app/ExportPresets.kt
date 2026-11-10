/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Saved export setups ("Instagram 2048 JPEG", "Print TIFF"): a name plus every choice the export
 * sheet makes for the file itself (format, quality, size, sharpening, film border, file-name template, GPS).
 * Colour space and transfer stay with the edit (they live in ParamsState), so a preset never
 * changes how a photo looks, only how it is written.
 *
 * One app-private JSON document (docs/adr/0002: documents on AtomicFile) also remembers the
 * preset used last, so the Export button can open with it already chosen.
 */
package com.spectrafilm.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
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

internal data class ExportPreset(
    val name: String,
    val options: ExportOptions,
    val keepGps: Boolean,
) {
    /** Does the sheet's current state equal this preset? (That is how the row shows a selection.) */
    fun matches(current: ExportOptions, currentKeepGps: Boolean): Boolean =
        options == current && keepGps == currentKeepGps
}

internal data class ExportPresetDocument(
    val presets: List<ExportPreset> = emptyList(),
    val lastUsed: String? = null,
) {
    val lastUsedPreset: ExportPreset? get() = lastUsed?.let { n -> presets.firstOrNull { it.name == n } }
}

internal object ExportPresetCodec {
    const val SCHEMA = "spektrafilm.export-presets"
    const val VERSION = 1
    const val MAX_NAME_CHARS = 40
    const val MAX_PRESETS = 30
    const val MAX_BYTES = 256 * 1024

    fun encode(doc: ExportPresetDocument): String {
        val list = JSONArray()
        for (p in doc.presets) {
            list.put(
                JSONObject()
                    .put("name", p.name)
                    .put("format", p.options.format.name)
                    .put("jpegQuality", p.options.jpegQuality)
                    .put("size", p.options.size.name)
                    .put("customLongEdge", p.options.customLongEdge)
                    .put("fileName", p.options.customName)
                    .put("sharpen", p.options.sharpen.name)
                    .put("border", p.options.border.name)
                    .put("frame", p.options.frame.toJson())
                    .put("keepGps", p.keepGps),
            )
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("lastUsed", doc.lastUsed ?: JSONObject.NULL)
            .put("presets", list)
            .toString()
    }

    /** Throws on a malformed document. Presets naming an unknown enum value are dropped. */
    fun decode(text: String): ExportPresetDocument {
        val root = JSONObject(text)
        require(root.optString("schema") == SCHEMA) { "not an export-presets document" }
        val version = root.optInt("version", -1)
        require(version == VERSION) { "unsupported export-presets version $version" }
        val list = root.getJSONArray("presets")
        val presets = (0 until list.length()).mapNotNull { i ->
            val o = list.getJSONObject(i)
            runCatching {
                ExportPreset(
                    name = cleanName(o.getString("name")).also { require(it.isNotEmpty()) },
                    options = ExportOptions(
                        format = ExportFormat.valueOf(o.getString("format")),
                        jpegQuality = o.getInt("jpegQuality").coerceIn(1, 100),
                        size = ExportSize.valueOf(o.getString("size")),
                        customLongEdge = o.getInt("customLongEdge"),
                        customName = o.optString("fileName", ""),
                        sharpen = OutputSharpen.valueOf(o.optString("sharpen", OutputSharpen.NONE.name)),
                        border = FilmBorder.valueOf(o.optString("border", FilmBorder.NONE.name)),
                        frame = FrameLook.fromJson(o.optJSONObject("frame")),
                    ),
                    keepGps = o.optBoolean("keepGps", false),
                )
            }.getOrNull() // a preset from a newer build with a format this one lacks
        }
        val last = if (root.isNull("lastUsed")) null else root.optString("lastUsed")
        return ExportPresetDocument(presets, last?.takeIf { n -> presets.any { it.name == n } })
    }

    fun cleanName(raw: String): String =
        AtomicJsonStore.truncateUtf16Safely(raw.replace(Regex("\\s+"), " ").trim(), MAX_NAME_CHARS)
}

internal class ExportPresetLimitException(message: String) : IOException(message)

internal interface ExportPresetRepository {
    fun read(): ExportPresetDocument

    /** Saves [preset], replacing one with the same name (ignoring case). Marks it last used. */
    fun save(preset: ExportPreset): ExportPresetDocument

    fun delete(name: String): ExportPresetDocument

    /** Remember [name] (or "no preset") as the one the next export sheet opens with. */
    fun markUsed(name: String?): ExportPresetDocument
}

internal class FileExportPresetRepository(private val file: File) : ExportPresetRepository {

    override fun read(): ExportPresetDocument = AtomicJsonStore.withPathLock(file) { readLocked() }

    override fun save(preset: ExportPreset): ExportPresetDocument = mutate { doc ->
        val name = ExportPresetCodec.cleanName(preset.name)
        require(name.isNotEmpty()) { "a preset needs a name" }
        val others = doc.presets.filterNot { it.name.equals(name, ignoreCase = true) }
        if (others.size >= ExportPresetCodec.MAX_PRESETS) {
            throw ExportPresetLimitException("at most ${ExportPresetCodec.MAX_PRESETS} export presets")
        }
        ExportPresetDocument(
            (others + preset.copy(name = name)).sortedBy { it.name.lowercase() },
            lastUsed = name,
        )
    }

    override fun delete(name: String): ExportPresetDocument = mutate { doc ->
        doc.copy(
            presets = doc.presets.filterNot { it.name == name },
            lastUsed = doc.lastUsed.takeUnless { it == name },
        )
    }

    override fun markUsed(name: String?): ExportPresetDocument = mutate { doc ->
        doc.copy(lastUsed = name?.takeIf { n -> doc.presets.any { it.name == n } })
    }

    private fun mutate(change: (ExportPresetDocument) -> ExportPresetDocument): ExportPresetDocument =
        AtomicJsonStore.withPathLock(file) {
            val next = change(readLocked())
            if (next.presets.isEmpty() && next.lastUsed == null) {
                AtomicJsonStore.delete(file)
            } else {
                AtomicJsonStore.writeUtf8(file, ExportPresetCodec.encode(next), ExportPresetCodec.MAX_BYTES)
            }
            next
        }

    /** A damaged document is moved aside (never silently overwritten) and reads as empty. */
    private fun readLocked(): ExportPresetDocument {
        if (!file.isFile) return ExportPresetDocument()
        val text = AtomicJsonStore.readUtf8(file, ExportPresetCodec.MAX_BYTES)
        return try {
            ExportPresetCodec.decode(text)
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            AtomicJsonStore.quarantine(file)
            ExportPresetDocument()
        }
    }
}

/** The export sheet's saved presets. Every repository call runs on [io]. */
internal class ExportPresetViewModel(
    private val repository: ExportPresetRepository,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExportPresetDocument())
    val uiState: StateFlow<ExportPresetDocument> = _uiState.asStateFlow()

    /** Errors for the editor's status line; set by the editor. */
    var onError: (String) -> Unit = {}

    // One operation at a time, including its publication: two quick deletes must not land
    // their documents out of order and bring a deleted preset back.
    private val serial = Mutex()

    private fun launchIo(block: () -> ExportPresetDocument) {
        viewModelScope.launch {
            serial.withLock {
                withContext(io) { runCatching(block) }
                    .onSuccess { _uiState.value = it }
                    .onFailure { onError(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    fun load() = launchIo { repository.read() }

    fun save(name: String, options: ExportOptions, keepGps: Boolean) =
        launchIo { repository.save(ExportPreset(name, options, keepGps)) }

    fun delete(name: String) = launchIo { repository.delete(name) }

    fun markUsed(name: String?) = launchIo { repository.markUsed(name) }
}
