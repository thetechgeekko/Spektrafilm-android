/*
 * Spektrafilm for Android — working copies of photos shared with a temporary grant (#508). GPLv3.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A photo that arrives by "Share" or "Open with" (ACTION_SEND / ACTION_VIEW) usually carries
 * only a TEMPORARY read grant: the sender did not add FLAG_GRANT_PERSISTABLE_URI_PERMISSION, so
 * `takePersistableUriPermission` is refused and the grant dies with the process. An app update,
 * a reboot, or the system reclaiming the app in the background then reopened the editor with
 * "Source access expired" (#508, reproduced on SM-S948W: the picker and Library paths survive an
 * `adb install -r`; only the intent path is lost).
 *
 * So such a photo is copied once, while the grant is still valid, into app-private storage and
 * the editor adopts the copy through this app's own non-exported FileProvider. The copy is a
 * plain content:// source to the rest of the editor, it survives process death, and nothing
 * outside the app can read it. Only one copy is kept: [prune] removes every copy that is no
 * longer the editor's source.
 */
internal object SharedSourceCopies {
    const val DIR = "shared-sources"

    fun authority(context: Context): String = "${context.packageName}.sharedsources"

    /** True when the sender let us keep the grant, so the original URI can be persisted. */
    fun grantIsPersistable(intent: Intent): Boolean =
        intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0

    /** True when [uri] is one of our own working copies. */
    fun isCopy(context: Context, uri: String): Boolean =
        uri.startsWith("content://${authority(context)}/")

    /**
     * Copies [uri] into the private directory and returns the copy's content URI, or null when
     * the copy could not be made (the caller then falls back to the original, temporary URI).
     * Runs blocking I/O: call it off the main thread.
     */
    fun copy(context: Context, uri: Uri, name: String, mime: String?): Uri? {
        val dir = File(context.filesDir, DIR)
        val target = File(dir, copyFileName(System.currentTimeMillis(), name, extensionFor(mime)))
        val written = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> writeCopy(input, dir, target) }
        }.getOrNull() ?: return null
        return runCatching { FileProvider.getUriForFile(context, authority(context), written) }
            .getOrElse {
                written.delete()
                null
            }
    }

    /** Deletes every working copy except the one at [keepUri] (null keeps none). Off main. */
    fun prune(context: Context, keepUri: String?) {
        val keep = keepUri
            ?.takeIf { isCopy(context, it) }
            ?.let { Uri.parse(it).lastPathSegment }
        pruneDir(File(context.filesDir, DIR), keep)
    }

    /** Deletes one working copy, e.g. a copy overtaken by a newer selection. Off main. */
    fun delete(context: Context, copy: Uri) {
        if (!isCopy(context, copy.toString())) return
        copy.lastPathSegment?.let { File(File(context.filesDir, DIR), it).delete() }
    }

    private fun extensionFor(mime: String?): String? =
        mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

    // --- pure parts, JVM-tested in SharedSourceCopiesTest ---

    /**
     * `<millis>-<name>[.<ext>]`, with [name] reduced to a safe file name. The extension is
     * appended only when the name lacks one, so FileProvider later reports the right MIME type
     * and the editor's kind detection sees the same name the sender gave.
     */
    internal fun copyFileName(millis: Long, name: String, extension: String?): String {
        val safe = name.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(MAX_NAME_CHARS)
            .ifEmpty { "shared" }
        val ext = extension?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        val withExt = if (ext != null && !safe.lowercase().endsWith(".$ext")) "$safe.$ext" else safe
        return "$millis-$withExt"
    }

    /**
     * Streams [input] to a temporary file and renames it to [target], so a failed or partial
     * copy never leaves a file the editor could adopt.
     */
    internal fun writeCopy(input: InputStream, dir: File, target: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val partial = File(dir, "${target.name}.partial")
        try {
            partial.outputStream().use { out -> input.copyTo(out) }
            if (!partial.renameTo(target)) throw IOException("cannot finish $target")
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
        return target
    }

    internal fun pruneDir(dir: File, keepName: String?) {
        dir.listFiles()?.forEach { file ->
            if (file.name != keepName) file.delete()
        }
    }

    private const val MAX_NAME_CHARS = 96
}
