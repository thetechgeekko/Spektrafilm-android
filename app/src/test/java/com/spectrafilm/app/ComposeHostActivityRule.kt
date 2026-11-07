package com.spectrafilm.app

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Registers the host [ComponentActivity] that `createComposeRule` launches with Robolectric's
 * package manager. Under `testImplementation`, ui-test-manifest's activity entry is not merged
 * into the unit-test manifest, so without this every Compose UI test fails to resolve its host.
 * Apply it at `@get:Rule(order = 0)`, before the compose rule (#280).
 */
class ComposeHostActivityRule : ExternalResource() {
    override fun before() {
        val app = RuntimeEnvironment.getApplication()
        val host = ComponentName(app, ComponentActivity::class.java)
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(host)
        pm.addIntentFilterForActivity(
            host,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }
}
