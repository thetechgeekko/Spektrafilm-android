/*
 * Spektrafilm for Android — update ViewModel and its Android seams. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * [UpdateViewModel] owns one [UpdateController] (UpdateController.kt) for the activity;
 * this file holds the SharedPreferences and network/package-manager implementations of
 * its two seams.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** SharedPreferences-backed [UpdatePrefs]; its own file, never in the backup allowlist. */
internal class SharedUpdatePrefs(private val prefs: SharedPreferences) : UpdatePrefs {
    override var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(v) { prefs.edit().putBoolean(KEY_AUTO, v).apply() }
    override var lastAutoCheckMillis: Long
        get() = prefs.getLong(KEY_LAST, 0L)
        set(v) { prefs.edit().putLong(KEY_LAST, v).apply() }
    override var skippedTag: String?
        get() = prefs.getString(KEY_SKIPPED, null)
        set(v) { prefs.edit().putString(KEY_SKIPPED, v).apply() }

    companion object {
        const val FILE = "updater_state_v1"

        /** The app's update state, in its own private file. */
        fun create(context: Context): SharedUpdatePrefs =
            SharedUpdatePrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST = "last_auto_check_ms"
        private const val KEY_SKIPPED = "skipped_tag"
    }
}

/** The real network / package-manager side of [UpdateBackend]. */
internal class AndroidUpdateBackend(
    context: Context,
    private val io: CoroutineDispatcher,
) : UpdateBackend {
    private val app = context.applicationContext
    private val downloader = UpdateDownloader(File(app.noBackupFilesDir, "updater"), io)

    override fun currentVersion(): String? = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull()

    override fun canSelfUpdate(): Boolean = !UpdateInstaller.installedFromPlay(app)

    override suspend fun fetchLatest(currentVersion: String): UpdateInfo? =
        AppUpdater.checkForUpdate(app, io)

    override suspend fun download(tag: String, asset: ApkAsset, onProgress: (Long) -> Unit) =
        downloader.download(tag, asset, onProgress)

    override suspend fun verify(apk: File, tag: String) = UpdateInstaller.verify(app, apk, tag, io)

    override suspend fun install(apk: File) = UpdateInstaller.install(app, apk, io)

    override fun openRelease(info: UpdateInfo) = AppUpdater.openRelease(app, info)

    override fun clearDownloads() = downloader.clear()

    override val installEvents: StateFlow<InstallEvent?> = UpdateInstaller.events

    override fun consumeInstallEvent() = UpdateInstaller.consumeEvent()
}

internal class UpdateViewModel(backend: UpdateBackend, prefs: UpdatePrefs) : ViewModel() {
    private val controller = UpdateController(viewModelScope, backend, prefs)
    val uiState: StateFlow<UpdateUiState> = controller.state

    fun onAction(action: UpdateAction) = controller.onAction(action)
}
