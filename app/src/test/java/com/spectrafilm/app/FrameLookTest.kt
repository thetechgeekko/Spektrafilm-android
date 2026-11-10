/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/** The Frame tab's styles, width, colour and aspect on top of the original fixed borders. */
class FrameLookTest {

    private val sizes = listOf(3000 to 2000, 2000 to 3000, 1000 to 1000, 37 to 23, 6000 to 1000)

    private fun inside(r: BorderRect, w: Int, h: Int) =
        r.left >= 0 && r.top >= 0 && r.right <= w && r.bottom <= h && r.width > 0 && r.height > 0

    private fun overlaps(a: BorderRect, b: BorderRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun everySpec(): List<FrameSpec> = FilmBorder.entries.flatMap { style ->
        listOf(null, 0.005f, 0.25f).flatMap { width ->
            FrameColor.entries.flatMap { color ->
                FrameAspect.entries.map { aspect -> FrameSpec(style, FrameLook(width, color, aspect)) }
            }
        }
    }

    @Test
    fun `the default look reproduces the original borders exactly`() {
        for (style in FilmBorder.entries) for ((w, h) in sizes) {
            assertEquals(
                "$style $w x $h",
                FilmBorderGeometry.layout(style, w, h, 7),
                FilmBorderGeometry.layout(FrameSpec(style), w, h, 7),
            )
        }
        assertEquals(FilmBorder.NONE.name, FrameSpec(FilmBorder.NONE).key())
        assertEquals(FilmBorder.ROLL_120.name, FrameSpec(FilmBorder.ROLL_120).key())
    }

    @Test
    fun `every frame keeps the whole photo, unscaled, inside the output and off every mark`() {
        for (spec in everySpec()) for ((w, h) in sizes) {
            val l = FilmBorderGeometry.layout(spec, w, h, 12)
            if (spec.isNone) {
                assertNull(l)
                continue
            }
            l!!
            val tag = "${spec.key()} $w x $h"
            assertEquals(tag, w, l.image.width)
            assertEquals(tag, h, l.image.height)
            assertTrue(tag, inside(l.image, l.outWidth, l.outHeight))
            for (r in l.holes + l.rules) {
                assertTrue("$tag mark $r", inside(r, l.outWidth, l.outHeight))
                assertFalse("$tag mark $r on the photo", overlaps(r, l.image))
            }
            for (p in l.inkOutline) {
                assertTrue("$tag ink $p off the output", p.x in 0..l.outWidth && p.y in 0..l.outHeight)
                val onPhoto = p.x > l.image.left && p.x < l.image.right && p.y > l.image.top && p.y < l.image.bottom
                assertFalse("$tag ink $p inside the photo", onPhoto)
            }
            assertTrue(tag, l.frameRect.left >= 0 && l.frameRect.right <= l.outWidth)
        }
    }

    @Test
    fun `aspect padding hits the ratio and centres the frame`() {
        for (spec in everySpec().filter { it.look.aspect != FrameAspect.ORIGINAL }) for ((w, h) in sizes) {
            val l = FilmBorderGeometry.layout(spec, w, h)!!
            val a = spec.look.aspect
            // Rounded up to whole pixels: within one pixel of the exact ratio.
            val exactH = l.outWidth.toDouble() * a.ratioH / a.ratioW
            val exactW = l.outHeight.toDouble() * a.ratioW / a.ratioH
            assertTrue(
                "${spec.key()} $w x $h -> ${l.outWidth} x ${l.outHeight}",
                kotlin.math.abs(l.outHeight - exactH) <= 1.0 || kotlin.math.abs(l.outWidth - exactW) <= 1.0,
            )
            val bare = FilmBorderGeometry.layout(spec.copy(look = spec.look.copy(aspect = FrameAspect.ORIGINAL)), w, h)
            val frameW = bare?.outWidth ?: w
            val frameH = bare?.outHeight ?: h
            assertTrue(l.outWidth >= frameW && l.outHeight >= frameH)
            val left = l.frameRect.left
            val right = l.outWidth - l.frameRect.right
            assertTrue("centred ${spec.key()}", kotlin.math.abs(left - right) <= 1)
        }
    }

    @Test
    fun `width sets the margin of the paper styles and leaves strips alone`() {
        val thin = FilmBorderGeometry.layout(FrameSpec(FilmBorder.PRINT_WHITE, FrameLook(width = 0.01f)), 3000, 2000)!!
        val wide = FilmBorderGeometry.layout(FrameSpec(FilmBorder.PRINT_WHITE, FrameLook(width = 0.2f)), 3000, 2000)!!
        assertEquals(20, thin.image.left)
        assertEquals(400, wide.image.left)
        for (strip in listOf(FilmBorder.STRIP_135, FilmBorder.ROLL_120, FilmBorder.HALF_FRAME)) {
            assertEquals(
                FilmBorderGeometry.layout(strip, 3000, 2000),
                FilmBorderGeometry.layout(FrameSpec(strip, FrameLook(width = 0.2f)), 3000, 2000),
            )
        }
    }

    @Test
    fun `instant has a deep bottom and the mat a keyline`() {
        val i = FilmBorderGeometry.layout(FilmBorder.INSTANT, 3000, 2000)!!
        val side = i.image.left
        assertEquals(side, i.image.top)
        assertTrue(i.outHeight - i.image.bottom > 3 * side)
        val m = FilmBorderGeometry.layout(FilmBorder.GALLERY_MAT, 3000, 2000)!!
        assertEquals(4, m.rules.size)
        assertTrue(m.image.left >= 200)
    }

    @Test
    fun `half frame runs its strip across the photo's long edge`() {
        // Portrait photo (an 18 x 24 frame): horizontal strip, perforations above and below.
        val l = FilmBorderGeometry.layout(FilmBorder.HALF_FRAME, 1800, 2400)!!
        assertEquals(2400 + 2 * 550, l.outHeight)
        assertTrue(l.holes.isNotEmpty())
        assertTrue(l.texts.none { it.vertical })
        val landscape = FilmBorderGeometry.layout(FilmBorder.HALF_FRAME, 2400, 1800)!!
        assertTrue(landscape.texts.all { it.vertical })
    }

    @Test
    fun `colours resolve to the chosen paint or the photo placeholder`() {
        val black = FilmBorderGeometry.layout(FrameSpec(FilmBorder.PRINT_WHITE, FrameLook(color = FrameColor.BLACK)), 300, 200)!!
        assertEquals(FilmBorderGeometry.FILED_BLACK, black.frameColor)
        val photo = FilmBorderGeometry.layout(FrameSpec(FilmBorder.INSTANT, FrameLook(color = FrameColor.FROM_PHOTO)), 300, 200)!!
        assertEquals(FilmBorderGeometry.PHOTO_TONE, photo.frameColor)
        // A strip keeps its film base whatever colour is picked; the colour pads the aspect.
        val strip = FilmBorderGeometry.layout(
            FrameSpec(FilmBorder.STRIP_135, FrameLook(color = FrameColor.CREAM, aspect = FrameAspect.SQUARE)), 300, 200,
        )!!
        assertEquals(FilmBorderGeometry.REBATE, strip.frameColor)
        assertEquals(FrameColor.CREAM.argb, strip.background)
        // No style but an aspect: the photo padded on paper white.
        val padOnly = FilmBorderGeometry.layout(FrameSpec(FilmBorder.NONE, FrameLook(aspect = FrameAspect.SQUARE)), 300, 200)!!
        assertEquals(300, padOnly.outWidth)
        assertEquals(300, padOnly.outHeight)
        assertEquals(FilmBorderGeometry.PAPER_WHITE, padOnly.background)
    }

    @Test
    fun `muted tone stays opaque, mid-toned and less saturated`() {
        for ((r, g, b) in listOf(Triple(255, 0, 0), Triple(0, 0, 0), Triple(255, 255, 255), Triple(40, 120, 200))) {
            val c = FilmBorderGeometry.mutedTone(r, g, b)
            assertEquals(0xFF, (c ushr 24) and 0xFF)
            val ch = listOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            assertTrue("$r,$g,$b -> $ch", ch.max() - ch.min() <= (max(max(r, g), b) - minOf(r, g, b)) / 2 + 1)
            assertTrue(ch.all { it in 40..215 })
        }
        assertNotEquals(FilmBorderGeometry.PHOTO_TONE, FilmBorderGeometry.mutedTone(0, 0, 0))
    }

    @Test
    fun `a size limit applies to the framed and padded image`() {
        for (spec in everySpec().filter { it.look.color == FrameColor.STYLE }) {
            for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 2001 to 1999)) for (edge in listOf(1080, 2048)) {
                val photoEdge = FilmBorderGeometry.photoLongEdgeFor(spec, w, h, edge)
                val (pw, ph) = scaledDimensions(w, h, photoEdge)
                val (ow, oh) = FilmBorderGeometry.outputSize(spec, pw, ph)
                assertTrue("${spec.key()} $w x $h @ $edge -> ${ow}x$oh", max(ow, oh) <= edge)
            }
        }
    }

    @Test
    fun `the look round-trips through JSON and parses leniently`() {
        val look = FrameLook(0.08f, FrameColor.CREAM, FrameAspect.PORTRAIT_4_5)
        assertEquals(look, FrameLook.fromJson(org.json.JSONObject(look.toJson().toString())))
        assertEquals(FrameLook(), FrameLook.fromJson(null))
        assertEquals(FrameLook(), FrameLook.fromJson(org.json.JSONObject("""{"color":"NEON","aspect":"3:1"}""")))
        assertEquals(0.25f, FrameLook.fromJson(org.json.JSONObject("""{"width":9}""")).width)
        assertNotEquals(FrameSpec(FilmBorder.INSTANT).key(), FrameSpec(FilmBorder.INSTANT, look).key())
    }
}
