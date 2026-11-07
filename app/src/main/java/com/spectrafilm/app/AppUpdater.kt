/*
 * Spektrafilm for Android — advisory update check. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The check reads one bounded response from the repository's fixed GitHub API endpoint.
 * From it, this file accepts only: a canonical stable tag, the canonical release page,
 * the release notes (bounded text, shown in the "What's new" dialog), and, when present,
 * the one APK asset the release workflow publishes for that tag at its canonical download
 * URL. Downloading and installing that APK is UpdateDownloader / UpdateInstaller's job;
 * the integrity gate there is signing-certificate continuity with the installed app (see
 * docs/UPDATER_SECURITY.md), not anything in this response.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The release workflow's signed APK for one tag, as listed in the release's assets. */
data class ApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 GitHub computed at upload, when the API reports one. */
    val sha256: String?,
)

data class UpdateInfo(
    val latestTag: String,
    val currentVersion: String,
    val releaseUrl: String,
    val isNewer: Boolean,
    /** Release body (Markdown), HTML comments stripped and bounded; may be empty. */
    val notes: String = "",
    /** Null when the release has no canonical APK asset: the dialog then offers the page. */
    val apk: ApkAsset? = null,
)

object AppUpdater {
    /** A real release (v0.10.0: 14 assets + a long body) is ~33 KiB; leave headroom. */
    internal const val MAX_RESPONSE_BYTES = 256 * 1024
    /** The shipped APK is ~21 MB; anything far beyond that is not ours. */
    internal const val MAX_APK_BYTES = 256L * 1024 * 1024
    internal const val MAX_NOTES_CHARS = 20_000

    private const val MAX_TAG_CHARS = 32
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val API_HOST = "api.github.com"
    private const val BROWSER_HOST = "github.com"
    private const val REPOSITORY_PATH = "/thetechgeekko/Spektrafilm-android"
    private val SHA256_DIGEST = Regex("""sha256:([0-9a-f]{64})""")
    private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private const val LATEST_API =
        "https://api.github.com/repos/thetechgeekko/Spektrafilm-android/releases/latest"
    private val STABLE_TAG = Regex(
        """v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)""",
    )

    /**
     * Query advisory release metadata. Any transport, policy, or parse failure returns
     * null; no response may choose a different endpoint or browser destination.
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return@withContext null

        fetchLatest(current)
    }

    private fun fetchLatest(currentVersion: String): UpdateInfo? {
        val endpoint = runCatching { URI(LATEST_API) }.getOrNull()
            ?.takeIf(::isAllowedApiEndpoint)
            ?: return null
        return try {
            val connection = URL(endpoint.toASCIIString()).openConnection() as? HttpURLConnection
                ?: return null
            try {
                connection.apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    doOutput = false
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Spektrafilm-Android")
                }
                val statusCode = connection.responseCode
                if (statusCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { body ->
                    evaluateResponse(
                        currentVersion = currentVersion,
                        statusCode = statusCode,
                        contentType = connection.contentType,
                        declaredLength = connection.contentLengthLong,
                        body = body,
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** HTTP response policy seam, exercised with hostile response streams in JVM tests. */
    internal fun evaluateResponse(
        currentVersion: String,
        statusCode: Int,
        contentType: String?,
        declaredLength: Long,
        body: InputStream,
    ): UpdateInfo? {
        if (statusCode != HttpURLConnection.HTTP_OK) return null
        val mediaType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (mediaType != "application/json" && mediaType != "application/vnd.github+json") {
            return null
        }
        if (declaredLength < -1L || declaredLength > MAX_RESPONSE_BYTES) return null
        val json = readBoundedUtf8(body) ?: return null
        return runCatching {
            val metadata = JSONObject(json)
            if (metadata.opt("draft") != false || metadata.opt("prerelease") != false) return null
            val tag = metadata.opt("tag_name") as? String ?: return null
            val releaseUrl = metadata.opt("html_url") as? String ?: return null
            if (tag.length > MAX_TAG_CHARS ||
                !STABLE_TAG.matches(tag) ||
                validatedReleaseUrl(releaseUrl, tag) == null
            ) return null
            UpdateInfo(
                latestTag = tag,
                currentVersion = currentVersion,
                releaseUrl = releaseUrl,
                isNewer = isNewer(currentVersion, tag),
                notes = releaseNotes(metadata.opt("body")),
                apk = apkAsset(metadata.optJSONArray("assets"), tag),
            )
        }.getOrNull()
    }

    /** The canonical APK name the release workflow uploads for [tag]. */
    internal fun apkName(tag: String): String = "Spektrafilm-$tag.apk"

    /** The only URL an APK download may start from (redirects are UpdateDownloader's). */
    internal fun apkDownloadUrl(tag: String): String =
        "https://$BROWSER_HOST$REPOSITORY_PATH/releases/download/$tag/${apkName(tag)}"

    private fun releaseNotes(raw: Any?): String {
        val body = raw as? String ?: return ""
        val cleaned = body.replace(HTML_COMMENT, "").replace("\r\n", "\n").trim()
        return if (cleaned.length <= MAX_NOTES_CHARS) cleaned
        else cleaned.take(MAX_NOTES_CHARS).trimEnd() + "\n…"
    }

    /**
     * Exactly one asset named [apkName] at exactly [apkDownloadUrl], fully uploaded and of
     * a plausible size; anything else (including a second asset of that name) is null, and
     * the dialog falls back to the release page.
     */
    private fun apkAsset(assets: org.json.JSONArray?, tag: String): ApkAsset? {
        if (assets == null) return null
        val name = apkName(tag)
        val matches = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.opt("name") == name }
        val asset = matches.singleOrNull() ?: return null
        if (asset.opt("state") != "uploaded") return null
        val url = asset.opt("browser_download_url") as? String ?: return null
        if (url != apkDownloadUrl(tag)) return null
        val size = (asset.opt("size") as? Number)?.toLong() ?: return null
        if (size <= 0L || size > MAX_APK_BYTES) return null
        val sha256 = (asset.opt("digest") as? String)
            ?.let { SHA256_DIGEST.matchEntire(it)?.groupValues?.get(1) }
        return ApkAsset(name = name, downloadUrl = url, sizeBytes = size, sha256 = sha256)
    }

    private fun readBoundedUtf8(input: InputStream): String? {
        val output = ByteArrayOutputStream(minOf(MAX_RESPONSE_BYTES, 8 * 1024))
        val buffer = ByteArray(4 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            // InputStream promises progress for a non-empty buffer. Treat a broken or
            // adversarial implementation as a failed response instead of spinning.
            if (read == 0) return null
            if (total > MAX_RESPONSE_BYTES - read) return null
            output.write(buffer, 0, read)
            total += read
        }
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        }.getOrNull()
    }

    private fun isAllowedApiEndpoint(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(API_HOST, ignoreCase = true) &&
            uri.port == -1 &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawPath == "/repos$REPOSITORY_PATH/releases/latest"

    private fun validatedReleaseUrl(raw: String, tag: String): String? {
        if (tag.length > MAX_TAG_CHARS || !STABLE_TAG.matches(tag)) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val valid = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(BROWSER_HOST, ignoreCase = true) &&
            uri.port == -1 &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawPath == "$REPOSITORY_PATH/releases/tag/$tag"
        return raw.takeIf { valid }
    }

    /** Revalidate at the Intent boundary even if an UpdateInfo was caller-constructed. */
    internal fun browserTarget(info: UpdateInfo): String? =
        validatedReleaseUrl(info.releaseUrl, info.latestTag)

    /** Open a validated release page in the user's browser. */
    fun openRelease(context: Context, info: UpdateInfo) {
        val target = browserTarget(info) ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** True if [tag] is a strictly newer semver than installed [current]. */
    internal fun isNewer(current: String, tag: String): Boolean {
        val installed = parseSemver(current) ?: return false
        val available = parseSemver(tag) ?: return false
        for (i in 0 until 3) {
            if (available[i] != installed[i]) return available[i] > installed[i]
        }
        return false
    }

    /** Parse `v1.2.3` / `1.2.3`; missing minor or patch components are zero. */
    private fun parseSemver(value: String): IntArray? {
        val core = value.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size !in 1..3 || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) {
            return null
        }
        return IntArray(3) { index -> parts.getOrNull(index)?.toIntOrNull() ?: 0 }
    }
}
