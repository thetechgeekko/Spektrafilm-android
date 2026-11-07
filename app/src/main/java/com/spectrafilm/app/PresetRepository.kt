/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Saved-preset storage behind one interface (#276, second slice). The editor used to call the
 * [Presets] object with an Activity context at every site; it now asks the [AppContainer] for a
 * [PresetRepository], so storage has one owner and tests can substitute it. Serialization stays
 * in [Presets] (it reads live Compose state and must run on main); this covers only the file
 * and document I/O, which callers run on the IO dispatcher as before.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri

internal interface PresetRepository {
    /** Names (without .json) of saved presets, alphabetically. */
    fun list(): List<String>

    /** A saved preset's normalized JSON. Throws if it is missing or unreadable. */
    fun read(name: String): String

    /** A preset document's normalized JSON, from a SAF [uri]. */
    fun readUri(uri: Uri): String

    /** Stores pre-serialized preset [json] under [name]. */
    fun saveJson(name: String, json: String)

    /** Writes pre-serialized preset [json] to a new SAF document [uri]. */
    fun exportJson(uri: Uri, json: String)

    fun delete(name: String)

    /** True if a preset is stored under the name [name] resolves to. */
    fun exists(name: String): Boolean

    /** The name a preset typed as [name] is actually stored under. */
    fun resolveName(name: String): String
}

/** [PresetRepository] over the app's presets directory ([Presets]). */
internal class FilePresetRepository(context: Context) : PresetRepository {
    private val app: Context = context.applicationContext

    override fun list() = Presets.list(app)
    override fun read(name: String) = Presets.read(app, name)
    override fun readUri(uri: Uri) = Presets.readUri(app, uri)
    override fun saveJson(name: String, json: String) = Presets.saveJson(app, name, json)
    override fun exportJson(uri: Uri, json: String) = Presets.exportJson(app, uri, json)
    override fun delete(name: String) = Presets.delete(app, name)
    override fun exists(name: String) = Presets.exists(app, name)
    override fun resolveName(name: String) = Presets.resolveName(name)
}
