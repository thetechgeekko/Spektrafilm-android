/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Per-source recipe (sidecar edit) storage behind one interface (#276, third slice). The editor
 * used to call the [Recipes] object with an Activity context, including from work submitted to
 * the process-owned [RecipeWorkRuntime], which then held that Activity until the work ran. The
 * [AppContainer] now owns a [RecipeRepository] bound to the application context. Generation
 * gating, the per-key operation gate and the document codec all stay in [Recipes].
 */
package com.spectrafilm.app

import android.content.Context

internal interface RecipeRepository {
    /** The key's write generation; bumped by [delete] so a stale save can be refused. */
    fun generation(key: String): Long

    fun readResult(key: String?): RecipeReadResult

    /** [key]'s recipe if it reads cleanly, else null; never quarantines (see [Recipes.peek]). */
    fun peek(key: String?): RecipeDocument? = null

    /**
     * Saves pre-serialized [paramsJson] as the recipe for [key]. Returns false, writing
     * nothing, when [expectedGeneration] is given and no longer current.
     */
    fun saveJson(
        key: String,
        paramsJson: String,
        sourceName: String,
        rotationDegrees: Int = 0,
        expectedGeneration: Long? = null,
    ): Boolean

    /** Deletes the recipe for [key], if any, and bumps its generation. */
    fun delete(key: String?)

    /**
     * The most recently saved recipe of any photo other than [excludeKey], or null when there
     * is none. Runs file IO: call it off the main thread.
     */
    fun previousEdit(excludeKey: String?): PreviousEdit? = null

    /**
     * Runs [block] holding [key]'s operation gate, so a read-modify-write of one recipe cannot
     * interleave with another save or delete of it. The gate is reentrant.
     */
    fun <T> atomically(key: String, block: () -> T): T = block()

    /** True when [key]'s edit was cleared on purpose here (see [Recipes.delete]). */
    fun wasCleared(key: String): Boolean = false
}

/** [RecipeRepository] over the app's recipes directory ([Recipes]). */
internal class FileRecipeRepository(context: Context) : RecipeRepository {
    private val app: Context = context.applicationContext

    override fun generation(key: String) = Recipes.generation(key)
    override fun readResult(key: String?) = Recipes.readResult(app, key)
    override fun peek(key: String?) = Recipes.peek(app, key)
    override fun saveJson(
        key: String,
        paramsJson: String,
        sourceName: String,
        rotationDegrees: Int,
        expectedGeneration: Long?,
    ) = Recipes.saveJson(app, key, paramsJson, sourceName, rotationDegrees, expectedGeneration)
    override fun delete(key: String?) = Recipes.delete(app, key)
    override fun <T> atomically(key: String, block: () -> T): T = Recipes.withOperationGate(key, block)
    override fun wasCleared(key: String): Boolean = Recipes.wasCleared(app, key)
    override fun previousEdit(excludeKey: String?): PreviousEdit? =
        PreviousEdits.firstReadable(
            PreviousEdits.candidates(PreviousEdits.stamps(Recipes.directory(app)), excludeKey),
        ) { key -> readResult(key) }
}
