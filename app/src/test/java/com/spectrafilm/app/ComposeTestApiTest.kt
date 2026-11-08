/*
 * Spektrafilm for Android — keep Compose UI tests on the v2 test APIs (#470). GPLv3.
 */
package com.spectrafilm.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1 Compose test entry points (createComposeRule, runComposeUiTest, ...) run effects on
 * an UnconfinedTestDispatcher and are deprecated; the v2 ones in `...junit4.v2` /
 * `...test.v2` use a StandardTestDispatcher, which queues coroutines like the real app does
 * (MAD checklist #66). A test that needs work to finish calls waitForIdle()/runOnIdle{}.
 * This stops a new test from quietly importing the v1 rule.
 */
class ComposeTestApiTest {

    private val v1 = Regex(
        """import androidx\.compose\.ui\.test\.(junit4\.)?(createComposeRule|createAndroidComposeRule|""" +
            """createEmptyComposeRule|runComposeUiTest|runAndroidComposeUiTest|runEmptyComposeUiTest)\b""",
    )

    @Test
    fun composeTestsUseTheV2Apis() {
        val root = repoRoot()
        val offenders = listOf("app/src/test", "app/src/androidTest")
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name != "ComposeTestApiTest.kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> v1.containsMatchIn(line) }
                    .map { (i, line) -> "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" }
            }
        assertTrue(
            "Use the v2 Compose test APIs (androidx.compose.ui.test.junit4.v2 / androidx.compose.ui.test.v2):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun repoRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            if (File(current, "app/src/test").isDirectory) return current
            if (File(current, "src/test").isDirectory && current.name == "app") return current.parentFile
            current = current.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
