/*
 * Spektrafilm for Android — in-app update verification rule. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The pure half of UpdateInstaller: the decision whether a downloaded APK may be handed
 * to the system installer, from facts PackageManager read out of it. See UpdateInstaller
 * and docs/UPDATER_SECURITY.md for why signing-certificate continuity is the gate.
 */
package com.spectrafilm.app

import java.security.MessageDigest

/** What an APK says about itself; [signers] holds hex SHA-256 of every acceptable cert. */
internal data class ArchiveFacts(
    val packageName: String?,
    val versionCode: Long,
    val versionName: String?,
    val signers: Set<String>,
)

internal enum class VerifyFailure { UNREADABLE, WRONG_PACKAGE, NOT_NEWER, WRONG_VERSION, WRONG_SIGNER }

internal object UpdateVerifier {
    /** Null when the archive may be handed to the installer; otherwise the first failure. */
    fun check(
        expectedPackage: String,
        installedVersionCode: Long,
        installedSigners: Set<String>,
        tag: String,
        archive: ArchiveFacts?,
    ): VerifyFailure? {
        if (archive == null) return VerifyFailure.UNREADABLE
        if (archive.packageName != expectedPackage) return VerifyFailure.WRONG_PACKAGE
        if (archive.versionCode <= installedVersionCode) return VerifyFailure.NOT_NEWER
        if (archive.versionName != tag.removePrefix("v")) return VerifyFailure.WRONG_VERSION
        if (installedSigners.isEmpty() || !archive.signers.containsAll(installedSigners)) {
            return VerifyFailure.WRONG_SIGNER
        }
        return null
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Outcome reported by the system installer after the user acted on the confirmation. */
internal sealed interface InstallEvent {
    data object Cancelled : InstallEvent
    data class Failed(val status: Int, val message: String?) : InstallEvent
}
