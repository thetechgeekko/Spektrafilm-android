/*
 * Spektrafilm for Android — the permission asks around an editor export. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope

/** What [rememberEditorExportPermissions] hands back to the editor. */
internal class EditorExportPermissions(
    /** WRITE_EXTERNAL_STORAGE on API <= 28, asked when the export sheet opens. */
    val legacyStoragePermission: ManagedActivityResultLauncher<String, Boolean>,
    private val mediaLocationMissing: () -> Boolean,
    private val requestMediaLocation: () -> Unit,
    private val askNotificationsThen: (() -> Unit) -> Unit,
) {
    /** Whether a GPS-keeping export of this source still lacks ACCESS_MEDIA_LOCATION. */
    fun mediaLocationPermissionMissing(): Boolean = mediaLocationMissing()

    /** Asks ACCESS_MEDIA_LOCATION when a GPS-keeping export of a MediaStore photo needs it. */
    fun requestMediaLocationIfMissing() = requestMediaLocation()

    /** Runs [export], first asking for notifications if this is the first export to need them. */
    fun exportAskingNotificationsOnce(export: () -> Unit) = askNotificationsThen(export)
}

/**
 * The permission launchers an editor export needs. Split out of [EditorScreen] (#273);
 * [currentSourceUri] is read when a permission is checked, not when this is composed.
 */
@Composable
internal fun rememberEditorExportPermissions(
    ctx: Context,
    exportVm: ExportViewModel,
    settings: AppSettings,
    currentSourceUri: () -> Uri?,
    scope: CoroutineScope,
    snackbarHost: SnackbarHostState,
    setStatus: (String) -> Unit,
): EditorExportPermissions {
    // ACCESS_MEDIA_LOCATION (API 29+): only asked for when "Include location (GPS)" is on
    // and the source is a MediaStore photo, because that is the one case where Android
    // redacts the GPS bytes from the stream (#261). Denial is not a block: the export
    // proceeds and simply carries no GPS, exactly as it did before the permission existed.
    val mediaLocationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            setStatus(ctx.getString(R.string.editor_status_media_location_denied))
        }
    }
    fun mediaLocationPermissionMissing(): Boolean {
        val sourceUri = currentSourceUri()
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            exportVm.current.keepGps &&
            requiresMediaLocationPermissionForGpsExport(SharedSourceCopies.originOf(sourceUri) ?: sourceUri) &&
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
    }
    fun requestMediaLocationIfMissing() {
        if (mediaLocationPermissionMissing()) {
            runCatching {
                mediaLocationPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }
    }
    // The export runs under a foreground service, and on API 33+ that service's
    // ongoing notification is silently suppressed unless POST_NOTIFICATIONS is
    // granted. The manifest has declared the permission since the service landed,
    // but nothing ever requested it — so on a real install the export notification
    // never appeared at all. Asked ONCE, by the first Export tap itself, and never
    // blocking: the service's kill-resistance (oom_score_adj 50 vs 700) works
    // whether or not the notification can be drawn.
    //
    // It used to be asked every time the Export sheet opened. That re-prompted on every
    // open while the permission stayed off, and the dialog arrived just as the user
    // reached for Export, so it swallowed that tap. Now the tap raises the dialog and the
    // export starts from its answer, granted or not: the tap is never lost, and the render
    // still starts only after the dialog has gone, so it never competes with a system
    // dialog for /top-app (§15.1).
    fun notificationPermissionMissing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    // The export waiting on the answer. Not saved: if the Activity is recreated while the
    // dialog is up, the user taps Export again and, already asked, it starts at once.
    val exportAfterNotificationAnswer = remember { arrayOfNulls<() -> Unit>(1) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Granted or denied, the export proceeds either way.
        val pending = exportAfterNotificationAnswer[0]
        exportAfterNotificationAnswer[0] = null
        pending?.invoke()
    }
    /**
     * Runs [export], first asking for notifications if this is the first export to need them.
     * Every export that runs under the foreground service goes through it: the sheet's Export,
     * and batch and half-frame pair before their pickers open (Codex on #564).
     */
    fun exportAskingNotificationsOnce(export: () -> Unit) {
        if (!notificationPermissionMissing() || settings.askedNotificationPermission) {
            export()
            return
        }
        settings.askedNotificationPermission = true
        exportAfterNotificationAnswer[0] = export
        val launched = runCatching {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }.isSuccess
        if (!launched) {
            exportAfterNotificationAnswer[0] = null
            export()
        }
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            setStatus(ctx.getString(R.string.editor_status_storage_permission_required))
            // The export sheet may still be open on API <= 28; close it so the message
            // is not hidden behind it.
            exportVm.onAction(ExportAction.CloseSheet)
            scope.showEditorMessage(snackbarHost, ctx.getString(R.string.editor_toast_storage_permission))
        }
    }
    return EditorExportPermissions(
        legacyStoragePermission,
        ::mediaLocationPermissionMissing,
        ::requestMediaLocationIfMissing,
        ::exportAskingNotificationsOnce,
    )
}
