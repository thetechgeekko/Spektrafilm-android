/*
 * SpectraFilm — one-time "press back again to exit" hint flag.
 *
 * Persists a single boolean in DataStore Preferences so the double-back exit hint
 * snackbar is shown only the FIRST time the user ever triggers it. After that,
 * double-back still works but the tip is not re-shown.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.backHintDataStore by preferencesDataStore(name = "back_exit_hint")

object BackHintStore {
    private val KEY_SHOWN = booleanPreferencesKey("back_exit_hint_shown")

    /** True once the hint has been shown at least once. */
    suspend fun hasShown(ctx: Context): Boolean =
        runCatching { ctx.backHintDataStore.data.first()[KEY_SHOWN] ?: false }
            .getOrDefault(false)

    /** Mark the hint as shown so it never appears again. */
    suspend fun markShown(ctx: Context) {
        runCatching { ctx.backHintDataStore.edit { it[KEY_SHOWN] = true } }
    }
}
