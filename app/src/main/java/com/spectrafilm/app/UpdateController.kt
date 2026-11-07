/*
 * Spektrafilm for Android — update check + "What's new" state machine. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * One state holder for the whole update flow, used by the launch-time auto check and by
 * Settings' "Check for updates":
 *
 *   check -> newer release? -> "What's new" dialog (release notes)
 *         -> Update: download -> verify (UpdateVerifier) -> system install confirmation
 *         -> Later / Skip this version / Open release page
 *
 * The auto check runs at most once a day, never re-prompts for a version the user chose
 * to skip, and can be switched off in Settings. A copy installed by Google Play is never
 * auto-checked or self-updated (Play policy); a manual check there only links to the
 * release page.
 *
 * [UpdateController] holds the logic against two small seams ([UpdateBackend],
 * [UpdatePrefs]) so it runs on the plain JVM in tests; [UpdateViewModel] owns one for
 * the activity.
 */
package com.spectrafilm.app

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, FAILED }

internal enum class UpdateProblem { DOWNLOAD_FAILED, SIGNER_MISMATCH, VERIFY_FAILED, INSTALL_FAILED }

internal sealed interface UpdateStage {
    data object Offer : UpdateStage
    /** [fraction] in 0..1. */
    data class Downloading(val fraction: Float) : UpdateStage
    data object Verifying : UpdateStage
    /** Handed to the system installer; waiting for the user to confirm there. */
    data object Installing : UpdateStage
    data class Failed(val problem: UpdateProblem) : UpdateStage
}

internal data class UpdateOffer(
    val info: UpdateInfo,
    val stage: UpdateStage = UpdateStage.Offer,
    /** False when the release has no APK, or Play installed this copy: link out instead. */
    val canInstallInApp: Boolean,
)

internal data class UpdateUiState(
    val autoCheck: Boolean = true,
    val status: UpdateStatus = UpdateStatus.IDLE,
    val currentVersion: String? = null,
    val latestTag: String? = null,
    /** Non-null while the "What's new" dialog is showing. */
    val offer: UpdateOffer? = null,
)

internal sealed interface UpdateAction {
    /** Launch-time check; a no-op unless enabled and due. */
    data object AutoCheck : UpdateAction
    /** Settings button; always checks and always shows a newer release. */
    data object CheckNow : UpdateAction
    data class SetAutoCheck(val enabled: Boolean) : UpdateAction
    /** Re-open the dialog for a release already found (Settings status row). */
    data object ShowOffer : UpdateAction
    data object Install : UpdateAction
    data object OpenReleasePage : UpdateAction
    data object Later : UpdateAction
    data object SkipVersion : UpdateAction
}

internal interface UpdatePrefs {
    var autoCheck: Boolean
    var lastAutoCheckMillis: Long
    var skippedTag: String?
}

internal interface UpdateBackend {
    fun currentVersion(): String?
    /** False for a Play-installed copy. */
    fun canSelfUpdate(): Boolean
    suspend fun fetchLatest(currentVersion: String): UpdateInfo?
    suspend fun download(tag: String, asset: ApkAsset, onProgress: (Long) -> Unit): DownloadResult
    suspend fun verify(apk: File, tag: String): VerifyFailure?
    suspend fun install(apk: File): Boolean
    fun openRelease(info: UpdateInfo)
    fun clearDownloads()
    val installEvents: StateFlow<InstallEvent?>
    fun consumeInstallEvent()
}

internal object UpdateCheckPolicy {
    const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Due when enabled and a day has passed (or the clock went backwards). */
    fun autoCheckDue(enabled: Boolean, nowMillis: Long, lastMillis: Long): Boolean =
        enabled && (lastMillis <= 0L || nowMillis < lastMillis || nowMillis - lastMillis >= AUTO_INTERVAL_MS)

    /** An automatic check prompts only for a newer release the user has not skipped. */
    fun autoPrompts(info: UpdateInfo, skippedTag: String?): Boolean =
        info.isNewer && info.latestTag != skippedTag
}

internal class UpdateController(
    private val scope: CoroutineScope,
    private val backend: UpdateBackend,
    private val prefs: UpdatePrefs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(
        UpdateUiState(autoCheck = prefs.autoCheck, currentVersion = backend.currentVersion()),
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var installJob: Job? = null
    /** Last newer release found, so Settings can re-open the dialog without a new request. */
    private var latest: UpdateInfo? = null

    init {
        // A new controller means a new activity host: no download is in flight, so anything
        // left in the download directory (an installed update, an abandoned one) is garbage.
        backend.clearDownloads()
        scope.launch {
            backend.installEvents.filterNotNull().collect { event ->
                backend.consumeInstallEvent()
                setStage(
                    when (event) {
                        InstallEvent.Cancelled -> UpdateStage.Offer
                        is InstallEvent.Failed -> UpdateStage.Failed(UpdateProblem.INSTALL_FAILED)
                    },
                )
            }
        }
    }

    fun onAction(action: UpdateAction) {
        when (action) {
            UpdateAction.AutoCheck -> autoCheck()
            UpdateAction.CheckNow -> check(manual = true)
            is UpdateAction.SetAutoCheck -> {
                prefs.autoCheck = action.enabled
                _state.update { it.copy(autoCheck = action.enabled) }
            }
            UpdateAction.ShowOffer -> latest?.let { showOffer(it) }
            UpdateAction.Install -> install()
            UpdateAction.OpenReleasePage -> {
                _state.value.offer?.info?.let(backend::openRelease)
                dismiss()
            }
            UpdateAction.Later -> dismiss()
            UpdateAction.SkipVersion -> {
                _state.value.offer?.info?.let { prefs.skippedTag = it.latestTag }
                dismiss()
            }
        }
    }

    private fun autoCheck() {
        if (checkJob?.isActive == true || _state.value.offer != null) return
        // Play updates its own installs; the app must not nag a Play user toward GitHub.
        if (!backend.canSelfUpdate()) return
        val now = clock()
        if (!UpdateCheckPolicy.autoCheckDue(prefs.autoCheck, now, prefs.lastAutoCheckMillis)) return
        prefs.lastAutoCheckMillis = now
        check(manual = false)
    }

    private fun check(manual: Boolean) {
        if (checkJob?.isActive == true || installJob?.isActive == true) return
        val current = backend.currentVersion()
        if (current == null) {
            _state.update { it.copy(status = UpdateStatus.FAILED) }
            return
        }
        _state.update { it.copy(status = UpdateStatus.CHECKING) }
        checkJob = scope.launch {
            val info = backend.fetchLatest(current)
            when {
                info == null -> _state.update { it.copy(status = UpdateStatus.FAILED) }
                !info.isNewer -> {
                    latest = null
                    _state.update {
                        it.copy(status = UpdateStatus.UP_TO_DATE, latestTag = info.latestTag)
                    }
                }
                else -> {
                    latest = info
                    _state.update {
                        it.copy(status = UpdateStatus.AVAILABLE, latestTag = info.latestTag)
                    }
                    if (manual || UpdateCheckPolicy.autoPrompts(info, prefs.skippedTag)) showOffer(info)
                }
            }
        }
    }

    private fun showOffer(info: UpdateInfo) {
        if (_state.value.offer != null) return
        _state.update {
            it.copy(
                offer = UpdateOffer(
                    info = info,
                    canInstallInApp = info.apk != null && backend.canSelfUpdate(),
                ),
            )
        }
    }

    private fun install() {
        val offer = _state.value.offer ?: return
        val asset = offer.info.apk
        if (!offer.canInstallInApp || asset == null || installJob?.isActive == true) return
        if (offer.stage is UpdateStage.Installing) return
        val tag = offer.info.latestTag
        setStage(UpdateStage.Downloading(0f))
        installJob = scope.launch {
            val download = backend.download(tag, asset) { done ->
                val fraction = (done.toDouble() / asset.sizeBytes).toFloat().coerceIn(0f, 1f)
                val stage = _state.value.offer?.stage
                // Throttle to whole percents so a 21 MB download is ~100 state updates.
                if (stage is UpdateStage.Downloading && (fraction * 100).toInt() != (stage.fraction * 100).toInt()) {
                    setStage(UpdateStage.Downloading(fraction))
                }
            }
            val file = (download as? DownloadResult.Ok)?.file
            if (file == null) {
                setStage(UpdateStage.Failed(UpdateProblem.DOWNLOAD_FAILED))
                return@launch
            }
            setStage(UpdateStage.Verifying)
            when (backend.verify(file, tag)) {
                null -> Unit
                VerifyFailure.WRONG_SIGNER -> {
                    backend.clearDownloads()
                    setStage(UpdateStage.Failed(UpdateProblem.SIGNER_MISMATCH))
                    return@launch
                }
                else -> {
                    backend.clearDownloads()
                    setStage(UpdateStage.Failed(UpdateProblem.VERIFY_FAILED))
                    return@launch
                }
            }
            setStage(UpdateStage.Installing)
            if (!backend.install(file)) setStage(UpdateStage.Failed(UpdateProblem.INSTALL_FAILED))
        }
    }

    private fun dismiss() {
        installJob?.cancel()
        installJob = null
        // A download in flight is cancelled; whatever was written is removed. An APK already
        // handed to the installer stays readable until the session copied it (it already has).
        backend.clearDownloads()
        _state.update { it.copy(offer = null) }
    }

    private fun setStage(stage: UpdateStage) {
        _state.update { state -> state.copy(offer = state.offer?.copy(stage = stage)) }
    }
}

