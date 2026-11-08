/*
 * Spektrafilm for Android — swipe-to-next rules. GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoBrowseTest {
    private val frames = listOf(
        BrowseFrame("content://a", raw = false),
        BrowseFrame("content://b", raw = true),
        BrowseFrame("content://c", raw = false),
    )

    @Test
    fun neighboursFollowTheGridOrder() {
        assertEquals("content://c", neighbourIn(frames, "content://b", 1)?.uri)
        assertEquals("content://a", neighbourIn(frames, "content://b", -1)?.uri)
    }

    @Test
    fun theEndsAndForeignPhotosHaveNoNeighbour() {
        assertNull(neighbourIn(frames, "content://c", 1))
        assertNull(neighbourIn(frames, "content://a", -1))
        assertNull(neighbourIn(frames, "content://picked", 1))
        assertNull(neighbourIn(frames, null, 1))
    }

    private fun commit(dx: Float, v: Float, next: Boolean = true, prev: Boolean = true) =
        PhotoSwipeRules.commitStep(dx, v, 1000f, 1000f, 8f, next, prev)

    @Test
    fun aLongSwipeLeftOpensTheNextAndRightThePrevious() {
        assertEquals(1, commit(-700f, 0f))
        assertEquals(-1, commit(700f, 0f))
    }

    @Test
    fun aShortSlowSwipeSpringsBack() {
        assertEquals(0, commit(-300f, -200f))
    }

    @Test
    fun aFlickOpensOnlyInTheDirectionItMoves() {
        assertEquals(1, commit(-60f, -2000f))
        // Dragged left, flicked back right: no commit.
        assertEquals(0, commit(-60f, 2000f))
    }

    @Test
    fun nothingOpensPastTheEnds() {
        assertEquals(0, commit(-900f, -3000f, next = false))
        assertEquals(0, commit(900f, 3000f, prev = false))
    }

    @Test
    fun theEndsResistTheFinger() {
        assertEquals(-100f, PhotoSwipeRules.shownOffset(-300f, hasNext = false, hasPrevious = true), 1e-4f)
        assertEquals(-300f, PhotoSwipeRules.shownOffset(-300f, hasNext = true, hasPrevious = false), 0f)
    }

    @Test
    fun thePhotoShrinksAndRoundsAsItLeaves() {
        assertEquals(1f, PhotoSwipeRules.scaleAt(0f), 0f)
        assertEquals(PhotoSwipeRules.MIN_SCALE, PhotoSwipeRules.scaleAt(1f), 1e-6f)
        assertEquals(0f, PhotoSwipeRules.cornerDpAt(0f), 0f)
        assertEquals(PhotoSwipeRules.MAX_CORNER_DP, PhotoSwipeRules.cornerDpAt(0.25f), 1e-4f)
        assertEquals(0.5f, PhotoSwipeRules.progress(-500f, 1000f), 0f)
    }

    @Test
    fun theStripKeepsTheOpenFrameSecondFromTheLeft() {
        assertEquals(0, folderStripScrollTarget(0))
        assertEquals(0, folderStripScrollTarget(1))
        assertEquals(6, folderStripScrollTarget(7))
    }

    @Test
    fun theStripFindsTheOpenFrame() {
        val saved = PhotoBrowse.frames
        try {
            PhotoBrowse.frames = frames
            assertEquals(1, PhotoBrowse.indexOf("content://b"))
            assertEquals(-1, PhotoBrowse.indexOf("content://z"))
            assertEquals(-1, PhotoBrowse.indexOf(null))
        } finally {
            PhotoBrowse.frames = saved
        }
    }

    @Test
    fun aNeighbourOpensWithItsOwnRecipe() {
        val own = ParamsState().apply { filmProfile = "fujifilm_pro_400h" }
        val doc = RecipeDocument("k", "b.jpg", 0L, 0, Presets.encode(own))
        var defaulted = false
        val s = openingStateFor(doc) { defaulted = true }
        assertEquals("fujifilm_pro_400h", s.filmProfile)
        assertEquals(false, defaulted)
    }

    @Test
    fun aNeighbourWithoutARecipeOpensWithTheDefaults() {
        val s = openingStateFor(null) { it.filmProfile = "kodak_gold_200" }
        assertEquals("kodak_gold_200", s.filmProfile)
    }

    @Test
    fun aWorkingCopyStillFindsTheNeighboursOfTheFrameItStandsFor() {
        val saved = PhotoBrowse.frames
        try {
            PhotoBrowse.frames = listOf(
                BrowseFrame("content://media/external/images/media/1", false),
                BrowseFrame("content://media/external/images/media/2", false),
                BrowseFrame("content://media/external/images/media/3", false),
            )
            PhotoBrowse.rememberOrigin("content://app.sharedsources/shared/copy-2.jpg", "content://media/external/images/media/2")
            val copy = "content://app.sharedsources/shared/copy-2.jpg"
            assertEquals("content://media/external/images/media/3", PhotoBrowse.neighbour(copy, 1)?.uri)
            assertEquals("content://media/external/images/media/1", PhotoBrowse.neighbour(copy, -1)?.uri)
            assertEquals(1, PhotoBrowse.indexOf(copy))
        } finally {
            PhotoBrowse.frames = saved
        }
    }
}
