/*
 * Spektrafilm for Android — in-app update download policy. GPLv3.
 */
package com.spectrafilm.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadPolicyTest {

    private val tag = "v0.12.0"
    private val asset = ApkAsset(
        name = AppUpdater.apkName(tag),
        downloadUrl = AppUpdater.apkDownloadUrl(tag),
        sizeBytes = 10,
        sha256 = null,
    )

    @Test
    fun onlyTheCanonicalAssetMayStartADownload() {
        assertTrue(UpdateDownloadPolicy.isAllowedStart(asset, tag))
        assertFalse(UpdateDownloadPolicy.isAllowedStart(asset.copy(downloadUrl = "https://evil.test/x.apk"), tag))
        assertFalse(UpdateDownloadPolicy.isAllowedStart(asset, "v0.12.1"))
        assertFalse(UpdateDownloadPolicy.isAllowedStart(asset.copy(name = "other.apk"), tag))
    }

    @Test
    fun redirectsGoOnlyToGithubReleaseAssetHostsOverHttps() {
        val signed = "/github-production-release-asset-2e65be/1/abc?X-Amz-Signature=deadbeef&response-content-type=x"
        assertNotNull(UpdateDownloadPolicy.allowedRedirect("https://objects.githubusercontent.com$signed"))
        assertNotNull(UpdateDownloadPolicy.allowedRedirect("https://release-assets.githubusercontent.com$signed"))
        listOf(
            null,
            "",
            "http://objects.githubusercontent.com$signed",
            "https://objects.githubusercontent.com.evil.test$signed",
            "https://evil.githubusercontent.com$signed",
            "https://raw.githubusercontent.com$signed",
            "https://user@objects.githubusercontent.com$signed",
            "https://objects.githubusercontent.com:8443$signed",
            "https://objects.githubusercontent.com/a#frag",
            "/relative/path",
            "https://github.com/thetechgeekko/Spektrafilm-android",
            "https://objects.githubusercontent.com/" + "a".repeat(5000),
        ).forEach { assertNull(it.toString(), UpdateDownloadPolicy.allowedRedirect(it)) }
    }

    @Test
    fun copyExactAcceptsOnlyTheDeclaredByteCount() {
        val bytes = ByteArray(10) { it.toByte() }
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val sha = UpdateDownloadPolicy.copyExact(ByteArrayInputStream(bytes), out, 10, onProgress = { progress += it })
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expected, sha)
        assertEquals(10L, progress.last())
        assertTrue(bytes.contentEquals(out.toByteArray()))

        assertNull(UpdateDownloadPolicy.copyExact(ByteArrayInputStream(bytes), ByteArrayOutputStream(), 9))
        assertNull(UpdateDownloadPolicy.copyExact(ByteArrayInputStream(bytes), ByteArrayOutputStream(), 11))
        assertNull(
            UpdateDownloadPolicy.copyExact(ByteArrayInputStream(bytes), ByteArrayOutputStream(), 10, keepGoing = { false }),
        )
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertNull(UpdateDownloadPolicy.copyExact(stalled, ByteArrayOutputStream(), 10))
    }
}
