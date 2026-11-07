/*
 * Spektrafilm for Android — gallery export writers. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Export formats and the MediaStore/legacy publication paths for JPEG, Ultra HDR, PNG, TIFF and
 * 16-bit/float outputs. Split out of ImagePipeline.kt (#273).
 */
package com.spectrafilm.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.annotation.StringRes
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SimResult
import com.spectrafilm.pngwriter.PngWriter
import com.spectrafilm.tiffwriter.ExifColorSpace
import com.spectrafilm.tiffwriter.TiffWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** [display] is the stable English name (logs, diagnostics); the UI shows [labelRes]. */
enum class ExportFormat(
    val display: String,
    val mime: String,
    val ext: String,
    @StringRes val labelRes: Int,
) {
    PNG("PNG (8-bit)", "image/png", "png", R.string.export_format_png),
    JPEG("JPEG", "image/jpeg", "jpg", R.string.export_format_jpeg),
    // Ultra HDR is a JPEG container with an embedded ISO 21496-1 / Google gain map +
    // MPF index — its MIME type stays image/jpeg and the extension stays jpg.
    ULTRA_HDR("Ultra HDR (JPEG)", "image/jpeg", "jpg", R.string.export_format_ultra_hdr),
    TIFF("16-bit TIFF", "image/tiff", "tif", R.string.export_format_tiff16),
    // True 16-bit-per-channel PNG via lib:pngwriter (libsfpng.so) — the engine's float
    // SimResult is quantised directly to uint16, no 8-bit Bitmap round-trip (unlike PNG).
    PNG16("16-bit PNG", "image/png", "png", R.string.export_format_png16),
    // True 32-bit IEEE-float TIFF (lib:tiffwriter) — the engine's float SimResult written
    // VERBATIM (no quantise/clamp). Archival / scene-linear-grade-elsewhere export.
    TIFF32F("32-bit float TIFF", "image/tiff", "tif", R.string.export_format_tiff32f),
    // The decoded scene-linear INPUT (before the film engine) as a 32-bit float TIFF — verbatim,
    // untagged scene-referred linear, for grading in another app (the honest "linear DNG" hand-off).
    SCENE_LINEAR_TIFF("Scene-linear input (32-bit TIFF)", "image/tiff", "tif", R.string.export_format_scene_linear_tiff),
}

/**
 * Whether [format] writes a JPEG byte-stream (plain JPEG or Ultra HDR). EXIF copy via
 * androidx ExifInterface is only attempted for JPEG targets — PNG has no standard EXIF
 * segment that ExifInterface writes reliably across the 1.3.7 version, and TIFF EXIF is
 * handled by the native TiffWriter (a limited subset).
 */
private fun ExportFormat.isJpeg(): Boolean =
    this == ExportFormat.JPEG || this == ExportFormat.ULTRA_HDR

/**
 * True for the high-bit-depth formats (16-bit TIFF/PNG, 32-bit float TIFF), written straight from
 * the float SimResult rather than via an 8-bit Bitmap — so they always export at full resolution
 * (the Bitmap-based resize does not apply).
 */
fun ExportFormat.isHighBitDepth(): Boolean =
    OutputDescriptor.fixedBitDepth(this).bitsPerSample > 8

/**
 * Downscale [bmp] so its longer edge is [longEdge] px, preserving aspect and never enlarging
 * (Lightroom-style export "Dimensions"). Returns [bmp] unchanged when it already fits. A
 * post-render resample, so grain/halation are rendered at full quality first, then downsampled.
 */
fun scaleBitmapToLongEdge(bmp: Bitmap, longEdge: Int): Bitmap {
    val (w, h) = scaledDimensions(bmp.width, bmp.height, longEdge)
    return if (w == bmp.width && h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
}

internal fun attachRenderedGainmap(
    result: SimResult,
    base: Bitmap,
    descriptor: OutputDescriptor,
    /**
     * Film border already composited around the photo in [base], or null. The map is computed
     * for the photo and padded with neutral gain to cover the border, so it stays aligned.
     */
    border: BorderLayout? = null,
    scene: SceneLuma? = null,
): HdrGainMap.Result? {
    val contract = descriptor.metadata.hdrGainMap ?: return null
    if (Build.VERSION.SDK_INT < 34) return null
    val colorSpace = descriptor.engineColorSpace ?: return null
    val cctf = descriptor.engineCctfEncoding ?: return null
    val computed = result.acquireDataLease().use { lease ->
        HdrGainMap.compute(
            rgb = lease.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer(),
            width = result.width,
            height = result.height,
            colorSpace = colorSpace,
            cctfEncoded = cctf,
            downsample = contract.downsample,
            ratioMaxCeiling = contract.ratioMaxCeiling,
            epsilonSdr = contract.epsilonSdr,
            epsilonHdr = contract.epsilonHdr,
            scene = scene,
            multichannel = contract.multichannel,
        )
    }.let { map ->
        border?.let { HdrGainMap.padForBorder(map, it) } ?: map
    }
    val content = gainmapContent(computed)

    val gainmap = Gainmap(content).apply {
        setRatioMin(contract.ratioMin, contract.ratioMin, contract.ratioMin)
        setRatioMax(computed.ratioMax, computed.ratioMax, computed.ratioMax)
        setGamma(contract.gamma, contract.gamma, contract.gamma)
        setEpsilonSdr(contract.epsilonSdr, contract.epsilonSdr, contract.epsilonSdr)
        setEpsilonHdr(contract.epsilonHdr, contract.epsilonHdr, contract.epsilonHdr)
        setDisplayRatioForFullHdr(computed.ratioMax)
        minDisplayRatioForHdrTransition = contract.minDisplayRatioForHdrTransition
    }
    base.setGainmap(gainmap)
    Diag.i(
        "gainmap ${computed.width}x${computed.height} ratioMax=${computed.ratioMax} " +
            "trueMax=${computed.maxRatio} gained=${computed.gainedPixels} " +
            "headroom=${computed.hasHeadroom} scene=${scene != null} " +
            "sceneCells=${computed.sceneCells} k=${computed.exposureMatch} ch=${computed.channels}",
    )
    return computed
}

/**
 * The gain map's pixels as a Bitmap: ALPHA_8 for a single shared gain, ARGB_8888 (R,G,B gains,
 * opaque alpha) for a per-channel map -- the two layouts android.graphics.Gainmap accepts.
 */
private fun gainmapContent(computed: HdrGainMap.Result): Bitmap {
    val w = computed.width
    val h = computed.height
    if (computed.channels == 3) {
        val a = computed.alpha
        val pixels = IntArray(w * h) { i ->
            val o = i * 3
            (0xFF shl 24) or ((a[o].toInt() and 0xFF) shl 16) or
                ((a[o + 1].toInt() and 0xFF) shl 8) or (a[o + 2].toInt() and 0xFF)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
    val content = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    // ALPHA_8 rows can be padded, so copy row by row against the bitmap's own stride rather
    // than assuming rowBytes == width.
    val rowBytes = content.rowBytes
    val staging = java.nio.ByteBuffer.allocate(rowBytes * h)
    for (y in 0 until h) {
        staging.position(y * rowBytes)
        staging.put(computed.alpha, y * w, w)
    }
    staging.rewind()
    content.copyPixelsFromBuffer(staging)
    return content
}

/**
 * Save [bmp] to the public gallery under Pictures/Spektrafilm as PNG or JPEG.
 * Uses scoped storage (MediaStore RELATIVE_PATH + IS_PENDING) on API 29+, and the
 * legacy direct-file + MediaStore insert path below that. Returns the content [Uri].
 *
 * For TIFF, use [saveSimResultAsTiff] instead — Bitmap.compress has no TIFF support.
 */
suspend fun saveToGallery(
    ctx: Context,
    bmp: Bitmap,
    descriptor: OutputDescriptor,
    jpegQuality: Int = 95,
    sourceExif: SourceExif = SourceExif(emptyMap()),
    displayName: String? = null,
    onCommitted: () -> Unit = {},
    /**
     * Invoked with the staged, verified artifact AFTER it has published successfully, so the
     * export cache (#179) can adopt the exact bytes that reached the gallery without re-encoding
     * or re-hashing them: the arguments are the staged file, its length and the SHA-256 the
     * publish path already computed. The stage is deleted immediately afterwards, so an
     * implementation that wants to keep it must move it. Never called for a failed or cancelled
     * export. Deliberately not an EncodedArtifact: these encoders are public API and that type
     * is internal.
     */
    onStaged: (File, Long, String) -> Unit = { _, _, _ -> },
): Uri {
    descriptor.requireExportable(Build.VERSION.SDK_INT)
    require(descriptor.encoder == OutputEncoder.ANDROID_BITMAP_PNG ||
        descriptor.encoder == OutputEncoder.ANDROID_BITMAP_JPEG) {
        "${descriptor.format} is not a Bitmap encoder contract"
    }
    val format = descriptor.format
    val expectedBitmapSpace = requireNotNull(descriptor.metadata.bitmapColorSpaceName)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val expected = android.graphics.ColorSpace.get(
            android.graphics.ColorSpace.Named.valueOf(expectedBitmapSpace),
        )
        require(bmp.colorSpace?.id == expected.id) {
            "Bitmap color space does not match $expectedBitmapSpace output contract"
        }
    } else {
        require(expectedBitmapSpace == "SRGB") { "Wide-gamut Bitmap export requires API 26+" }
    }
    val name = "${displayName ?: "Spektrafilm_${System.currentTimeMillis()}"}.${format.ext}"
    val compress = if (descriptor.encoder == OutputEncoder.ANDROID_BITMAP_PNG) {
        Bitmap.CompressFormat.PNG
    } else {
        Bitmap.CompressFormat.JPEG
    }
    val quality = if (descriptor.encoder == OutputEncoder.ANDROID_BITMAP_PNG) 100
    else jpegQuality.coerceIn(1, 100)
    val exportJob = currentCoroutineContext()[Job]
    val isCancelled = { exportJob?.isActive == false }
    fun ensureExportActive() {
        if (isCancelled()) throw CancellationException("bitmap export cancelled")
    }
    ensureExportActive()

    // Ultra HDR: the per-pixel gain map (attachRenderedGainmap) is already on [bmp] when it
    // reaches here; Bitmap.compress(JPEG) on API 34+ writes it as an Ultra HDR JPEG (SDR base +
    // gain map + MPF). setGainmap mutated [bmp] in place; its pixel data is untouched.

    // EXIF is only writable (via androidx ExifInterface) for JPEG targets. The exported
    // dimensions are taken from the rendered bitmap (post crop/resize/rotate).
    val writeExif = descriptor.metadata.copySourceExif
    val outW = bmp.width
    val outH = bmp.height

    // Encode completely into a unique app-private stage. This makes Bitmap.compress(false),
    // EXIF failure, ENOSPC and interruption fail before any public gallery item is visible.
    val stage = File.createTempFile("spectrafilm-export-", ".${format.ext}.part", ctx.cacheDir)
    try {
        FileOutputStream(stage).use { fileOutput ->
            // Bitmap.compress is native, but it emits encoded chunks through this stream.
            // Poll at that boundary so cancellation cannot leave a stale public item.
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    ensureExportActive()
                    fileOutput.write(value)
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    ensureExportActive()
                    fileOutput.write(buffer, offset, length)
                    ensureExportActive()
                }

                override fun flush() = fileOutput.flush()
            }
            val compressed = bmp.compress(compress, quality, output)
            ensureExportActive()
            if (!compressed) {
                error("Bitmap encoder returned false")
            }
            output.flush()
            fileOutput.fd.sync()
            ensureExportActive()
        }
        ensureExportActive()
        if (writeExif) writeExifToPath(
            stage.absolutePath,
            sourceExif,
            outW,
            outH,
            descriptor.metadata.exifColorSpace,
        )
        ensureExportActive()
        val artifact = EncodedArtifact.fromCompletedFile(stage, isCancelled = isCancelled)
        val published = publishStagedToGallery(
            ctx,
            artifact,
            name,
            format.mime,
            isCancelled,
            onCommitted,
        )
        onStaged(artifact.file, artifact.length, artifact.sha256)
        return published
    } finally {
        if (stage.exists() && !stage.delete()) {
            Diag.w("could not delete export stage ${stage.name}")
        }
    }
}

/**
 * Convert the descriptor's EXIF ColorSpace policy to the native TIFF writer value.
 */
private fun OutputExifColorSpace.toNativeTiffTag(): ExifColorSpace = when (this) {
    OutputExifColorSpace.SRGB -> ExifColorSpace.SRGB
    OutputExifColorSpace.UNCALIBRATED -> ExifColorSpace.UNCALIBRATED
}

/**
 * Quantise the engine's display-referred float RGB [SimResult] to 16-bit per channel
 * and write a baseline TIFF to the gallery via [TiffWriter], using MediaStore for
 * scoped-storage / legacy compatibility.
 *
 * Bit depth: the engine's leased [SimResult] buffer holds float32 RGB values already
 * CCTF-encoded in the chosen [SimResult.colorSpace]. We round-to-nearest quantise
 * each [0,1]-clamped float to [0, 65535] uint16 — a true 16-bit encode with no
 * intermediate 8-bit Bitmap step.
 *
 * ICC: the bundled profile matching the output space is embedded (see [ColorManagement]),
 * so wide-gamut exports open correctly in color-managed apps. The EXIF ColorSpace advisory
 * tag is also set — SRGB only for encoded sRGB, UNCALIBRATED otherwise.
 *
 * @param ctx     Android context (for MediaStore / cacheDir)
 * @param result  The engine SimResult whose float data is quantised to 16-bit
 * @return        The MediaStore [Uri] of the written file
 */
suspend fun saveSimResultAsTiff(
    ctx: Context,
    result: SimResult,
    descriptor: OutputDescriptor,
    displayName: String? = null,
    onCommitted: () -> Unit = {},
    /**
     * Invoked with the staged, verified artifact AFTER it has published successfully, so the
     * export cache (#179) can adopt the exact bytes that reached the gallery without re-encoding
     * or re-hashing them: the arguments are the staged file, its length and the SHA-256 the
     * publish path already computed. The stage is deleted immediately afterwards, so an
     * implementation that wants to keep it must move it. Never called for a failed or cancelled
     * export. Deliberately not an EncodedArtifact: these encoders are public API and that type
     * is internal.
     */
    onStaged: (File, Long, String) -> Unit = { _, _, _ -> },
): Uri {
    require(descriptor.encoder == OutputEncoder.NATIVE_TIFF_UINT16 ||
        descriptor.encoder == OutputEncoder.NATIVE_TIFF_FLOAT32) {
        "${descriptor.format} is not a rendered TIFF contract"
    }
    require(descriptor.engineColorSpace == result.colorSpace) {
        "Engine result ${result.colorSpace} does not match ${descriptor.engineColorSpace} output contract"
    }
    val float32 = descriptor.encoder == OutputEncoder.NATIVE_TIFF_FLOAT32
    val w = result.width
    val h = result.height
    checkedRgbFloatByteCount(w, h, "TIFF export")
    val exportJob = currentCoroutineContext()[Job]
    fun ensureExportActive() {
        if (exportJob?.isActive == false) throw CancellationException("TIFF export cancelled")
    }
    ensureExportActive()

    val dateTime = ExportClock.exifDateTime()
    val exifCs = descriptor.metadata.exifColorSpace.toNativeTiffTag()
    val icc = ColorManagement.requireIccBytes(ctx, descriptor)

    // Write to a temp file in cacheDir first; this avoids holding a MediaStore
    // output stream open for the entire (potentially large) TiffWriter write.
    val tmpFile = File.createTempFile("spectrafilm-export-", ".tif.part", ctx.cacheDir)

    try {
        val encodedBytes = if (float32) {
            // 32-bit float TIFF: write the engine's float samples VERBATIM (no quantise/clamp).
            // The lease keeps the native float32 buffer alive for the whole native writer call.
            runCancellableTiffWrite { cancellation ->
                result.acquireDataLease().use { lease ->
                    val data = lease.data
                    TiffWriter.writeFloat32(
                        rgbFloat = checkedRgbFloatByteWindow(data, w, h, "TIFF32F export"),
                        width = w, height = h, outPath = tmpFile.absolutePath,
                        icc = icc, exifColorSpace = exifCs, software = "Spektrafilm",
                        dateTime = dateTime, packBits = false,
                        cancellation = cancellation,
                    )
                }
            }
        } else {
            // 16-bit TIFF quantised INSIDE the writer, row by row, from the engine's
            // float buffer. This used to quantise here into a second full image --
            // 75 MB at 12.5 MP, and it had to be an off-heap native allocation
            // because a direct ByteBuffer is a managed byte[] on Android and 100 MP
            // would not fit the ART heap at all. Rounding is unchanged, so the file
            // is byte-identical to what the uint16 entry point produced (#175).
            result.acquireDataLease().use { lease ->
                ensureExportActive()
                val floatBuf = checkedRgbFloatByteWindow(lease.data, w, h, "TIFF export")
                runCancellableTiffWrite { cancellation ->
                    TiffWriter.writeFloat16(
                        rgbFloat = floatBuf,
                        width = w,
                        height = h,
                        outPath = tmpFile.absolutePath,
                        icc = icc,
                        exifColorSpace = exifCs,
                        software = "Spektrafilm",
                        dateTime = dateTime,
                        packBits = false,
                        cancellation = cancellation,
                    )
                }
            }
        }

        ensureExportActive()
        val isCancelled = { exportJob?.isActive == false }
        val artifact = EncodedArtifact.fromCompletedFile(tmpFile, encodedBytes, isCancelled)
        val published = publishStagedToGallery(
            ctx,
            artifact,
            "${displayName ?: "Spektrafilm_${System.currentTimeMillis()}"}.tif",
            descriptor.format.mime,
            isCancelled,
            onCommitted,
        )
        onStaged(artifact.file, artifact.length, artifact.sha256)
        return published
    } finally {
        // Delete on ALL paths: a writer/publish throw must not leave the (potentially
        // huge) temp file behind in cacheDir. Publish has consumed the bytes by now.
        tmpFile.delete()
    }
}

/**
 * Publish an export straight from the content-addressed cache (#179), skipping decode, engine,
 * grade and encode entirely.
 *
 * The entry was already validated against its recorded key, length and SHA-256 when it was read,
 * so its digest is reused here rather than re-hashing 70 MB: publishStagedToGallery still verifies
 * what it writes and reads back, so the published bytes are checked end to end regardless.
 */
internal fun publishCachedExport(
    ctx: Context,
    entry: ExportCacheEntry,
    displayName: String,
    mime: String,
    isCancelled: () -> Boolean = { false },
    onCommitted: () -> Unit = {},
): Uri = publishStagedToGallery(
    ctx,
    EncodedArtifact(entry.payload, entry.bytes, entry.sha256),
    displayName,
    mime,
    isCancelled,
    onCommitted,
)

/** Publish one already-encoded, verified stage through the shared transaction path. */
private fun publishStagedToGallery(
    ctx: Context,
    artifact: EncodedArtifact,
    name: String,
    mime: String,
    isCancelled: () -> Boolean = { false },
    onCommitted: () -> Unit = {},
): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    publishStagedImage(
        ctx,
        artifact,
        name,
        mime,
        isCancelled,
        onPublished = { onCommitted() },
    )
} else {
    publishLegacyArtifact(ctx, artifact, name, mime, isCancelled, onCommitted)
}

/** API 24–28: sibling `.part` + fsync + digest + non-overwriting rename. */
@Suppress("DEPRECATION")
private fun publishLegacyArtifact(
    ctx: Context,
    artifact: EncodedArtifact,
    requestedName: String,
    mime: String,
    isCancelled: () -> Boolean = { false },
    onCommitted: () -> Unit = {},
): Uri {
    fun ensureExportActive() {
        if (isCancelled()) throw CancellationException("legacy export publication cancelled")
    }
    ensureExportActive()
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Spektrafilm",
    )
    if (!dir.isDirectory && !dir.mkdirs()) error("Could not create legacy export directory")
    val part = File.createTempFile(".spectrafilm-", ".part", dir)
    var destination: File? = null
    var destinationCommitted = false
    try {
        val copied = FileOutputStream(part).use { output ->
            val count = artifact.file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    ensureExportActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    ensureExportActive()
                }
                total
            }
            output.flush()
            output.fd.sync()
            ensureExportActive()
            count
        }
        val copiedArtifact = EncodedArtifact.fromCompletedFile(part, copied, isCancelled)
        check(copiedArtifact.length == artifact.length && copiedArtifact.sha256 == artifact.sha256) {
            "legacy export digest mismatch"
        }
        ensureExportActive()
        destination = renameWithoutOverwrite(part, dir, requestedName, isCancelled)
        // Rename is the legacy publication linearization point. A late cancellation must
        // not turn the already-visible file into a retryable duplicate.
        destinationCommitted = true
        onCommitted()
        val finalArtifact = EncodedArtifact.fromCompletedFile(destination)
        check(finalArtifact.length == artifact.length && finalArtifact.sha256 == artifact.sha256) {
            "legacy export changed during commit"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, destination.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATA, destination.absolutePath)
        }
        return ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("legacy gallery index insert failed")
    } catch (failure: Throwable) {
        if (destinationCommitted) {
            // The final file is already visible. Gallery indexing or callback/unwind failure must
            // not delete it and invite a duplicate retry; file:// is the stable committed handle.
            Diag.w("legacy export committed but gallery indexing failed: ${failure.message}")
            return Uri.fromFile(checkNotNull(destination))
        }
        val cleanup = destination ?: part
        if (cleanup.exists() && !cleanup.delete()) {
            failure.addSuppressed(IOException("could not delete failed legacy export: $cleanup"))
        }
        throw failure
    }
}

private fun renameWithoutOverwrite(
    part: File,
    dir: File,
    requestedName: String,
    isCancelled: () -> Boolean = { false },
): File {
    require('/' !in requestedName && '\\' !in requestedName && '\u0000' !in requestedName)
    return synchronized(legacyExportNameLock) {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        for (suffix in 0..9_999) {
            if (isCancelled()) throw CancellationException("legacy export publication cancelled")
            val name = if (suffix == 0) requestedName else "$base ($suffix)$extension"
            val candidate = File(dir, name)
            // createNewFile is O_EXCL: it atomically reserves this exact final name even
            // against another process. Rename then atomically replaces our own zero-byte
            // reservation with the already-fsynced sibling stage.
            if (!candidate.createNewFile()) continue
            try {
                Os.rename(part.absolutePath, candidate.absolutePath)
                return@synchronized candidate
            } catch (failure: Throwable) {
                val cleanup = runCatching {
                    if (candidate.exists() && !candidate.delete()) {
                        throw IOException("could not remove failed legacy name reservation: $candidate")
                    }
                }
                cleanup.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        error("Could not allocate a unique legacy export name")
    }
}

private val legacyExportNameLock = Any()

/** API 24-28 startup cleanup for hidden, incomplete sibling stages left by process death. */
internal fun recoverAbandonedLegacyExportStages(
    context: Context,
    priorProcessCutoffMillis: Long,
): AbandonedExportStageRecoveryReport {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return AbandonedExportStageRecoveryReport(0, 0, 0)
    }
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Spektrafilm",
    )
    if (!dir.isDirectory) return AbandonedExportStageRecoveryReport(0, 0, 0)
    var examined = 0
    var removed = 0
    var retained = 0
    dir.listFiles()?.forEach { candidate ->
        if (
            candidate.isFile &&
            candidate.name.startsWith(".spectrafilm-") &&
            candidate.name.endsWith(".part") &&
            candidate.lastModified() < priorProcessCutoffMillis
        ) {
            examined++
            if (candidate.delete()) removed++ else retained++
        }
    }
    return AbandonedExportStageRecoveryReport(examined, removed, retained)
}

/**
 * Export the decoded *scene-linear input* (the linear RGB fed to the engine, before the film
 * simulation) as a 32-bit IEEE-float TIFF — the honest answer to "give me a linear file to finish
 * elsewhere". Written VERBATIM and UNTAGGED (no ICC; EXIF Uncalibrated): the data is scene-referred
 * linear in [image]'s own primaries ([LinearImage.colorSpace], e.g. ACES2065-1 for RAW), which a
 * grading app reads as linear. A display-gamma ICC would mis-describe linear data, so none is
 * embedded; the producer string records the primaries.
 */
suspend fun saveLinearInputAsTiff32f(
    ctx: Context,
    image: LinearImage,
    descriptor: OutputDescriptor,
    displayName: String? = null,
    onCommitted: () -> Unit = {},
): Uri {
    require(descriptor.format == ExportFormat.SCENE_LINEAR_TIFF &&
        descriptor.reference == OutputReference.SCENE_REFERRED &&
        descriptor.encoder == OutputEncoder.NATIVE_TIFF_FLOAT32 &&
        descriptor.quantizer == OutputQuantizer.VERBATIM_FLOAT32 &&
        descriptor.metadata.iccAssetPath == null) {
        "Scene-linear input requires the untagged verbatim TIFF32F contract"
    }
    val exportJob = currentCoroutineContext()[Job]
    val isCancelled = { exportJob?.isActive == false }
    val dateTime = ExportClock.exifDateTime()
    val tmpFile = File.createTempFile("spectrafilm-export-", ".tif.part", ctx.cacheDir)
    try {
        val encodedBytes = runCancellableTiffWrite { cancellation ->
            image.acquireDataLease().use { lease ->
                val data = lease.data
                TiffWriter.writeFloat32(
                    rgbFloat = checkedRgbFloatByteWindow(
                        data, image.width, image.height, "scene-linear TIFF export",
                    ),
                    width = image.width,
                    height = image.height,
                    outPath = tmpFile.absolutePath,
                    icc = null,
                    exifColorSpace = descriptor.metadata.exifColorSpace.toNativeTiffTag(),
                    software = "Spektrafilm (scene-linear ${image.colorSpace})",
                    dateTime = dateTime,
                    packBits = false,
                    cancellation = cancellation,
                )
            }
        }
        if (isCancelled()) {
            throw CancellationException("scene-linear TIFF export cancelled")
        }
        val artifact = EncodedArtifact.fromCompletedFile(tmpFile, encodedBytes, isCancelled)
        return publishStagedToGallery(
            ctx,
            artifact,
            "${displayName ?: "Spektrafilm_${System.currentTimeMillis()}"}_scene-linear.tif",
            descriptor.format.mime,
            isCancelled,
            onCommitted,
        )
    } finally {
        // Delete on ALL paths: a writer/publish throw must not leave the temp behind.
        tmpFile.delete()
    }
}

/**
 * Quantise the engine's display-referred float RGB [SimResult] to 16-bit per channel
 * and write a true 16-bit-per-channel RGB PNG to the gallery via [PngWriter]
 * (lib:pngwriter / libsfpng.so), using MediaStore for scoped-storage compatibility.
 *
 * Mirrors [saveSimResultAsTiff]: same round-to-nearest float[0,1] -> uint16[0,65535]
 * quantisation with no intermediate 8-bit Bitmap step. The PNG writer byte-swaps the
 * little-endian uint16 samples to big-endian internally (PNG spec), so the caller only
 * supplies native/little-endian uint16. The PNG carries an iCCP chunk with the bundled
 * profile matching the output space (see [ColorManagement]) so wide-gamut exports are tagged.
 *
 * Unlike the 8-bit [saveToGallery] PNG path (Bitmap.compress, 8 bpc), this preserves
 * the engine's full tonal precision.
 */
suspend fun saveSimResultAsPng16(
    ctx: Context,
    result: SimResult,
    descriptor: OutputDescriptor,
    displayName: String? = null,
    onCommitted: () -> Unit = {},
    /**
     * Invoked with the staged, verified artifact AFTER it has published successfully, so the
     * export cache (#179) can adopt the exact bytes that reached the gallery without re-encoding
     * or re-hashing them: the arguments are the staged file, its length and the SHA-256 the
     * publish path already computed. The stage is deleted immediately afterwards, so an
     * implementation that wants to keep it must move it. Never called for a failed or cancelled
     * export. Deliberately not an EncodedArtifact: these encoders are public API and that type
     * is internal.
     */
    onStaged: (File, Long, String) -> Unit = { _, _, _ -> },
): Uri {
    require(descriptor.encoder == OutputEncoder.NATIVE_PNG16 &&
        descriptor.quantizer == OutputQuantizer.UINT16_ROUND_CLAMP) {
        "${descriptor.format} is not a PNG16 contract"
    }
    require(descriptor.engineColorSpace == result.colorSpace) {
        "Engine result ${result.colorSpace} does not match ${descriptor.engineColorSpace} output contract"
    }
    val w = result.width
    val h = result.height
    // Still validated up front: the writer needs the same geometry to be sane, and
    // failing here keeps the error at the caller rather than inside JNI.
    checkedRgbFloatByteCount(w, h, "PNG16 export")
    val exportJob = currentCoroutineContext()[Job]
    fun ensureExportActive() {
        if (exportJob?.isActive == false) throw CancellationException("PNG16 export cancelled")
    }
    ensureExportActive()

    // Write to a temp file in cacheDir first; avoids holding a MediaStore output stream
    // open for the whole (potentially large) PNG deflate. Deleted on ALL paths by the
    // try/finally below — a writer/publish throw must not leave it behind.
    val tmpFile = File.createTempFile("spectrafilm-export-", ".png.part", ctx.cacheDir)

    try {
        // The engine's float buffer goes straight to the writer, which quantises one
        // row at a time (#175). This used to allocate a second full-size uint16
        // buffer -- 75 MB at 12.5 MP -- and fill it by walking every sample in a JVM
        // loop, for pixels the writer then consumed once. The writer's quantisation
        // is the same arithmetic this loop used (clamp to [0,1], v*65535+0.5,
        // truncate, NaN to 0), so the exported pixels are unchanged.
        val encodedBytes = result.acquireDataLease().use { lease ->
            ensureExportActive()
            val floatBuf = checkedRgbFloatByteWindow(lease.data, w, h, "PNG16 export")
            runCancellablePngWrite { cancellation ->
                PngWriter.writeFloat(
                    rgbFloat = floatBuf,
                    width = w,
                    height = h,
                    outPath = tmpFile.absolutePath,
                    icc = ColorManagement.requireIccBytes(ctx, descriptor),
                    software = "Spektrafilm",
                    cancellation = cancellation,
                )
            }
        }

        ensureExportActive()
        val name = "${displayName ?: "Spektrafilm_${System.currentTimeMillis()}"}.png"
        val isCancelled = { exportJob?.isActive == false }
        val artifact = EncodedArtifact.fromCompletedFile(tmpFile, encodedBytes, isCancelled)
        val published = publishStagedToGallery(
            ctx,
            artifact,
            name,
            descriptor.format.mime,
            isCancelled,
            onCommitted,
        )
        onStaged(artifact.file, artifact.length, artifact.sha256)
        return published
    } finally {
        tmpFile.delete()
    }
}
