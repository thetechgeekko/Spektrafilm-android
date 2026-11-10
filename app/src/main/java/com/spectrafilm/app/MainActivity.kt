/*
 * Spektrafilm for Android — app entry. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The Activity: edge-to-edge window setup, crash handler, export-journal recovery, inbound
 * VIEW/SEND intents and the themed setContent around [AppRoot]. The editor itself lives in
 * EditorScreen.kt, navigation in AppRoot.kt, decoding in SourceDecoding.kt (#273).
 */
package com.spectrafilm.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Everything the composables need from this Activity (AppHost.kt, #273). */
    private val host: AppHost by lazy { appHost() }

    /** Routes a launching intent: a multi-photo share to the Library, anything else to the editor. */
    private fun deliver(intent: Intent?) {
        val batch = AlbumImports.sharedUris(intent)
        if (batch.isNotEmpty()) {
            host.incomingShareBatch.value = batch.map(Uri::toString)
        } else {
            host.incomingImageIntent.value = intent
        }
    }

    /** Lists the editor's shortcuts in the system keyboard-shortcuts helper (Meta+/). */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<android.view.KeyboardShortcutGroup>,
        menu: android.view.Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        if (host.editorShortcutHandler != null) data += editorKeyboardShortcutGroup(resources)
    }

    /**
     * Only reached for keys the focused view did NOT consume, so a text field keeps its own
     * Ctrl+Z and typed characters. Dialogs and sheets are separate windows and never get here.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val shortcut = editorShortcutFor(event)
        if (shortcut != null && host.editorShortcutHandler?.invoke(shortcut) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliver(intent)
        LauncherShortcut.fromAction(intent.action)?.let { host.launcherShortcut.value = it }
    }

    /**
     * Settings writes are queued to DataStore (#276). Wait briefly for them here, where
     * Android used to drain SharedPreferences.apply(), so a setting changed just before the
     * app is backgrounded and killed is not lost.
     *
     * This runs on the main thread, so the wait is short: it returns at once when nothing is
     * queued (the normal case), a normal DataStore write lands in a few ms, and a write still
     * running when the wait gives up keeps going on the store's own scope -- only a process
     * killed in that same instant could lose it. A longer wait only froze the UI on a stalled
     * disk without making the write any more likely to finish.
     */
    override fun onStop() {
        super.onStop()
        if (!appContainer.settings.flush(ON_STOP_FLUSH_TIMEOUT_MS)) {
            Diag.w("settings still writing after ${ON_STOP_FLUSH_TIMEOUT_MS} ms at onStop; continuing in background")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Launch screen (MAD audit fix 4): the theme's splash stays up until saved settings
        // are read, so the first frame already has the user's theme and nothing reads
        // settings on the main thread before they are loaded.
        val settings = appContainer.settings
        installSplashScreen().setKeepOnScreenCondition { !settings.isLoaded }
        enableEdgeToEdge()
        // Debug builds: Compose crashes carry the composable call stack, not only framework
        // frames (an applier crash otherwise names no app code at all).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            @OptIn(androidx.compose.runtime.ExperimentalComposeRuntimeApi::class)
            androidx.compose.runtime.Composer.setDiagnosticStackTraceEnabled(true)
        }
        super.onCreate(savedInstanceState)
        // A recreated Activity still holds a multi-photo share; importing it again is a no-op
        // (an album never lists a photo twice), but there is no reason to start it.
        if (savedInstanceState == null || intent?.action != Intent.ACTION_SEND_MULTIPLE) deliver(intent)
        // Only on a fresh launch: a recreated Activity still holds the shortcut intent, and
        // reopening the picker or the Library on every rotation would be wrong.
        if (savedInstanceState == null) host.launcherShortcut.value = LauncherShortcut.fromAction(intent?.action)
        // Wide-gamut compositing so the color-managed preview bitmaps (tagged Adobe/ProPhoto/Rec2020
        // per the output space) are not clamped to sRGB at composition on wide-gamut panels. No-op on
        // sRGB displays. API 26+; minSdk is 24.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        // Persist the last fatal stack trace so the in-app Diagnostics screen can show it
        // after a restart (no permission needed; chains to the platform handler).
        Diagnostics.installCrashHandler(this)
        // Register synchronously: even if this lifecycle coroutine is canceled before dispatch,
        // a replacement Activity cannot advance the prior-process stage-cleanup cutoff.
        val priorProcessStageCutoffMillis = registerExportProcessStageCutoff(
            System.currentTimeMillis(),
        )
        // Reconcile export journal entries left by process death before a new export can start.
        // Once-per-process guarding prevents Activity recreation from racing a live transaction.
        lifecycleScope.launch(appContainer.dispatchers.io) {
            runCatching {
                recoverPendingMediaStoreExportsOnce(
                    applicationContext,
                    priorProcessStageCutoffMillis,
                )
            }
                .onSuccess { report ->
                    if (
                        report != null &&
                        (report.examined > 0 || report.removedAbandonedStages > 0 || report.retainedAbandonedStages > 0)
                    ) {
                        Diag.i(
                            "export recovery examined=${report.examined} removed=${report.removedPending} " +
                                "retired=${report.retiredCommittedOrMissing} retry=${report.retainedForRetry} " +
                                "stagesRemoved=${report.removedAbandonedStages} " +
                                "stagesRetry=${report.retainedAbandonedStages}",
                        )
                    }
                }
                .onFailure { Diag.w("export recovery failed: ${it.message}") }
        }
        setContent {
            // Nothing below may read settings before they are loaded (that read would block
            // the main thread). The splash covers this wait, normally a few milliseconds.
            val loaded by produceState(settings.isLoaded) {
                settings.awaitLoaded()
                value = true
            }
            if (loaded) AppContent(host, settings)
        }
    }
}

/**
 * Longest the main thread waits in onStop for queued settings writes. Was 2 s (the store's
 * default), which could freeze the screen on a stalled disk while backgrounding.
 */
private const val ON_STOP_FLUSH_TIMEOUT_MS = 250L
