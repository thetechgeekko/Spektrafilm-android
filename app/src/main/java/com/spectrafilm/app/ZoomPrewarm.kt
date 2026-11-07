/*
 * Spektrafilm for Android — background decode of the zoom source. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.app.ActivityManager
import android.content.Context

/**
 * Zoom step 4 (rawr study): the first pinch past fit used to wait on a full MAX_EDGE_PX decode
 * of the source (LibRaw for a RAW, ~1-2.6 s on the test phone) before the first sharp crop could
 * even start. After a fit preview settles and the editor has been idle for [IDLE_DELAY_MS], we
 * decode that zoom source in the background into the existing single-entry zoom cache, so the
 * first zoom starts from a cache hit. Any edit before then cancels the wait (the next settle
 * schedules it again), and a zoom that arrives while the decode runs joins it through the zoom
 * decode single-flight instead of starting a second one.
 *
 * The cost is one held decoded proxy (about 37 MB for a 2048x1536 float RGB image) whether or
 * not the user zooms, so it only runs on devices with more than 6 GiB of RAM that do not
 * declare themselves low-RAM. Export never reads this cache, so export speed is unaffected.
 */
internal object ZoomPrewarm {
    /** Idle time after a settled fit preview before the background decode starts. */
    const val IDLE_DELAY_MS = 1_200L

    /** True when holding a spare zoom proxy is cheap enough for this device. */
    fun eligible(physicalBytes: Long, lowRamDevice: Boolean): Boolean {
        if (lowRamDevice) return false
        return when (deviceMemoryPolicy(physicalBytes).tier) {
            DeviceMemoryTier.LARGE, DeviceMemoryTier.XLARGE, DeviceMemoryTier.XXLARGE -> true
            DeviceMemoryTier.COMPACT, DeviceMemoryTier.STANDARD -> false
        }
    }

    fun eligible(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return eligible(mem.totalMem, am.isLowRamDevice)
    }
}
