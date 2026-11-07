/*
 * Spektrafilm for Android -- edit files beside the photos in a Library folder. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The app-private recipe ([Recipes]) is the edit. It is keyed by the photo's URI string, so it
 * does not survive a reinstall, a new phone, or the same folder reached through another URI.
 * A sidecar is a copy of that edit written beside the photo, `<photo name>.spektra.json`, so the
 * edit travels with the folder (an SD card, a synced folder, a backup).
 *
 *  - Writing is opt-in and explicit: "Save edits beside photos" in the Library menu. The Library
 *    otherwise only reads the folder, and writing needs the folder's write grant.
 *  - Reading is automatic and only fills a gap: a frame with a sidecar and NO recipe gets the
 *    sidecar's edit as its recipe. An existing recipe is never replaced, and neither is a recipe
 *    the user cleared here (reset, or edited back to defaults), so the app-private edit stays
 *    authoritative and a stale sidecar cannot undo newer work or a reset.
 *  - A sidecar is parsed with the same strict, bounded preset parser as an imported preset, so
 *    a hostile or future file is skipped, never half-applied.
 */
package com.spectrafilm.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject

internal object LibrarySidecars {
    const val SCHEMA_ID = "org.spektrafilm.sidecar"
    const val VERSION = 1
    private const val SUFFIX = ".spektra.json"
    private val VALID_ROTATIONS = setOf(0, 90, 180, 270)

    fun nameFor(photoName: String): String = photoName + SUFFIX

    /** The photo a sidecar belongs to, or null when [name] is not a sidecar. */
    fun photoNameOf(name: String): String? =
        name.takeIf { it.endsWith(SUFFIX, ignoreCase = true) && it.length > SUFFIX.length }
            ?.dropLast(SUFFIX.length)

    /** One frame's edit, as stored beside it. */
    data class Edit(val params: JSONObject, val manualRotationDeg: Int)

    fun encode(photoName: String, edit: Edit, updatedAtMillis: Long): String {
        require(edit.manualRotationDeg in VALID_ROTATIONS) { "invalid rotation" }
        val root = JSONObject().apply {
            put("schema", SCHEMA_ID)
            put("version", VERSION)
            put("photo", photoName)
            put("updatedAt", updatedAtMillis)
            put("manualRotationDeg", edit.manualRotationDeg)
            put("params", Presets.parseImportJson(edit.params.toString()))
        }
        AtomicJsonStore.validate(root)
        return root.toString(2)
    }

    /** Throws on anything that is not a current, well-formed sidecar. */
    fun decode(text: String): Edit {
        val root = AtomicJsonStore.parseObject(text)
        require(root.optString("schema") == SCHEMA_ID) { "not a Spektrafilm sidecar" }
        // The bounded parser returns integer tokens as BigInteger; accept only exact integers.
        require(exactInt(root.opt("version"), "version") == VERSION) { "unsupported sidecar version" }
        val rotation = if (root.has("manualRotationDeg")) exactInt(root.opt("manualRotationDeg"), "rotation") else 0
        require(rotation in VALID_ROTATIONS) { "invalid rotation" }
        val params = root.optJSONObject("params") ?: throw IllegalArgumentException("no params")
        return Edit(Presets.parseImportJson(params.toString()), rotation)
    }

    private fun exactInt(value: Any?, field: String): Int {
        val big = when (value) {
            is Int, is Long, is Short, is Byte -> java.math.BigInteger.valueOf((value as Number).toLong())
            is java.math.BigInteger -> value
            is java.math.BigDecimal -> runCatching { value.toBigIntegerExact() }.getOrNull()
            else -> null
        } ?: throw IllegalArgumentException("$field must be a JSON integer")
        require(big.bitLength() < 32) { "$field is out of range" }
        return big.toInt()
    }

    /**
     * Pairs each frame with the sidecar beside it, by exact display name. [rows] is a whole
     * folder listing (frames and other files); the result maps frame document URI to sidecar
     * document URI.
     */
    fun pair(frames: List<LibraryEntry>, rows: List<LibraryEntry>): Map<String, String> {
        val sidecars = HashMap<String, String>()
        for (row in rows) photoNameOf(row.name)?.let { sidecars[it] = row.documentUri }
        val paired = LinkedHashMap<String, String>()
        for (frame in frames) sidecars[frame.name]?.let { paired[frame.documentUri] = it }
        return paired
    }

    /**
     * Gives each frame that has a sidecar and no recipe the sidecar's edit. Returns how many
     * were imported. Blocking IO; [readText] reads a sidecar document. Every write is guarded
     * by the generation read before it, under the key's operation gate.
     */
    fun importMissing(
        repo: RecipeRepository,
        frames: List<LibraryEntry>,
        sidecarOf: Map<String, String>,
        keyOf: (String) -> String?,
        readText: (String) -> String,
    ): Int {
        var imported = 0
        for (frame in frames) {
            val sidecar = sidecarOf[frame.documentUri] ?: continue
            val key = keyOf(frame.documentUri) ?: continue
            runCatching {
                // Cheap checks first, then the provider read OUTSIDE the key's gate: a slow or
                // stalled provider must never hold the gate the editor takes on the main thread.
                if (repo.wasCleared(key) || repo.readResult(key) != RecipeReadResult.Missing) {
                    return@runCatching false
                }
                val edit = decode(readText(sidecar))
                repo.atomically(key) {
                    // Re-check under the gate: an edit or reset may have landed meanwhile.
                    val generation = repo.generation(key)
                    if (repo.readResult(key) != RecipeReadResult.Missing) return@atomically false
                    // A reset here is a decision, not a gap: never undo it from a sidecar.
                    if (repo.wasCleared(key)) return@atomically false
                    repo.saveJson(key, edit.params.toString(), frame.name, edit.manualRotationDeg, generation)
                }
            }.onFailure { Diag.w("sidecar import skipped for ${frame.name}: ${it.message}") }
                .onSuccess { if (it) imported++ }
        }
        return imported
    }

    data class SaveResult(val saved: Int, val failed: Int, val needsWriteAccess: Boolean = false)

    /** Where sidecars are written: a folder's documents. */
    interface Folder {
        /** False when the folder cannot be written (no write grant). */
        fun writable(): Boolean
        fun write(existingSidecarUri: String?, name: String, text: String)
    }

    /** Writes the recipe of each of [frames] that has one beside it. Blocking IO. */
    fun saveAll(
        repo: RecipeRepository,
        folder: Folder,
        frames: List<LibraryEntry>,
        sidecarOf: Map<String, String>,
        keyOf: (String) -> String?,
        now: () -> Long = System::currentTimeMillis,
    ): SaveResult {
        if (!folder.writable()) return SaveResult(0, 0, needsWriteAccess = true)
        var saved = 0
        var failed = 0
        for (frame in frames) {
            val key = keyOf(frame.documentUri) ?: continue
            val doc = (repo.readResult(key) as? RecipeReadResult.Loaded)?.document ?: continue
            runCatching {
                val text = encode(frame.name, Edit(doc.params, doc.manualRotationDeg), now())
                folder.write(sidecarOf[frame.documentUri], nameFor(frame.name), text)
            }.onSuccess { saved++ }
                .onFailure {
                    failed++
                    Diag.w("sidecar write failed for ${frame.name}: ${it.message}")
                }
        }
        return SaveResult(saved, failed)
    }

    fun readDocument(resolver: ContentResolver, documentUri: String): String =
        (resolver.openInputStream(Uri.parse(documentUri)) ?: error("provider returned no stream"))
            .use { AtomicJsonStore.readUtf8(it, AtomicJsonStore.MAX_RECIPE_BYTES) }

    /** [Folder] over a SAF tree the app holds a persisted grant for. */
    fun androidFolder(resolver: ContentResolver, treeUri: String): Folder = object : Folder {
        private val tree = Uri.parse(treeUri)

        override fun writable(): Boolean {
            if (resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }) return true
            // A grant from the picker this session may still carry write: persist it.
            return runCatching {
                resolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)
        }

        override fun write(existingSidecarUri: String?, name: String, text: String) {
            val target = existingSidecarUri?.let(Uri::parse) ?: run {
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
                DocumentsContract.createDocument(resolver, parent, "application/json", name)
                    ?: error("could not create $name")
            }
            // "wt" truncates, so a shorter edit does not leave the old tail behind.
            (resolver.openOutputStream(target, "wt") ?: error("provider returned no stream")).use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }
}
