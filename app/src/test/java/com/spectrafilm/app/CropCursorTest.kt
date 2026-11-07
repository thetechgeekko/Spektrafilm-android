/*
 * Spektrafilm for Android — crop overlay mouse-cursor mapping tests. GPLv3.
 */
package com.spectrafilm.app

import android.view.PointerIcon
import org.junit.Assert.assertEquals
import org.junit.Test

class CropCursorTest {

    @Test
    fun cornersEdgesAndBodyGetDesktopCropCursors() {
        assertEquals(PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW, cropCursorType(Handle.TL))
        assertEquals(PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW, cropCursorType(Handle.BR))
        assertEquals(PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW, cropCursorType(Handle.TR))
        assertEquals(PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW, cropCursorType(Handle.BL))
        assertEquals(PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW, cropCursorType(Handle.L))
        assertEquals(PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW, cropCursorType(Handle.R))
        assertEquals(PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW, cropCursorType(Handle.T))
        assertEquals(PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW, cropCursorType(Handle.B))
        assertEquals(PointerIcon.TYPE_GRAB, cropCursorType(Handle.MOVE))
        assertEquals(PointerIcon.TYPE_ARROW, cropCursorType(Handle.NONE))
    }
}
