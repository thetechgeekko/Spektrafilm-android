/*
 * Spektrafilm for Android -- bringing photos from outside the Library into an album. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Two ways in. A picker (photo picker, or the files picker for RAWs) hands over grants the app
 * may keep, so the item points at the original. A share from another app usually carries only
 * a temporary grant (see SharedSourceCopies), so the file is copied while that grant lasts into
 * filesDir/imported and served by the app's own non-exported FileProvider.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Turns an outside URI into an album item, or null when it is not a photo it can open. Blocking. */
internal fun interface AlbumImporter {
    fun import(uri: String, keepGrant: Boolean): AlbumItem?
}

internal object AlbumImports {
    const val DIR = "imported"

    /** The URIs of a SEND_MULTIPLE (or a single SEND) share, content:// only. */
    fun sharedUris(intent: Intent?): List<Uri> {
        if (intent?.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        val list: List<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return list.orEmpty().filter { it.scheme == android.content.ContentResolver.SCHEME_CONTENT }.distinct()
    }

    /** True when [uri] is one of the Imported album's own copies (FileProvider path "imported"). */
    fun isCopy(context: Context, uri: String): Boolean =
        uri.startsWith("content://${SharedSourceCopies.authority(context)}/imported/")

    /** True for a file the Library would list: a photo or a known RAW. */
    fun isFrame(name: String, mime: String?): Boolean =
        libraryEntriesOf(listOf(LibraryEntry("content://x", name, mime, 0L, 0L))).isNotEmpty()

    fun androidImporter(context: Context): AlbumImporter {
        val app = context.applicationContext
        return AlbumImporter { raw, keepGrant ->
            val uri = Uri.parse(raw)
            val resolver = app.contentResolver
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
            var size = -1L
            var modified = 0L
            runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                            ?.let { i -> c.getString(i)?.let { name = it } }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }
                            ?.let { size = c.getLong(it) }
                        c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            .takeIf { it >= 0 && !c.isNull(it) }
                            ?.let { modified = c.getLong(it) }
                        // The photo picker reports MediaStore's date taken instead.
                        if (modified == 0L) {
                            c.getColumnIndex("datetaken").takeIf { it >= 0 && !c.isNull(it) }
                                ?.let { modified = c.getLong(it) }
                        }
                    }
                }
            }
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            if (!isFrame(name, mime)) return@AlbumImporter null
            if (modified <= 0L) modified = System.currentTimeMillis()
            val kept = keepGrant && runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            val itemUri = if (kept) uri else copy(app, uri, name, mime) ?: return@AlbumImporter null
            AlbumItem(itemUri.toString(), name, mime, modified, size, treeUri = null)
        }
    }

    /** Copies [uri] into the imported folder; the same original always lands on the same copy. */
    private fun copy(context: Context, uri: Uri, name: String, mime: String?): Uri? {
        val dir = File(context.filesDir, DIR)
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val target = File(
            dir,
            SharedSourceCopies.copyFileName(SharedSourceCopies.identityKey(uri.toString()), name, ext),
        )
        if (!target.isFile) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                SharedSourceCopies.writeCopy(input, dir, target)
            } ?: return null
        }
        return FileProvider.getUriForFile(context, SharedSourceCopies.authority(context), target)
    }

    /** Deletes the imported copies whose URIs are not in [keep]. */
    fun prune(context: Context, keep: Set<String>) {
        val app = context.applicationContext
        val dir = File(app.filesDir, DIR)
        val names = dir.listFiles()?.mapNotNullTo(HashSet()) { file ->
            val published = runCatching {
                FileProvider.getUriForFile(app, SharedSourceCopies.authority(app), file).toString()
            }.getOrNull()
            file.name.takeIf { published != null && published in keep }
        }.orEmpty()
        SharedSourceCopies.pruneDir(dir, names)
    }
}
