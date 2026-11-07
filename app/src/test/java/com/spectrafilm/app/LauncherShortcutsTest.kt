/*
 * Spektrafilm for Android — launcher shortcut wiring (#467). GPLv3.
 */
package com.spectrafilm.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The shortcut XML, the manifest and [LauncherShortcut] are three spellings of the same two
 * actions; a typo in any one makes a launcher shortcut open the app and do nothing.
 */
class LauncherShortcutsTest {

    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun actionsMapToShortcutsAndNothingElse() {
        for (s in LauncherShortcut.entries) assertEquals(s, LauncherShortcut.fromAction(s.action))
        assertNull(LauncherShortcut.fromAction("android.intent.action.MAIN"))
        assertNull(LauncherShortcut.fromAction("android.intent.action.VIEW"))
        assertNull(LauncherShortcut.fromAction(null))
    }

    @Test
    fun shortcutsXmlTargetsMainActivityWithKnownActions() {
        val shortcuts = elements(xml("app/src/main/res/xml/shortcuts.xml").documentElement, "shortcut")
        assertEquals(LauncherShortcut.entries.size, shortcuts.size)
        val actions = shortcuts.map { shortcut ->
            val intent = elements(shortcut, "intent").single()
            assertEquals("com.spectrafilm.app", intent.getAttributeNS(android, "targetPackage"))
            assertEquals("com.spectrafilm.app.MainActivity", intent.getAttributeNS(android, "targetClass"))
            for (attr in listOf("shortcutShortLabel", "shortcutLongLabel")) {
                val ref = shortcut.getAttributeNS(android, attr)
                assertTrue("$attr must be a string resource", ref.startsWith("@string/"))
                assertTrue("missing $ref", stringNames().contains(ref.removePrefix("@string/")))
            }
            val icon = shortcut.getAttributeNS(android, "icon").removePrefix("@drawable/")
            assertTrue("missing icon $icon", repoFile("app/src/main/res/drawable/$icon.xml").isFile)
            intent.getAttributeNS(android, "action")
        }.toSet()
        assertEquals(LauncherShortcut.entries.map { it.action }.toSet(), actions)
    }

    @Test
    fun launcherActivityPublishesTheShortcuts() {
        val manifest = xml("app/src/main/AndroidManifest.xml").documentElement
        val main = elements(manifest, "activity").single { it.getAttributeNS(android, "name") == ".MainActivity" }
        val meta = elements(main, "meta-data").single { it.getAttributeNS(android, "name") == "android.app.shortcuts" }
        assertEquals("@xml/shortcuts", meta.getAttributeNS(android, "resource"))
    }

    private fun stringNames(): Set<String> =
        repoFile("app/src/main/res/values").listFiles { f -> f.name.startsWith("strings") }!!
            .flatMap { f -> elements(xml(f).documentElement, "string").map { it.getAttribute("name") } }
            .toSet()

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun xml(relativePath: String): Document = xml(repoFile(relativePath))

    private fun xml(file: File): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(file)

    private fun repoFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        throw AssertionError("Could not locate $relativePath")
    }
}
