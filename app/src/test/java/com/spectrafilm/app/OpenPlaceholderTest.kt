/*
 * Spektrafilm for Android — instant first pixels on open (#286). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPlaceholderTest {

    private fun exif(tag: Int) = ExifOrientation.fromExif(tag)

    @Test
    fun uprightSourceWithNoUserStepIsIdentity() {
        val t = placeholderTransform(exif(ExifInterface.ORIENTATION_NORMAL), SourceRotation.NONE)
        assertTrue(t.isIdentity)
    }

    @Test
    fun exifRotationAndUserStepCompose() {
        // A portrait shot stored sideways (ROTATE_90), then the user's own 90 degree step.
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 180),
            placeholderTransform(exif(ExifInterface.ORIENTATION_ROTATE_90), SourceRotation.CW90),
        )
        // Wraps past 360.
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 0),
            placeholderTransform(exif(ExifInterface.ORIENTATION_ROTATE_270), SourceRotation.CW90),
        )
    }

    @Test
    fun mirroredOrientationsKeepTheFlip() {
        // TRANSVERSE = rotate 270 then mirror; the user's step adds to the rotation only.
        assertEquals(
            PlaceholderTransform(flipH = true, degrees = 0),
            placeholderTransform(exif(ExifInterface.ORIENTATION_TRANSVERSE), SourceRotation.CW90),
        )
        assertEquals(
            PlaceholderTransform(flipH = true, degrees = 0),
            placeholderTransform(exif(ExifInterface.ORIENTATION_FLIP_HORIZONTAL), SourceRotation.NONE),
        )
    }

    @Test
    fun providerThumbnailGetsOnlyTheUserStep() {
        // The provider path passes NONE for EXIF (MediaStore thumbnails are already upright).
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 270),
            placeholderTransform(ExifOrientation.NONE, SourceRotation.CW270),
        )
    }

    @Test
    fun sidewaysDngProviderThumbnailIsRotated() {
        // The phone case: DNG stored 4080x3060 landscape with ROTATE_90; provider returned a
        // landscape 582x437 thumbnail, so the provider did not rotate it.
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 90),
            providerTransform(
                exif(ExifInterface.ORIENTATION_ROTATE_90), 4080, 3060, 582, 437,
                rawSource = true, user = SourceRotation.NONE,
            ),
        )
    }

    @Test
    fun alreadyUprightProviderThumbnailIsLeftAlone() {
        // Portrait JPEG stored landscape + ROTATE_90; MediaStore returned it portrait.
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 0),
            providerTransform(
                exif(ExifInterface.ORIENTATION_ROTATE_90), 4000, 3000, 437, 582,
                rawSource = false, user = SourceRotation.NONE,
            ),
        )
        // Same for a RAW whose provider did rotate: the aspect decides, not the kind.
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 0),
            providerTransform(
                exif(ExifInterface.ORIENTATION_ROTATE_90), 4080, 3060, 437, 582,
                rawSource = true, user = SourceRotation.NONE,
            ),
        )
    }

    @Test
    fun unknownSizeFallsBackOnTheSourceKind() {
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 270),
            providerTransform(
                exif(ExifInterface.ORIENTATION_ROTATE_270), 0, 0, 582, 437,
                rawSource = true, user = SourceRotation.NONE,
            ),
        )
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 0),
            providerTransform(
                exif(ExifInterface.ORIENTATION_ROTATE_180), 4000, 3000, 582, 437,
                rawSource = false, user = SourceRotation.NONE,
            ),
        )
    }

    @Test
    fun userStepStillAppliesOnTop() {
        assertEquals(
            PlaceholderTransform(flipH = false, degrees = 180),
            providerTransform(
                exif(ExifInterface.ORIENTATION_ROTATE_90), 4080, 3060, 582, 437,
                rawSource = true, user = SourceRotation.CW90,
            ),
        )
    }
}
