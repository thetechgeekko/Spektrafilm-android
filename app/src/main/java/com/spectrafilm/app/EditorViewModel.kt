/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * The editor's own ViewModel (audit A2). It owns two things that used to be `remember`ed
 * inside EditorScreen:
 *
 *  - the per-composition [EditorSession]: editing state [ParamsState], the visible photo
 *    ([EditorSourceState], one immutable value changed only through session transitions),
 *    manual rotation, the undo/redo history and its settle bookkeeping, and
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
import androidx.compose.runtime.State
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
import org.json.JSONObject

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
            source = restoration?.source ?: EditorSession.NO_SOURCE,
        ).also { session = it }
}

/**
 * Editing state that lives exactly as long as one editor composition. Each field was a separate
 * `remember` in EditorScreen with the same initial value; they are Compose state holders, so the
 * screen and the helpers it passes them to observe them unchanged.
 */
@Stable
internal class EditorSession(
    document: EditorSessionDocument?,
    rotationDegrees: Int,
    source: EditorSourceState = NO_SOURCE,
) {
    /** Live editing parameters, Settings defaults until the checkpoint is decoded. */
    val params = ParamsState()

    private val sourceState = mutableStateOf(source)

    private val appliedCatalogState = mutableStateOf<EditorCatalog?>(null)

    /**
     * The catalog this session has applied ([adoptCatalog]); null until then. The screen reads
     * the engine, profile ids and built-in looks from here instead of keeping its own copies,
     * and treats non-null as "catalog ready".
     */
    val appliedCatalog: State<EditorCatalog?> get() = appliedCatalogState

    /**
     * Publishes [catalog] to the screen. Called right after [applyCatalog] in the same
     * main-thread step, so nothing can render with the engine before the restore is decoded.
     */
    fun adoptCatalog(catalog: EditorCatalog) {
        appliedCatalogState.value = catalog
    }

    /**
     * The photo the editor shows, as one immutable value (#452): uri, kind, display name and
     * whether access must be re-granted before it can render. Read-only here; it changes only
     * through [showSource], [showDemo] and the two authorization transitions below, so the four
     * fields can never be observed half-updated.
     */
    val source: State<EditorSourceState> get() = sourceState

    /** Shows [uri] as the editor's photo. */
    fun showSource(
        uri: String,
        kind: SourceKind,
        displayName: String,
        authorizationRequired: Boolean = false,
    ) {
        sourceState.value = EditorSourceState(uri, kind, displayName, authorizationRequired)
    }

    /** Shows the built-in demo image ([displayName] is its localized label). */
    fun showDemo(displayName: String) {
        sourceState.value = EditorSourceState(null, SourceKind.DEMO, displayName, false)
    }

    /** The current photo's access was lost (a render or restore hit a permission failure). */
    fun requireSourceAuthorization() {
        sourceState.value = sourceState.value.copy(authorizationRequired = true)
    }

    /**
     * Source state could not be confirmed: a real photo now needs re-authorization, the demo
     * never does. Returns whether authorization is now required.
     */
    fun requireAuthorizationIfRealSource(): Boolean {
        val current = sourceState.value
        val required = current.uri != null && current.kind != SourceKind.DEMO
        sourceState.value = current.copy(authorizationRequired = required)
        return required
    }

    companion object {
        /** Placeholder before the first composition (and in tests): the demo, unlabelled. */
        val NO_SOURCE = EditorSourceState(null, SourceKind.DEMO, "", false)
    }

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

    /**
     * The CURRENT live editing state (+ manual rotation) as a snapshot. Session decode
     * canonicalizes embedded Params JSON, so the live cursor uses the same compact canonical
     * form; comparing a pretty producer string with a compact restored string would
     * manufacture an undo entry during the first restore settle.
     */
    fun snapshot(): EditSnapshot =
        EditSnapshot(Presets.encode(params).toString(), rotation.value.degrees)

    /**
     * Restores [snap] into the live state: decodes params (shared preset schema), re-applies
     * rotation and adopts it as committed. Sets [restoring] so the next settle does not
     * record this mutation as a new edit. The caller re-renders (previewTick).
     */
    fun restore(snap: EditSnapshot) {
        restoring.value = true
        runCatching { Presets.decode(JSONObject(snap.paramsJson), params) }
        rotation.value = SourceRotation.fromDegrees(snap.rotationDegrees)
        committedSnapshot.value = snap
    }

    /** One undo step: restores and returns the target, or null when there is nothing to undo. */
    fun undo(): EditSnapshot? = history.undo(snapshot())?.also(::restore)

    /** One redo step: restores and returns the target, or null when there is nothing to redo. */
    fun redo(): EditSnapshot? = history.redo(snapshot())?.also(::restore)

    /**
     * The catalog half of the restore, run once per composition when the catalog arrives:
     * applies Settings defaults ([applyDefaults]) to the untouched params, falls back to the
     * first installed profile, snapshots that as the FRESH DEFAULTS (reset and dirty checks
     * compare against it, so it must be taken before any checkpoint decode), then decodes
     * [restored], the session document already matched to the editor's source, if any.
     *
     * A catalog update can remove a profile named by an older session. The source and valid
     * edits are kept, a real installed profile is used, and history is re-baselined instead
     * of leaving an undo branch that cannot render ([CatalogRestore.rebaselined]).
     */
    fun applyCatalog(
        profiles: List<String>,
        applyDefaults: (ParamsState, List<String>) -> Unit,
        restored: EditorSessionDocument?,
    ): CatalogRestore {
        applyDefaults(params, profiles)
        if (profiles.isNotEmpty()) {
            if (params.filmProfile !in profiles) params.filmProfile = profiles.first()
            if (params.printProfile !in profiles) params.printProfile = profiles.first()
        }
        val freshDefaults = Presets.toJsonString(params)
        var rebaselined = false
        if (restored != null) {
            val removedProfileReference = restored.referencesUnavailableProfiles(profiles.toSet())
            Presets.decode(AtomicJsonStore.parseObject(restored.current.paramsJson), params)
            var migratedProfile = false
            if (profiles.isNotEmpty() && params.filmProfile !in profiles) {
                params.filmProfile = profiles.first()
                migratedProfile = true
            }
            if (profiles.isNotEmpty() && params.printProfile !in profiles) {
                params.printProfile = profiles.first()
                migratedProfile = true
            }
            if (migratedProfile || removedProfileReference) {
                history.clear()
                committedSnapshot.value = null
                rebaselined = true
            }
        }
        return CatalogRestore(freshDefaults, restored != null, rebaselined)
    }

    /**
     * The capture effect's settle step ([settleDecision], unit-tested in EditHistoryTest):
     * pushes the previous committed snapshot when the settled state is a real edit, adopts the
     * settled state and clears [restoring]. Returns true when an undo step was pushed.
     */
    fun settle(): Boolean {
        val action = settleDecision(restoring.value, committedSnapshot.value, snapshot())
        val pushed = action.push?.also(history::push) != null
        committedSnapshot.value = action.committed
        restoring.value = false
        return pushed
    }
}

/** What [EditorSession.applyCatalog] did. */
internal data class CatalogRestore(
    /** Params JSON after Settings defaults and BEFORE the checkpoint decode. */
    val freshDefaults: String,
    /** True when a session document was decoded into the params. */
    val restored: Boolean,
    /** True when a missing profile forced history to be cleared (the editor tells the user). */
    val rebaselined: Boolean,
)

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
