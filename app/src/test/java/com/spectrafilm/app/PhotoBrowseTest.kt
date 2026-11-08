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
}
