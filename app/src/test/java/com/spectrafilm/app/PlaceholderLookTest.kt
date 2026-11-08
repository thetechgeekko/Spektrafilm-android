/*
 * Spektrafilm for Android — film-look open placeholder sizing. GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderLookTest {
    @Test
    fun aLargeThumbnailRendersAtTheCap() {
        assertEquals(PlaceholderLook.RENDER_EDGE_PX, PlaceholderLook.renderEdge(1024, 683))
    }

    @Test
    fun aSmallThumbnailIsNeverUpscaled() {
        assertEquals(160, PlaceholderLook.renderEdge(160, 120))
        assertEquals(120, PlaceholderLook.renderEdge(90, 120))
    }

    @Test
    fun aDegenerateThumbnailStillGetsAPositiveEdge() {
        assertEquals(1, PlaceholderLook.renderEdge(0, 0))
    }
}
