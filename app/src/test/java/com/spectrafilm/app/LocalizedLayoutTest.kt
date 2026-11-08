/*
 * Spektrafilm for Android — layout guards for translated and right-to-left UIs. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The pseudo-locale device checks (en-XA, ar-XB in Ticket181AccessibilityChecks) only run on
 * the release device gate. These two checks run with every unit-test pass:
 *
 * - RTL safety: the app declares supportsRtl, so every Compose layout must mirror. The
 *   absolute (non-mirroring) APIs below are banned in feature code; an overlay pinned to photo
 *   pixels, which must not mirror, takes an entry in [rtlExempt] with the reason.
 * - Long strings: the five stage labels share the bottom toolbar in equal slots of about
 *   56dp next to the Export FAB, one line at 11sp with an ellipsis. A label past the budget
 *   shows as "Numérisa…". German, French and Portuguese run 30-100% longer than English, so
 *   every translation is held to the budget here instead of on a phone.
 */
package com.spectrafilm.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedLayoutTest {

    /** Compose APIs that ignore LayoutDirection (or force one). Use the Start/End forms instead. */
    private val absoluteApis = Regex(
        """\bAbsoluteAlignment\.|\bArrangement\.Absolute\b|\babsolutePadding\(|""" +
            """\babsoluteOffset\(|\bTextAlign\.(Left|Right)\b|\bPaddingValues\.Absolute\(|""" +
            """\bLocalLayoutDirection\s+provides\s+LayoutDirection\.(Ltr|Rtl)\b""",
    )

    /** file name -> why that file may use an absolute API. Empty today. */
    private val rtlExempt = emptyMap<String, String>()

    @Test
    fun composeLayoutsMirrorInRightToLeft() {
        val root = findDir("app/src/main/java/com/spectrafilm/app")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in rtlExempt }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> !line.trimStart().startsWith("//") && absoluteApis.containsMatchIn(line) }
                    .map { (i, line) -> "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "These do not mirror in RTL (ar, he, fa). Use Alignment.*Start/*End, start/end " +
                "padding or TextAlign.Start/End, or add the file to rtlExempt with a reason:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // The bottom bar's modes: the selected one shows its label in the pill.
    private val stageLabels = listOf("editor_mode_looks", "editor_mode_edit", "editor_mode_masks")

    /** Latin letters count 1, CJK and full-width count 2 (they set about twice as wide). */
    private fun width(text: String): Int = text.sumOf { c ->
        val block = Character.UnicodeBlock.of(c)
        val wide = Character.isIdeographic(c.code) ||
            block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        if (wide) 2 else 1
    }

    @Test
    fun stageLabelsFitTheirToolbarSlot() {
        val budget = 8
        val res = findDir("app/src/main/res")
        val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val tooLong = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { dir ->
                dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
                    .flatMap { f -> entry.findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }
                    .filter { (name, text) -> name in stageLabels && width(text) > budget }
                    .map { (name, text) -> "${dir.name}/$name = \"$text\" (width ${width(text)})" }
            }
        assertTrue(
            "Stage labels must fit a width of $budget (CJK counts 2) or they ellipsize in the " +
                "bottom toolbar. Pick a shorter word:\n" + tooLong.joinToString("\n"),
            tooLong.isEmpty(),
        )
    }

    /** Walk up from the working directory (module or repo root) to [rel]. */
    private fun findDir(rel: String): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            File(current, rel).takeIf { it.isDirectory }?.let { return it }
            File(current, rel.removePrefix("app/")).takeIf { it.isDirectory }?.let { return it }
            current = current.parentFile
        }
        throw AssertionError("Could not locate $rel")
    }
}
