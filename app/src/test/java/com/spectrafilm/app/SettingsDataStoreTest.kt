package com.spectrafilm.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataStoreTest {
    private val dir: File = Files.createTempDirectory("settings").toFile()
    private val file = File(dir, "spectrafilm_settings.preferences_pb")
    private val jobs = mutableListOf<Job>()

    private val theme = stringPreferencesKey("theme")
    private val quality = intPreferencesKey("export_quality")
    private val gps = booleanPreferencesKey("export_keep_gps")

    /** A store over [file], plus a way to read what actually reached the file. */
    private fun open(): Pair<SettingsDataStore, DataStore<Preferences>> {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        jobs += job
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return SettingsDataStore(dataStore, scope) to dataStore
    }

    private fun closeAll() = runBlocking { jobs.forEach { it.cancelAndJoin() }; jobs.clear() }

    @After fun tearDown() {
        closeAll()
        dir.deleteRecursively()
    }

    @Test fun emptyFileReadsAsDefaults() {
        val (store, _) = open()
        assertNull(store[theme])
        assertFalse(theme in store)
    }

    @Test fun editIsVisibleImmediately() {
        val (store, _) = open()
        store.edit { it[theme] = "DARK" }
        assertEquals("DARK", store[theme])
        assertTrue(theme in store)
        assertEquals("DARK", store.data.value[theme])
    }

    @Test fun editsReachTheFileInOrder() = runBlocking {
        val (store, dataStore) = open()
        for (q in 1..50) store.edit { it[quality] = q }
        store.edit { it[gps] = true }
        assertTrue("writer did not drain", store.flush(timeoutMs = 10_000))
        val persisted = dataStore.data.first()
        assertEquals(true, persisted[gps])
        assertEquals(50, persisted[quality])
    }

    @Test fun aNewStoreReadsWhatTheLastOneWrote() = runBlocking {
        val (store, dataStore) = open()
        store.edit { it[theme] = "LIGHT"; it[quality] = 80 }
        // Wait on the writer itself, then read the file back, instead of waiting for the
        // DataStore data flow to re-emit: flush() returns once the edit is persisted.
        assertTrue("writer did not drain", store.flush(timeoutMs = 10_000))
        assertEquals(80, dataStore.data.first()[quality])
        closeAll()

        val (reopened, _) = open()
        assertEquals("LIGHT", reopened[theme])
        assertEquals(80, reopened[quality])
    }

    @Test fun flushWaitsUntilEveryEditIsOnDisk() = runBlocking {
        val (store, dataStore) = open()
        assertTrue(store.flush())
        for (q in 1..20) store.edit { it[quality] = q }
        assertTrue(store.flush(timeoutMs = 10_000))
        assertEquals(20, dataStore.data.first()[quality])
    }

    @Test fun removingAKeyRestoresTheDefault() {
        val (store, _) = open()
        store.edit { it[gps] = true }
        store.edit { it.remove(gps) }
        assertNull(store[gps])
    }

    /**
     * Edits are saved as they are made, so once the writer has caught up, onStop's flush
     * must return on its fast path without waiting at all (MAD audit fix 1).
     */
    @Test fun flushDoesNotWaitOnceEditsAreSaved() = runBlocking {
        val (store, dataStore) = open()
        store.edit { it[theme] = "DARK" }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (dataStore.data.first()[theme] != "DARK" && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("DARK", dataStore.data.first()[theme])
        // Persisted without any flush call; now a zero-timeout flush must still succeed.
        Thread.sleep(50)
        assertTrue(store.flush(timeoutMs = 0))
    }

    /** A burst written as one batched edit still lands in order, removals included. */
    @Test fun aBurstOfEditsLandsInOrder() = runBlocking {
        val (store, dataStore) = open()
        for (i in 1..500) {
            store.edit { it[quality] = i }
            store.edit { if (i % 2 == 0) it[gps] = true else it.remove(gps) }
        }
        assertTrue("writer did not drain", store.flush(timeoutMs = 10_000))
        val persisted = dataStore.data.first()
        assertEquals(500, persisted[quality])
        assertEquals(true, persisted[gps])
    }

    /**
     * The first read happens in the background (MAD audit fix 4). An edit made before it
     * lands must be applied on top of the saved values, not replace them with defaults.
     */
    @Test fun anEditBeforeTheFirstReadKeepsSavedValues() = runBlocking {
        val (store, _) = open()
        store.edit { it[theme] = "LIGHT"; it[quality] = 80 }
        assertTrue("writer did not drain", store.flush(timeoutMs = 10_000))
        closeAll()

        val (reopened, dataStore) = open()
        reopened.edit { it[quality] = 50 }
        reopened.awaitLoaded()
        assertTrue(reopened.isLoaded)
        assertEquals("LIGHT", reopened[theme])
        assertEquals(50, reopened[quality])
        assertTrue("writer did not drain", reopened.flush(timeoutMs = 10_000))
        assertEquals("LIGHT", dataStore.data.first()[theme])
        assertEquals(50, dataStore.data.first()[quality])
    }
}
