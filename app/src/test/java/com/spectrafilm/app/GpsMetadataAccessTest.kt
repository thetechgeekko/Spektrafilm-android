/*
 * Spektrafilm for Android — GPS metadata access tests. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsMetadataAccessTest {

    @Test
    fun isMediaStoreBackedContentUri_forMediaStoreUri_isTrue() {
        assertTrue(isMediaStoreBackedContentUri(Uri.parse("content://media/external/images/media/42")))
    }

    @Test
    fun isMediaStoreBackedContentUri_forMediaDocumentUri_isTrue() {
        assertTrue(
            isMediaStoreBackedContentUri(
                Uri.parse("content://com.android.providers.media.documents/document/image:42"),
            ),
        )
    }

    @Test
    fun isMediaStoreBackedContentUri_forCloudDocumentUri_isFalse() {
        assertFalse(
            isMediaStoreBackedContentUri(
                Uri.parse("content://com.google.android.apps.docs.storage/document/abc"),
            ),
        )
    }

    @Test
    fun isMediaStoreBackedContentUri_forFileUri_isFalse() {
        assertFalse(isMediaStoreBackedContentUri(Uri.parse("file:///sdcard/DCIM/Camera/photo.jpg")))
    }
}
