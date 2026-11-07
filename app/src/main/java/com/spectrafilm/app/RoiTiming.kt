/*
 * Spektrafilm for Android — zoom (ROI) render timing. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

/**
 * Milestones of one zoom (ROI) render, in ms from the moment the pinch/pan settled:
 * the full-resolution source ready ([sourceMs]), the draft rendered ([draftMs]) and the
 * sharp final rendered ([finalMs]). A milestone the render never reached stays -1.
 *
 * Logged as one line per ROI render so a device run shows where zoom time goes before
 * any of it is optimised (rawr study, step 1). Display only; it changes no behaviour.
 */
internal class RoiTiming(private val nowNs: () -> Long = System::nanoTime) {
    private val startNs = nowNs()
    var sourceMs = -1L
    var draftMs = -1L
    var finalMs = -1L
    var cropW = 0
    var cropH = 0

    fun sinceStartMs(): Long = (nowNs() - startNs) / 1_000_000L

    /** `zoom roi done crop=1200x900 source=12ms draft=180ms final=640ms total=650ms`. */
    fun line(outcome: String): String =
        "zoom roi $outcome crop=${cropW}x$cropH source=${ms(sourceMs)} " +
            "draft=${ms(draftMs)} final=${ms(finalMs)} total=${sinceStartMs()}ms"

    private fun ms(v: Long): String = if (v < 0) "-" else "${v}ms"
}
