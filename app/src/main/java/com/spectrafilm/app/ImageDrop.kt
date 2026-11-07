/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Drag and drop of a photo into the editor (split screen on tablets and foldables, Samsung
 * DeX, ChromeOS, Android desktop windows). A drop is just another inbound content:// URI:
 * it goes through the same adoptIncomingImage() dispatch as a VIEW/SEND intent or a picked
 * document, whose content sniffing is fail-closed (#190), so this file only decides whether
 * a drag is worth accepting and which URI it carries.
 */
package com.spectrafilm.app

import android.content.ClipData
import android.content.ContentResolver
import android.net.Uri

/**
 * Accept a drag that offers an image, or a generic binary (file managers often label a .dng
 * or other RAW as application/octet-stream; the decode route is content-sniffed anyway).
 * Text, links and everything else are refused so the editor does not light up for them.
 */
internal fun isDroppableImage(mimeTypes: Collection<String>): Boolean =
    mimeTypes.any { mime ->
        val m = mime.lowercase()
        m.startsWith("image/") || m == "application/octet-stream"
    }

/** The first content:// URI in a drop; a file:// path is never adopted (same rule as #162). */
internal fun droppedContentUri(clip: ClipData?): Uri? {
    clip ?: return null
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) return uri
    }
    return null
}
