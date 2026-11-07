package com.spectrafilm.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A translation may lag behind English (missing keys fall back to English at runtime), but it
 * must never carry a key English no longer has: that means the English string was renamed or
 * removed and the translated text now shows nowhere, silently. The editor redesign renames
 * strings often, so this fails the build instead of letting a translation rot.
 */
class TranslationKeysTest {
    private val nameAttr = Regex("""<(?:string|plurals|string-array)\s+name="([^"]+)"""")

    private fun resDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/res")
            if (f.isDirectory) return f
            val g = File(dir, "src/main/res")
            if (g.isDirectory && File(dir, "build.gradle.kts").isFile) return g
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate app/src/main/res from ${System.getProperty("user.dir")}")
    }

    private fun keys(folder: File): Set<String> =
        folder.listFiles { f -> f.isFile && f.extension == "xml" }.orEmpty()
            .flatMap { f -> nameAttr.findAll(f.readText()).map { it.groupValues[1] }.toList() }
            .toSet()

    @Test fun `every translated key still exists in English`() {
        val res = resDir()
        val english = keys(File(res, "values"))
        assertTrue("English strings not found under $res/values", english.size > 100)

        val stale = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .filter { it.listFiles().orEmpty().any { f -> f.name.startsWith("strings") } }
            .associate { dir -> dir.name to (keys(dir) - english).sorted() }
            .filterValues { it.isNotEmpty() }

        assertTrue("Translated keys missing from English: $stale", stale.isEmpty())
    }

    /**
     * Every app translation folder must be listed in app/build.gradle.kts `localeFilters`,
     * otherwise the translation is stripped from the APK and never offered.
     */
    @Test fun `every translation is kept by localeFilters`() {
        val res = resDir()
        val gradle = File(res.parentFile.parentFile.parentFile, "build.gradle.kts").readText()
        val filters = Regex("""localeFilters\s*\+=\s*listOf\(([^)]*)\)""").find(gradle)
            ?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') }?.toSet()
            ?: throw AssertionError("localeFilters not found in app/build.gradle.kts")
        val translated = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .filter { it.listFiles().orEmpty().any { f -> f.name.startsWith("strings") } }
            .map { it.name.removePrefix("values-") }
        val dropped = translated.filterNot { it in filters }
        assertTrue("Translations missing from localeFilters: $dropped", dropped.isEmpty())
    }
}
