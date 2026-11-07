/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Preferences DataStore behind AppSettings (#276). AppSettings keeps its synchronous
 * property API, which the editor, EngineHolder and export read on every render, so this
 * store holds the whole Preferences snapshot in memory:
 *
 *  - The snapshot is loaded once, in the background, as soon as the store is built. The
 *    activity waits for it behind the splash screen ([awaitLoaded]); any other read made
 *    before it lands waits for it (bounded), as a first SharedPreferences get did.
 *  - [edit] applies the change to the snapshot at once, so the next read sees it, and queues
 *    the same change for DataStore. A single consumer drains the queue in order, so the
 *    file ends up with the edits in the order they were made.
 *  - This process is the only writer, so the snapshot is never refreshed from the file
 *    after the first read (retried if it failed, see [reconcile]). Edits made before that
 *    read are replayed on top of what it returns.
 *  - Edits are saved as they are made, not at onStop: the writer starts on each edit at
 *    once, and edits that pile up while a write is in progress go out together as one
 *    file write. Callers save a value when it is committed (a slider on release, not every
 *    drag frame), so by the time the app is backgrounded the queue is normally empty.
 *  - [flush] waits for queued edits to reach the file; the activity calls it from onStop.
 *    It returns at once when nothing is queued, which is the normal case; the bounded wait
 *    is a backstop for an edit made moments before backgrounding (durability over a few
 *    ms of onStop latency in that rare case).
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
import kotlinx.coroutines.withTimeoutOrNull

internal class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val lock = Any()
    private val snapshot = MutableStateFlow(emptyPreferences())
    private val writes = Channel<(MutablePreferences) -> Unit>(Channel.UNLIMITED)

    /**
     * Edits made before the file has been read (or while the first read had failed), replayed
     * onto the file's contents once it reads. Non-null until the first successful read.
     */
    private var unreconciled: MutableList<(MutablePreferences) -> Unit>? = mutableListOf()

    /** True once the first read has finished, successfully or not (then defaults are used). */
    private val loadedFlow = MutableStateFlow(false)

    /** Edits queued so far, and edits the writer has finished with (persisted or failed). */
    private var queued = 0L
    private val done = MutableStateFlow(0L)

    /** Every setting as last written in this process. */
    val data: StateFlow<Preferences> = snapshot.asStateFlow()

    /** Whether the first read has finished; the launch screen stays up until it has. */
    val isLoaded: Boolean get() = loadedFlow.value

    /** Suspends until the first read has finished. */
    suspend fun awaitLoaded() {
        loadedFlow.first { it }
    }

    init {
        scope.launch {
            // The first read happens here, off the caller's thread, instead of blocking the
            // constructor (MAD audit fixes 1 and 4). The activity waits for it behind the
            // splash screen with awaitLoaded().
            if (!reconcile()) Diag.e("settings read failed, using defaults until a retry succeeds")
            loadedFlow.value = true
            for (first in writes) {
                // Take every edit already waiting and write them as ONE DataStore edit, in
                // order: a burst of edits costs one file write instead of one each, so the
                // queue drains quickly and flush() usually finds it empty.
                val batch = mutableListOf(first)
                while (true) batch += writes.tryReceive().getOrNull() ?: break
                reconcile()
                runCatching { dataStore.edit { prefs -> batch.forEach { it(prefs) } } }
                    .onFailure { Diag.e("settings write failed: ${it.message}") }
                done.value += batch.size
            }
        }
    }

    operator fun <T> get(key: Preferences.Key<T>): T? {
        ensureLoaded()
        return snapshot.value[key]
    }

    operator fun contains(key: Preferences.Key<*>): Boolean {
        ensureLoaded()
        return key in snapshot.value
    }

    /**
     * A read before the file has loaded waits for it (bounded), as the first read always did,
     * so a background caller such as the engine never sees defaults in place of saved values.
     * The UI does not get here: it waits in awaitLoaded() behind the splash screen first.
     */
    private fun ensureLoaded() {
        if (loadedFlow.value) return
        runBlocking { withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadedFlow.first { it } } }
    }

    /** Applies [block] to the in-memory settings now and to the file in order, later. */
    fun edit(block: (MutablePreferences) -> Unit) {
        synchronized(lock) {
            snapshot.value = snapshot.value.toMutablePreferences().apply(block).toPreferences()
            unreconciled?.add(block)
            queued += 1
            writes.trySend(block)
        }
    }

    /**
     * Waits up to [timeoutMs] for every edit made so far to reach the file. The activity calls
     * this from onStop, the point where Android used to drain SharedPreferences.apply(), so a
     * setting changed just before the app is backgrounded and killed is not lost.
     */
    fun flush(timeoutMs: Long = FLUSH_TIMEOUT_MS): Boolean {
        val target = synchronized(lock) { queued }
        if (done.value >= target) return true
        return runBlocking { withTimeoutOrNull(timeoutMs) { done.first { it >= target } } } != null
    }

    /**
     * If the first read failed, try it again; on success the snapshot becomes the file's
     * contents with this process's edits replayed on top, so saved settings come back instead
     * of reading as defaults for the rest of the process.
     */
    private suspend fun reconcile(): Boolean {
        if (synchronized(lock) { unreconciled } == null) return true
        val read = runCatching { dataStore.data.first() }
            .onFailure { Diag.w("settings read failed: ${it.message}") }
            .getOrNull() ?: return false
        synchronized(lock) {
            val replay = unreconciled ?: return true
            snapshot.value = read.toMutablePreferences().apply { replay.forEach { it(this) } }.toPreferences()
            unreconciled = null
        }
        if (loadedFlow.value) Diag.i("settings read recovered after an earlier failure")
        return true
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

        private const val FLUSH_TIMEOUT_MS = 2_000L
        private const val LOAD_TIMEOUT_MS = 5_000L
    }
}
