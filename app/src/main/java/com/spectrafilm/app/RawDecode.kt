/*
 * Spektrafilm for Android — RAW decode. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * LibRaw decode into a linear image, with the memory-fallback ladder and bounded half-size
 * decode. Split out of EngineHelpers.kt (#273).
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.libraw.LinearResult
import com.spectrafilm.libraw.DecodeStatus
import com.spectrafilm.libraw.RawDecodeCancellation
import com.spectrafilm.libraw.RawDecodeException
import com.spectrafilm.libraw.RawDecoder
import com.spectrafilm.libraw.RawInputLimits
import com.spectrafilm.libraw.WhiteBalance
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Decode a camera RAW/DNG [uri] to a scene-linear ProPhoto-RGB [LinearImage] via LibRaw
 * (decoded as ACES2065-1, then converted to the engine's ProPhoto input space).
 *
 * Lightroom-style big-file handling: rather than always decoding at full native
 * resolution (a 50-200MP DNG is a multi-hundred-MB float transient — the thing that
 * OOMs), we decode a reduced *proxy* for interactive/preview-scale targets and reserve
 * the full-resolution decode for export. Lightroom does the same with Smart Previews
 * (it edits a ~2560px proxy and only reaches for the original on export / deep zoom).
 * Concretely, when [maxEdge] is at or below [HALF_DECODE_EDGE_THRESHOLD] we ask LibRaw
 * for a half-size decode (averages each Bayer 2x2 -> 1/4 the pixels and memory), then
 * box-downsample the rest of the way to [maxEdge]. Export-scale targets decode full-res.
 *
 * [wb]/[temperatureK]/[tint] mirror the GUI "Import Raw" white-balance controls.
 */
fun decodeRawToLinear(
    ctx: Context,
    uri: Uri,
    wb: WhiteBalance,
    temperatureK: Double,
    tint: Double,
    maxEdge: Int = MAX_EDGE_PX,
    cancellation: RawDecodeCancellation? = null,
    memoryDegraded: AtomicBoolean? = null,
    hotPixels: Boolean = false,
    chromaNoise: Float = 0f,
    admittedBytes: Long = 0L,
    allowReducedFallback: Boolean = true,
): LinearImage {
    return decodeRawWithMemoryFallback(maxEdge, memoryDegraded, allowReducedFallback) { edge, halfSize ->
        decodeRawAtEdge(
            ctx, uri, wb, temperatureK, tint, edge, halfSize, cancellation,
            hotPixels, chromaNoise, admittedBytes,
        )
    }
}

/**
 * Native allocation/admission failures are typed RawDecodeExceptions, not JVM
 * OutOfMemoryErrors. Retry full decode once with half-size output; an already
 * reduced typed failure must propagate instead of repeatedly copying the RAW.
 * Every retry sets [memoryDegraded]: the result is then smaller than asked for.
 */
internal fun <T> decodeRawWithMemoryFallback(
    maxEdge: Int,
    memoryDegraded: AtomicBoolean? = null,
    /** False for an export: a reduced decode would be refused anyway, so fail at once. */
    allowReducedFallback: Boolean = true,
    decode: (edge: Int, halfSize: Boolean) -> T,
): T {
    if (!allowReducedFallback) return decode(maxEdge.coerceAtLeast(MIN_RAW_FALLBACK_EDGE), false)
    var attemptEdge = maxEdge.coerceAtLeast(MIN_RAW_FALLBACK_EDGE)
    var halfSize = maxEdge <= HALF_DECODE_EDGE_THRESHOLD
    while (true) {
        try {
            return decode(attemptEdge, halfSize)
        } catch (failure: RawDecodeException) {
            if (failure.status != DecodeStatus.NO_MEMORY || halfSize) throw failure
            memoryDegraded?.set(true)
            halfSize = true
        } catch (oom: OutOfMemoryError) {
            // Whatever this ladder returns now is smaller than asked for; callers that
            // persist the result (#288's disk cache) must not store it as the full decode.
            memoryDegraded?.set(true)
            // Encourage the collector to reclaim the failed transient before retrying.
            System.gc()
            if (!halfSize) {
                halfSize = true
                continue
            }
            if (attemptEdge <= MIN_RAW_FALLBACK_EDGE) {
                throw RuntimeException(
                    "Not enough memory to decode this RAW file (too large for this device).", oom,
                )
            }
            attemptEdge = max(MIN_RAW_FALLBACK_EDGE, attemptEdge / 2)
        }
    }
}

/**
 * Read [uri] fully into a DIRECT [ByteBuffer], streaming in 1 MB chunks so the only managed
 * allocation is the small reusable chunk — never `readBytes()`'s file-sized (and transiently
 * up to 2x) Java byte[]. Used as the OOM-safer input for LibRaw's buffer decode path when the
 * fd decode isn't usable; LibRaw's in-memory open also tends to succeed where its fd open
 * failed. Returns null if the stream can't be opened; encoded inputs above the canonical
 * 64 MiB LibRaw ceiling fail typed before any provider-sized allocation or read.
 *
 * NOTE: on Android `ByteBuffer.allocateDirect` is still a non-movable byte[] on the ART heap,
 * so this is NOT off-heap — for a pathologically large file the allocateDirect itself can OOM;
 * that case rethrows and loadSource falls back to the sample-size-bounded ImageDecoder.
 */
private fun readUriToDirectBuffer(
    ctx: Context,
    uri: Uri,
    cancellation: RawDecodeCancellation?,
): ByteBuffer? {
    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
    return pfd.use {
        val size = it.statSize
        val capacity = checkedRawUriInputCapacity(size) ?: return@use null
        val buf = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
        java.io.FileInputStream(it.fileDescriptor).use { input ->
            val chunk = ByteArray(1 shl 20)
            while (true) {
                if (cancellation?.isCancellationRequested == true) {
                    throw RawDecodeException(
                        "RAW decode cancelled",
                        DecodeStatus.CANCELLED.code,
                        0,
                    )
                }
                val n = input.read(chunk)
                if (n < 0) break
                if (n > buf.remaining()) {
                    throw RawDecodeException(
                        "RAW provider returned more bytes than its declared size",
                        DecodeStatus.INPUT.code,
                        0,
                    )
                }
                buf.put(chunk, 0, n)
            }
        }
        buf.flip()
        buf
    }
}

/** Smallest longest-edge cap the RAW OOM-retry ladder will fall back to before giving up. */
private const val MIN_RAW_FALLBACK_EDGE = 512

/**
 * At or below this target longest-edge, decode the RAW at half native resolution (the
 * interactive proxy). Above it (export-scale), decode full-res. The preview/magnifier cap
 * (MAX_EDGE_PX = 2048) sits below this; EXPORT_MAX_EDGE_PX is far above.
 */
internal const val HALF_DECODE_EDGE_THRESHOLD = 4096

/** Single RAW decode + box-downsample attempt at a specific [maxEdge] (see [decodeRawToLinear]). */
private fun decodeRawAtEdge(
    ctx: Context,
    uri: Uri,
    wb: WhiteBalance,
    temperatureK: Double,
    tint: Double,
    maxEdge: Int,
    halfSize: Boolean,
    cancellation: RawDecodeCancellation?,
    hotPixels: Boolean,
    chromaNoise: Float,
    admittedBytes: Long,
): LinearImage {
    val settings = RawDecoder.Settings(
        whiteBalance = wb, temperatureK = temperatureK, tint = tint, halfSize = halfSize,
        // Hard cap the NATIVE decode to the target edge: some DNGs ignore LibRaw half_size
        // and decode full-resolution, and the result's direct ByteBuffer is a managed
        // byte[] on Android — a 4080x3060 buffer is ~150 MB and OOMs the ART heap. With
        // this cap the native decoder downsamples before returning, so the allocation is
        // bounded by maxEdge regardless of half_size.
        maxLongEdge = maxEdge,
        hotPixelCorrection = hotPixels,
        chromaNoiseReduction = chromaNoise,
        admittedBytes = admittedBytes,
    )
    // Decode straight from the file descriptor: LibRaw reads through the fd, so we never
    // copy the whole RAW file into a single contiguous Java byte[] first. That byte[]
    // (100-200 MB for a 50MP Samsung Expert-RAW DNG) was itself throwing OutOfMemoryError
    // on the Java heap (growth limit ~256 MB) before LibRaw ever ran. The fd is duplicated
    // natively (caller retains ownership), so the ParcelFileDescriptor is closed here.
    // Fall back to the byte[] path only if the provider's fd can't be decoded (e.g. a
    // non-seekable/in-memory document provider that LibRaw can't seek).
    val result = try {
        decodeRawFromFd(ctx, uri, settings, cancellation)
    } catch (e: RawDecodeException) {
        // A real LibRaw decode verdict (lossy-JPEG / JPEG-XL Expert-RAW DNG it can't decode):
        // let it propagate so loadSource routes to the bounded platform decoder, instead of
        // swallowing it here and reading the whole file.
        throw e
    } catch (e: RuntimeException) {
        // fd-direct decode failed for a non-verdict reason (some providers' fds LibRaw can't
        // open). Retry from the file bytes via a direct ByteBuffer (avoids readBytes()'s
        // file-sized + 2x-regrowth managed array; LibRaw's buffer open often succeeds here).
        // If even that allocation fails, propagate -> loadSource's platform decoder.
        val direct = readUriToDirectBuffer(ctx, uri, cancellation) ?: throw e
        RawDecoder.decodeToLinear(direct, settings, cancellation)
    }
    return rawResultToLinearImage(result, maxEdge)

    // The result is now backed by a NATIVE, off-heap buffer (malloc +
    // NewDirectByteBuffer; see
    // raw_decoder_jni.cpp) rather than a JVM-managed ByteBuffer.allocateDirect (which on
    // Android is a non-movable byte[] on the ~256 MB ART heap). For EXPORT-scale targets
    // we hand that off-heap buffer straight to the engine — keeping the full-res ~140 MB
    // off the managed heap is the actual OOM fix, modelled on Lightroom — and the caller
    // close()s the returned LinearImage to free it. For PREVIEW/magnifier-scale targets we
    // copy into a small managed buffer and free the native original right away, so the
    // proxy decode + preview cache lifecycle (which reuses the buffer across renders and
    // relies on GC) is unchanged.
    // Native ignored the maxLongEdge cap (some DNGs do): box-downsample into a managed
    // proxy, then free the off-heap native original.
}

/** Validate a provider's declared length before any provider-sized allocation or read. */
internal fun checkedRawUriInputCapacity(statSize: Long): Int? =
    if (statSize <= 0L) null else RawInputLimits.checkedCapacity(statSize)

/**
 * Converts a decoded RAW result while making the native allocation ownership explicit.
 * This consumes [result]. Export transfers its active data lease into [LinearImage];
 * proxy paths copy and close immediately. A concurrent `result.close()` cannot free
 * the native allocation while conversion or the transferred image still holds the lease.
 */
internal fun rawResultToLinearImage(result: LinearResult, maxEdge: Int): LinearImage {
    val lease = result.acquireDataLease()
    var leaseTransferred = false
    try {
        require(maxEdge > 0) { "maxEdge must be positive" }
        val width = result.width
        val height = result.height
        val requiredBytes = checkedRgbFloatBytes(width, height)
        val logical = lease.data.duplicate().order(ByteOrder.nativeOrder())
        require(logical.remaining() >= requiredBytes) {
            "RAW result buffer is truncated: ${logical.remaining()} < $requiredBytes"
        }
        logical.limit(logical.position() + requiredBytes)
        val packed = logical.slice().order(ByteOrder.nativeOrder())

        val longest = max(width, height)
        var step = 1
        while (longest / step > maxEdge) step++
        val exportScale = maxEdge > HALF_DECODE_EDGE_THRESHOLD

        return if (step <= 1 && exportScale) {
            val image = LinearImage.fromDataLease(
                packed,
                width,
                height,
                colorSpace = result.colorSpace,
                lease = lease,
            )
            leaseTransferred = true
            image
        } else if (step <= 1) {
            val managed = ByteBuffer.allocateDirect(requiredBytes)
                .order(ByteOrder.nativeOrder())
            managed.put(packed.duplicate())
            managed.flip()
            LinearImage(managed, width, height, colorSpace = result.colorSpace)
        } else {
            val src = packed.asFloatBuffer()
            val outWidth = ((width.toLong() + step - 1L) / step).toInt()
            val outHeight = ((height.toLong() + step - 1L) / step).toInt()
            val out = ByteBuffer.allocateDirect(checkedRgbFloatBytes(outWidth, outHeight))
                .order(ByteOrder.nativeOrder())
            val output = out.asFloatBuffer()
            var outputIndex = 0
            for (outputY in 0 until outHeight) {
                val sourceY = outputY * step
                val sourceRow = sourceY * width
                for (outputX in 0 until outWidth) {
                    val sourceIndex = (sourceRow + outputX * step) * 3
                    output.put(outputIndex, src.get(sourceIndex))
                    output.put(outputIndex + 1, src.get(sourceIndex + 1))
                    output.put(outputIndex + 2, src.get(sourceIndex + 2))
                    outputIndex += 3
                }
            }
            LinearImage(out, outWidth, outHeight, colorSpace = result.colorSpace)
        }
    } finally {
        // Linearize ownership consumption before dropping a local lease. For the
        // export path the transferred lease remains active until LinearImage.close().
        result.close()
        if (!leaseTransferred) lease.close()
    }
}

internal fun checkedRgbFloatBytes(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "invalid RAW dimensions ${width}x$height" }
    val bytes = width.toLong() * height.toLong() * 3L * Float.SIZE_BYTES
    require(bytes <= Int.MAX_VALUE) { "RAW image is too large: ${width}x$height" }
    return bytes.toInt()
}

/** Close the decoded result if descriptor cleanup itself fails after native success. */
private fun decodeRawFromFd(
    ctx: Context,
    uri: Uri,
    settings: RawDecoder.Settings,
    cancellation: RawDecodeCancellation?,
): LinearResult {
    val descriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Could not open RAW file")
    var decoded: LinearResult? = null
    try {
        // Only a source too large for the in-memory read (or of unknown size) is
        // snapshotted to the private cache; the common phone RAW skips that disk copy.
        decoded = if (RawInputLimits.needsInputSnapshot(descriptor.statSize)) {
            RawDecoder.decodeToLinear(
                descriptor.fd, settings, cancellation, scratchDirectory = ctx.cacheDir,
            )
        } else {
            RawDecoder.decodeToLinear(descriptor.fd, settings, cancellation)
        }
        descriptor.close()
        return decoded
    } catch (failure: Throwable) {
        decoded?.close()
        runCatching { descriptor.close() }
        throw failure
    }
}
