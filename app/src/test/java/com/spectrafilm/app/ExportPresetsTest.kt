package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Saved export presets: round-trip, replace-by-name, last-used, limits and damaged files. */
class ExportPresetsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val file get() = tmp.root.resolve("export_presets.json")
    private fun repo() = FileExportPresetRepository(file)

    private val insta = ExportOptions(
        ExportFormat.JPEG, 90, ExportSize.MEDIUM, 0, "{name}_{film}",
        border = FilmBorder.entries.last(), sharpen = OutputSharpen.STANDARD,
        frame = FrameLook(0.1f, FrameColor.CREAM, FrameAspect.PORTRAIT_4_5),
    )
    private val print = ExportOptions(ExportFormat.TIFF, 95, ExportSize.FULL, 0, "", sharpen = OutputSharpen.NONE)

    @Test fun nothingSavedReadsEmpty() {
        val doc = repo().read()
        assertTrue(doc.presets.isEmpty())
        assertNull(doc.lastUsedPreset)
    }

    @Test fun presetsRoundTripSortedWithEveryChoice() {
        repo().save(ExportPreset("Print", print, keepGps = true))
        repo().save(ExportPreset("instagram", insta, keepGps = false))
        val doc = repo().read()
        assertEquals(listOf("instagram", "Print"), doc.presets.map { it.name })
        assertEquals(insta, doc.presets[0].options)
        assertTrue(doc.presets[1].keepGps)
        assertEquals("instagram", doc.lastUsed)
    }

    @Test fun savingTheSameNameReplacesIt() {
        repo().save(ExportPreset("Web", insta, false))
        repo().save(ExportPreset(" web ", print, false))
        val doc = repo().read()
        assertEquals(1, doc.presets.size)
        assertEquals("web", doc.presets.single().name)
        assertEquals(print, doc.presets.single().options)
    }

    @Test fun matchesComparesEveryChoice() {
        val p = ExportPreset("x", insta, keepGps = false)
        assertTrue(p.matches(insta, false))
        assertFalse(p.matches(insta, true))
        assertFalse(p.matches(insta.copy(sharpen = OutputSharpen.HIGH), false))
    }

    @Test fun markUsedAndDeleteKeepLastUsedConsistent() {
        repo().save(ExportPreset("A", insta, false))
        repo().save(ExportPreset("B", print, false))
        assertEquals("A", repo().markUsed("A").lastUsed)
        assertNull("unknown names are not remembered", repo().markUsed("nope").lastUsed)
        repo().markUsed("B")
        val afterDelete = repo().delete("B")
        assertNull(afterDelete.lastUsed)
        assertEquals(listOf("A"), afterDelete.presets.map { it.name })
    }

    @Test(expected = ExportPresetLimitException::class)
    fun presetsAreCapped() {
        repeat(ExportPresetCodec.MAX_PRESETS) { repo().save(ExportPreset("p$it", insta, false)) }
        repo().save(ExportPreset("one more", insta, false))
    }

    @Test fun aPresetFromANewerBuildIsSkippedNotFatal() {
        val root = org.json.JSONObject(
            ExportPresetCodec.encode(
                ExportPresetDocument(listOf(ExportPreset("future", insta, false), ExportPreset("ok", insta, false))),
            ),
        )
        root.getJSONArray("presets").getJSONObject(0).put("format", "HEIF_FROM_THE_FUTURE")
        val text = root.toString()
        file.writeText(text)
        assertEquals(listOf("ok"), repo().read().presets.map { it.name })
    }

    @Test fun aDamagedDocumentIsSetAsideAndReadsEmpty() {
        file.writeText("{\"schema\":\"nope\"}")
        assertTrue(repo().read().presets.isEmpty())
        assertFalse(file.exists())
        repo().save(ExportPreset("fresh", insta, false))
        assertEquals(1, repo().read().presets.size)
    }
}
