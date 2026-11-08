/*
 * Spektrafilm for Android — working copies of photos shared with a temporary grant (#508). GPLv3.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

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
 * outside the app can read it. Its file name is derived from the ORIGINAL URI, so sharing the
 * same photo again yields the same copy URI and therefore the same recipe (edits, rotation),
 * across restarts too. [prune] removes every copy that is neither the editor's current source
 * nor still in flight, so at most one settled copy remains.
 */
internal object SharedSourceCopies {
    const val DIR = "shared-sources"

    fun authority(context: Context): String = "${context.packageName}.sharedsources"

    /** True when the sender let us keep the grant, so the original URI can be persisted. */
    fun grantIsPersistable(intent: Intent): Boolean =
        intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0

    /** What an import produced: the URI to adopt and how it was obtained. */
    data class Imported(val source: Uri, val copied: Boolean, val durable: Boolean, val mime: String?)

    /** A copy started for selection [selection]; adopted by the editor whenever it is shown. */
    class PendingImport(val selection: Long, val original: Uri, val name: String, val result: Deferred<Imported>)

    /** The process-wide import in flight (or finished but not yet adopted), if any. */
    val pendingImport = MutableStateFlow<PendingImport?>(null)

    /**
     * True when [uri] is already readable through a persisted grant this app holds, either its
     * own or its folder's tree grant (a Library frame): nothing to copy, and keeping the
     * original URI keeps its recipe key.
     */
    fun alreadyDurable(uri: String, persistedReads: Set<String>): Boolean =
        uri in persistedReads || persistedReads.any { isDocumentInTree(it, uri) }

    /**
     * The original URI a working copy was made from, while this process still holds its
     * temporary grant; null for anything else. Used only to read GPS from the original
     * media (MediaStore redacts location in the stream the copy was made from). After a
     * restart the grant is gone anyway, so the copy's own metadata is the fallback.
     */
    fun originOf(uri: Uri?): Uri? = uri?.let { synchronized(lock) { origins[it.toString()] } }

    /** True when [uri] is one of our own working copies. */
    fun isCopy(context: Context, uri: String): Boolean =
        uri.startsWith("content://${authority(context)}/shared/")

    /**
     * Copies [uri] into the private directory and returns the copy's content URI, or null when
     * the copy could not be made (the caller then falls back to the original, temporary URI).
     * Runs blocking I/O: call it off the main thread.
     */
    fun copy(context: Context, uri: Uri, name: String, mime: String?): Uri? {
        val dir = File(context.filesDir, DIR)
        val target = File(dir, copyFileName(identityKey(uri.toString()), name, extensionFor(mime)))
        // Pending from before the first byte, so a concurrent [prune] spares the .partial file
        // and the finished copy until it is adopted or discarded. Counted: the same photo can
        // be shared twice at once, and both imports land on the same name.
        synchronized(lock) { pending[target.name] = (pending[target.name] ?: 0) + 1 }
        val written = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> writeCopy(input, dir, target) }
        }.getOrNull()
        val published = written?.let {
            runCatching { FileProvider.getUriForFile(context, authority(context), it) }.getOrNull()
        }
        synchronized(lock) {
            if (published == null) {
                // The target is untouched (writeCopy renames last) and may be the current
                // source from an earlier share of the same photo: leave it to [prune].
                release(target.name)
            } else {
                origins[published.toString()] = uri
            }
        }
        return published
    }

    /**
     * Deletes every working copy that is neither the editor's DURABLE source (read from the
     * source store now, under the lock, never from a caller's possibly stale snapshot) nor a
     * copy still in flight or awaiting adoption. Off main.
     */
    fun prune(context: Context) = synchronized(lock) {
        val current = runCatching { SharedPreferencesSourceRefStore(context).load()?.uri }
            .getOrElse { return@synchronized } // unknown source: delete nothing
            ?.takeIf { isCopy(context, it) }
            ?.let { Uri.parse(it).lastPathSegment }
        current?.let { pending -= it } // adopted: an ordinary source from here on
        pruneDir(File(context.filesDir, DIR), keep = pending.keys + setOfNotNull(current))
    }

    /**
     * Discards one import's copy, e.g. one overtaken by a newer selection. The file itself goes
     * only when no other import still holds it and it is not the editor's current source (the
     * same photo shared again lands on the same name). Off main.
     */
    fun delete(context: Context, copy: Uri) {
        if (!isCopy(context, copy.toString())) return
        val name = copy.lastPathSegment ?: return
        synchronized(lock) {
            release(name)
            val current = runCatching { SharedPreferencesSourceRefStore(context).load()?.uri }
                .getOrElse { return } // unknown source: keep the file
                ?.let { Uri.parse(it).lastPathSegment }
            if (name !in pending && name != current) File(File(context.filesDir, DIR), name).delete()
        }
    }

    private fun release(name: String) {
        val left = (pending[name] ?: 0) - 1
        if (left > 0) pending[name] = left else pending -= name
    }

    private fun extensionFor(mime: String?): String? =
        mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

    // --- pure parts, JVM-tested in SharedSourceCopiesTest ---

    /** A stable, path-safe key for an original URI: the first 16 hex digits of its SHA-256. */
    internal fun identityKey(originalUri: String): String =
        MessageDigest.getInstance("SHA-256").digest(originalUri.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    /**
     * `<key>-<name>[.<ext>]`, with [name] reduced to a safe file name. The extension is
     * appended only when the name lacks one, so FileProvider later reports the right MIME type
     * and the editor's kind detection sees the same name the sender gave.
     */
    internal fun copyFileName(key: String, name: String, extension: String?): String {
        val safe = name.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(MAX_NAME_CHARS)
            .ifEmpty { "shared" }
        val ext = extension?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        val withExt = if (ext != null && !safe.lowercase().endsWith(".$ext")) "$safe.$ext" else safe
        return "$key-$withExt"
    }

    /**
     * Streams [input] to a temporary file and renames it to [target], so a failed or partial
     * copy never leaves a file the editor could adopt.
     */
    internal fun writeCopy(input: InputStream, dir: File, target: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        // Unique per writer: two imports of the same photo write side by side, then each renames
        // atomically over the same identical target.
        val partial = File(dir, "${target.name}$PARTIAL${System.nanoTime()}")
        try {
            partial.outputStream().use { out -> input.copyTo(out) }
            if (!partial.renameTo(target)) throw IOException("cannot finish $target")
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
        return target
    }

    /** Deletes every file in [dir] except those named in [keep] and their in-flight partials. */
    internal fun pruneDir(dir: File, keep: Set<String>) {
        dir.listFiles()?.forEach { file ->
            // The full name first: a sender's own file name may contain ".partial-" too.
            val owner = PARTIAL_FILE.matchEntire(file.name)?.groupValues?.get(1)
            if (file.name !in keep && owner !in keep) file.delete()
        }
    }

    /** `<target>.partial-<nanos>`, the writer suffix [writeCopy] appends; group 1 is the target. */
    private val PARTIAL_FILE = Regex("""(.+)\.partial-\d+""")

    private const val PARTIAL = ".partial-"

    private const val MAX_NAME_CHARS = 96

    private val lock = Any()

    /** Copies written or being written but not yet adopted or discarded; guarded by [lock]. */
    private val pending = mutableMapOf<String, Int>()

    /** Copy URI -> the original it was made from, for this process only; guarded by [lock]. */
    private val origins = mutableMapOf<String, Uri>()
}
