/*
 * Spektrafilm for Android — unit tests for the Settings list-detail breakpoint. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPanesTest {

    @Test
    fun compactAndMedium_stayOnePane() {
        assertFalse(settingsTwoPane(412f)) // phone portrait
        assertFalse(settingsTwoPane(700f)) // unfolded foldable / tablet portrait
        assertFalse(settingsTwoPane(839f))
    }

    @Test
    fun expandedAndWider_goTwoPane() {
        assertTrue(settingsTwoPane(840f))
        assertTrue(settingsTwoPane(1280f))
    }

    @Test
    fun listPane_isTheM3FixedPaneWidth() {
        assertEquals(360f, sidePaneWidthDp(1000f), 0f)
        assertEquals(412f, sidePaneWidthDp(1280f), 0f)
    }

    @Test
    fun everyDetail_hasItsOwnTitle() {
        val titles = SettingsDetail.entries.map { it.titleRes }
        assertEquals(titles.size, titles.toSet().size)
    }
}
