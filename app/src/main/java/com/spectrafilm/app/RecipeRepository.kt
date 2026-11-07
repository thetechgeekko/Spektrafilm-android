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
}

/** [RecipeRepository] over the app's recipes directory ([Recipes]). */
internal class FileRecipeRepository(context: Context) : RecipeRepository {
    private val app: Context = context.applicationContext

    override fun generation(key: String) = Recipes.generation(key)
    override fun readResult(key: String?) = Recipes.readResult(app, key)
    override fun saveJson(
        key: String,
        paramsJson: String,
        sourceName: String,
        rotationDegrees: Int,
        expectedGeneration: Long?,
    ) = Recipes.saveJson(app, key, paramsJson, sourceName, rotationDegrees, expectedGeneration)
    override fun delete(key: String?) = Recipes.delete(app, key)
}
