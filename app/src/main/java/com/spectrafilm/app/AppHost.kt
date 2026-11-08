/*
 * Spektrafilm for Android — what the app's composables need from the hosting Activity. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * #273: no composable takes MainActivity. AppRoot (and through it the editor) receives this
 * plain holder instead: the Activity-scoped ViewModels, the inbound-intent states, the
 * keyboard-shortcut and drag-and-drop hooks, and the Activity's lifecycle and back dispatcher.
 * MainActivity builds one with [appHost] and keeps the Activity callbacks themselves.
 */
package com.spectrafilm.app

import android.content.Intent
import android.view.DragAndDropPermissions
import android.view.DragEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

internal class AppHost(
    /** Injected coroutine dispatchers (#277). */
    val dispatchers: AppDispatchers,
    private val viewModels: () -> ViewModelProvider,
    /** The hosting Activity's lifecycle (ON_STOP checkpoints the editor session). */
    val lifecycle: Lifecycle,
    /** The hosting Activity's back dispatcher (the editor's Esc shortcut). */
    val onBackPressedDispatcher: OnBackPressedDispatcher,
    /** Read access for a photo dragged in from another app (ImageDrop.kt). */
    val requestDragAndDropPermissions: (DragEvent) -> DragAndDropPermissions?,
) {
    private inline fun <reified T : ViewModel> vm(): Lazy<T> = lazy { viewModels().get(T::class.java) }
    private inline fun <reified T : ViewModel> vm(key: String): Lazy<T> = lazy { viewModels().get(key, T::class.java) }

    /** Editing state and the engine/profile catalog for the editor (audit A2). */
    val editorVm: EditorViewModel by vm()

    /** Export sheet + export phase state for the editor (#272). Activity-scoped. */
    val exportVm: ExportViewModel by vm()

    /** Saved export presets for the export sheet. */
    val exportPresetVm: ExportPresetViewModel by vm()

    /** Viewer modes: compare, histogram, hidden chrome (#272). */
    val viewerVm: EditorViewerViewModel by vm()

    /** On-photo tool overlays and mask selection for the editor (#272). */
    val toolVm: EditorToolViewModel by vm()

    /** Preset panel, amount blend and settings clipboard state for the editor (#272). */
    val presetVm: PresetViewModel by vm()

    /** Previews of the photo through each film stock / print paper, one set per browser. */
    val filmPreviewVm: StockPreviewViewModel by vm("film")
    val printPreviewVm: StockPreviewViewModel by vm("print")

    /** Previews of the photo through each built-in look (the Look stage strip). */
    val presetPreviewVm: StockPreviewViewModel by vm("presets")

    /** Named versions of the open photo. */
    val versionsVm: VersionsViewModel by vm()

    /** App settings and the update check, read by the themed root. */
    val settingsVm: SettingsViewModel by vm()
    val updateVm: UpdateViewModel by vm()

    /**
     * #162 inbound import: the latest unconsumed VIEW/SEND intent. Compose state so the
     * live editor observes delivery through onNewIntent (singleTask) as well as a cold
     * start; consumed exactly once by the editor's LaunchedEffect.
     */
    val incomingImageIntent: MutableState<Intent?> = mutableStateOf(null)

    /**
     * #467: a launcher shortcut waiting to be acted on. AppRoot takes LIBRARY (opens the
     * Library); OPEN_PHOTO pops to the editor, which consumes it by opening its photo picker.
     * Set from the launching intent like [incomingImageIntent], never on Activity recreation.
     */
    val launcherShortcut: MutableState<LauncherShortcut?> = mutableStateOf(null)

    /**
     * Photos shared in several at once (SEND_MULTIPLE), waiting for the Library to import them
     * into its Imported album. AppRoot opens the Library; the Library takes and clears it.
     */
    val incomingShareBatch: MutableState<List<String>?> = mutableStateOf(null)

    /**
     * Hardware keyboard shortcuts (DeX, ChromeOS, keyboard covers). Set by the live editor
     * composition and cleared when it leaves, so a shortcut can never act on an editor that
     * is not on screen. Returns true when the shortcut was handled.
     */
    var editorShortcutHandler: ((EditorShortcut) -> Boolean)? = null

    /**
     * Read access to photos dragged in from other apps (ImageDrop.kt). Never released early:
     * export re-reads the source at full resolution long after the drop, and adoption is
     * asynchronous and may refuse a new drop, in which case the PREVIOUS dropped photo stays
     * the source and still needs its grant. The platform revokes them all with the Activity;
     * a session holds one small token per drop.
     */
    val heldDropPermissions = mutableListOf<DragAndDropPermissions>()
}

/** The [AppHost] for this Activity: its ViewModel store, lifecycle and back dispatcher. */
internal fun ComponentActivity.appHost(): AppHost = AppHost(
    dispatchers = appContainer.dispatchers,
    viewModels = { ViewModelProvider(this, appContainer.viewModelFactory) },
    lifecycle = lifecycle,
    onBackPressedDispatcher = onBackPressedDispatcher,
    requestDragAndDropPermissions = { requestDragAndDropPermissions(it) },
)
