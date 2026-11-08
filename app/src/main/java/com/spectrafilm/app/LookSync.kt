/*
 * Spektrafilm for Android -- paste one photo's look onto many (Library multi-select). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Lightroom's "Sync settings": select frames in the Library and give them the look of the photo
 * open in the editor. Only the LOOK travels, the same split as a preset ([Presets.decodeLook]):
 * film, print, exposure, grain and every other global setting replace the target's, while what
 * belongs to one framing stays with each target -- its local masks, its crop, and its rotation.
 * A target with no recipe yet gets the look with no crop and no rotation.
 *
 * Every target is read and written under its per-key operation gate with the generation it was
 * read at, so a delete or editor save racing the paste is never silently overwritten, and a
 * recipe that cannot be read (corrupt, a future version, an IO error) is skipped and counted, not
 * replaced: the paste must never be the thing that destroys an edit it could not see.
 */
package com.spectrafilm.app

import org.json.JSONObject

internal object LookSync {
    /** Keys of `input` that describe one photo's framing, not its look. */
    private val FRAMING_INPUT_KEYS = listOf("crop", "cropCenter", "cropSize", "straighten")

    /**
     * [source]'s look with [target]'s framing: a new object, neither argument is changed.
     * [target] null means the frame has no recipe yet.
     */
    fun merge(source: JSONObject, target: JSONObject?): JSONObject {
        val merged = JSONObject(source.toString())
        merged.remove("masks")
        merged.optJSONObject("input")?.let { input -> FRAMING_INPUT_KEYS.forEach(input::remove) }
        if (target == null) return merged
        target.opt("masks")?.takeIf { it != JSONObject.NULL }?.let { merged.put("masks", copy(it)) }
        val framing = target.optJSONObject("input")
        if (framing != null) {
            val input = merged.optJSONObject("input") ?: JSONObject().also { merged.put("input", it) }
            for (key in FRAMING_INPUT_KEYS) {
                framing.opt(key)?.takeIf { it != JSONObject.NULL }?.let { input.put(key, copy(it)) }
            }
        }
        return merged
    }

    private fun copy(value: Any): Any = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is org.json.JSONArray -> org.json.JSONArray(value.toString())
        else -> value
    }

    /** One frame to paste onto. */
    data class Target(val documentUri: String, val name: String)

    data class Result(val applied: Int, val skipped: Int, val noSource: Boolean = false)

    /**
     * Pastes the look saved for [sourceUri] onto [targets]. Blocking file IO: call off the main
     * thread. The source itself is never a target (the editor holds its live state). Targets run
     * in a stable order so a repeated paste is reproducible.
     */
    fun paste(repo: RecipeRepository, sourceUri: String, targets: List<Target>): Result {
        val sourceKey = Recipes.keyFor(android.net.Uri.parse(sourceUri)) ?: return Result(0, 0, noSource = true)
        val source = (repo.readResult(sourceKey) as? RecipeReadResult.Loaded)?.document?.params
            ?: return Result(0, 0, noSource = true)
        return pasteParams(repo, source, sourceKey, targets) { uri -> Recipes.keyFor(android.net.Uri.parse(uri)) }
    }

    /** [paste] after the source is resolved; [keyOf] is injectable so the JVM tests need no Uri. */
    fun pasteParams(
        repo: RecipeRepository,
        source: JSONObject,
        sourceKey: String,
        targets: List<Target>,
        keyOf: (String) -> String?,
    ): Result {
        var applied = 0
        var skipped = 0
        for (target in targets.distinctBy { it.documentUri }.sortedBy { it.documentUri }) {
            val key = keyOf(target.documentUri)
            if (key == null || key == sourceKey) {
                if (key == null) skipped++
                continue
            }
            val ok = runCatching {
                repo.atomically(key) {
                    val generation = repo.generation(key)
                    when (val read = repo.readResult(key)) {
                        RecipeReadResult.Missing -> repo.saveJson(
                            key,
                            merge(source, null).toString(),
                            target.name,
                            rotationDegrees = 0,
                            expectedGeneration = generation,
                        )
                        is RecipeReadResult.Loaded -> repo.saveJson(
                            key,
                            merge(source, read.document.params).toString(),
                            target.name,
                            rotationDegrees = read.document.manualRotationDeg,
                            expectedGeneration = generation,
                        )
                        else -> false
                    }
                }
            }.onFailure { Diag.w("look paste failed: ${it.message}") }.getOrDefault(false)
            if (ok) applied++ else skipped++
        }
        return Result(applied, skipped)
    }
}
