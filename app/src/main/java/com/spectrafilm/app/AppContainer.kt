/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Manual dependency injection (#275, docs/adr/0001-manual-di-app-container.md). One
 * AppContainer per process, owned by SpektraApplication, wraps the existing process
 * singletons without changing their ownership or lifetime, and builds every ViewModel
 * through one factory so collaborators arrive as constructor parameters.
 */
package com.spectrafilm.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

/**
 * Background dispatchers for the process-owned singletons (export, checkpoint, source and recipe
 * runtimes) that are created before, and outlive, any [AppContainer] (#277). The only place
 * outside [AppDispatchers] that names a `Dispatchers.*` literal. Main is deliberately absent:
 * reading it on the plain JVM fails, and these singletons are touched by unit tests.
 */
internal object ProcessDispatchers {
    val io: CoroutineDispatcher = Dispatchers.IO
    val default: CoroutineDispatcher = Dispatchers.Default
}

/** Injected dispatchers (#277). Tests pass a StandardTestDispatcher for each. */
internal data class AppDispatchers(
    val io: CoroutineDispatcher = ProcessDispatchers.io,
    val default: CoroutineDispatcher = ProcessDispatchers.default,
    val main: CoroutineDispatcher = Dispatchers.Main,
    val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate,
)

/** The process's [AppDispatchers], for composables (#277). */
@Composable
internal fun rememberAppDispatchers(): AppDispatchers {
    val context = LocalContext.current
    return remember(context) { context.appContainer.dispatchers }
}

internal class AppContainer(context: Context) {
    private val app: Context = context.applicationContext

    val dispatchers = AppDispatchers()

    /**
     * Process-lifetime scope for user-requested writes that must finish even after the screen
     * that started them is gone (a Library paste or sidecar save when its entry is popped).
     */
    val processScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.mainImmediate)

    /** Serializes Library pastes and sidecar saves across every Library entry (see [LibraryViewModel]). */
    private val libraryWriteLock = Mutex()

    /** Serializes album loads and saves across every Library visit (see [AlbumsViewModel]). */
    private val albumSync = AlbumSync()

    /** The settings store (Preferences DataStore since #276). */
    val settings: AppSettings get() = AppSettings.from(app)

    /**
     * The process's single native engine. Heavy on first use; call off the main thread.
     * Ownership stays with [EngineHolder] (docs/JNI_LIFETIME_SAFETY.md): never closed.
     */
    fun engine(): SpektraEngine = EngineHolder.get(app)

    /** Saved presets (#276). */
    val presets: PresetRepository = FilePresetRepository(app)

    /** Per-source recipes (#276). */
    val recipes: RecipeRepository = FileRecipeRepository(app)

    /** Named versions of each photo, keyed like [recipes]. */
    val versions: VersionRepository = FileVersionRepository(java.io.File(app.filesDir, "versions"))
    /** Saved export setups and the one used last. */
    val exportPresets: ExportPresetRepository =
        FileExportPresetRepository(java.io.File(app.filesDir, "export_presets.json"))

    /**
     * The durable source reference and its URI grant (#276). The process-wide runtime that
     * orders every acquire, clear and restore; see docs/adr/0002-storage-boundaries.md for
     * why it stays on SharedPreferences.commit().
     */
    val sources: SourceAccessRuntime get() = sourceAccessRuntime(app)

    /** Builds every app ViewModel from this container. */
    val viewModelFactory: ViewModelProvider.Factory = AppViewModelFactory(
        settingsViewModel = { SettingsViewModel(settings, settings.autoPreviewEdges()) },
        updateViewModel = {
            UpdateViewModel(
                AndroidUpdateBackend(app, dispatchers.io),
                SharedUpdatePrefs.create(app),
            )
        },
        versionsViewModel = { VersionsViewModel(versions, dispatchers.io) },
        exportPresetViewModel = { ExportPresetViewModel(exportPresets, dispatchers.io) },
        stockPreviewViewModel = {
            StockPreviewViewModel(StockPreviewViewModel.engineRenderer(app), dispatchers.default)
        },
        editorViewModel = { EditorViewModel(loadEditorCatalog(app, dispatchers.io)) },
        libraryViewModel = {
            LibraryViewModel(
                LibraryViewModel.androidStore(app, settings),
                LibraryViewModel.androidLister(app),
                dispatchers.io,
                LibraryViewModel.androidEditLookup(app),
                LibraryViewModel.androidLookPaste(recipes),
                LibraryViewModel.androidSidecars(app, recipes),
                writes = processScope,
                writeLock = libraryWriteLock,
                queryStore = LibraryViewModel.androidQueryStore(settings),
                aspectReader = LibraryViewModel.androidAspectReader(app),
            )
        },
        galleryViewModel = {
            GalleryViewModel(
                PhoneGallery.androidLister(app),
                access = { PhoneGallery.access(app) },
                io = dispatchers.io,
                hasEdit = LibraryViewModel.androidEditLookup(app),
            )
        },
        albumsViewModel = {
            AlbumsViewModel(
                FileAlbumStore.of(app),
                dispatchers.io,
                hasEdit = LibraryViewModel.androidEditLookup(app),
                aspectReader = LibraryViewModel.androidAspectReader(app),
                writes = processScope,
                sync = albumSync,
                importer = AlbumImports.androidImporter(app),
                pruneImports = { keep -> AlbumImports.prune(app, keep) },
            )
        },
    )
}

/**
 * One factory for every app ViewModel. ViewModels whose collaborators need Android are
 * built by the given lambdas; the editor state holders need nothing and are built here.
 */
internal class AppViewModelFactory(
    private val settingsViewModel: () -> SettingsViewModel,
    private val updateViewModel: () -> UpdateViewModel,
    private val versionsViewModel: () -> VersionsViewModel = { error("versions not provided") },
    private val exportPresetViewModel: () -> ExportPresetViewModel = { error("export presets not provided") },
    private val stockPreviewViewModel: () -> StockPreviewViewModel = {
        error("stock previews not provided")
    },
    private val libraryViewModel: () -> LibraryViewModel = { error("library not provided") },
    private val albumsViewModel: () -> AlbumsViewModel = { error("albums not provided") },
    private val galleryViewModel: () -> GalleryViewModel = { error("gallery not provided") },
    private val editorViewModel: () -> EditorViewModel = { error("editor not provided") },
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val vm: ViewModel = when (modelClass) {
            SettingsViewModel::class.java -> settingsViewModel()
            UpdateViewModel::class.java -> updateViewModel()
            VersionsViewModel::class.java -> versionsViewModel()
            ExportPresetViewModel::class.java -> exportPresetViewModel()
            StockPreviewViewModel::class.java -> stockPreviewViewModel()
            LibraryViewModel::class.java -> libraryViewModel()
            AlbumsViewModel::class.java -> albumsViewModel()
            GalleryViewModel::class.java -> galleryViewModel()
            EditorViewModel::class.java -> editorViewModel()
            ExportViewModel::class.java -> ExportViewModel()
            PresetViewModel::class.java -> PresetViewModel()
            EditorToolViewModel::class.java -> EditorToolViewModel()
            EditorViewerViewModel::class.java -> EditorViewerViewModel()
            else -> throw IllegalArgumentException("unknown ViewModel $modelClass")
        }
        @Suppress("UNCHECKED_CAST")
        return vm as T
    }
}

/** Owns the process's [AppContainer]. Registered as `android:name` in the manifest. */
class SpektraApplication : Application() {
    internal val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        DebugStrictMode.installIfDebuggable(this)
        // Start the settings read now, in the background, so it has usually finished by the
        // time the activity asks (MAD audit fix 4). Building the store does not block.
        container.settings
    }
}

private val fallbackContainerLock = Any()

// AppContainer only ever holds the application context (see its `app` field), which lives
// as long as the process, so this static reference cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
@Volatile private var fallbackContainer: AppContainer? = null

/**
 * The process's [AppContainer]. Falls back to a process-wide instance when the running
 * Application is not [SpektraApplication] (an instrumentation host that substitutes its own).
 */
internal val Context.appContainer: AppContainer
    get() = (applicationContext as? SpektraApplication)?.container
        ?: fallbackContainer
        ?: synchronized(fallbackContainerLock) {
            fallbackContainer ?: AppContainer(this).also { fallbackContainer = it }
        }
