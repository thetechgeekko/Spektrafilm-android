/*
 * Spektrafilm for Android — unit tests for the update check. GPLv3.
 * Semver compare, response policy, release notes and APK-asset acceptance; plain JVM.
 */
package com.spectrafilm.app

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test fun newerByPatchMinorMajor() {
        assertTrue(AppUpdater.isNewer("0.5.0", "v0.5.1"))
        assertTrue(AppUpdater.isNewer("0.5.0", "v0.6.0"))
        assertTrue(AppUpdater.isNewer("0.5.0", "v1.0.0"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(AppUpdater.isNewer("0.5.0", "v0.5.0"))
        assertFalse(AppUpdater.isNewer("0.5.0", "v0.4.9"))
        assertFalse(AppUpdater.isNewer("1.0.0", "v0.9.9"))
    }

    @Test fun tolerantOfPrefixAndShortForms() {
        assertTrue(AppUpdater.isNewer("v0.5", "0.5.1"))      // missing patch = 0
        assertFalse(AppUpdater.isNewer("0.5.0", "0.5.0-rc1")) // pre-release core equal
    }

    @Test fun unparseableIsNotNewer() {
        assertFalse(AppUpdater.isNewer("0.5.0", "nightly"))
        assertFalse(AppUpdater.isNewer("weird", "v9.9.9"))
    }

    @Test
    fun validStableReleaseMetadataIsAcceptedForTheCanonicalBrowserPage() {
        val info = response(
            body = """{
                "tag_name":"v0.9.1",
                "html_url":"https://github.com/thetechgeekko/Spektrafilm-android/releases/tag/v0.9.1",
                "draft":false,
                "prerelease":false
            }""".trimIndent(),
        )

        assertNotNull(info)
        assertEquals("v0.9.1", info!!.latestTag)
        assertTrue(info.isNewer)
        assertEquals(info.releaseUrl, AppUpdater.browserTarget(info))
    }

    @Test
    fun redirectWrongMediaTypeAndOversizedBodiesFailClosed() {
        val valid = validBody()
        assertNull(response(status = 302, body = valid))
        assertNull(response(contentType = "text/html", body = valid))
        assertNull(response(declaredLength = AppUpdater.MAX_RESPONSE_BYTES + 1L, body = valid))
        assertNull(
            AppUpdater.evaluateResponse(
                currentVersion = "0.9.0",
                statusCode = 200,
                contentType = "application/json",
                declaredLength = -1L,
                body = ByteArrayInputStream(ByteArray(AppUpdater.MAX_RESPONSE_BYTES + 1)),
            ),
        )
    }

    @Test
    fun malformedUtf8AndNonProgressingBodiesFailClosed() {
        assertNull(
            AppUpdater.evaluateResponse(
                currentVersion = "0.9.0",
                statusCode = 200,
                contentType = "application/json",
                declaredLength = -1L,
                body = ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)),
            ),
        )
        assertNull(
            AppUpdater.evaluateResponse(
                currentVersion = "0.9.0",
                statusCode = 200,
                contentType = "application/json",
                declaredLength = -1L,
                body = object : InputStream() {
                    override fun read(): Int = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
                },
            ),
        )
    }

    @Test
    fun maliciousReleaseMetadataCannotChooseAHostPathOrChannel() {
        val badBodies = listOf(
            validBody(url = "http://github.com/thetechgeekko/Spektrafilm-android/releases/tag/v0.9.1"),
            validBody(url = "https://github.com.evil.test/thetechgeekko/Spektrafilm-android/releases/tag/v0.9.1"),
            validBody(url = "https://user@github.com/thetechgeekko/Spektrafilm-android/releases/tag/v0.9.1"),
            validBody(url = "https://github.com/thetechgeekko/another-repo/releases/tag/v0.9.1"),
            validBody(url = "https://github.com/thetechgeekko/Spektrafilm-android/releases/tag/v9.9.9"),
            validBody(url = "https://github.com/thetechgeekko/Spektrafilm-android/releases/tag/v0.9.1?download=1"),
            validBody(tag = "nightly"),
            validBody(tag = "v${"9".repeat(1_000)}.1.1"),
            validBody(draft = true),
            validBody(prerelease = true),
        )

        badBodies.forEach { assertNull(it, response(body = it)) }
    }

    @Test
    fun browserHandoffRevalidatesEvenCallerConstructedUpdateInfo() {
        val hostile = UpdateInfo(
            latestTag = "v9.9.9",
            currentVersion = "0.9.0",
            releaseUrl = "https://attacker.test/fake.apk",
            isNewer = true,
        )

        assertNull(AppUpdater.browserTarget(hostile))
    }

    @Test
    fun releaseNotesAreKeptWithoutHtmlCommentsAndBounded() {
        val info = response(
            body = withExtras(notes = "<!-- spektrafilm-release-run:v2:abc -->\r\n## Highlights\r\n- One"),
        )!!
        assertEquals("## Highlights\n- One", info.notes)

        val long = response(body = withExtras(notes = "x".repeat(AppUpdater.MAX_NOTES_CHARS + 50)))!!
        assertTrue(long.notes.length <= AppUpdater.MAX_NOTES_CHARS + 2)
        assertTrue(long.notes.endsWith("…"))

        assertEquals("", response(body = validBody())!!.notes)
    }

    @Test
    fun theCanonicalApkAssetIsAccepted() {
        val info = response(body = withExtras(assets = listOf(apkAsset())))!!
        val apk = info.apk!!
        assertEquals("Spektrafilm-v0.9.1.apk", apk.name)
        assertEquals(
            "https://github.com/thetechgeekko/Spektrafilm-android/releases/download/v0.9.1/Spektrafilm-v0.9.1.apk",
            apk.downloadUrl,
        )
        assertEquals(21_341_655L, apk.sizeBytes)
        assertEquals("a".repeat(64), apk.sha256)
        // The test-only instrumentation APK published next to it is never picked.
        val withTestApk = response(
            body = withExtras(
                assets = listOf(
                    apkAsset(
                        name = "Spektrafilm-v0.9.1-engine-boundary-androidTest.apk",
                        url = "https://github.com/thetechgeekko/Spektrafilm-android/releases/download/v0.9.1/" +
                            "Spektrafilm-v0.9.1-engine-boundary-androidTest.apk",
                    ),
                    apkAsset(),
                ),
            ),
        )!!
        assertEquals("Spektrafilm-v0.9.1.apk", withTestApk.apk!!.name)
        // A malformed digest is ignored, not trusted.
        assertNull(response(body = withExtras(assets = listOf(apkAsset(digest = "md5:abc"))))!!.apk!!.sha256)
    }

    @Test
    fun anApkAssetThatIsNotExactlyTheReleaseApkFallsBackToTheReleasePage() {
        val base = "https://github.com/thetechgeekko/Spektrafilm-android/releases/download"
        val bad = listOf(
            listOf(apkAsset(url = "https://evil.test/Spektrafilm-v0.9.1.apk")),
            listOf(apkAsset(url = "http://github.com/thetechgeekko/Spektrafilm-android/releases/download/v0.9.1/Spektrafilm-v0.9.1.apk")),
            listOf(apkAsset(url = "$base/v9.9.9/Spektrafilm-v0.9.1.apk")),
            listOf(apkAsset(url = "$base/v0.9.1/Spektrafilm-v0.9.1.apk?x=1")),
            listOf(apkAsset(name = "Spektrafilm-v0.9.2.apk")),
            listOf(apkAsset(size = 0)),
            listOf(apkAsset(size = AppUpdater.MAX_APK_BYTES + 1)),
            listOf(apkAsset(state = "starter")),
            listOf(apkAsset(), apkAsset()),
        )
        bad.forEach { assets ->
            val info = response(body = withExtras(assets = assets))
            assertNotNull(info)
            assertNull(assets.toString(), info!!.apk)
        }
    }

    private fun apkAsset(
        name: String = "Spektrafilm-v0.9.1.apk",
        url: String = "https://github.com/thetechgeekko/Spektrafilm-android/releases/download/v0.9.1/Spektrafilm-v0.9.1.apk",
        size: Long = 21_341_655L,
        state: String = "uploaded",
        digest: String = "sha256:" + "a".repeat(64),
    ): org.json.JSONObject = org.json.JSONObject()
        .put("name", name)
        .put("browser_download_url", url)
        .put("size", size)
        .put("state", state)
        .put("digest", digest)

    private fun withExtras(
        notes: String? = null,
        assets: List<org.json.JSONObject> = emptyList(),
    ): String = org.json.JSONObject(validBody()).apply {
        if (notes != null) put("body", notes)
        put("assets", org.json.JSONArray(assets))
    }.toString()

    private fun response(
        status: Int = 200,
        contentType: String? = "application/json; charset=utf-8",
        declaredLength: Long? = null,
        body: String,
    ): UpdateInfo? {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return AppUpdater.evaluateResponse(
            currentVersion = "0.9.0",
            statusCode = status,
            contentType = contentType,
            declaredLength = declaredLength ?: bytes.size.toLong(),
            body = ByteArrayInputStream(bytes),
        )
    }

    private fun validBody(
        tag: String = "v0.9.1",
        url: String = "https://github.com/thetechgeekko/Spektrafilm-android/releases/tag/$tag",
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String = """{
        "tag_name":"$tag",
        "html_url":"$url",
        "draft":$draft,
        "prerelease":$prerelease
    }""".trimIndent()
}
