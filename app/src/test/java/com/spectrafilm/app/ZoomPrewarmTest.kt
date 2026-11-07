/*
 * Spektrafilm for Android — background decode of the zoom source. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomPrewarmTest {
    private val gib = 1024L * 1024L * 1024L

    @Test
    fun onlyRoomyDevicesPrewarm() {
        assertFalse(ZoomPrewarm.eligible(0L, lowRamDevice = false))
        assertFalse(ZoomPrewarm.eligible(4L * gib, lowRamDevice = false))
        assertFalse(ZoomPrewarm.eligible(6L * gib, lowRamDevice = false))
        assertTrue(ZoomPrewarm.eligible(8L * gib, lowRamDevice = false))
        assertTrue(ZoomPrewarm.eligible(12L * gib, lowRamDevice = false))
    }

    @Test
    fun lowRamDevicesNeverPrewarm() {
        assertFalse(ZoomPrewarm.eligible(12L * gib, lowRamDevice = true))
    }
}
