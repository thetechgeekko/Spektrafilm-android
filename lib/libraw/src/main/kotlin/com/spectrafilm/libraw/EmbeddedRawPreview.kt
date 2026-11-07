/*
 * Spektrafilm for Android -- a camera JPEG embedded in a RAW. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.libraw

/**
 * The JPEG a camera embedded in a RAW file ([RawDecoder.extractEmbeddedPreview]).
 *
 * [width]/[height] are what the container records (0 when it does not say); decode the JPEG
 * for the real size. The pixels are stored in sensor orientation: [clockwiseDegrees] is the
 * rotation that shows them upright.
 *
 * Display only. This is the camera's own rendering, not the scene-linear decode the engine
 * works from, so it must never be fed to the engine or written to an export.
 */
class EmbeddedRawPreview(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    /** LibRaw `sizes.flip`: 0 upright, 3 = 180 degrees, 5 = 90 CCW, 6 = 90 CW. */
    val libRawFlip: Int,
) {
    val clockwiseDegrees: Int
        get() = clockwiseDegreesForFlip(libRawFlip)

    companion object {
        /** LibRaw flip code to the clockwise rotation that uprights the frame. */
        fun clockwiseDegreesForFlip(flip: Int): Int = when (flip) {
            3 -> 180
            5 -> 270
            6 -> 90
            else -> 0
        }
    }
}
