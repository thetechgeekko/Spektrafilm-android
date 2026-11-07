/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Preferences DataStore behind AppSettings (#276). AppSettings keeps its synchronous
 * property API, which the editor, EngineHolder and export read on every render, so this
 * store holds the whole Preferences snapshot in memory:
 *
 *  - The snapshot is loaded once, when the store is built. That first read is a disk read,
 *    the same cost SharedPreferences paid on its first get.
 *  - [edit] applies the change to the snapshot at once, so the next read sees it, and queues
 *    the same change for DataStore. A single consumer drains the queue in order, so the
 *    file ends up with the edits in the order they were made.
 *  - This process is the only writer, so the snapshot is never refreshed from the file.
 *
 * [data] is the snapshot as a StateFlow, for callers that want to observe settings.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val lock = Any()
    private val snapshot = MutableStateFlow(load(dataStore))
    private val writes = Channel<(MutablePreferences) -> Unit>(Channel.UNLIMITED)

    /** Every setting as last written in this process. */
    val data: StateFlow<Preferences> = snapshot.asStateFlow()

    init {
        scope.launch {
            for (write in writes) {
                runCatching { dataStore.edit { write(it) } }
                    .onFailure { Diag.e("settings write failed: ${it.message}") }
            }
        }
    }

    operator fun <T> get(key: Preferences.Key<T>): T? = snapshot.value[key]

    operator fun contains(key: Preferences.Key<*>): Boolean = key in snapshot.value

    /** Applies [block] to the in-memory settings now and to the file in order, later. */
    fun edit(block: (MutablePreferences) -> Unit) {
        synchronized(lock) {
            snapshot.value = snapshot.value.toMutablePreferences().apply(block).toPreferences()
            writes.trySend(block)
        }
    }

    companion object {
        /**
         * The settings store for [context], migrating [keysToMigrate] out of the
         * SharedPreferences file [name] on first use. Keys not listed stay in that file.
         */
        fun create(
            context: Context,
            name: String,
            keysToMigrate: Set<String>,
            scope: CoroutineScope,
        ): SettingsDataStore {
            val app = context.applicationContext
            val dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                migrations = listOf(SharedPreferencesMigration(app, name, keysToMigrate)),
                scope = scope,
                produceFile = { app.preferencesDataStoreFile(name) },
            )
            return SettingsDataStore(dataStore, scope)
        }

        /** The first read. A file that cannot be read starts from defaults, as before. */
        private fun load(dataStore: DataStore<Preferences>): Preferences =
            runCatching { runBlocking { dataStore.data.first() } }
                .onFailure { Diag.e("settings read failed, using defaults: ${it.message}") }
                .getOrDefault(emptyPreferences())
    }
}
