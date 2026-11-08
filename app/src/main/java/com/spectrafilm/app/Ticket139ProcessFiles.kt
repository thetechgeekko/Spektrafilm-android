/*
 * Spektrafilm for Android — ticket #139 process-death probe files. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import org.json.JSONObject

/**
 * The no-backup files the #139 process-death probe writes and reads back across a process kill.
 * Split out of [Ticket139EditorProbe] (#273).
 */
internal object Ticket139ProcessFiles {
    private const val PROBE_DIRECTORY = "ticket139-editor-oracle"
    private const val PROCESS_MARKER = "ticket139-process-probe.json"
    private const val PROCESS_ORACLE = "ticket139-process-oracle.json"
    private const val ORACLE_SCHEMA = "org.spektrafilm.ticket139-editor-oracle"
    private const val ORACLE_VERSION = 1
    private const val PROCESS_ORACLE_MAX_BYTES = 4 * 1024 * 1024

    internal fun writeProcessOracle(
        context: Context,
        seedIdentity: String,
        document: EditorSessionDocument,
    ) {
        val payload = JSONObject()
            .put("schema", ORACLE_SCHEMA)
            .put("version", ORACLE_VERSION)
            .put("buildIdentity", buildIdentity(context))
            .put("seedIdentity", seedIdentity)
            .put("document", JSONObject(EditorSessionDocumentCodec.encode(document)))
        AtomicJsonStore.writeUtf8(
            processOracle(context),
            payload.toString(),
            PROCESS_ORACLE_MAX_BYTES,
        )
    }

    internal fun readProcessOracle(context: Context, expectedSeedIdentity: String): EditorSessionDocument {
        val payload = AtomicJsonStore.parseObject(
            AtomicJsonStore.readUtf8(processOracle(context), PROCESS_ORACLE_MAX_BYTES),
        )
        check(payload.getString("schema") == ORACLE_SCHEMA) { "process oracle schema mismatch" }
        check(payload.getInt("version") == ORACLE_VERSION) { "process oracle version mismatch" }
        check(payload.getString("buildIdentity") == buildIdentity(context)) {
            "process oracle belongs to another build"
        }
        check(payload.getString("seedIdentity") == expectedSeedIdentity) {
            "process oracle belongs to another seed"
        }
        return EditorSessionDocumentCodec.decode(payload.getJSONObject("document").toString())
    }

    internal fun buildIdentity(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val buildType = if (
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) {
            "debug"
        } else {
            "release"
        }
        return "${context.packageName}:$versionCode:$buildType"
    }

    internal fun requireLoaded(context: Context): EditorSessionDocument =
        when (val restored = EditorSessionStore.read(context.applicationContext)) {
            is EditorSessionReadResult.Loaded -> restored.document
            else -> error("editor session unavailable: ${restored.javaClass.simpleName}")
        }

    internal fun processMarker(context: Context): File = File(
        File(context.noBackupFilesDir, PROBE_DIRECTORY),
        PROCESS_MARKER,
    )

    internal fun processOracle(context: Context): File = File(
        File(context.noBackupFilesDir, PROBE_DIRECTORY),
        PROCESS_ORACLE,
    )
}
