/*
 * Spektrafilm for Android — in-app update verification and install. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Before a downloaded APK reaches the installer it must be, by its own manifest and
 * signature block: this package, a strictly higher versionCode than the installed one,
 * the versionName its release tag names, and signed by every certificate that signs the
 * installed app (or by a rotated key whose proof-of-rotation lineage includes them).
 * That last check is the integrity gate (docs/UPDATER_SECURITY.md): only the holder of
 * the production key can produce an APK that passes it, whatever served the bytes.
 * Android's package manager enforces the same continuity again at install time; checking
 * first means a mismatch (a debug build, a fork) gets a clear message and the release
 * page instead of an opaque installer error.
 *
 * The install itself is a PackageInstaller session the user confirms in the system UI.
 * The app never installs silently.
 */
package com.spectrafilm.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal object UpdateInstaller {
    private const val ACTION_STATUS = "com.spectrafilm.app.action.UPDATE_INSTALL_STATUS"
    private const val PLAY_STORE = "com.android.vending"

    private val _events = MutableStateFlow<InstallEvent?>(null)
    /** Latest unconsumed installer outcome; the consumer resets it with [consumeEvent]. */
    val events: StateFlow<InstallEvent?> = _events.asStateFlow()

    fun consumeEvent() {
        _events.value = null
    }

    internal fun report(event: InstallEvent) {
        _events.value = event
    }

    /**
     * True when Google Play installed this copy. Play builds must take updates from Play
     * (its policy forbids self-update), so the app only links to the release page there.
     */
    fun installedFromPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)

    suspend fun verify(context: Context, apk: File, tag: String, io: CoroutineDispatcher): VerifyFailure? =
        withContext(io) {
            val pm = context.packageManager
            val installed = runCatching { packageInfo(pm, context.packageName) }.getOrNull()
                ?: return@withContext VerifyFailure.UNREADABLE
            val archive = runCatching { archiveInfo(pm, apk) }.getOrNull()
            val result = UpdateVerifier.check(
                expectedPackage = context.packageName,
                installedVersionCode = PackageInfoCompat.getLongVersionCode(installed),
                installedSigners = currentSigners(installed),
                tag = tag,
                archive = archive?.let {
                    ArchiveFacts(
                        packageName = it.packageName,
                        versionCode = PackageInfoCompat.getLongVersionCode(it),
                        versionName = it.versionName,
                        signers = acceptableSigners(it),
                    )
                },
            )
            if (result != null) Diag.w("update verify failed: $result")
            result
        }

    /** Stream [apk] into a PackageInstaller session and commit it. False on any failure. */
    suspend fun install(context: Context, apk: File, io: CoroutineDispatcher): Boolean = withContext(io) {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            params.setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val status = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(ACTION_STATUS)
                    .setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    // The installer fills in EXTRA_STATUS / EXTRA_INTENT, so this must be mutable;
                    // it is explicit (component + package) so nobody else can redirect it.
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, status, flags)
                session.commit(pending.intentSender)
            }
            true
        }.getOrElse { error ->
            Diag.w("update install session failed: ${error.message}")
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            false
        }
    }

    // EXTRA_INTENT is the system installer's confirmation activity; the receiver that hands it
    // to us is not exported, so no other app can choose what is launched here.
    @SuppressLint("UnsafeIntentLaunch")
    internal fun onStatus(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    report(InstallEvent.Failed(status, null))
                    return
                }
                runCatching {
                    // This receiver is not exported, so only the system installer (through the
                    // PendingIntent above) can deliver EXTRA_INTENT here.
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { report(InstallEvent.Failed(status, it.message)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The process is replaced; nothing to show.
            PackageInstaller.STATUS_FAILURE_ABORTED -> report(InstallEvent.Cancelled)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Diag.w("update install failed status=$status message=$message")
                report(InstallEvent.Failed(status, message))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }

    /** The certificates that sign the installed app right now (not past rotations). */
    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { UpdateVerifier.sha256Hex(it.toByteArray()) }.toSet()
    }

    /** The archive's signers plus, for a single-signer APK, its proven rotation lineage. */
    @Suppress("DEPRECATION")
    private fun acceptableSigners(info: PackageInfo): Set<String> {
        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            val current = signing.apkContentsSigners.orEmpty().toList()
            if (signing.hasMultipleSigners()) current
            else current + signing.signingCertificateHistory.orEmpty()
        } else {
            info.signatures.orEmpty().toList()
        }
        return signatures.map { UpdateVerifier.sha256Hex(it.toByteArray()) }.toSet()
    }
}

/** Receives PackageInstaller session status. Not exported: only our PendingIntent reaches it. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateInstaller.onStatus(context, intent)
    }
}
