/*
 * Spektrafilm for Android — Library export: each photo with its own edit. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryExportTest {

    private val profiles = listOf("kodak_portra_400", "kodak_gold_200", "fujifilm_pro_400h", "kodak_portra_endura")

    private fun recipeOf(rotationDeg: Int = 0, edit: ParamsState.() -> Unit): RecipeDocument =
        RecipeDocument("k", "a.jpg", 0L, rotationDeg, Presets.encode(ParamsState().apply(edit)))

    @Test
    fun `a photo's own edit keeps its film, framing and manual turn`() {
        val recipe = recipeOf(rotationDeg = 90) {
            filmProfile = "fujifilm_pro_400h"
            straightenDegrees = 2.5f
        }
        val edit = ownEditFor(recipe, profiles) { s, _ -> s.filmProfile = "kodak_gold_200" }
        assertEquals("fujifilm_pro_400h", edit.state.filmProfile)
        // Unlike a shared look, the photo's own framing is part of what it exports with.
        assertEquals(2.5f, edit.state.straightenDegrees, 0f)
        assertEquals(SourceRotation.fromDegrees(90), edit.rotation)
    }

    @Test
    fun `a photo without an edit exports as a first open shows it`() {
        val edit = ownEditFor(null, profiles) { s, _ ->
            s.filmProfile = "kodak_gold_200"
            s.outputColorSpace = ColorSpace.ADOBE_RGB
        }
        assertEquals("kodak_gold_200", edit.state.filmProfile)
        assertEquals(ColorSpace.ADOBE_RGB, edit.state.outputColorSpace)
        assertEquals(SourceRotation.NONE, edit.rotation)
        assertEquals(emptyList<Any>(), edit.adjustments)
    }

    @Test
    fun `a profile this install no longer has falls back to an installed one`() {
        val recipe = recipeOf { filmProfile = "gone_film_100" }
        val edit = ownEditFor(recipe, profiles) { _, _ -> }
        assertEquals(profiles.first(), edit.state.filmProfile)
    }

    @Test
    fun `a shared look still leaves the framing to each photo`() {
        val look = Presets.encode(ParamsState().apply { straightenDegrees = 3f })
        val edit = sharedLookEdit(look)
        assertEquals(ParamsState().straightenDegrees, edit.state.straightenDegrees, 0f)
        assertEquals(SourceRotation.NONE, edit.rotation)
    }

    @Test
    fun `only frames a batch can render are exported, once each`() {
        fun entry(uri: String, name: String, mime: String?) = LibraryEntry(uri, name, mime, 0L, 0L)
        val frames = listOf(
            entry("content://f/1", "a.jpg", "image/jpeg"),
            entry("content://f/2", "clip.mp4", "video/mp4"),
            entry("content://f/1", "a.jpg", "image/jpeg"),
            entry("content://f/3", "b.dng", "image/x-adobe-dng"),
        )
        val kept = libraryExportFrames(frames)
        assertEquals(listOf("content://f/1", "content://f/3"), kept.map { it.first.documentUri })
        assertEquals(listOf(SourceKind.PHOTO, SourceKind.RAW), kept.map { it.second })
    }

    @Test
    fun `the sheet opens with the export defaults, never the scene-linear format`() {
        val jpeg = libraryExportDefaults(ExportFormat.SCENE_LINEAR_TIFF, 88, FilmBorder.NONE)
        assertEquals(ExportFormat.JPEG, jpeg.format)
        assertEquals(88, jpeg.jpegQuality)
        assertEquals(ExportSize.FULL, jpeg.size)
        assertEquals(ExportFormat.TIFF, libraryExportDefaults(ExportFormat.TIFF, 90, FilmBorder.NONE).format)
    }

    @Test
    fun `the sheet's last choices come back after a restart, without the file name`() {
        val chosen = ExportOptions(
            ExportFormat.JPEG, 80, ExportSize.MEDIUM, 2048, "trip",
            border = FilmBorder.INSTANT, sharpen = OutputSharpen.values().last(),
            frame = FrameLook(0.04f, FrameColor.FROM_PHOTO, FrameAspect.SQUARE),
        )
        val fallback = libraryExportDefaults(ExportFormat.ULTRA_HDR, 95, FilmBorder.NONE)
        val back = decodeLibraryExportOptions(encodeLibraryExportOptions(chosen), fallback)
        assertEquals(chosen.copy(customName = ""), back)
    }

    @Test
    fun `nothing saved or a broken save opens with the defaults`() {
        val fallback = libraryExportDefaults(ExportFormat.ULTRA_HDR, 95, FilmBorder.NONE)
        assertEquals(fallback, decodeLibraryExportOptions(null, fallback))
        assertEquals(fallback, decodeLibraryExportOptions("{not json", fallback))
        assertEquals(fallback, decodeLibraryExportOptions("""{"format":"GONE"}""", fallback))
        val linear = encodeLibraryExportOptions(fallback.copy(format = ExportFormat.SCENE_LINEAR_TIFF))
        assertEquals(fallback, decodeLibraryExportOptions(linear, fallback))
    }

    @Test
    fun `a cleared custom size still means full size after a restart`() {
        val fallback = libraryExportDefaults(ExportFormat.JPEG, 95, FilmBorder.NONE)
        val cleared = fallback.copy(size = ExportSize.CUSTOM, customLongEdge = 0)
        val back = decodeLibraryExportOptions(encodeLibraryExportOptions(cleared), fallback)
        assertEquals(null, back.targetLongEdge())
    }
}
