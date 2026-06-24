/*
 * Spektrafilm for Android — in-app diagnostics (logcat + crash capture). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A lightweight, permission-free error/diagnostics facility so users can capture and
 * share what went wrong without a USB cable or adb:
 *   - A process-global UncaughtExceptionHandler persists the last fatal stack trace to
 *     filesDir/diag/last_crash.txt (survives the restart), chaining to the previous
 *     handler so the normal crash dialog still shows.
 *   - On demand, a one-shot `logcat -d` snapshot of THIS process is read (apps may read
 *     their own logs with no permission) and shown in a Diagnostics screen with
 *     copy/share. Nothing is uploaded anywhere.
 *
 * Clean-room design (inspired by the pattern, not the code, of other editors' diagnostics).
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-TAG breadcrumb logger for the key engine/IO lifecycle points (engine create,
 * decode, render, export). These `Log.i("Spektra", …)` lines are what make a field
 * `logcat` — and the in-app [Diagnostics] report, which snapshots this process's own
 * logcat — actually diagnostic: from a capture you can tell whether the native engine
 * loaded, whether a render ran (mode + pixels + elapsed), and whether an export
 * succeeded (format + dims + bytes). Previously none of that was logged, so a device
 * capture was indistinguishable framework noise.
 *
 * Policy: ONE tag ("Spektra"); NO PII — never log file paths or content URIs, only the
 * source KIND and pixel DIMENSIONS. Not stripped in release (proguard-rules.pro has no
 * `-assumenosideeffects` for android.util.Log), which is intentional: field captures
 * come from release builds, so the breadcrumbs must survive into the shipped APK.
 */
object Diag {
    const val TAG = "Spektra"

    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
    }
}

object Diagnostics {
    private const val DIR = "diag"
    private const val CRASH_FILE = "last_crash.txt"

    // The threadtime "pid tid LEVEL" column matcher, compiled ONCE (not per logcat line).
    private val PID_LINE_RE = Regex(".*\\s\\d+\\s\\d+\\s[VDIWEF]\\s.*")

    /** Install once (e.g. in MainActivity.onCreate). Idempotent. */
    @Volatile private var installed = false

    fun installCrashHandler(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(app, thread, throwable) }
            // Chain so the platform still shows its crash dialog / records the ANR-free exit.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun crashFile(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, CRASH_FILE)

    private fun writeCrash(context: Context, thread: Thread, t: Throwable) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("=== Spektrafilm crash @ ").append(ts).append(" ===\n")
        sb.append("thread: ").append(thread.name).append('\n')
        sb.append(android.util.Log.getStackTraceString(t))
        crashFile(context).writeText(sb.toString())
    }

    /** The last persisted crash trace, or null if none. */
    fun lastCrash(context: Context): String? =
        crashFile(context).takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clearLastCrash(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    /**
     * One-shot snapshot of this process's recent logcat. `logcat -d` dumps and exits; we
     * filter to our own PID so it works without READ_LOGS on modern Android (apps read
     * only their own logs anyway). Returns a best-effort string; never throws.
     */
    fun captureLogcat(maxLines: Int = 500): String {
        val pid = Process.myPid().toString()
        return runCatching {
            val proc = ProcessBuilder(
                listOf("logcat", "-d", "-v", "threadtime", "-t", maxLines.toString()),
            ).redirectErrorStream(true).start()
            val out = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                for (line in lines) {
                    // Keep our own process lines (+ any line without a pid column).
                    if (line.contains(" $pid ") || !line.matches(PID_LINE_RE)) {
                        out.append(line).append('\n')
                    }
                }
            }
            runCatching { proc.waitFor() }
            if (out.isEmpty()) "(no logcat lines for pid $pid)" else out.toString()
        }.getOrElse { e -> "logcat capture failed: ${e.message}" }
    }

    /** Build a shareable report = crash (if any) + a logcat snapshot. */
    fun buildReport(context: Context): String {
        val header = "Spektrafilm diagnostics\n" +
            "app: ${appVersion(context)}\n" +
            "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n"
        val crash = lastCrash(context)?.let { "--- last crash ---\n$it\n\n" } ?: ""
        return header + crash + "--- logcat (recent) ---\n" + captureLogcat()
    }

    fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode: Long = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION") pi.versionCode.toLong()
        }
        "${pi.versionName} ($versionCode)"
    }.getOrDefault("unknown")

    /** Fire a share-sheet with the report text. */
    fun share(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Spektrafilm diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostics").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
