/*
 * Spektrafilm for Android — the editor's inbound images: VIEW/SEND intents and the shortcut. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/**
 * Consumes the Activity's pending VIEW/SEND intent and launcher shortcut, and adopts a shared
 * photo once its working copy lands. Effects only; split out of [EditorScreen] (#273).
 */
@Composable
internal fun EditorIncomingImageEffects(
    incomingImageIntent: MutableState<Intent?>,
    launcherShortcut: MutableState<LauncherShortcut?>,
    ctx: Context,
    dispatchers: AppDispatchers,
    sourceMutationGate: LatestSourceMutationGate,
    photoPicker: ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>,
    adoptIncomingImage: (uri: Uri, nameHint: String?, mimeHint: String?, ownedCopy: Uri?) -> Unit,
    setStatus: (String) -> Unit,
) {
    // #162 inbound import: consume the Activity's pending VIEW/SEND exactly once and
    // route it through the same dispatch as a picked document. A rejected shape
    // (non-content scheme, missing stream) surfaces a status instead of adopting.
    val pendingIncoming by incomingImageIntent
    LaunchedEffect(pendingIncoming) {
        val intent = pendingIncoming ?: return@LaunchedEffect
        incomingImageIntent.value = null
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return@LaunchedEffect
        val incoming = incomingImageUri(intent)
        if (incoming == null) {
            setStatus(ctx.getString(R.string.editor_status_shared_image_unsupported))
            return@LaunchedEffect
        }
        if (SharedSourceCopies.grantIsPersistable(intent)) {
            adoptIncomingImage(incoming, null, null, null)
            return@LaunchedEffect
        }
        // #508: a temporary grant dies with the process (update, reboot, background kill), so
        // keep a private working copy while it is still readable and open that instead -- unless
        // the photo is already durably readable (a Library frame under its folder's tree grant),
        // which keeps its own URI and so its recipe. The copy runs in the PROCESS scope and is
        // parked in [SharedSourceCopies.pendingImport]: leaving the editor mid-copy no longer
        // drops the photo, the effect below adopts it on return. The selection is claimed now,
        // so a photo picked while a large copy runs wins and the late copy is discarded.
        val selection = sourceMutationGate.begin()
        val name = incoming.lastPathSegment ?: "raw"
        val app = ctx.applicationContext // outlives this Activity, like the process scope
        val result = app.appContainer.processScope.async(dispatchers.io) {
            val mime = runCatching { app.contentResolver.getType(incoming) }.getOrNull()
            // An Imported-album copy is the app's own file: as durable as a persisted grant.
            val durable = AlbumImports.isCopy(app, incoming.toString()) ||
                PhoneGallery.isReadable(app, incoming.toString()) || runCatching {
                SharedSourceCopies.alreadyDurable(incoming.toString(), AndroidUriGrantBackend(app).persistedReads())
            }.getOrDefault(false)
            // Kinds the editor refuses (video, .mcraw) are never copied: adoption turns them
            // away anyway, and a clip can be gigabytes.
            val refused = detectSourceKind(name, mime).let { it == SourceFileKind.VIDEO || it == SourceFileKind.MCRAW }
            val copy = if (durable || refused) null else SharedSourceCopies.copy(app, incoming, name, mime)
            // Swiping from the copy still walks the folder or Gallery it was opened from.
            copy?.let { PhotoBrowse.rememberOrigin(it.toString(), incoming.toString()) }
            SharedSourceCopies.Imported(source = copy ?: incoming, copied = copy != null, durable = durable, mime = mime)
        }
        val superseded = SharedSourceCopies.pendingImport.getAndUpdate {
            SharedSourceCopies.PendingImport(selection, incoming, name, result)
        }
        // A share that arrives before the previous one was adopted replaces it; nothing will
        // adopt the older copy, so delete it once it lands instead of keeping it as pending.
        if (superseded != null) {
            app.appContainer.processScope.launch(dispatchers.io) {
                val stale = runCatching { superseded.result.await() }.getOrNull() ?: return@launch
                if (stale.copied) SharedSourceCopies.delete(app, stale.source)
            }
        }
    }
    val pendingImport by SharedSourceCopies.pendingImport.collectAsStateWithLifecycle()
    LaunchedEffect(pendingImport) {
        val pending = pendingImport ?: return@LaunchedEffect
        val imported = pending.result.await()
        // Claim it exactly once, even if the effect restarts while adopting.
        if (!SharedSourceCopies.pendingImport.compareAndSet(pending, null)) return@LaunchedEffect
        if (!sourceMutationGate.isCurrent(pending.selection)) {
            // Hand the cleanup to the process scope BEFORE anything can cancel this effect:
            // clearing the slot above re-keys it.
            if (imported.copied) {
                val app = ctx.applicationContext
                app.appContainer.processScope.launch(dispatchers.io) { SharedSourceCopies.delete(app, imported.source) }
            }
            return@LaunchedEffect
        }
        if (!imported.copied && !imported.durable) {
            Diag.w("shared source copy failed; opening it for this session only")
        }
        adoptIncomingImage(
            imported.source,
            pending.name,
            imported.mime,
            imported.source.takeIf { imported.copied },
        )
    }
    // #467: the launcher's "Open photo" shortcut opens the same picker as the Source panel.
    val pendingShortcut by launcherShortcut
    LaunchedEffect(pendingShortcut) {
        if (pendingShortcut != LauncherShortcut.OPEN_PHOTO) return@LaunchedEffect
        launcherShortcut.value = null
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
