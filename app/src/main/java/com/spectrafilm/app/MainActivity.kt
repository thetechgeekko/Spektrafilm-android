/*
 * Spektrafilm for Android — app entry. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The Activity: edge-to-edge window setup, crash handler, export-journal recovery, inbound
 * VIEW/SEND intents and the themed setContent around [AppRoot]. The editor itself lives in
 * EditorScreen.kt, navigation in AppRoot.kt, decoding in SourceDecoding.kt (#273).
 */
package com.spectrafilm.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Which kind of source image is loaded. */

/** #286: how long the open stand-in fades into the first render, and stays under it. */

class MainActivity : ComponentActivity() {
    /** Injected coroutine dispatchers (#277); no `Dispatchers.*` literal in this file. */
    internal val dispatchers: AppDispatchers by lazy { appContainer.dispatchers }

    /** Export sheet + export phase state for the editor (#272). Activity-scoped. */
    internal val exportVm: ExportViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)[ExportViewModel::class.java]
    }

    /** Saved export presets for the export sheet. */
    internal val exportPresetVm: ExportPresetViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)[ExportPresetViewModel::class.java]
    }

    /** Viewer modes: compare, histogram, hidden chrome (#272). */
    internal val viewerVm: EditorViewerViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)[EditorViewerViewModel::class.java]
    }

    /** On-photo tool overlays and mask selection for the editor (#272). */
    internal val toolVm: EditorToolViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)[EditorToolViewModel::class.java]
    }

    /** Preset panel, amount blend and settings clipboard state for the editor (#272). */
    internal val presetVm: PresetViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)[PresetViewModel::class.java]
    }

    /** Previews of the photo through each film stock / print paper, one set per browser. */
    internal val filmPreviewVm: StockPreviewViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)["film", StockPreviewViewModel::class.java]
    }
    internal val printPreviewVm: StockPreviewViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)["print", StockPreviewViewModel::class.java]
    }

    /** Named versions of the open photo. */
    internal val libraryVm: LibraryViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)[LibraryViewModel::class.java]
    }
    internal val versionsVm: VersionsViewModel by lazy {
        ViewModelProvider(this, appContainer.viewModelFactory)[VersionsViewModel::class.java]
    }

    /**
     * #162 inbound import: the latest unconsumed VIEW/SEND intent. Compose state so the
     * live editor observes delivery through onNewIntent (singleTask) as well as a cold
     * start; consumed exactly once by the editor's LaunchedEffect.
     */
    internal val incomingImageIntent = androidx.compose.runtime.mutableStateOf<Intent?>(null)

    /**
     * Hardware keyboard shortcuts (DeX, ChromeOS, keyboard covers). Set by the live editor
     * composition and cleared when it leaves, so a shortcut can never act on an editor that
     * is not on screen. Returns true when the shortcut was handled.
     */
    internal var editorShortcutHandler: ((EditorShortcut) -> Boolean)? = null

    /**
     * Read access to photos dragged in from other apps (ImageDrop.kt). Never released early:
     * export re-reads the source at full resolution long after the drop, and adoption is
     * asynchronous and may refuse a new drop, in which case the PREVIOUS dropped photo stays
     * the source and still needs its grant. The platform revokes them all with the Activity;
     * a session holds one small token per drop.
     */
    internal val heldDropPermissions = mutableListOf<android.view.DragAndDropPermissions>()

    /**
     * Only reached for keys the focused view did NOT consume, so a text field keeps its own
     * Ctrl+Z and typed characters. Dialogs and sheets are separate windows and never get here.
     */
    /** Lists the editor's shortcuts in the system keyboard-shortcuts helper (Meta+/). */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<android.view.KeyboardShortcutGroup>,
        menu: android.view.Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        if (editorShortcutHandler != null) data += editorKeyboardShortcutGroup(resources)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val shortcut = editorShortcutFor(event)
        if (shortcut != null && editorShortcutHandler?.invoke(shortcut) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingImageIntent.value = intent
    }

    /**
     * Settings writes are queued to DataStore (#276). Wait briefly for them here, where
     * Android used to drain SharedPreferences.apply(), so a setting changed just before the
     * app is backgrounded and killed is not lost.
     */
    override fun onStop() {
        super.onStop()
        appContainer.settings.flush()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        incomingImageIntent.value = intent
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
        lifecycleScope.launch(dispatchers.io) {
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
        val settings = appContainer.settings
        val settingsVm = ViewModelProvider(this, appContainer.viewModelFactory)[SettingsViewModel::class.java]
        val updateVm = ViewModelProvider(this, appContainer.viewModelFactory)[UpdateViewModel::class.java]
        setContent {
            // Theme follows the saved settings state, so a Settings edit re-themes at once.
            val settingsUi by settingsVm.uiState.collectAsStateWithLifecycle()
            val dark = when (settingsUi.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val updateUi by updateVm.uiState.collectAsStateWithLifecycle()
            // Launch-time "what's new" check: at most daily, off in Settings, never on Play.
            LaunchedEffect(Unit) { updateVm.onAction(UpdateAction.AutoCheck) }
            SpektraTheme(dark = dark, dynamicColor = settingsUi.dynamicColor) {
                AppRoot(
                    settings = settings,
                    settingsUi = settingsUi,
                    onSettingsAction = settingsVm::onAction,
                    updateUi = updateUi,
                    onUpdateAction = updateVm::onAction,
                )
                updateUi.offer?.let { offer -> UpdateDialog(offer, updateVm::onAction) }
            }
        }
    }

    /** Hosts onboarding + top-level navigation around the editor. */
}

/** Small helpers kept at file scope. */

/** Average RGB (0..1) of a small LinearImage crop — the WB eyedropper's sampled neutral. */

@Composable
internal fun localConfigurationHeightDp(): Int =
    androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

internal fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.then(
    Modifier.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
)

/**
 * #162: the one accepted inbound shape — a content:// image from VIEW (data) or SEND
 * (EXTRA_STREAM). Anything else (file:// paths, missing streams, foreign actions) is null
 * and never adopted; the decode route itself stays content-sniffed fail-closed (#190).
 */
internal fun incomingImageUri(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
    else -> null
}?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
