/*
 * Spektrafilm for Android — unit tests for the update version compare. GPLv3.
 * AppUpdater.isNewer is pure semver logic; runs on the plain JVM.
 */
package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test fun newerByPatchMinorMajor() {
        assertTrue(AppUpdater.isNewer("0.5.0", "v0.5.1"))
        assertTrue(AppUpdater.isNewer("0.5.0", "v0.6.0"))
        assertTrue(AppUpdater.isNewer("0.5.0", "v1.0.0"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(AppUpdater.isNewer("0.5.0", "v0.5.0"))
        assertFalse(AppUpdater.isNewer("0.5.0", "v0.4.9"))
        assertFalse(AppUpdater.isNewer("1.0.0", "v0.9.9"))
    }

    @Test fun tolerantOfPrefixAndShortForms() {
        assertTrue(AppUpdater.isNewer("v0.5", "0.5.1"))      // missing patch = 0
        assertFalse(AppUpdater.isNewer("0.5.0", "0.5.0-rc1")) // pre-release core equal
    }

    @Test fun unparseableIsNotNewer() {
        assertFalse(AppUpdater.isNewer("0.5.0", "nightly"))
        assertFalse(AppUpdater.isNewer("weird", "v9.9.9"))
    }
}
