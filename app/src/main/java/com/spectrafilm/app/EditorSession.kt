/*
 * Spektrafilm for Android — bounded editor-session persistence. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The document stores identifiers and editable model state only. Bitmaps, decoded/native buffers,
 * URI grants, exception text and other process-owned resources are deliberately excluded. The
 * source grant remains authoritative in SourceAccess; this document only lets a recreated editor
 * resume the same cursor, bounded history and UI selection after that grant is reconciled.
 *
 * The JSON codec is in EditorSessionCodec.kt and the durable store and checkpointing in
 * EditorSessionStore.kt (#273).
 */
package com.spectrafilm.app

internal enum class EditorOverlayTool {
    NONE,
    CROP,
    MASK_GEOMETRY,
    MASK_SAMPLE_COLOR,
    MASK_SAMPLE_LUMINANCE,
    WHITE_BALANCE_SAMPLE,
}

/**
 * Tool selection is durable even when a tool's in-progress gesture is not. The Crop tab and
 * MaskGeometryOverlay create a new draft from committed ParamsState/mask geometry when recomposed;
 * reopening either tool is therefore a clean no-op until the user gestures and confirms.
 */
internal fun restorableEditorOverlay(saved: EditorOverlayTool): EditorOverlayTool = saved

internal enum class EditorExportPhase {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILURE,
    CANCELLED,
    RECONCILING,
}

internal data class EditorSourceState(
    val uri: String?,
    val kind: SourceKind,
    val displayName: String,
    val authorizationRequired: Boolean,
)

internal val VALID_EDITOR_ROTATIONS = setOf(0, 90, 180, 270)

/** Small SavedState fallback. A valid versioned session always takes precedence over this value. */
internal data class EditorSavedFallback(
    val source: EditorSourceState?,
    val rotationDegrees: Int,
) {
    companion object {
        val Empty = EditorSavedFallback(source = null, rotationDegrees = 0)
    }
}

/** One authoritative startup value, produced only after SourceAccess reconciliation completes. */
internal data class EditorRestoration(
    val document: EditorSessionDocument?,
    val source: EditorSourceState,
    val rotationDegrees: Int,
    val retainedSessionCursor: Boolean,
    val usedSavedFallback: Boolean,
)

private fun demoEditorSource() = EditorSourceState(
    uri = null,
    kind = SourceKind.DEMO,
    displayName = "synthetic demo image",
    authorizationRequired = false,
)

private fun PersistedSourceRef.toEditorSource(authorizationRequired: Boolean): EditorSourceState =
    EditorSourceState(
        uri = uri,
        kind = SourceKind.valueOf(kind),
        displayName = displayName,
        authorizationRequired = authorizationRequired,
    )

/**
 * Reconcile all restoration inputs before EditorScreen enters composition. Durable SourceAccess
 * identity is authoritative; a complete session supplies the cursor only for that exact subject.
 * SavedState is a last-resort, identity-checked fallback and can never override a valid session.
 */
internal fun reconcileEditorRestoration(
    session: EditorSessionDocument?,
    sourceRestore: SourceRestoreResult,
    savedFallback: EditorSavedFallback,
): EditorRestoration {
    val reconciledSessionSource = session?.source?.let { saved ->
        when (sourceRestore) {
            SourceRestoreResult.Demo -> if (saved.kind == SourceKind.DEMO) demoEditorSource() else null
            is SourceRestoreResult.Ready -> sourceRestore.ref
                .takeIf { saved.uri == it.uri && saved.kind.name == it.kind }
                ?.toEditorSource(authorizationRequired = false)
            is SourceRestoreResult.NeedsAuthorization -> sourceRestore.ref
                .takeIf { saved.uri == it.uri && saved.kind.name == it.kind }
                ?.toEditorSource(authorizationRequired = true)
            SourceRestoreResult.None,
            is SourceRestoreResult.Invalid,
            -> if (saved.kind == SourceKind.DEMO) {
                demoEditorSource()
            } else {
                saved.copy(authorizationRequired = true)
            }
        }
    }
    if (session != null && reconciledSessionSource != null) {
        val reconciled = session.copy(source = reconciledSessionSource)
        return EditorRestoration(
            document = reconciled,
            source = reconciledSessionSource,
            rotationDegrees = reconciled.current.rotationDegrees,
            retainedSessionCursor = true,
            usedSavedFallback = false,
        )
    }

    val fallbackSource = savedFallback.source?.let { saved ->
        when (sourceRestore) {
            SourceRestoreResult.Demo -> if (saved.kind == SourceKind.DEMO) demoEditorSource() else null
            is SourceRestoreResult.Ready -> sourceRestore.ref
                .takeIf { saved.uri == it.uri && saved.kind.name == it.kind }
                ?.toEditorSource(authorizationRequired = false)
            is SourceRestoreResult.NeedsAuthorization -> sourceRestore.ref
                .takeIf { saved.uri == it.uri && saved.kind.name == it.kind }
                ?.toEditorSource(authorizationRequired = true)
            SourceRestoreResult.None,
            is SourceRestoreResult.Invalid,
            -> if (saved.kind == SourceKind.DEMO) {
                demoEditorSource()
            } else {
                saved.copy(authorizationRequired = true)
            }
        }
    }
    if (fallbackSource != null && savedFallback.rotationDegrees in VALID_EDITOR_ROTATIONS) {
        return EditorRestoration(
            document = null,
            source = fallbackSource,
            rotationDegrees = savedFallback.rotationDegrees,
            retainedSessionCursor = false,
            usedSavedFallback = true,
        )
    }

    val authoritativeSource = when (sourceRestore) {
        SourceRestoreResult.Demo -> demoEditorSource()
        is SourceRestoreResult.Ready -> sourceRestore.ref.toEditorSource(authorizationRequired = false)
        is SourceRestoreResult.NeedsAuthorization ->
            sourceRestore.ref.toEditorSource(authorizationRequired = true)
        SourceRestoreResult.None,
        is SourceRestoreResult.Invalid,
        -> demoEditorSource()
    }
    return EditorRestoration(
        document = null,
        source = authoritativeSource,
        rotationDegrees = 0,
        retainedSessionCursor = false,
        usedSavedFallback = false,
    )
}

/** Stable selection even when the editor has no masks; otherwise always a valid list index. */
internal fun clampSelectedMaskIndex(index: Int, maskCount: Int): Int = when {
    maskCount <= 0 -> 0
    else -> index.coerceIn(0, maskCount - 1)
}

/** Whether a durable grant reconciliation still names the session's exact editing subject. */
internal fun shouldRetainEditorCursor(
    saved: EditorSourceState,
    restored: SourceRestoreResult,
): Boolean = when (restored) {
    SourceRestoreResult.Demo -> saved.kind == SourceKind.DEMO
    is SourceRestoreResult.Ready ->
        saved.uri == restored.ref.uri && saved.kind.name == restored.ref.kind
    is SourceRestoreResult.NeedsAuthorization ->
        saved.uri == restored.ref.uri && saved.kind.name == restored.ref.kind
    // No durable capability is expected for a transient picker after process death. Preserve the
    // edits and require reauthorization instead of silently switching to the demo subject.
    SourceRestoreResult.None,
    is SourceRestoreResult.Invalid,
    -> true
}

internal data class EditorToolState(
    val category: Category?,
    val overlay: EditorOverlayTool,
    val maskIndex: Int,
)

internal data class EditorExportState(
    val sheetOpen: Boolean,
    val options: ExportOptions,
    val keepGps: Boolean,
    val phase: EditorExportPhase,
    val runtimeRunId: Long?,
)

/**
 * Whether the checkpointed export sheet should be on screen after a restore.
 *
 * The sheet is journalled so an Activity recreation — a rotation, a theme change, a
 * configuration change mid-export — does not throw away what the user was configuring. A COLD
 * start is different: reopening a modal the user last saw hours ago hides the photo behind a
 * sheet they did not open, which reads as "the editor rendered nothing". So on a cold start the
 * sheet returns only when an export is actually live and the user needs to see its progress.
 */
internal fun exportSheetVisibleAtRestore(
    checkpointed: Boolean,
    phase: EditorExportPhase,
    coldStart: Boolean,
): Boolean = checkpointed && (!coldStart || phase != EditorExportPhase.IDLE)

/** A process-owned export cannot still be running when its exact run id is absent after restore. */
internal fun reconcileRestoredExport(
    saved: EditorExportState,
    activeRuntimeRunId: Long?,
): EditorExportState = when {
    // A checkpointed RUNNING export whose run the process runtime no longer carries (or whose
    // id changed) is orphaned: its durable MediaStore transaction is reconciled by recovery,
    // never blindly retried by a recreated UI.
    saved.phase == EditorExportPhase.RUNNING && saved.runtimeRunId != activeRuntimeRunId ->
        saved.copy(phase = EditorExportPhase.RECONCILING, runtimeRunId = null, sheetOpen = false)
    // Ticket #139 adoption: a run the runtime is still executing for THIS editor's
    // publication-authorized source identity is the editor's own export. The recreated
    // cursor resumes it instead of restarting at the checkpointed pre-launch phase.
    saved.phase != EditorExportPhase.RUNNING && activeRuntimeRunId != null ->
        saved.copy(phase = EditorExportPhase.RUNNING, runtimeRunId = activeRuntimeRunId)
    else -> saved
}

internal data class EditorPresetState(
    val baseJson: String?,
    val fullJson: String?,
    val amount: Float,
    val clipboardJson: String?,
    val selectedPreset: String,
    val presetName: String,
)

internal data class EditorSessionDocument(
    val source: EditorSourceState,
    val current: EditSnapshot,
    val committed: EditSnapshot?,
    val history: EditHistoryState,
    val tool: EditorToolState,
    val preset: EditorPresetState,
    val export: EditorExportState,
)

/** Compare a live/restored cursor to an oracle captured before that editor was allowed to launch. */
internal fun verifyCompleteEditorCursor(
    actual: EditorSessionDocument,
    oracle: EditorSessionDocument,
    expectedPhase: EditorExportPhase,
    expectedRuntimeRunId: Long? = null,
) {
    val runtimeRunId = if (expectedPhase == EditorExportPhase.RUNNING) {
        requireNotNull(expectedRuntimeRunId) { "RUNNING cursor oracle requires an exact run id" }
            .also { require(it > 0L) { "cursor oracle run id must be positive" } }
    } else {
        require(expectedRuntimeRunId == null) { "only RUNNING cursor oracle may carry a run id" }
        null
    }
    check(actual.source == oracle.source) { "restored source differs from pre-launch oracle" }
    check(actual.current == oracle.current) { "restored current snapshot differs from pre-launch oracle" }
    check(actual.committed == oracle.committed) {
        "restored committed snapshot differs from pre-launch oracle"
    }
    check(actual.history.undo == oracle.history.undo) {
        "restored undo branch differs from pre-launch oracle " +
            "(actualCount=${actual.history.undo.size}, oracleCount=${oracle.history.undo.size}, " +
            "actualRotations=${actual.history.undo.map(EditSnapshot::rotationDegrees)}, " +
            "oracleRotations=${oracle.history.undo.map(EditSnapshot::rotationDegrees)}, " +
            "actualKinds=${actual.history.undo.map { snapshot ->
                when {
                    snapshot == oracle.current -> "CURRENT"
                    snapshot in oracle.history.undo -> "UNDO"
                    snapshot in oracle.history.redo -> "REDO"
                    else -> "OTHER"
                }
            }})"
    }
    check(actual.history.redo == oracle.history.redo) {
        "restored redo branch differs from pre-launch oracle"
    }
    check(actual.tool == oracle.tool) { "restored tool cursor differs from pre-launch oracle" }
    check(actual.preset == oracle.preset) { "restored preset cursor differs from pre-launch oracle" }
    val expectedExport = oracle.export.copy(
        phase = expectedPhase,
        runtimeRunId = runtimeRunId,
    )
    check(actual.export == expectedExport) {
        "restored export cursor differs from pre-launch oracle " +
            "(actual=${actual.export}, expected=$expectedExport)"
    }
}

/** Catalog compatibility is external to the JSON schema and must cover every restorable branch. */
internal fun EditorSessionDocument.referencesUnavailableProfiles(
    availableProfiles: Set<String>,
): Boolean {
    if (availableProfiles.isEmpty()) return false
    val snapshots = buildList {
        add(current.paramsJson)
        committed?.paramsJson?.let(::add)
        history.undo.forEach { add(it.paramsJson) }
        history.redo.forEach { add(it.paramsJson) }
        preset.baseJson?.let(::add)
        preset.fullJson?.let(::add)
        preset.clipboardJson?.let(::add)
    }
    return snapshots.any { json ->
        runCatching {
            val params = ParamsState()
            Presets.decode(AtomicJsonStore.parseObject(json), params)
            params.filmProfile !in availableProfiles || params.printProfile !in availableProfiles
        }.getOrDefault(true)
    }
}
