/*
 * Spektrafilm for Android — update flow state machine. GPLv3.
 * Runs UpdateController against a fake backend on an unconfined scope, so every
 * launched step completes before onAction returns.
 */
package com.spectrafilm.app

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private val apk = ApkAsset(
        name = "Spektrafilm-v0.12.0.apk",
        downloadUrl = AppUpdater.apkDownloadUrl("v0.12.0"),
        sizeBytes = 100,
        sha256 = null,
    )
    private val newer = UpdateInfo(
        latestTag = "v0.12.0",
        currentVersion = "0.11.0",
        releaseUrl = "https://github.com/thetechgeekko/Spektrafilm-android/releases/tag/v0.12.0",
        isNewer = true,
        notes = "## Highlights",
        apk = apk,
    )

    private class Prefs : UpdatePrefs {
        override var autoCheck = true
        override var lastAutoCheckMillis = 0L
        override var skippedTag: String? = null
    }

    private class Backend(var latest: UpdateInfo?) : UpdateBackend {
        var selfUpdate = true
        var downloadOk = true
        var verifyResult: VerifyFailure? = null
        var installOk = true
        val fetches = mutableListOf<String>()
        val installed = mutableListOf<File>()
        val opened = mutableListOf<UpdateInfo>()
        var clears = 0
        val events = MutableStateFlow<InstallEvent?>(null)

        override fun currentVersion() = "0.11.0"
        override fun canSelfUpdate() = selfUpdate
        override suspend fun fetchLatest(currentVersion: String): UpdateInfo? {
            fetches += currentVersion
            return latest
        }
        override suspend fun download(tag: String, asset: ApkAsset, onProgress: (Long) -> Unit): DownloadResult {
            onProgress(50); onProgress(asset.sizeBytes)
            return if (downloadOk) DownloadResult.Ok(File("/tmp/${asset.name}")) else DownloadResult.Failed
        }
        override suspend fun verify(apk: File, tag: String) = verifyResult
        override suspend fun install(apk: File): Boolean {
            installed += apk
            return installOk
        }
        override fun openRelease(info: UpdateInfo) { opened += info }
        override fun clearDownloads() { clears++ }
        override val installEvents: StateFlow<InstallEvent?> = events
        override fun consumeInstallEvent() { events.value = null }
    }

    private var now = 1_000_000_000L
    private fun controller(backend: Backend, prefs: UpdatePrefs = Prefs()) =
        UpdateController(scope, backend, prefs, clock = { now })

    @Test
    fun autoCheckPromptsForANewerReleaseWithItsNotes() {
        val c = controller(Backend(newer))
        c.onAction(UpdateAction.AutoCheck)
        val offer = c.state.value.offer
        assertNotNull(offer)
        assertEquals("## Highlights", offer!!.info.notes)
        assertTrue(offer.canInstallInApp)
        assertEquals(UpdateStatus.AVAILABLE, c.state.value.status)
    }

    @Test
    fun autoCheckRunsAtMostDailyAndNotWhenSwitchedOff() {
        val backend = Backend(newer)
        val prefs = Prefs()
        val c = controller(backend, prefs)
        c.onAction(UpdateAction.AutoCheck)
        c.onAction(UpdateAction.Later)
        now += UpdateCheckPolicy.AUTO_INTERVAL_MS - 1
        c.onAction(UpdateAction.AutoCheck)
        assertEquals(1, backend.fetches.size)
        now += 1
        c.onAction(UpdateAction.AutoCheck)
        assertEquals(2, backend.fetches.size)

        c.onAction(UpdateAction.Later)
        c.onAction(UpdateAction.SetAutoCheck(false))
        assertFalse(prefs.autoCheck)
        now += UpdateCheckPolicy.AUTO_INTERVAL_MS
        c.onAction(UpdateAction.AutoCheck)
        assertEquals(2, backend.fetches.size)
        // The manual check is never gated.
        c.onAction(UpdateAction.CheckNow)
        assertEquals(3, backend.fetches.size)
    }

    @Test
    fun aSkippedVersionIsNotAutoPromptedButAManualCheckStillShowsIt() {
        val prefs = Prefs()
        val c = controller(Backend(newer), prefs)
        c.onAction(UpdateAction.CheckNow)
        c.onAction(UpdateAction.SkipVersion)
        assertEquals("v0.12.0", prefs.skippedTag)
        assertNull(c.state.value.offer)

        now += UpdateCheckPolicy.AUTO_INTERVAL_MS
        c.onAction(UpdateAction.AutoCheck)
        assertNull(c.state.value.offer)
        assertEquals(UpdateStatus.AVAILABLE, c.state.value.status)
        c.onAction(UpdateAction.ShowOffer)
        assertNotNull(c.state.value.offer)
        c.onAction(UpdateAction.Later)
        c.onAction(UpdateAction.CheckNow)
        assertNotNull(c.state.value.offer)
    }

    @Test
    fun upToDateAndFailedChecksNeverOpenTheDialog() {
        val backend = Backend(newer.copy(latestTag = "v0.11.0", isNewer = false))
        val c = controller(backend)
        c.onAction(UpdateAction.CheckNow)
        assertEquals(UpdateStatus.UP_TO_DATE, c.state.value.status)
        assertNull(c.state.value.offer)
        backend.latest = null
        c.onAction(UpdateAction.CheckNow)
        assertEquals(UpdateStatus.FAILED, c.state.value.status)
        assertNull(c.state.value.offer)
    }

    @Test
    fun updateDownloadsVerifiesThenHandsToTheInstaller() {
        val backend = Backend(newer)
        val c = controller(backend)
        c.onAction(UpdateAction.CheckNow)
        c.onAction(UpdateAction.Install)
        assertEquals(UpdateStage.Installing, c.state.value.offer!!.stage)
        assertEquals(listOf(File("/tmp/Spektrafilm-v0.12.0.apk")), backend.installed)

        // The user backs out of the system confirmation: back to the offer.
        backend.events.value = InstallEvent.Cancelled
        assertEquals(UpdateStage.Offer, c.state.value.offer!!.stage)
        assertNull(backend.events.value)
        // The installer reports a failure.
        c.onAction(UpdateAction.Install)
        backend.events.value = InstallEvent.Failed(1, "nope")
        assertEquals(UpdateStage.Failed(UpdateProblem.INSTALL_FAILED), c.state.value.offer!!.stage)
    }

    @Test
    fun aWrongSignerNeverReachesTheInstaller() {
        val backend = Backend(newer).apply { verifyResult = VerifyFailure.WRONG_SIGNER }
        val c = controller(backend)
        assertEquals("a new host clears stale downloads", 1, backend.clears)
        c.onAction(UpdateAction.CheckNow)
        c.onAction(UpdateAction.Install)
        assertEquals(UpdateStage.Failed(UpdateProblem.SIGNER_MISMATCH), c.state.value.offer!!.stage)
        assertTrue(backend.installed.isEmpty())
        assertTrue("the rejected APK is deleted", backend.clears > 1)

        backend.verifyResult = VerifyFailure.NOT_NEWER
        c.onAction(UpdateAction.Install)
        assertEquals(UpdateStage.Failed(UpdateProblem.VERIFY_FAILED), c.state.value.offer!!.stage)
        assertTrue(backend.installed.isEmpty())

        c.onAction(UpdateAction.OpenReleasePage)
        assertEquals(listOf(newer), backend.opened)
        assertNull(c.state.value.offer)
    }

    @Test
    fun aFailedDownloadCanBeRetried() {
        val backend = Backend(newer).apply { downloadOk = false }
        val c = controller(backend)
        c.onAction(UpdateAction.CheckNow)
        c.onAction(UpdateAction.Install)
        assertEquals(UpdateStage.Failed(UpdateProblem.DOWNLOAD_FAILED), c.state.value.offer!!.stage)
        backend.downloadOk = true
        c.onAction(UpdateAction.Install)
        assertEquals(UpdateStage.Installing, c.state.value.offer!!.stage)
    }

    @Test
    fun noApkOrAPlayInstallLinksOutInsteadOfInstalling() {
        val noApk = Backend(newer.copy(apk = null))
        controller(noApk).apply {
            onAction(UpdateAction.CheckNow)
            assertFalse(state.value.offer!!.canInstallInApp)
            onAction(UpdateAction.Install)
            assertEquals(UpdateStage.Offer, state.value.offer!!.stage)
            assertTrue(noApk.installed.isEmpty())
        }
        val play = Backend(newer).apply { selfUpdate = false }
        controller(play).apply {
            onAction(UpdateAction.AutoCheck)
            assertTrue("Play installs are never auto-checked", play.fetches.isEmpty())
            onAction(UpdateAction.CheckNow)
            assertFalse(state.value.offer!!.canInstallInApp)
            onAction(UpdateAction.Install)
            assertTrue(play.installed.isEmpty())
        }
    }
}
