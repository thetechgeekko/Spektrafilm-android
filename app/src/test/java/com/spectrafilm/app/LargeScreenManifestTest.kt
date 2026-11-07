/*
 * Spektrafilm for Android — large-screen / desktop-windowing manifest regression tests. GPLv3.
 */
package com.spectrafilm.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Pins the manifest side of Android's large-screen app quality guidelines: the editor must
 * stay resizable with no orientation or aspect-ratio lock, and window-shape changes (desktop
 * window drags, fold/unfold, rotation, keyboard attach) must not recreate the Activity.
 */
class LargeScreenManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val mainActivity: Element by lazy {
        val manifest = xml("app/src/main/AndroidManifest.xml")
        elements(manifest.documentElement, "activity")
            .single { it.getAttributeNS(android, "name") == ".MainActivity" }
    }

    @Test
    fun editorIsResizableWithNoOrientationOrAspectLock() {
        assertEquals("true", mainActivity.getAttributeNS(android, "resizeableActivity"))
        for (lock in listOf("screenOrientation", "maxAspectRatio", "minAspectRatio")) {
            assertFalse("MainActivity must not set android:$lock", mainActivity.hasAttributeNS(android, lock))
        }
    }

    @Test
    fun windowShapeChangesAreHandledInPlace() {
        val handled = mainActivity.getAttributeNS(android, "configChanges").split('|').toSet()
        for (change in listOf(
            "orientation", "screenSize", "smallestScreenSize", "screenLayout",
            "keyboard", "keyboardHidden", "navigation",
        )) {
            assertTrue("configChanges must include $change", change in handled)
        }
        // These still recreate on purpose: resources (locale, per-display density) are re-resolved.
        for (recreates in listOf("locale", "layoutDirection", "density", "fontScale", "uiMode")) {
            assertFalse("configChanges must not include $recreates", recreates in handled)
        }
    }

    @Test
    fun freeformWindowHasNoMinimumSizeThatWouldBlockSplitScreen() {
        val layout = elements(mainActivity, "layout").singleOrNull() ?: return
        assertFalse(layout.hasAttributeNS(android, "minWidth"))
        assertFalse(layout.hasAttributeNS(android, "minHeight"))
    }

    @Test
    fun touchscreenIsNotRequired() {
        val manifest = xml("app/src/main/AndroidManifest.xml")
        val touch = elements(manifest.documentElement, "uses-feature")
            .single { it.getAttributeNS(android, "name") == "android.hardware.touchscreen" }
        assertEquals("false", touch.getAttributeNS(android, "required"))
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun xml(relativePath: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(repoFile(relativePath))

    private fun repoFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        throw AssertionError("Could not locate $relativePath")
    }
}
