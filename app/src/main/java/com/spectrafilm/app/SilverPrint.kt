/*
 * Spektrafilm for Android — black-and-white printing: silver stocks, paper grades, pairing. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The black-and-white stocks are ordinary three-channel profiles whose channels all hold neutral
 * silver (`info.channel_model = "bw"`), so the engine renders them unchanged. What differs is the
 * darkroom around them, and that lives here, as plain data the JVM tests can check:
 *
 *  - A silver film only prints well on silver paper. On a colour paper its untinted negative has
 *    no neutral filter pack and comes out a strong orange (measured on the host engine: mid-grey
 *    renders as RGB 0.85 / 0.28 / 0.09). A colour negative on silver paper is a real darkroom
 *    practice and renders neutral, so that direction is allowed.
 *  - Multigrade is a variable-contrast paper: the enlarger's yellow or magenta filtration picks
 *    the grade. [GRADES] is that table, with the print exposure that holds mid-grey at each grade.
 *
 * The profiles, the grade table and the filter-2 pack come from the SpektraLab macOS port's
 * product data (GPLv3, by Hanze Qiu), built from the Kodak, Ilford and Fujifilm datasheets; see
 * NOTICE.md. Verified on our engine: Tri-X, T-Max 100, HP5 Plus and Acros II all print mid-grey to
 * the same value at every grade, with contrast rising from 00 to 5.
 */
package com.spectrafilm.app

/** One Multigrade grade: the enlarger's neutral pack and the exposure that holds mid-grey. */
data class PaperGrade(
    val label: String,
    val mFilter: Float,
    val yFilter: Float,
    val printExposure: Float,
)

object SilverPrint {

    const val MULTIGRADE = "ilford_multigrade_iv_rc"

    /** Silver papers. Every one takes the [GRADES] filtration instead of the colour-pack table. */
    val PAPERS: Set<String> = setOf(MULTIGRADE)

    /** Silver (black-and-white) films. Mirrors `monochrome: true` on the films in catalog.json. */
    val FILMS: Set<String> = setOf(
        "kodak_tri_x_400",
        "ilford_hp5_plus_400",
        "kodak_tmax_100",
        "fujifilm_neopan_acros_100_ii",
    )

    const val DEFAULT_GRADE = "2"

    /**
     * Multigrade filter 00 to 5 (no half grades: the datasheet has none). Grade 2 is the paper's
     * neutral pack (Y 68, print exposure 1), the same entry neutral_print_filters.json carries.
     * Fitted on Tri-X; the pack is the paper's, so it prints the other three films the same way.
     */
    val GRADES: List<PaperGrade> = listOf(
        PaperGrade("00", mFilter = 0f, yFilter = 199f, printExposure = 0.5323f),
        PaperGrade("0", mFilter = 0f, yFilter = 137.9f, printExposure = 0.6642f),
        PaperGrade("1", mFilter = 0f, yFilter = 99.8f, printExposure = 0.8257f),
        PaperGrade("2", mFilter = 0f, yFilter = 68f, printExposure = 1f),
        PaperGrade("3", mFilter = 0f, yFilter = 34.1f, printExposure = 1.159f),
        PaperGrade("4", mFilter = 39.1f, yFilter = 0f, printExposure = 1.334f),
        PaperGrade("5", mFilter = 151.7f, yFilter = 0f, printExposure = 1.3507f),
    )

    fun isSilverFilm(id: String): Boolean = id in FILMS

    fun isSilverPaper(id: String): Boolean = id in PAPERS

    /** The grade called [label], or grade 2 for an unknown or blank label (an older recipe). */
    fun grade(label: String): PaperGrade =
        GRADES.firstOrNull { it.label == label } ?: GRADES.first { it.label == DEFAULT_GRADE }

    /**
     * The print to use after the film changes from [oldFilm] to [newFilm].
     *
     * A silver film moves a colour print onto [MULTIGRADE] (when [available] has it). Leaving a
     * silver film for a colour one while still on silver paper returns to [lastColourPrint], the
     * colour paper the user had before, because that paper was chosen for them, not by them. A
     * colour film the user deliberately put on silver paper stays there.
     */
    fun printAfterFilmChange(
        oldFilm: String,
        newFilm: String,
        print: String,
        lastColourPrint: String?,
        available: Collection<String>,
    ): String = when {
        isSilverFilm(newFilm) && !isSilverPaper(print) ->
            if (MULTIGRADE in available) MULTIGRADE else print
        !isSilverFilm(newFilm) && isSilverFilm(oldFilm) && isSilverPaper(print) &&
            lastColourPrint != null && lastColourPrint in available -> lastColourPrint
        else -> print
    }

    /**
     * The colour paper to remember after a film change that moved the print from [oldPrint] to
     * [newPrint]. Only the automatic move of a colour paper onto silver paper is remembered; it
     * is kept while the film stays silver, and consumed (cleared) as soon as a colour film is
     * back, whether or not it was restored, so a later deliberate choice of silver paper is
     * never undone by a stale memory. A manual paper change clears it too.
     */
    fun colourPrintToRemember(
        newFilm: String,
        oldPrint: String,
        newPrint: String,
        remembered: String?,
    ): String? = when {
        newPrint != oldPrint && isSilverFilm(newFilm) && !isSilverPaper(oldPrint) -> oldPrint
        isSilverFilm(newFilm) -> remembered
        else -> null
    }

    /** True when [print] cannot print [film] well: a silver film on a colour paper. */
    fun isMismatched(film: String, print: String): Boolean =
        isSilverFilm(film) && !isSilverPaper(print)
}
