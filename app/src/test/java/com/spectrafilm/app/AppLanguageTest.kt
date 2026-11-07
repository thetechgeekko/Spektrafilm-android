package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test fun `no choice before Android 13`() {
        assertFalse(shouldOfferLanguageChoice(sdkInt = 32, shippedLocaleCount = 2))
    }

    @Test fun `no choice while only one language ships`() {
        assertFalse(shouldOfferLanguageChoice(sdkInt = 35, shippedLocaleCount = 1))
        assertFalse(shouldOfferLanguageChoice(sdkInt = 35, shippedLocaleCount = 0))
    }

    @Test fun `choice once a translation ships on Android 13+`() {
        assertTrue(shouldOfferLanguageChoice(sdkInt = 33, shippedLocaleCount = 2))
    }
}
