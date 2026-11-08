/*
 * Spektrafilm for Android — export foreground-service type regression tests. GPLv3.
 */
package com.spectrafilm.app

import android.content.pm.ServiceInfo
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Export keeps the process in the foreground with [ExportForegroundService]. On API 35+ it
 * must run as mediaProcessing (long media renders), below that as dataSync, and the manifest
 * must declare every type the service starts with plus each type's permission, or
 * startForeground throws and the export drops to background priority.
 */
class ExportForegroundServiceTypeTest {

    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun api35AndUpRunAsMediaProcessing() {
        for (sdk in listOf(35, 36, 37)) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                ExportForegroundService.foregroundServiceTypeFor(sdk),
            )
        }
    }

    @Test
    fun api35AndUpFallBackToDataSyncWhenMediaProcessingIsRefused() {
        // #458 on device: an API 37 Samsung build refused mediaProcessing; dataSync must
        // still be tried next, and the first choice must stay mediaProcessing.
        for (sdk in listOf(35, 36, 37)) {
            assertEquals(
                listOf(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ),
                ExportForegroundService.foregroundServiceTypesFor(sdk),
            )
        }
        assertEquals(
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
            ExportForegroundService.foregroundServiceTypesFor(34),
        )
        assertEquals(listOf(0), ExportForegroundService.foregroundServiceTypesFor(28))
    }

    @Test
    fun aRefusedTypeIsSkippedButTheListIsNeverEmpty() {
        val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        val sync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        assertEquals(listOf(media, sync), ExportForegroundService.typesToTry(37, 0))
        // Seen on SM-S948W API 37: mediaProcessing refused, dataSync accepted.
        assertEquals(listOf(sync), ExportForegroundService.typesToTry(37, media))
        // Everything refused: try the full list again rather than nothing.
        assertEquals(listOf(media, sync), ExportForegroundService.typesToTry(37, media or sync))
        assertEquals(listOf(sync), ExportForegroundService.typesToTry(34, sync))
        assertEquals(listOf(0), ExportForegroundService.typesToTry(28, media or sync))
    }

    @Test
    fun api29To34StayDataSync() {
        for (sdk in 29..34) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ExportForegroundService.foregroundServiceTypeFor(sdk),
            )
        }
    }

    @Test
    fun beforeApi29StartsUntyped() {
        for (sdk in 24..28) assertEquals(0, ExportForegroundService.foregroundServiceTypeFor(sdk))
    }

    @Test
    fun manifestDeclaresBothTypesAndTheirPermissions() {
        val manifest = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(repoFile("app/src/main/AndroidManifest.xml")).documentElement

        val service = elements(manifest, "service")
            .single { it.getAttributeNS(android, "name") == ".ExportForegroundService" }
        val types = service.getAttributeNS(android, "foregroundServiceType").split('|').toSet()
        assertEquals(setOf("dataSync", "mediaProcessing"), types)

        val permissions = elements(manifest, "uses-permission").map { it.getAttributeNS(android, "name") }.toSet()
        for (p in listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
        )) {
            assertTrue("manifest must request $p", p in permissions)
        }
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun repoFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        error("could not find $relativePath from ${System.getProperty("user.dir")}")
    }
}
