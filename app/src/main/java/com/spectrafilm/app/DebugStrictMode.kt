/*
 * Spektrafilm for Android — StrictMode for debuggable builds (#464). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode

/**
 * Turns StrictMode on in debuggable builds only (audit P4, MAD checklist #27): disk and
 * network work on the main thread, custom slow calls, leaked closeables/SQLite cursors,
 * leaked Activities and non-SDK API use are all LOGGED (tag "StrictMode"), never fatal, so a
 * violation shows up in logcat during development without changing app behaviour.
 *
 * Release builds, including the release instrumentation APK, are not debuggable and never
 * reach this. The check reads FLAG_DEBUGGABLE rather than BuildConfig, which this app does
 * not generate.
 *
 * Known, explained violations (do not "fix" by silencing): the bounded settings flush in
 * MainActivity.onStop (#448 removes the wait) and first-launch asset/profile reads that
 * AppContainer performs lazily.
 */
internal object DebugStrictMode {
    fun installIfDebuggable(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) detectNonSdkApiUsage() }
                .penaltyLog()
                .build(),
        )
    }
}
