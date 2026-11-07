/*
 * Spektrafilm for Android — "What's new" Markdown subset. GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun headingsBulletsAndParagraphsBecomeBlocks() {
        val blocks = ReleaseNotes.parse(
            """
            **Spektrafilm 0.12.0** · Android 7.0+
            continued line.

            ## Highlights

            - **Bold** lead and `code`.
              wrapped continuation
            - Second, see [#301](https://github.com/x/y/pull/301)
              - Nested

            ---
            Tail.
            """.trimIndent(),
        )
        assertEquals(6, blocks.size)
        val first = blocks[0] as NoteBlock.Paragraph
        assertEquals(NoteSpan("Spektrafilm 0.12.0", bold = true), first.spans[0])
        assertEquals(" · Android 7.0+ continued line.", first.spans[1].text)
        assertEquals("Highlights", text(blocks[1]))
        val bullet = blocks[2] as NoteBlock.Bullet
        assertEquals(0, bullet.level)
        assertEquals("Bold lead and code. wrapped continuation", text(bullet))
        assertTrue(bullet.spans.any { it.code && it.text == "code" })
        assertEquals("Second, see #301", text(blocks[3]))
        assertEquals(1, (blocks[4] as NoteBlock.Bullet).level)
        assertEquals("Tail.", text(blocks[5]))
    }

    @Test
    fun linksNeverSurviveAsUrls() {
        val spans = ReleaseNotes.inline("[click](https://evil.test/a.apk) here")
        assertEquals("click here", spans.joinToString("") { it.text })
    }

    @Test
    fun unbalancedMarkersStayLiteral() {
        assertEquals("a ** b ` c", ReleaseNotes.inline("a ** b ` c").joinToString("") { it.text })
        assertTrue(ReleaseNotes.parse("").isEmpty())
        assertTrue(ReleaseNotes.parse("\n\n---\n").isEmpty())
    }

    private fun text(block: NoteBlock): String = when (block) {
        is NoteBlock.Heading -> block.spans
        is NoteBlock.Bullet -> block.spans
        is NoteBlock.Paragraph -> block.spans
    }.joinToString("") { it.text }
}
