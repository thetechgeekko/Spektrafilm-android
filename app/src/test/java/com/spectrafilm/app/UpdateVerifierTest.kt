/*
 * Spektrafilm for Android — in-app update APK acceptance rule. GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateVerifierTest {

    private val release = "a".repeat(64)
    private val debugKey = "d".repeat(64)
    private val rotated = "b".repeat(64)

    private fun check(
        archive: ArchiveFacts?,
        installedSigners: Set<String> = setOf(release),
        installedVersionCode: Long = 13,
    ) = UpdateVerifier.check(
        expectedPackage = "com.spectrafilm.app",
        installedVersionCode = installedVersionCode,
        installedSigners = installedSigners,
        tag = "v0.12.0",
        archive = archive,
    )

    private val good = ArchiveFacts("com.spectrafilm.app", 14, "0.12.0", setOf(release))

    @Test
    fun theNextReleaseSignedWithTheSameKeyIsAccepted() {
        assertNull(check(good))
        // A key rotation whose proven lineage includes the installed key is also accepted.
        assertNull(check(good.copy(signers = setOf(rotated, release))))
    }

    @Test
    fun anythingElseIsRejectedBeforeTheInstaller() {
        assertEquals(VerifyFailure.UNREADABLE, check(null))
        assertEquals(VerifyFailure.WRONG_PACKAGE, check(good.copy(packageName = "com.evil")))
        assertEquals(VerifyFailure.WRONG_PACKAGE, check(good.copy(packageName = null)))
        assertEquals(VerifyFailure.NOT_NEWER, check(good.copy(versionCode = 13)))
        assertEquals(VerifyFailure.NOT_NEWER, check(good.copy(versionCode = 12)))
        assertEquals(VerifyFailure.WRONG_VERSION, check(good.copy(versionName = "0.11.0")))
        assertEquals(VerifyFailure.WRONG_SIGNER, check(good.copy(signers = setOf(rotated))))
        assertEquals(VerifyFailure.WRONG_SIGNER, check(good.copy(signers = emptySet())))
        // A debug / self-built install can never be updated in place by the release.
        assertEquals(VerifyFailure.WRONG_SIGNER, check(good, installedSigners = setOf(debugKey)))
        // No installed signer read: fail closed rather than vacuously pass containsAll().
        assertEquals(VerifyFailure.WRONG_SIGNER, check(good, installedSigners = emptySet()))
    }
}
