/*
 * Spektrafilm for Android — editor source adoption: the picked / opened photo becomes the
 * editor's source, or a failed selection is reconciled with the durable one. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273). EditorScreen builds one per composition, as the
 * local functions were, and keeps one-line wrappers with the same names. Editor values are
 * read and written through their state holders, so launched work sees them live.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class EditorSourceAdoption(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val session: EditorSession,
    private val sourceRuntime: SourceAccessRuntime,
    private val sourceState: State<EditorSourceState>,
    private val sourceUriState: State<Uri?>,
    private val sourceKindState: State<SourceKind>,
    rotationState: MutableState<SourceRotation>,
    statusState: MutableState<String>,
    previewTickState: MutableIntState,
    activeCategoryState: MutableState<Category?>,
    private val snackbarHost: SnackbarHostState,
    private val retireSourceResources: () -> Unit,
    private val resetEditorCursorForSourceChange: () -> Unit,
    private val checkpointEditorSession: () -> Boolean,
) {
    private val sourceAccess = sourceRuntime.coordinator
    private val sourceMutationGate = sourceRuntime.mutations
    private val sourceUri: Uri? get() = sourceUriState.value
    private val sourceKind: SourceKind get() = sourceKindState.value
    private var rotation by rotationState
    private var status by statusState
    private var previewTick by previewTickState
    private var activeCategory by activeCategoryState

    private fun visibleSourceState() = sourceState.value

    fun reconcileVisibleSourceAfterFailure(durable: SourceRestoreResult?) {
        when (durable) {
            SourceRestoreResult.Demo -> {
                if (sourceUri != null || sourceKind != SourceKind.DEMO) {
                    retireSourceResources()
                    resetEditorCursorForSourceChange()
                }
                session.showDemo(ctx.getString(R.string.editor_source_demo_name))
                rotation = SourceRotation.NONE
                status = ctx.getString(R.string.editor_status_demo_image)
            }
            is SourceRestoreResult.Ready -> {
                val switched = !shouldRetainEditorCursor(visibleSourceState(), durable)
                if (switched) {
                    retireSourceResources()
                    resetEditorCursorForSourceChange()
                }
                session.showSource(
                    uri = durable.ref.uri,
                    kind = SourceKind.valueOf(durable.ref.kind),
                    displayName = durable.ref.displayName,
                    authorizationRequired = false,
                )
                if (switched) rotation = SourceRotation.NONE
                status = ctx.getString(R.string.editor_status_selection_failed_restored)
            }
            is SourceRestoreResult.NeedsAuthorization -> {
                val switched = !shouldRetainEditorCursor(visibleSourceState(), durable)
                retireSourceResources()
                if (switched) {
                    resetEditorCursorForSourceChange()
                }
                session.showSource(
                    uri = durable.ref.uri,
                    kind = SourceKind.valueOf(durable.ref.kind),
                    displayName = durable.ref.displayName,
                    authorizationRequired = true,
                )
                if (switched) rotation = SourceRotation.NONE
                status = ctx.getString(R.string.editor_status_selection_failed_auth)
            }
            SourceRestoreResult.None -> {
                if (sourceUri != null && sourceKind != SourceKind.DEMO) {
                    retireSourceResources()
                    session.requireSourceAuthorization()
                    status = ctx.getString(R.string.editor_status_selection_failed_auth)
                } else {
                    session.showDemo(ctx.getString(R.string.editor_source_demo_name))
                    rotation = SourceRotation.NONE
                    status = ctx.getString(R.string.editor_status_source_unavailable_demo)
                }
            }
            is SourceRestoreResult.Invalid, null -> {
                if (session.requireAuthorizationIfRealSource()) retireSourceResources()
                status = ctx.getString(R.string.editor_status_source_state_unavailable)
            }
        }
        previewTick++
    }

    fun adoptSource(
        uri: Uri,
        kind: SourceKind,
        displayName: String,
        readyStatus: String,
        probeLabel: String? = null,
        ownedCopy: Uri? = null,
    ) {
        val selectionGeneration = sourceMutationGate.begin()
        // The mutation starts in the process-owned runtime before this Activity awaits it.
        // Activity recreation may cancel the waiter, but it cannot drop the grant/store write.
        val pendingAcquire = sourceRuntime.submitReconciled(selectionGeneration) {
            sourceAccess.acquire(
                uri.toString(),
                kind.name,
                AtomicJsonStore.truncateUtf16Safely(displayName, 512),
            )
        }
        // #508: the working copy belongs to this acquisition from here on, not to the Activity.
        // Once the queued mutation settles -- applied, skipped by a newer selection, or rejected --
        // the process scope releases it; [SharedSourceCopies.delete] keeps the file only when it
        // became the durable source, so a skipped or failed adoption never leaks a copy.
        if (ownedCopy != null) {
            val app = ctx.applicationContext
            pendingAcquire.invokeOnCompletion {
                app.appContainer.processScope.launch(dispatchers.io) { SharedSourceCopies.delete(app, ownedCopy) }
            }
        }
        scope.launch {
            val outcome = pendingAcquire.await() ?: return@launch
            if (!sourceMutationGate.isCurrent(selectionGeneration)) return@launch
            when (outcome) {
                is ReconciledSourceMutation.Applied -> {
                    val ref = outcome.value
                    val switched = sourceUri?.toString() != uri.toString() || sourceKind != kind
                    if (switched) {
                        retireSourceResources()
                        resetEditorCursorForSourceChange()
                    }
                    session.showSource(uri.toString(), kind, displayName)
                    if (switched) rotation = SourceRotation.NONE
                    // A new photo opens Film & look: picking a stock is the next step for
                    // almost everyone, so it should not cost a tap on the category bar.
                    if (switched) activeCategory = CATEGORY_AFTER_NEW_PHOTO
                    // A private working copy (#508) is as durable as a persisted grant.
                    status = if (
                        ref.accessMode == SourceAccessMode.PERSISTED ||
                        SharedSourceCopies.isCopy(ctx, ref.uri) ||
                        AlbumImports.isCopy(ctx, ref.uri) ||
                        PhoneGallery.isReadable(ctx, ref.uri)
                    ) {
                        readyStatus
                    } else {
                        ctx.getString(R.string.editor_status_access_temporary, readyStatus)
                    }
                    previewTick++
                    probeLabel?.let(editorProbe::publishProbeSource)
                    // One working copy at most (#508): drop any that is no longer the source.
                    launch(dispatchers.io) { SharedSourceCopies.prune(ctx) }
                }
                is ReconciledSourceMutation.Rejected -> {
                    Diag.w("source adoption failed: ${outcome.failure.message}")
                    reconcileVisibleSourceAfterFailure(outcome.durableState)
                    snackbarHost.currentSnackbarData?.dismiss()
                    snackbarHost.showSnackbar(
                        ctx.getString(R.string.editor_snack_source_open_failed),
                        withDismissAction = true,
                    )
                }
            }
            checkpointEditorSession()
        }
    }

    private fun refuseUnsupported(statusRes: Int, snackRes: Int) {
        status = ctx.getString(statusRes)
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(ctx.getString(snackRes))
        }
    }
    fun adoptIncomingImage(uri: Uri, nameHint: String? = null, mimeHint: String? = null, ownedCopy: Uri? = null) {
        val name = nameHint ?: uri.lastPathSegment ?: "raw"
        val mime = mimeHint ?: runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        val kind = detectSourceKind(name, mime)
        if (ownedCopy != null && (kind == SourceFileKind.MCRAW || kind == SourceFileKind.VIDEO)) {
            // Never copied on purpose, but a refused kind must not keep a copy either (#508).
            val app = ctx.applicationContext
            app.appContainer.processScope.launch(dispatchers.io) { SharedSourceCopies.delete(app, ownedCopy) }
        }
        when (kind) {
            // MotionCam RAW-video container: recognized (see McrawContainer /
            // docs/RESEARCH_MCRAW.md) but native frame decode isn't wired yet, so don't set
            // a RAW source that would fail — tell the user instead.
            SourceFileKind.MCRAW -> refuseUnsupported(
                R.string.editor_status_mcraw_unsupported,
                R.string.editor_snack_mcraw_coming,
            )
            // A motion clip. The engine simulates stills today, so refuse it the same honest
            // way as .mcraw rather than failing deep inside the RAW decoder.
            SourceFileKind.VIDEO -> refuseUnsupported(
                R.string.editor_status_video_unsupported,
                R.string.editor_snack_video_coming,
            )
            // A JPEG/HEIC chosen via the RAW document picker: process it on the normal photo
            // path rather than forcing it through LibRaw (which would fail, then fall back to
            // a lossy display-referred decode).
            SourceFileKind.PHOTO -> adoptSource(
                uri,
                SourceKind.PHOTO,
                name.substringAfterLast('/'),
                ctx.getString(R.string.editor_status_photo_selected),
                ownedCopy = ownedCopy,
            )
            // RAW/DNG, and every ambiguous content URI (e.g. MIUI document IDs with no
            // extension), so a genuine DNG is never misrouted.
            SourceFileKind.RAW -> adoptSource(
                uri,
                SourceKind.RAW,
                ctx.getString(R.string.editor_source_raw_name, name.substringAfterLast('/')),
                ctx.getString(R.string.editor_status_raw_selected),
                ownedCopy = ownedCopy,
            )
        }
    }
}
