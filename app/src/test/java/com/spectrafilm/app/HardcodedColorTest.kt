/*
 * Spektrafilm for Android — guard against hard-coded colours in feature code (#297). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Every colour either comes from MaterialTheme.colorScheme or is a named, documented,
 * theme-independent token in SpektraBrand (Theme.kt): the editor canvas and the content
 * drawn on it stay fixed so the photo is always judged against the same neutral ground.
 * A raw Color.White / Color.Black / Color(0x...) anywhere else fails this test.
 */
package com.spectrafilm.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HardcodedColorTest {

    private val allowed = setOf(
        "Theme.kt", // SpektraBrand: the one place literals live
        "MaterialSymbols.kt", // vector path default fill, replaced by Icon tint at draw time
    )
    private val literal = Regex("""\bColor\.(White|Black)\b|\bColor\(0x""")

    @Test
    fun featureCode_hasNoColourLiterals() {
        val root = sourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> literal.containsMatchIn(line) }
                    .map { (i, line) -> "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "Use MaterialTheme.colorScheme or a SpektraBrand token instead:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** Walk up from the working directory (module or repo root) to the app's source package. */
    private fun sourceRoot(): File {
        val rel = "app/src/main/java/com/spectrafilm/app"
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            File(current, rel).takeIf { it.isDirectory }?.let { return it }
            File(current, rel.removePrefix("app/")).takeIf { it.isDirectory }?.let { return it }
            current = current.parentFile
        }
        throw AssertionError("Could not locate $rel")
    }
}
