/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * The editor's own ViewModel (audit A2, first slice). It owns two things that used to be
 * `remember`ed inside EditorScreen:
 *
 *  - the editing state [ParamsState], and
 *  - the editor catalog (native engine, installed profile ids, built-in looks), loaded once
 *    per ViewModel in [viewModelScope] instead of once per editor composition.
 *
 * Behaviour is deliberately unchanged in this slice. The session document is still the only
 * restoration authority: every editor composition calls [beginSession] once and gets a FRESH
 * [ParamsState], exactly like the old `remember { ParamsState() }`. That matters because the
 * editor captures "fresh defaults" (Settings defaults applied to an untouched state) right
 * before decoding the checkpoint, and reset/dirty checks compare against that snapshot; handing
 * a recreated Activity the previous, already-edited state would make the edits look like
 * defaults. Letting the state outlive recreation without the checkpoint round trip is a later
 * slice, once the restore path itself lives here.
 *
 * The catalog is applied to the screen in the same place and order as before (one effect that
 * sets engine and profiles together with the restore), so no render can start before the
 * checkpoint is decoded.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor needs from disk and the native side before it can restore and render. */
@Immutable
internal data class EditorCatalog(
    /** Process-wide native engine (EngineHolder); immutable and thread-safe once built. */
    val engine: SpektraEngine,
    /** Installed film and paper profile ids, in engine order. Empty if listing failed. */
    val profiles: List<String>,
    /** Built-in looks grouped for the preset panel. Empty if they could not be read. */
    val builtInGroups: Map<String, List<BuiltInPreset>>,
)

internal class EditorViewModel(
    /** Loads the catalog. Runs once, on [viewModelScope]; it switches to its own dispatcher. */
    private val loadCatalog: suspend () -> EditorCatalog,
) : ViewModel() {
    private val _catalog = MutableStateFlow<EditorCatalog?>(null)

    /** Null until the first (and only) load finishes. */
    val catalog: StateFlow<EditorCatalog?> = _catalog.asStateFlow()

    init {
        viewModelScope.launch { _catalog.value = loadCatalog() }
    }

    /** The editing state of the current editor composition. */
    var params: ParamsState = ParamsState()
        private set

    /**
     * Starts a new editor composition with fresh, default editing state and returns it.
     * Call exactly once per composition (inside `remember`); see the file header for why
     * the state is not carried over.
     */
    fun beginSession(): ParamsState = ParamsState().also { params = it }
}

/**
 * The production catalog loader, moved verbatim from EditorScreen's one-time engine init.
 * The engine is a process-scoped singleton (EngineHolder): immutable and thread-safe after
 * construction, so it is reused across Activity recreations instead of being re-created and
 * leaked on every configuration change. EngineHolder also handles the AAssetManager-then-
 * extract fallback. Runs on [io] because asset wiring is heavy on the first call.
 */
internal fun loadEditorCatalog(
    app: Context,
    io: CoroutineDispatcher,
): suspend () -> EditorCatalog = {
    withContext(io) {
        val engine = EngineHolder.get(app)
        val profiles = runCatching { engine.listProfiles() }.getOrDefault(emptyList())
        StockCatalog.stocks(app) // warm the catalog cache
        val builtInGroups = runCatching { BuiltInPresets.grouped(app) }.getOrDefault(emptyMap())
        EditorCatalog(engine, profiles, builtInGroups)
    }
}
