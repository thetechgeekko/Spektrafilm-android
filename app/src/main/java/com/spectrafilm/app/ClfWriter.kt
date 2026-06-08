/*
 * Spektrafilm for Android — Common LUT Format (CLF) writer. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Serializes a baked 3D LUT ([CubeLut], as produced by SpektraEngine.bakeCubeLut → CubeLut.parse) into
 * an Academy/ASC **CLF v3** ProcessList with a single LUT3D — the format DaVinci Resolve (17+) and
 * OpenColorIO (2.3+) import, alongside the existing `.cube` export. Pure Kotlin, JVM-testable, no engine
 * touched: a 3D LUT is the same pointwise look the `.cube` carries (spatial/stochastic effects are
 * omitted from the bake, exactly as for `.cube`).
 *
 * Data ordering: CLF orders a 3D-LUT Array with BLUE varying fastest, then green, then red — which is
 * exactly the order CubeLut.rgb already holds (the engine's blue-fastest layout), so the samples are
 * written through unchanged. Values are 32-bit float; the decimal point is forced to '.' (Locale.US) so
 * the file parses in any device locale. (CLF-import fidelity should be confirmed in your Resolve/OCIO.)
 */
package com.spectrafilm.app

import java.util.Locale

object ClfWriter {

    /** A CLF v3 document for [lut], titled [title]. */
    fun write(lut: CubeLut, title: String = "Spektrafilm look"): String {
        val n = lut.size
        val sb = StringBuilder(lut.rgb.size * 9 + 512)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<ProcessList id=\"").append(idFrom(title))
            .append("\" compCLFversion=\"3.0\" name=\"").append(esc(title)).append("\">\n")
        sb.append("  <Description>").append(esc("$title. Film modeling powered by spektrafilm."))
            .append("</Description>\n")
        sb.append("  <LUT3D id=\"lut3d-0\" interpolation=\"trilinear\" inBitDepth=\"32f\" outBitDepth=\"32f\">\n")
        sb.append("    <Array dim=\"").append(n).append(' ').append(n).append(' ').append(n).append(" 3\">\n")
        val total = n * n * n
        var e = 0
        while (e < total) {
            val k = e * 3
            sb.append("      ").append(fmt(lut.rgb[k])).append(' ')
                .append(fmt(lut.rgb[k + 1])).append(' ').append(fmt(lut.rgb[k + 2])).append('\n')
            e++
        }
        sb.append("    </Array>\n  </LUT3D>\n</ProcessList>\n")
        return sb.toString()
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.6f", v)

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A CLF-id-safe slug from a title (letters/digits/hyphen), never empty. */
    private fun idFrom(s: String): String =
        s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "spektrafilm" }
}
