/*
 * Spektrafilm for Android — drag-and-drop acceptance tests. GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDropTest {

    @Test
    fun imagesAndGenericBinariesAreAccepted() {
        assertTrue(isDroppableImage(listOf("image/jpeg")))
        assertTrue(isDroppableImage(listOf("image/x-adobe-dng")))
        assertTrue(isDroppableImage(listOf("IMAGE/HEIC")))
        assertTrue(isDroppableImage(listOf("text/plain", "image/png")))
        assertTrue(isDroppableImage(listOf("application/octet-stream")))
    }

    @Test
    fun textLinksAndEmptyDragsAreRefused() {
        assertFalse(isDroppableImage(emptyList()))
        assertFalse(isDroppableImage(listOf("text/plain")))
        assertFalse(isDroppableImage(listOf("text/uri-list")))
        assertFalse(isDroppableImage(listOf("video/mp4")))
        assertFalse(isDroppableImage(listOf("application/json")))
    }
}
