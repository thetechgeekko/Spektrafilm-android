/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * "Apply previous edit": the look of the photo edited most recently before this one, read from
 * its saved recipe. It is the same whole-look replacement as Paste settings, without having to
 * copy first, which is what a shoot edited frame by frame wants.
 */
package com.spectrafilm.app

import android.content.Context
import java.io.File

/** One saved recipe file, as far as choosing the previous edit needs it. */
internal data class RecipeFileStamp(val key: String, val modifiedMillis: Long)

/** The previous edit: whose it was and its params, in the preset schema. */
internal data class PreviousEdit(
    val sourceKey: String,
    val sourceName: String,
    val updatedAtMillis: Long,
    val paramsJson: String,
    val rotationDegrees: Int,
)

internal object PreviousEdits {
    /** Recipes looked at before giving up; unreadable ones are skipped, not fatal. */
    const val MAX_CANDIDATES = 8

    /**
     * Keys to try, newest first, without [excludeKey] (the photo being edited) and without
     * anything that is not a recipe key. Ties fall back to the key so the order is stable.
     */
    fun candidates(
        files: List<RecipeFileStamp>,
        excludeKey: String?,
        limit: Int = MAX_CANDIDATES,
    ): List<String> = files.asSequence()
        .filter { it.key != excludeKey && runCatching { RecipeDocumentCodec.validateKey(it.key) }.isSuccess }
        .sortedWith(compareByDescending<RecipeFileStamp> { it.modifiedMillis }.thenBy { it.key })
        .map { it.key }
        .take(limit)
        .toList()

    /** Lists `<key>.json` recipe files in [dir] with their modification times. */
    fun stamps(dir: File): List<RecipeFileStamp> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { RecipeFileStamp(it.name.removeSuffix(".json"), it.lastModified()) }
            .orEmpty()

    /**
     * The newest readable recipe among [candidates], as a [PreviousEdit]. [read] is the
     * repository's typed read, so a damaged file is handled (quarantined) exactly as it
     * would be when that photo is opened.
     */
    fun firstReadable(candidates: List<String>, read: (String) -> RecipeReadResult): PreviousEdit? {
        for (key in candidates) {
            val loaded = read(key) as? RecipeReadResult.Loaded ?: continue
            val doc = loaded.document
            return PreviousEdit(
                sourceKey = doc.sourceKey,
                sourceName = doc.sourceName,
                updatedAtMillis = doc.updatedAtMillis,
                paramsJson = doc.params.toString(),
                rotationDegrees = doc.manualRotationDeg,
            )
        }
        return null
    }
}

/** The name shown for [edit]'s photo, or a generic phrase when the recipe has none. */
internal fun previousEditDisplayName(ctx: Context, edit: PreviousEdit): String =
    edit.sourceName.trim().ifEmpty { ctx.getString(R.string.editor_preset_previous_unnamed) }
