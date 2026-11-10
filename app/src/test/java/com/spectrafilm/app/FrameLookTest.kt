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

    @Test
    fun `a caption sits centred below the photo on every paper style and never on a strip`() {
        val look = FrameLook(caption = CaptionKind.CUSTOM, captionText = "Kodak trip, 2026")
        for (style in FilmBorder.entries) for ((w, h) in listOf(3000 to 2000, 2000 to 3000, 1000 to 1000)) {
            val spec = FrameSpec(style, look)
            val plain = FilmBorderGeometry.layout(FrameSpec(style), w, h, 3)
            val l = FilmBorderGeometry.layout(spec, w, h, 3, caption = "Kodak trip, 2026")!!
            val tag = "$style $w x $h"
            val caption = l.texts.filter { it.centered }
            if (style.isStrip) {
                assertTrue(tag, caption.isEmpty())
                assertEquals(tag, plain, l)
                continue
            }
            assertEquals(tag, 1, caption.size)
            val t = caption.single()
            assertEquals(tag, l.outWidth / 2, t.x)
            assertEquals(tag, CaptionFont.SANS, t.font)
            // Below the photo, inside the output, and no wider than the frame.
            assertTrue(tag, t.baseline - t.size > l.image.bottom)
            assertTrue(tag, t.baseline <= l.outHeight)
            assertTrue(tag, FilmBorderGeometry.captionRunLength(t.text, t.size) <= t.maxWidth)
            assertTrue(tag, t.maxWidth <= l.outWidth)
            // The photo has not moved or grown.
            assertEquals(tag, w, l.image.width)
            assertEquals(tag, h, l.image.height)
            // Below any ink: every ink point is above the cap line.
            for (p in l.inkOutline) assertTrue(tag, p.y < t.baseline - t.size)
            if (style == FilmBorder.INSTANT) {
                assertEquals(tag, plain!!.outHeight, l.outHeight)
            } else {
                assertTrue(tag, l.outHeight > (plain?.outHeight ?: h))
            }
        }
    }

    @Test
    fun `no caption text adds nothing, and a long caption shrinks or is left off`() {
        val look = FrameLook(caption = CaptionKind.SHOT_INFO)
        assertEquals(
            FilmBorderGeometry.layout(FilmBorder.PRINT_WHITE, 3000, 2000, 1),
            FilmBorderGeometry.layout(FrameSpec(FilmBorder.PRINT_WHITE, look), 3000, 2000, 1, caption = null),
        )
        // A caption with nothing to say over a bare photo adds nothing at all.
        assertEquals(
            3000 to 2000,
            FilmBorderGeometry.layout(FrameSpec(FilmBorder.NONE, look), 3000, 2000, 1, caption = "  ")
                ?.let { it.outWidth to it.outHeight } ?: (3000 to 2000),
        )
        val long = "x".repeat(FrameLook.CAPTION_MAX)
        val l = FilmBorderGeometry.layout(FrameSpec(FilmBorder.PRINT_WHITE, look), 1000, 1000, 1, caption = long)!!
        val t = l.texts.single()
        assertTrue(FilmBorderGeometry.captionRunLength(t.text, t.size) <= t.maxWidth)
        // Wide (CJK) characters count a full em, so a CJK caption gets a smaller cap than a
        // Latin one of the same length.
        fun cap(text: String) = FilmBorderGeometry.layout(
            FrameSpec(FilmBorder.PRINT_WHITE, look), 1000, 1000, 1, caption = text,
        )!!.texts.single()
        val latin = cap("x".repeat(40))
        val cjk = cap("写".repeat(40))
        assertTrue("${cjk.size} < ${latin.size}", cjk.size < latin.size)
        assertTrue(FilmBorderGeometry.captionRunLength(cjk.text, cjk.size) <= cjk.maxWidth)
        val tiny = FilmBorderGeometry.layout(FrameSpec(FilmBorder.PRINT_WHITE, look), 40, 30, 1, caption = long)!!
        assertTrue(tiny.texts.isEmpty())
    }

    @Test
    fun `a caption on no frame becomes a paper band under the photo`() {
        val spec = FrameSpec(FilmBorder.NONE, FrameLook(caption = CaptionKind.FILM_STOCK))
        assertFalse(spec.isNone)
        val l = FilmBorderGeometry.layout(spec, 3000, 2000, 1, caption = "Portra 400")!!
        assertEquals(BorderRect(0, 0, 3000, 2000), l.image)
        assertEquals(3000, l.outWidth)
        assertTrue(l.outHeight > 2000)
        assertEquals(FilmBorderGeometry.PAPER_WHITE, l.frameColor)
        assertEquals(FilmBorderGeometry.FILED_BLACK, l.texts.single().color)
    }

    @Test
    fun `the size limit counts the caption band`() {
        val spec = FrameSpec(FilmBorder.PRINT_WHITE, FrameLook(caption = CaptionKind.CUSTOM))
        for ((w, h) in listOf(6000 to 4000, 4000 to 6000)) {
            val edge = FilmBorderGeometry.photoLongEdgeFor(spec, w, h, 2048, "A caption")
            val (sw, sh) = scaledDimensions(w, h, edge)
            val (ow, oh) = FilmBorderGeometry.outputSize(spec, sw, sh, "A caption")
            assertTrue("$w x $h -> $ow x $oh", max(ow, oh) <= 2048)
        }
    }

    @Test
    fun `edge markings can be hidden, relabelled and renumbered`() {
        val strip = { look: FrameLook -> FilmBorderGeometry.layout(FrameSpec(FilmBorder.STRIP_135, look), 3000, 2000, 7)!! }
        val default = strip(FrameLook())
        assertTrue(default.texts.any { it.text.startsWith(FilmBorderGeometry.EDGE_LABEL) })
        assertTrue(default.texts.any { it.text == "7A" })

        val hidden = strip(FrameLook(edgeMarkings = false))
        assertTrue(hidden.texts.isEmpty())
        assertEquals(default.holes, hidden.holes)
        assertEquals(default.image, hidden.image)

        val custom = strip(FrameLook(edgeLabel = "my roll", frameNumber = 24))
        assertTrue(custom.texts.any { it.text == "MY ROLL" })
        assertTrue(custom.texts.any { it.text == "24A" })
        assertTrue(custom.texts.none { it.text.startsWith(FilmBorderGeometry.EDGE_LABEL) })
        for (t in custom.texts) {
            assertTrue(t.x + FilmBorderGeometry.textRunLength(t.text, t.size) <= custom.outWidth)
        }
    }

    @Test
    fun `caption and marking settings round-trip and change the cache key`() {
        val look = FrameLook(
            caption = CaptionKind.CUSTOM,
            captionText = "Lisbon",
            captionFont = CaptionFont.SERIF,
            edgeMarkings = false,
            edgeLabel = "ROLL 3",
            frameNumber = 12,
        )
        assertEquals(look, FrameLook.fromJson(org.json.JSONObject(look.toJson().toString())))
        assertNotEquals(FrameSpec(FilmBorder.PRINT_WHITE).key(), FrameSpec(FilmBorder.PRINT_WHITE, look).key())
        assertNotEquals(
            FrameSpec(FilmBorder.PRINT_WHITE, look).key(),
            FrameSpec(FilmBorder.PRINT_WHITE, look.copy(captionText = "Porto")).key(),
        )
        // Lenient: junk numbers and labels fall back or are cleaned.
        val junk = FrameLook.fromJson(
            org.json.JSONObject("""{"caption":"NOPE","frameNumber":500,"edgeLabel":"  héllo\u0007 ","captionFont":"X"}"""),
        )
        assertEquals(CaptionKind.NONE, junk.caption)
        assertNull(junk.frameNumber)
        assertEquals("HLLO", junk.edgeLabel)
        assertEquals(CaptionFont.SANS, junk.captionFont)
        // A look without the new keys (written before captions) is the same as before.
        assertEquals(FrameLook(), FrameLook.fromJson(org.json.JSONObject("""{"color":"STYLE","aspect":"ORIGINAL"}""")))
    }
}
