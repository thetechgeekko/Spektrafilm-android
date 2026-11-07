/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * The editor's own ViewModel (audit A2). It owns two things that used to be `remember`ed
 * inside EditorScreen:
 *
 *  - the per-composition [EditorSession]: editing state [ParamsState], manual rotation, the
 *    undo/redo history and its settle bookkeeping, and
 *  - the editor catalog (native engine, installed profile ids, built-in looks), loaded once
 *    per ViewModel in [viewModelScope] instead of once per editor composition.
 *
 * Behaviour is deliberately unchanged in this slice. The session document is still the only
 * restoration authority: every editor composition calls [beginSession] once and gets a FRESH
 * [EditorSession] seeded from the reconciled checkpoint, exactly like the old individual
 * `remember { ... }` initializers. That matters because the
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
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

    /** The session of the current editor composition (a placeholder until the first one). */
    var session: EditorSession = EditorSession(document = null, rotationDegrees = 0)
        private set

    /** The editing state of the current editor composition. */
    val params: ParamsState get() = session.params

    /**
     * Starts a new editor composition and returns its session: default editing state (the
     * checkpoint's params are decoded into it later, once the catalog is applied), and the
     * rotation, history and committed snapshot taken from [restoration]. Call exactly once per
     * composition (inside `remember`); see the file header for why nothing is carried over.
     */
    fun beginSession(restoration: EditorRestoration? = null): EditorSession =
        EditorSession(
            document = restoration?.document,
            rotationDegrees = restoration?.rotationDegrees ?: 0,
        ).also { session = it }
}

/**
 * Editing state that lives exactly as long as one editor composition. Each field was a separate
 * `remember` in EditorScreen with the same initial value; they are Compose state holders, so the
 * screen and the helpers it passes them to observe them unchanged.
 */
@Stable
internal class EditorSession(document: EditorSessionDocument?, rotationDegrees: Int) {
    /** Live editing parameters, Settings defaults until the checkpoint is decoded. */
    val params = ParamsState()

    /**
     * The user's MANUAL rotation step (applied to the decoded image for preview and export).
     * The EXIF baseline is derived fresh per load and is not persisted in the recipe.
     */
    val rotation: MutableState<SourceRotation> =
        mutableStateOf(SourceRotation.fromDegrees(rotationDegrees))

    /** In-session undo/redo of settled snapshots (params JSON + manual rotation). */
    val history = EditHistory().also { history ->
        document?.history?.let(history::restoreState)
    }

    /**
     * The last SETTLED snapshot committed to [history]. Capture coalescing pushes this
     * pre-edit state onto undo when a newer settled state differs, so one slider drag
     * (which settles once) is one undo step.
     */
    val committedSnapshot: MutableState<EditSnapshot?> = mutableStateOf(document?.committed)

    /**
     * True while a snapshot is being restored programmatically (undo/redo/reset), so the
     * capture effect skips one settle and does not record the restore as a new edit.
     */
    val restoring: MutableState<Boolean> = mutableStateOf(false)
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
