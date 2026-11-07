/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class FilmBorderTest {

    private fun inside(r: BorderRect, w: Int, h: Int) =
        r.left >= 0 && r.top >= 0 && r.right <= w && r.bottom <= h && r.width > 0 && r.height > 0

    private fun overlaps(a: BorderRect, b: BorderRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test
    fun `none and empty photos have no layout`() {
        assertNull(FilmBorderGeometry.layout(FilmBorder.NONE, 3000, 2000))
        assertNull(FilmBorderGeometry.layout(FilmBorder.STRIP_135, 0, 2000))
        assertEquals(3000 to 2000, FilmBorderGeometry.outputSize(FilmBorder.NONE, 3000, 2000))
    }

    @Test
    fun `every style keeps the whole photo, unscaled, inside the output`() {
        for (border in FilmBorder.entries - FilmBorder.NONE) {
            for ((w, h) in listOf(3000 to 2000, 2000 to 3000, 1000 to 1000, 37 to 23)) {
                val l = FilmBorderGeometry.layout(border, w, h)!!
                assertEquals("$border $w x $h width", w, l.image.width)
                assertEquals("$border $w x $h height", h, l.image.height)
                assertTrue("$border $w x $h inside", inside(l.image, l.outWidth, l.outHeight))
                assertTrue("$border grows the output", l.outWidth > w && l.outHeight > h)
            }
        }
    }

    @Test
    fun `print and black edge are even on all four sides`() {
        for (border in listOf(FilmBorder.PRINT_WHITE, FilmBorder.FILED_BLACK)) {
            val l = FilmBorderGeometry.layout(border, 3000, 2000)!!
            val left = l.image.left
            assertEquals(left, l.image.top)
            assertEquals(left, l.outWidth - l.image.right)
            assertEquals(left, l.outHeight - l.image.bottom)
        }
        // 6 % of the short edge for the print mat.
        assertEquals(120, FilmBorderGeometry.layout(FilmBorder.PRINT_WHITE, 3000, 2000)!!.image.left)
    }

    @Test
    fun `135 strip has real 35 mm proportions and perforations on both rebates`() {
        val l = FilmBorderGeometry.layout(FilmBorder.STRIP_135, 3600, 2400)!!
        // 2400 px over a 24 mm frame = 100 px/mm; stock is 35 mm wide.
        assertEquals(3500, l.outHeight)
        assertEquals(3600 + 2 * 200, l.outWidth)
        assertTrue(l.holes.isNotEmpty())
        assertEquals("holes come in top/bottom pairs", 0, l.holes.size % 2)
        // 4.75 mm pitch over a 40 mm strip (36 mm frame + 2 mm to each neighbour): 9 per row.
        assertEquals(18, l.holes.size)
        for (h in l.holes) {
            assertTrue("hole $h inside", inside(h, l.outWidth, l.outHeight))
            assertFalse("hole $h over the photo", overlaps(h, l.image))
        }
        val top = l.holes.filter { it.top < l.image.top }
        val bottom = l.holes.filter { it.top >= l.image.bottom }
        assertEquals(top.size, bottom.size)
        assertEquals("symmetric", top.first().left, bottom.first().left)
    }

    @Test
    fun `portrait strips run vertically`() {
        val l = FilmBorderGeometry.layout(FilmBorder.STRIP_135, 2400, 3600)!!
        assertEquals(3500, l.outWidth)
        assertTrue(l.outHeight > l.outWidth)
        for (h in l.holes) assertFalse(overlaps(h, l.image))
        assertTrue(l.texts.all { it.vertical })
        assertTrue(FilmBorderGeometry.layout(FilmBorder.STRIP_135, 3600, 2400)!!.texts.none { it.vertical })
    }

    @Test
    fun `120 roll has no perforations and edge text off the photo`() {
        val l = FilmBorderGeometry.layout(FilmBorder.ROLL_120, 5600, 5600)!!
        assertTrue(l.holes.isEmpty())
        assertEquals(5600 + 2 * 275, l.outHeight)
        for (t in l.texts) {
            assertTrue("baseline below the photo", t.baseline > l.image.bottom)
            assertTrue(t.baseline <= l.outHeight)
        }
    }

    @Test
    fun `edge text runs never overlap and stay on the strip`() {
        // Phone test on a 4032 x 3024 photo drew the frame number on top of the label
        // ("SPEKTRAFILM 11A35"): the runs were placed by fraction, not by their length.
        for (border in listOf(FilmBorder.STRIP_135, FilmBorder.ROLL_120)) {
            for ((w, h) in listOf(4032 to 3024, 3024 to 4032, 6000 to 4000, 3000 to 3000, 4000 to 1000, 37 to 23)) {
                for (n in listOf(1, 9, 36)) {
                    val l = FilmBorderGeometry.layout(border, w, h, n)!!
                    val along = if (w >= h) l.outWidth else l.outHeight
                    // Extent of each run along the strip, in left-to-right reading order.
                    val spans = l.texts.map { t ->
                        val len = FilmBorderGeometry.textRunLength(t.text, t.size)
                        val start = if (t.vertical) along - t.baseline else t.x
                        start to start + len
                    }.sortedBy { it.first }
                    for ((a, b) in spans.zipWithNext()) {
                        assertTrue("$border $w x $h #$n: $a overlaps $b", a.second <= b.first)
                    }
                    for ((s0, s1) in spans) {
                        assertTrue("$border $w x $h #$n: run $s0..$s1 off the strip", s0 >= 0 && s1 <= along)
                    }
                    if (w >= 1000) {
                        assertTrue(
                            "$border $w x $h #$n lost the label",
                            l.texts.any { it.text.startsWith(FilmBorderGeometry.EDGE_LABEL) },
                        )
                        assertTrue("$border $w x $h #$n lost the frame number", l.texts.any { it.text == "${n}A" })
                    }
                }
            }
        }
    }

    @Test
    fun `edge text is generic`() {
        val text = FilmBorder.entries.mapNotNull { FilmBorderGeometry.layout(it, 3000, 2000, 7) }
            .flatMap { it.texts }.joinToString(" ") { it.text }
        assertTrue(text.contains("SPEKTRAFILM"))
        assertTrue(text.contains("7A"))
        for (brand in listOf("KODAK", "FUJI", "ILFORD", "PORTRA", "AGFA")) {
            assertFalse(brand, text.uppercase().contains(brand))
        }
    }

    @Test
    fun `frame number is stable per source and in range`() {
        assertEquals(1, FilmBorderGeometry.frameNumberFor(null))
        val a = FilmBorderGeometry.frameNumberFor("abc")
        assertEquals(a, FilmBorderGeometry.frameNumberFor("abc"))
        for (s in listOf("a", "b", "c".repeat(64), "-")) {
            assertTrue(FilmBorderGeometry.frameNumberFor(s) in 1..36)
        }
    }

    @Test
    fun `a size limit applies to the framed image`() {
        for (border in FilmBorder.entries) {
            for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 6000 to 1000, 2001 to 1999)) {
                for (edge in listOf(1080, 2048, 4096)) {
                    val photoEdge = FilmBorderGeometry.photoLongEdgeFor(border, w, h, edge)
                    val (pw, ph) = scaledDimensions(w, h, photoEdge)
                    val (ow, oh) = FilmBorderGeometry.outputSize(border, pw, ph)
                    assertTrue("$border $w x $h @ $edge -> ${ow}x$oh", max(ow, oh) <= edge)
                    // Tight: one more pixel of photo would not fit (or the photo is unscaled).
                    if (photoEdge < max(w, h)) {
                        val (pw2, ph2) = scaledDimensions(w, h, photoEdge + 1)
                        val (ow2, oh2) = FilmBorderGeometry.outputSize(border, pw2, ph2)
                        assertTrue("$border $w x $h @ $edge not tight", max(ow2, oh2) > edge - 3)
                    }
                }
            }
        }
        // Never enlarges.
        assertEquals(500, FilmBorderGeometry.photoLongEdgeFor(FilmBorder.PRINT_WHITE, 500, 400, 4096))
    }

    @Test
    fun `high-bit-depth exports never get a border`() {
        fun opts(f: ExportFormat) = ExportOptions(f, 95, ExportSize.FULL, 0, "", FilmBorder.STRIP_135)
        assertEquals(FilmBorder.STRIP_135, opts(ExportFormat.JPEG).effectiveBorder())
        assertEquals(FilmBorder.STRIP_135, opts(ExportFormat.PNG).effectiveBorder())
        assertEquals(FilmBorder.STRIP_135, opts(ExportFormat.ULTRA_HDR).effectiveBorder())
        assertEquals(FilmBorder.NONE, opts(ExportFormat.TIFF).effectiveBorder())
        assertEquals(FilmBorder.NONE, opts(ExportFormat.PNG16).effectiveBorder())
        assertEquals(FilmBorder.NONE, opts(ExportFormat.TIFF32F).effectiveBorder())
        assertEquals(FilmBorder.NONE, opts(ExportFormat.SCENE_LINEAR_TIFF).effectiveBorder())
    }

    @Test
    fun `stored names parse leniently`() {
        assertEquals(FilmBorder.ROLL_120, FilmBorder.parse("ROLL_120"))
        assertEquals(FilmBorder.NONE, FilmBorder.parse(null))
        assertEquals(FilmBorder.NONE, FilmBorder.parse("POLAROID"))
    }

    @Test
    fun `gain map is padded with neutral gain and the photo's map stays aligned`() {
        val w = 40
        val h = 30
        // Map at 1/2 resolution, every value distinct and non-zero.
        val mw = 20
        val mh = 15
        val alpha = ByteArray(mw * mh) { (1 + it % 250).toByte() }
        val map = HdrGainMap.Result(alpha, mw, mh, 1.5f, 1.6f, 7)
        val layout = FilmBorderGeometry.layout(FilmBorder.PRINT_WHITE, w, h)!! // margin 2 px
        val padded = HdrGainMap.padForBorder(map, layout)
        assertEquals(1.5f, padded.ratioMax)
        // Same scale: output is (40+4) x (30+4) -> 22 x 17 at 1/2.
        assertEquals(22, padded.width)
        assertEquals(17, padded.height)
        var nonZero = 0
        for (y in 0 until padded.height) for (x in 0 until padded.width) {
            val v = padded.alpha[y * padded.width + x]
            val inPhoto = x in 1 until 1 + mw && y in 1 until 1 + mh
            if (inPhoto) {
                assertEquals(alpha[(y - 1) * mw + (x - 1)], v)
                nonZero++
            } else {
                assertEquals("border at $x,$y must be neutral", 0.toByte(), v)
            }
        }
        assertEquals(mw * mh, nonZero)
    }

    @Test
    fun `a three-channel gain map pads with every channel kept together`() {
        val mw = 20
        val mh = 15
        val alpha = ByteArray(mw * mh * 3) { (1 + it % 250).toByte() }
        val map = HdrGainMap.Result(alpha, mw, mh, 1.5f, 1.6f, 7, channels = 3)
        val layout = FilmBorderGeometry.layout(FilmBorder.PRINT_WHITE, 40, 30)!! // margin 2 px
        val padded = HdrGainMap.padForBorder(map, layout)
        assertEquals(3, padded.channels)
        assertEquals(22 * 17 * 3, padded.alpha.size)
        for (y in 0 until padded.height) for (x in 0 until padded.width) for (c in 0 until 3) {
            val v = padded.alpha[(y * padded.width + x) * 3 + c]
            val inPhoto = x in 1 until 1 + mw && y in 1 until 1 + mh
            val want = if (inPhoto) alpha[((y - 1) * mw + (x - 1)) * 3 + c] else 0.toByte()
            assertEquals("cell $x,$y channel $c", want, v)
        }
    }
}
