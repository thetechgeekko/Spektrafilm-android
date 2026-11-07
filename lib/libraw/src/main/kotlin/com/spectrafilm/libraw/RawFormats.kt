/*
 * Spektrafilm for Android -- the camera RAW formats this build decodes. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * One list, used everywhere a file is recognised as camera RAW by its name or MIME type
 * (the Library folder listing, gallery decoders, source routing). LibRaw itself identifies a
 * file by its content, not its extension, so this list only decides what is *offered* as RAW;
 * an unknown file opened on purpose still goes to the RAW decoder (see the app's
 * detectSourceKind).
 *
 * Pure Kotlin with no native dependency, so it is unit-testable on the plain JVM (RawDecoder's
 * object init loads libsfraw).
 */
package com.spectrafilm.libraw

import java.util.Locale

object RawFormats {
    /** A camera maker and the RAW extensions (lower case, no dot) its bodies write. */
    data class Maker(val name: String, val extensions: List<String>)

    /**
     * Makers and containers the pinned LibRaw 0.22.2 build decodes. Grouped by maker so a
     * settings or help screen can show them the way photographers think about them.
     *
     * Deliberately absent, because this build cannot decode them and listing them would only
     * produce a failed open:
     *  - `.x3f` (Sigma Foveon): LibRaw decodes it only with USE_X3FTOOLS, which we do not
     *    build. Sigma's later bodies (fp, fp L) write DNG, which is listed.
     *  - `.gpr` (GoPro): needs the GoPro GPR SDK (USE_GPRSDK).
     *  - `.r3d` (RED), `.braw` (Blackmagic), `.ari` (ARRI): cinema RAW LibRaw does not handle.
     */
    val makers: List<Maker> = listOf(
        Maker("Adobe DNG", listOf("dng")),
        Maker("Canon", listOf("cr3", "cr2", "crw")),
        Maker("Nikon", listOf("nef", "nrw")),
        Maker("Sony", listOf("arw", "srf", "sr2")),
        Maker("Fujifilm", listOf("raf")),
        Maker("OM System / Olympus", listOf("orf")),
        Maker("Panasonic", listOf("rw2", "raw")),
        Maker("Leica", listOf("rwl", "raw")),
        Maker("Pentax", listOf("pef", "ptx")),
        Maker("Samsung", listOf("srw")),
        Maker("Hasselblad", listOf("3fr", "fff")),
        Maker("Phase One", listOf("iiq")),
        Maker("Leaf", listOf("mos")),
        Maker("Mamiya", listOf("mef")),
        Maker("Minolta", listOf("mrw")),
        Maker("Epson", listOf("erf")),
        Maker("Kodak", listOf("dcr", "kdc")),
    )

    /** Every listed extension, lower case, no dot. */
    val extensions: Set<String> = makers.flatMapTo(LinkedHashSet()) { it.extensions }

    /**
     * MIME types that name a camera RAW. Android's own extension map, the freedesktop
     * shared-mime-info names, and what common document providers report. A provider that does
     * not know the format usually says `application/octet-stream`, which is NOT here: it says
     * nothing about the content.
     */
    val mimeTypes: Set<String> = setOf(
        "image/x-adobe-dng", "image/dng",
        "image/x-canon-cr3", "image/x-canon-cr2", "image/x-canon-crw",
        "image/x-nikon-nef", "image/x-nikon-nrw",
        "image/x-sony-arw", "image/x-sony-srf", "image/x-sony-sr2",
        "image/x-fuji-raf", "image/x-fujifilm-raf",
        "image/x-olympus-orf",
        "image/x-panasonic-rw2", "image/x-panasonic-raw", "image/x-panasonic-rw",
        "image/x-leica-rwl",
        "image/x-pentax-pef", "image/x-pentax-ptx",
        "image/x-samsung-srw",
        "image/x-hasselblad-3fr", "image/x-hasselblad-fff",
        "image/x-phaseone-iiq",
        "image/x-leaf-mos",
        "image/x-mamiya-mef",
        "image/x-minolta-mrw",
        "image/x-epson-erf",
        "image/x-kodak-dcr", "image/x-kodak-kdc",
        "image/x-dcraw",
    )

    /** True if [extension] (with or without a leading dot, any case) is a listed RAW type. */
    fun isRawExtension(extension: String): Boolean =
        extension.trimStart('.').lowercase(Locale.ROOT) in extensions

    /** True if a file name, path or URI segment ends in a listed RAW extension. */
    fun isRawFileName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        // A path separator after the last dot means the dot belongs to a directory.
        if (name.indexOf('/', dot) >= 0) return false
        return isRawExtension(name.substring(dot + 1))
    }

    /** True if [mimeType] names a camera RAW. Parameters (`; charset=...`) are ignored. */
    fun isRawMimeType(mimeType: String?): Boolean {
        val base = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return base in mimeTypes
    }
}
