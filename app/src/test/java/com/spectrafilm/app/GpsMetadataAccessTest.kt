/*
 * Spektrafilm for Android — GPS metadata access tests. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsMetadataAccessTest {

    @Test
    fun isMediaStoreBackedContentUri_forMediaStoreUri_isTrue() {
        assertTrue(isMediaStoreBackedContentUri("content", "media"))
    }

    @Test
    fun isMediaStoreBackedContentUri_forMediaDocumentUri_isTrue() {
        assertTrue(isMediaStoreBackedContentUri("content", "com.android.providers.media.documents"))
    }

    @Test
    fun isMediaStoreBackedContentUri_forCloudDocumentUri_isFalse() {
        assertFalse(isMediaStoreBackedContentUri("content", "com.google.android.apps.docs.storage"))
    }

    @Test
    fun isMediaStoreBackedContentUri_forFileUri_isFalse() {
        assertFalse(isMediaStoreBackedContentUri("file", null))
    }
}
