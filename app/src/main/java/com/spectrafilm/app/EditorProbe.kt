/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import com.spectrafilm.app.masks.MaskComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only surface app and editor code use to talk to the ticket #139 release probe (#469).
 *
 * The probe ships in the release APK on purpose: `ReleaseCandidateSmokeInstrumentation` drives
 * the exact minified candidate through [Ticket139EditorTestBridge] (docs/EDITOR_SESSION.md), so
 * it must not move to a debug/androidTest source set or become a no-op. It is default-off: every
 * publish returns at once and every request flow stays silent until the instrumentation arms it.
 *
 * Production code calls [editorProbe] and never [Ticket139EditorProbe] directly, so the probe's
 * internals (oracles, verifiers, the bridge-facing API) stay out of the editor.
 */
internal interface EditorProbe {
    /** True only while the instrumentation has armed the probe. */
    fun isArmed(): Boolean

    // --- App host (AppRoot) ---
    val navigationRequests: StateFlow<Ticket139EditorProbe.NavigationRequest>
    fun onHostCreated()
    fun publishDestination(destination: Screen)
    fun publishCheckpoint()

    // --- Editor composition (EditorScreen) ---
    val sourceProbeRequests: Flow<Ticket139EditorProbe.SourceProbeRequest>
    val exportProbeRequests: Flow<Ticket139EditorProbe.ExportProbeRequest>
    val previewCompletionRequests: Flow<Ticket139EditorProbe.PreviewCompletionRequest>
    fun beginLiveEditorInstance(): Long
    fun publishLiveEditorSnapshot(instance: Long, document: EditorSessionDocument)
    fun publishLiveEditorReady(instance: Long, document: EditorSessionDocument)
    fun publishSourceRetirement()
    fun publishExportProbeResult(sequence: Long, sheetOpen: Boolean)
    fun sourceProbeUri(label: String): String
    fun sourceProbeLabel(uri: String?): String
    fun publishProbeSource(label: String)

    // --- Preview render ---
    fun publishLivePreview(label: String)
    fun publishPreviewDecision(label: String, claimed: Boolean)
    fun publishPreviewCleanup(label: String)

    /** Holds probe source "A"'s completed preview before publication; false when unarmed. */
    suspend fun awaitCompletedPreviewBeforePublication(label: String): Boolean

    // --- Tool overlays ---
    fun publishCropDraftInitialized(
        bitmapWidth: Int,
        bitmapHeight: Int,
        initialCrop: Boolean,
        initialCenter: Pair<Float, Float>,
        initialSize: Pair<Float, Float>,
        actualLeft: Float,
        actualTop: Float,
        actualRight: Float,
        actualBottom: Float,
    )

    fun publishMaskDraftInitialized(
        bitmapWidth: Int,
        bitmapHeight: Int,
        committed: MaskComponent,
        actual: MaskComponent,
    )
}

/** The process-wide probe. Default-off; see [EditorProbe]. */
internal val editorProbe: EditorProbe get() = Ticket139EditorProbe
