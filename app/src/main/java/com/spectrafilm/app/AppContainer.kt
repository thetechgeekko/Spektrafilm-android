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

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Injected dispatchers. Tests pass a StandardTestDispatcher for all three. */
internal data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)

internal class AppContainer(context: Context) {
    private val app: Context = context.applicationContext

    val dispatchers = AppDispatchers()

    /** The settings store (SharedPreferences-backed today; #276 moves it to DataStore). */
    val settings: AppSettings get() = AppSettings.from(app)

    /**
     * The process's single native engine. Heavy on first use; call off the main thread.
     * Ownership stays with [EngineHolder] (docs/JNI_LIFETIME_SAFETY.md): never closed.
     */
    fun engine(): SpektraEngine = EngineHolder.get(app)

    /** Builds every app ViewModel from this container. */
    val viewModelFactory: ViewModelProvider.Factory = AppViewModelFactory(
        settingsViewModel = { SettingsViewModel(settings, settings.autoPreviewEdges()) },
        updateViewModel = {
            UpdateViewModel(
                AndroidUpdateBackend(app),
                SharedUpdatePrefs(app.getSharedPreferences(SharedUpdatePrefs.FILE, Context.MODE_PRIVATE)),
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
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val vm: ViewModel = when (modelClass) {
            SettingsViewModel::class.java -> settingsViewModel()
            UpdateViewModel::class.java -> updateViewModel()
            ExportViewModel::class.java -> ExportViewModel()
            PresetViewModel::class.java -> PresetViewModel()
            EditorToolViewModel::class.java -> EditorToolViewModel()
            else -> throw IllegalArgumentException("unknown ViewModel $modelClass")
        }
        @Suppress("UNCHECKED_CAST")
        return vm as T
    }
}

/** Owns the process's [AppContainer]. Registered as `android:name` in the manifest. */
class SpektraApplication : Application() {
    internal val container: AppContainer by lazy { AppContainer(this) }
}

private val fallbackContainerLock = Any()

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
