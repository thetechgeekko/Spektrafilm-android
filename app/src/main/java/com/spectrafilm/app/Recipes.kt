/*
 * SpectraFilm for Android — non-destructive recipe / sidecar layer. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * M5 headline feature. A "recipe" is simply a preset bound to a specific source
 * image: the full SpektraParams (+ film/print profile ids, raw WB, output color
 * space) serialized with the EXACT same JSON schema as a saved preset (see
 * Presets.encode/decode — no parallel params model), wrapped in a small envelope
 * that records the stable source key plus light metadata.
 *
 * The original image is NEVER written. Edits are persisted automatically (debounced)
 * to app-private storage at filesDir/recipes/<key>.json. When a source is re-opened,
 * if a recipe exists for its key it is loaded and the editing state is restored, so
 * the edit is non-destructive and reproducible. If none exists, defaults are used.
 *
 * Source key: a SHA-256 (hex) of the source's stable identity string. For a content
 * Uri that is the Uri string itself; this is stable across re-opens of the SAME Uri.
 * Limitation: photo-picker Uris are ephemeral grants whose string can differ between
 * sessions for the same underlying file, so the recipe may not re-bind after the
 * grant is revoked — RAW/DNG opened via OpenDocument (persistable) and re-picked
 * identical Uris re-bind reliably. The DEMO source has no stable identity and is not
 * persisted.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val RECIPE_VERSION = 1

object Recipes {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "recipes").apply { mkdirs() }

    /**
     * Stable key for a source. Returns null for sources that have no stable identity
     * (e.g. the synthetic demo image), which should not be persisted as recipes.
     */
    fun keyFor(uri: Uri?): String? {
        val id = uri?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return sha256Hex(id)
    }

    private fun file(ctx: Context, key: String): File = File(dir(ctx), "$key.json")

    fun exists(ctx: Context, key: String?): Boolean =
        key != null && file(ctx, key).isFile

    /**
     * Save/update the recipe for [key] from the current editing [state]. The original
     * image is untouched — only this app-private sidecar JSON is written. [sourceName]
     * is stored purely as a human-readable hint for any future recipe browser.
     */
    fun save(ctx: Context, key: String, state: ParamsState, sourceName: String) {
        val envelope = JSONObject().apply {
            put("recipeVersion", RECIPE_VERSION)
            put("sourceKey", key)
            put("sourceName", sourceName)
            put("updatedAt", System.currentTimeMillis())
            // Params payload uses the shared preset schema verbatim.
            put("params", Presets.encode(state))
        }
        file(ctx, key).writeText(envelope.toString(2))
    }

    /**
     * Load the recipe for [key] into [into]. Returns true if a recipe existed and was
     * applied, false otherwise (leaving [into] untouched so defaults stand).
     */
    fun load(ctx: Context, key: String?, into: ParamsState): Boolean {
        if (key == null) return false
        val f = file(ctx, key)
        if (!f.isFile) return false
        val root = JSONObject(f.readText())
        // Tolerate either an enveloped recipe or (defensively) a bare preset JSON.
        val params = root.optJSONObject("params") ?: root
        Presets.decode(params, into)
        return true
    }

    /** Delete the recipe for [key] (clears the saved edit). No-op if none exists. */
    fun delete(ctx: Context, key: String?) {
        if (key == null) return
        file(ctx, key).delete()
    }

    /**
     * Reset the live editing [state] back to defaults, reusing the shared preset schema
     * so every field is restored exactly as a fresh launch would seed it. A pristine
     * [ParamsState] is round-tripped through the same encode/decode path (no parallel
     * reset logic to drift from serialization), then the user's saved app defaults are
     * re-applied just like on first launch. The original image is never touched.
     */
    fun resetToDefaults(state: ParamsState, settings: AppSettings, availableProfiles: List<String>) {
        Presets.decode(Presets.encode(ParamsState()), state)
        settings.applyDefaultsTo(state, availableProfiles)
    }

    private fun sha256Hex(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
