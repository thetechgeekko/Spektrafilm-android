/*
 * Spektrafilm for Android — guard for how the app talks to the user. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * MAD audit fix 3: messages go through a Snackbar (showEditorMessage or a screen's
 * SnackbarHost), never a Toast, so they follow the theme, can carry an action such as
 * Retry, and are announced like the rest of the UI. This keeps a Toast from coming back.
 */
package com.spectrafilm.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageGuardTest {
    @Test fun noToastsInAppCode() {
        val root = findDir("app/src/main/java/com/spectrafilm/app")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    if ("Toast.makeText" in code || "import android.widget.Toast" in code) {
                        "${file.relativeTo(root)}:${i + 1}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertTrue("Use a Snackbar (showEditorMessage), not a Toast: $offenders", offenders.isEmpty())
    }

    private fun findDir(rel: String): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            File(current, rel).takeIf { it.isDirectory }?.let { return it }
            File(current, rel.removePrefix("app/")).takeIf { it.isDirectory }?.let { return it }
            current = current.parentFile
        }
        throw AssertionError("Could not locate $rel")
    }
}
