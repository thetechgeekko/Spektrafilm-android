/*
 * Spektrafilm for Android — in-app update download. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Fetches the one APK asset AppUpdater accepted, into no-backup app storage, under a fixed
 * request policy (docs/UPDATER_SECURITY.md):
 *   - the first request is exactly AppUpdater.apkDownloadUrl(tag) on github.com;
 *   - redirects are followed by hand, at most three, and only to HTTPS on GitHub's two
 *     release-asset hosts (a pre-signed object-store URL, so a query string is allowed);
 *   - the body must be exactly the size the release API declared, never more;
 *   - when the API reported a SHA-256 the bytes must match it.
 * The size/digest checks catch truncation and accidents; they are not the integrity gate
 * (the digest arrives in the same response as the URL). The gate is UpdateVerifier's
 * signing-certificate continuity check, which Android's installer then enforces again.
 */
package com.spectrafilm.app

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal sealed interface DownloadResult {
    data class Ok(val file: File) : DownloadResult
    data object Failed : DownloadResult
}

internal object UpdateDownloadPolicy {
    const val MAX_REDIRECTS = 3
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private val REDIRECT_HOSTS = setOf(
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** The first hop: only the canonical asset URL for this exact asset. */
    fun isAllowedStart(asset: ApkAsset, tag: String): Boolean =
        asset.name == AppUpdater.apkName(tag) && asset.downloadUrl == AppUpdater.apkDownloadUrl(tag)

    /**
     * A redirect target. HTTPS, default port, no user-info or fragment, one of GitHub's
     * release-asset hosts. Relative Locations are not accepted: GitHub sends absolute ones.
     */
    fun allowedRedirect(location: String?): URI? {
        if (location.isNullOrEmpty() || location.length > 4096) return null
        val uri = runCatching { URI(location) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
        val ok = uri.isAbsolute &&
            uri.scheme.equals("https", ignoreCase = true) &&
            host in REDIRECT_HOSTS &&
            uri.port == -1 &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null
        return uri.takeIf { ok }
    }

    fun configure(connection: HttpURLConnection) {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.doOutput = false
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("User-Agent", "Spektrafilm-Android")
    }

    /**
     * Copy exactly [expectedSize] bytes and return their lower-case hex SHA-256, or null if
     * the stream is longer or shorter, stops making progress, or [keepGoing] turns false.
     */
    fun copyExact(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        keepGoing: () -> Boolean = { true },
        onProgress: (Long) -> Unit = {},
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (!keepGoing()) return null
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) return null
            if (total + read > expectedSize) return null
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read
            onProgress(total)
        }
        if (total != expectedSize) return null
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal class UpdateDownloader(
    private val directory: File,
    private val io: CoroutineDispatcher,
    private val open: (URI) -> HttpURLConnection = { uri ->
        uri.toURL().openConnection() as HttpURLConnection
    },
) {
    private val attemptCounter = java.util.concurrent.atomic.AtomicLong(System.nanoTime())

    /** Remove every partial or finished download. Safe to call at any time. */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    suspend fun download(
        tag: String,
        asset: ApkAsset,
        onProgress: (Long) -> Unit,
    ): DownloadResult = withContext(io) {
        if (!UpdateDownloadPolicy.isAllowedStart(asset, tag)) return@withContext DownloadResult.Failed
        clear()
        if (!directory.isDirectory && !directory.mkdirs()) return@withContext DownloadResult.Failed
        // Unique per attempt: a cancelled attempt whose blocking read has not returned yet
        // must not overwrite, or later delete, the files of the attempt that replaced it.
        val attempt = attemptCounter.incrementAndGet()
        val partial = File(directory, "$attempt-${asset.name}.part")
        val target = File(directory, "$attempt-${asset.name}")
        val context = coroutineContext
        val ok = runCatching {
            var uri = URI(asset.downloadUrl)
            // One initial request plus at most MAX_REDIRECTS redirected ones.
            repeat(UpdateDownloadPolicy.MAX_REDIRECTS + 1) {
                val connection = open(uri)
                try {
                    UpdateDownloadPolicy.configure(connection)
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            val declared = connection.contentLengthLong
                            if (declared != -1L && declared != asset.sizeBytes) return@runCatching false
                            val sha = connection.inputStream.use { body ->
                                partial.outputStream().use { out ->
                                    UpdateDownloadPolicy.copyExact(
                                        input = body,
                                        output = out,
                                        expectedSize = asset.sizeBytes,
                                        keepGoing = { context.isActive },
                                        onProgress = onProgress,
                                    )
                                }
                            } ?: return@runCatching false
                            if (asset.sha256 != null && asset.sha256 != sha) {
                                Diag.w("update download digest mismatch for ${asset.name}")
                                return@runCatching false
                            }
                            return@runCatching partial.renameTo(target)
                        }
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307, 308 -> {
                            uri = UpdateDownloadPolicy.allowedRedirect(
                                connection.getHeaderField("Location"),
                            ) ?: return@runCatching false
                        }
                        else -> {
                            Diag.w("update download HTTP $code")
                            return@runCatching false
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            false // redirect budget exhausted
        }.getOrDefault(false)
        if (ok) DownloadResult.Ok(target) else {
            partial.delete()
            target.delete()
            DownloadResult.Failed
        }
    }
}
