/*
 * Spektrafilm for Android — release-notes rendering for the "What's new" dialog. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * GitHub release bodies are Markdown. This reads the small subset the release notes use
 * (headings, bullets, paragraphs, **bold**, `code`, [links](url)) into plain blocks the
 * dialog styles itself. Links render as their text only: the dialog never opens a URL
 * that came from the release body.
 */
package com.spectrafilm.app

internal data class NoteSpan(val text: String, val bold: Boolean = false, val code: Boolean = false)

internal sealed interface NoteBlock {
    data class Heading(val spans: List<NoteSpan>) : NoteBlock
    data class Bullet(val spans: List<NoteSpan>, val level: Int) : NoteBlock
    data class Paragraph(val spans: List<NoteSpan>) : NoteBlock
}

internal object ReleaseNotes {
    private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")

    fun parse(markdown: String): List<NoteBlock> {
        val blocks = mutableListOf<NoteBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += NoteBlock.Paragraph(inline(paragraph.toString().trim()))
            paragraph.clear()
        }
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() || RULE.matches(line) -> flush()
                HEADING.matches(line) -> {
                    flush()
                    blocks += NoteBlock.Heading(inline(HEADING.find(line)!!.groupValues[1].trim().trimEnd('#').trim()))
                }
                BULLET.matches(line) -> {
                    flush()
                    val match = BULLET.find(line)!!
                    val indent = match.groupValues[1].replace("\t", "  ").length
                    blocks += NoteBlock.Bullet(inline(match.groupValues[2].trim()), level = (indent / 2).coerceAtMost(3))
                }
                // A wrapped bullet continues the bullet, not a new paragraph.
                paragraph.isEmpty() && blocks.lastOrNull() is NoteBlock.Bullet && raw.startsWith(" ") -> {
                    val last = blocks.removeAt(blocks.lastIndex) as NoteBlock.Bullet
                    blocks += last.copy(spans = last.spans + inline(" " + line.trim()))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    /** Split [text] into plain / **bold** / `code` runs, with links reduced to their text. */
    fun inline(text: String): List<NoteSpan> {
        val source = LINK.replace(text) { it.groupValues[1] }
        val spans = mutableListOf<NoteSpan>()
        val plain = StringBuilder()
        var bold = false
        var i = 0
        fun emit() {
            if (plain.isNotEmpty()) spans += NoteSpan(plain.toString(), bold = bold)
            plain.clear()
        }
        while (i < source.length) {
            when {
                source.startsWith("**", i) && (bold || source.indexOf("**", i + 2) > i + 2) -> {
                    emit(); bold = !bold; i += 2
                }
                source[i] == '`' && source.indexOf('`', i + 1) > i -> {
                    emit()
                    val end = source.indexOf('`', i + 1)
                    spans += NoteSpan(source.substring(i + 1, end), bold = bold, code = true)
                    i = end + 1
                }
                else -> { plain.append(source[i]); i++ }
            }
        }
        emit()
        return spans
    }
}
