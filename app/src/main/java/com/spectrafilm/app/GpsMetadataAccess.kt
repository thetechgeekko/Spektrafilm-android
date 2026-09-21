/*
 * Spektrafilm for Android — GPS metadata access helpers. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.Locale

/**
 * Android 10+ redacts location EXIF from many MediaStore-backed content Uris unless the app holds
 * ACCESS_MEDIA_LOCATION and reads via MediaStore.setRequireOriginal(). SAF/cloud Uris are not part
 * of that contract, so do not force the extra permission there.
 */
internal fun isMediaStoreBackedContentUri(sourceUri: Uri?): Boolean {
    return isMediaStoreBackedContentUri(
        scheme = sourceUri?.scheme,
        authority = sourceUri?.authority,
    )
}

internal fun isMediaStoreBackedContentUri(scheme: String?, authority: String?): Boolean {
    if (scheme != ContentResolver.SCHEME_CONTENT) return false
    val normalizedAuthority = authority?.lowercase(Locale.ROOT) ?: return false
    return normalizedAuthority == MediaStore.AUTHORITY ||
        normalizedAuthority.contains("providers.media")
}

internal fun requiresMediaLocationPermissionForGpsExport(sourceUri: Uri?): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || sourceUri == null) return false
    return isMediaStoreBackedContentUri(sourceUri)
}

/** Upgrade [sourceUri] to its original-media form when Android's MediaStore GPS redaction applies. */
internal fun originalMediaUriForGpsMetadata(sourceUri: Uri?): Uri? {
    if (!requiresMediaLocationPermissionForGpsExport(sourceUri)) return sourceUri
    return runCatching { MediaStore.setRequireOriginal(requireNotNull(sourceUri)) }
        .getOrDefault(sourceUri)
}
