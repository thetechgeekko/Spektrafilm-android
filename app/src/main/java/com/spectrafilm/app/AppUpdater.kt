/*
 * Spektrafilm for Android — in-app update check. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Spektrafilm ships as a signed APK via GitHub Releases (release.yml). This checks the
 * repo's latest release, compares its `vMAJOR.MINOR.PATCH` tag to the installed
 * versionName, and — when newer — surfaces an update prompt that opens the release page
 * in the browser to download. We deliberately do NOT auto-download/-install the APK
 * (that needs REQUEST_INSTALL_PACKAGES + a PackageInstaller flow); opening the signed
 * GitHub Release is the safe, reviewable path. Nothing is sent; it's a single GET.
 *
 * Clean-room (uses only the public GitHub REST API + org.json from the platform).
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val latestTag: String,        // e.g. "v0.6.0"
    val currentVersion: String,   // installed versionName
    val releaseUrl: String,       // html_url of the release page
    val apkUrl: String?,          // first .apk asset, if any
    val isNewer: Boolean,
)

object AppUpdater {
    // The app's own releases.
    private const val LATEST_API =
        "https://api.github.com/repos/thetechgeekko/Spectrafilmandroid/releases/latest"

    /**
     * Query the latest release. Returns null on any network/parse failure (caller treats
     * null as "couldn't check"). Runs on IO.
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return@withContext null

        val json = runCatching {
            val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Spektrafilm-Android")
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: return@withContext null

        runCatching {
            val obj = JSONObject(json)
            val tag = obj.getString("tag_name")
            val url = obj.optString("html_url")
            var apk: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val name = assets.getJSONObject(i).optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apk = assets.getJSONObject(i).optString("browser_download_url"); break
                    }
                }
            }
            UpdateInfo(
                latestTag = tag,
                currentVersion = current,
                releaseUrl = url,
                apkUrl = apk,
                isNewer = isNewer(current, tag),
            )
        }.getOrNull()
    }

    /** Open the release page (browser) so the user can grab the signed APK. */
    fun openRelease(context: Context, info: UpdateInfo) {
        val target = info.releaseUrl.ifBlank {
            "https://github.com/thetechgeekko/Spectrafilmandroid/releases/latest"
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** True if [tag] (e.g. "v0.6.0") is a strictly newer semver than installed [current]. */
    internal fun isNewer(current: String, tag: String): Boolean {
        val c = parseSemver(current) ?: return false
        val t = parseSemver(tag) ?: return false
        for (i in 0 until 3) {
            if (t[i] != c[i]) return t[i] > c[i]
        }
        return false
    }

    /** Parse "v1.2.3" / "1.2.3" → [1,2,3]; missing parts = 0; null if unparseable. */
    private fun parseSemver(s: String): IntArray? {
        val core = s.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts[0].toIntOrNull() == null) return null
        return IntArray(3) { i -> parts.getOrNull(i)?.toIntOrNull() ?: 0 }
    }
}
