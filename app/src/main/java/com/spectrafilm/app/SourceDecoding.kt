/*
 * Spektrafilm for Android — source decoding. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Activity-free source decoder (RAW via LibRaw with the bounded platform fallback, photos via
 * the platform codec, the synthetic demo) behind the on-disk proxy cache. Used by previews and
 * the process-owned export runtime. Moved out of MainActivity.kt (#273).
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.libraw.RawDecodeException
import com.spectrafilm.libraw.DecodeStatus
import com.spectrafilm.libraw.WhiteBalance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal enum class SourceKind { DEMO, PHOTO, RAW }

internal fun Throwable.isSourceAuthorizationFailure(): Boolean =
    generateSequence(this) { it.cause }
        .any { it is SecurityException || it is java.io.FileNotFoundException }

internal data class SourceDecodeRequest(
    val context: Context,
    val uri: Uri?,
    val kind: SourceKind,
    val authorizationRequired: Boolean,
    val rawWhiteBalance: WhiteBalance,
    val rawTemperature: Float,
    val rawTint: Float,
    val rotation: SourceRotation,
    val creativeTemp: Float,
    val creativeTint: Float,
    val balanceToFilmStock: Boolean,
    val filmProfile: String,
    /** Opt-in RAW hot-pixel correction on the Bayer mosaic (#259); ignored for non-RAW. */
    val rawHotPixels: Boolean = false,
    /** Opt-in RAW chroma noise reduction strength 0..100 (#259); ignored for non-RAW. */
    val rawChromaNoise: Float = 0f,
)

internal data class SourceDecodeResult(
    val image: LinearImage,
    val usedPlatformFallback: Boolean,
    /** The RAW OOM ladder returned a smaller decode than requested (#288: never persist it). */
    val memoryDegraded: Boolean = false,
)

internal data class CachedSourceDecodeRequest(
    val decode: SourceDecodeRequest,
    val ticket: DecodedSourceCache.Ticket,
)

/**
 * The decode half of the #179 cache identity. Spelled out rather than taken from the request's
 * own toString, which embeds a Context whose toString differs between two identical exports.
 * Shared by the export and the idle pre-render: if these two ever disagreed, the pre-render
 * would key its payload under a name the export never looks up, and would silently do nothing.
 */
internal fun decodeIdentityOf(request: SourceDecodeRequest): String = with(request) {
    "kind=$kind;rot=$rotation;rawWb=$rawWhiteBalance;" +
        "rawTemp=$rawTemperature;rawTint=$rawTint;" +
        "temp=$creativeTemp;tint=$creativeTint;" +
        "balance=$balanceToFilmStock;film=$filmProfile" +
        // Appended only when on, so every identity minted before #259 is unchanged.
        (if (rawHotPixels) ";hot=1" else "") +
        (if (rawChromaNoise > 0f) ";cnr=${rawChromaNoise.toRawBits()}" else "")
}

/**
 * Whether a refused RAW decode should be retried through the bounded platform decoder
 * instead of failing the open outright.
 *
 * The qualified LibRaw route deliberately refuses inputs it cannot carry EXACTLY: a float
 * DNG, a lossy or JPEG-XL codec it will not quantize behind your back, and metadata whose
 * precision it cannot model. (A fractional RATIONAL BlackLevel such as GCam's 6406/100 used
 * to be refused here too, and the platform decoder then handed back the DNG's ~1 MP embedded
 * preview instead of the raw (#271). The native route now rounds it to the nearest integer
 * code and decodes the real raw.) Refusing the ROUTE is
 * right. Refusing the PHOTO is not: decodeViaPlatform does not use the LibRaw route at all,
 * so the reason the route stood down does not apply to it. PRECISION_METADATA therefore lands
 * here alongside the codecs that already did.
 *
 * Before this, that status had no fallback and no message either: the exception unwound
 * through the single-flight into the editor's shared scope and the preview simply never
 * arrived. See SingleFlight for the other half of that failure.
 *
 * FILE_UNSUPPORTED used to be gated on the path ending in ".dng". That proxy never fired for
 * the URIs this app actually gets: a SAF document id is "image%3A220439", with no suffix at
 * all, so the arm was dead for every picked file and a DNG that LibRaw merely fails to
 * recognise lost its second route. The guard is deleted rather than replaced with another
 * proxy: we only reach here from the RAW branch, so the user has already said this is a RAW
 * file, and the cost of being wrong is one bounded platform decode on a path that has
 * already failed.
 */
internal fun rawFallbackSupported(status: DecodeStatus): Boolean =
    when (status) {
        DecodeStatus.DEFLATE_DNG,
        DecodeStatus.LOSSY_JPEG_DNG,
        DecodeStatus.JPEGXL_DNG,
        DecodeStatus.PRECISION_METADATA,
        DecodeStatus.FILE_UNSUPPORTED,
        -> true
        else -> false
    }

internal suspend fun decodeSourceRequest(
    request: SourceDecodeRequest,
    maxEdge: Int,
    io: CoroutineDispatcher,
): SourceDecodeResult = withOwnedContext(
    context = io,
    dispose = { decoded -> decoded.image.close() },
) {
    val decodeJob = currentCoroutineContext()[Job]
    val isCancelled = { decodeJob?.isActive == false }
    if (isCancelled()) throw CancellationException("source decode cancelled")
    check(!request.authorizationRequired || request.kind == SourceKind.DEMO) {
        "Source authorization required"
    }
    val uri = request.uri
    val exif = if (uri != null && request.kind != SourceKind.DEMO) {
        readExifOrientation(request.context, uri)
    } else {
        ExifOrientation.NONE
    }
    var applyExifBaseline = request.kind != SourceKind.RAW
    var usedPlatformFallback = false
    val rawMemoryDegraded = java.util.concurrent.atomic.AtomicBoolean(false)
    val image = when (request.kind) {
        SourceKind.RAW -> try {
            runCancellableRawDecode(onLateResult = { late -> late.close() }) { cancellation ->
                decodeRawToLinear(
                    request.context,
                    requireNotNull(uri),
                    request.rawWhiteBalance,
                    request.rawTemperature.toDouble(),
                    request.rawTint.toDouble(),
                    maxEdge,
                    cancellation,
                    rawMemoryDegraded,
                    hotPixels = request.rawHotPixels,
                    chromaNoise = request.rawChromaNoise,
                )
            }
        } catch (failure: RawDecodeException) {
            if (failure.status == DecodeStatus.CANCELLED) {
                throw CancellationException("RAW decode cancelled").also {
                    it.initCause(failure)
                }
            }
            val fallbackSupported = rawFallbackSupported(failure.status)
            if (!fallbackSupported) throw failure
            usedPlatformFallback = true
            applyExifBaseline = true
            decodeViaPlatform(
                request.context,
                requireNotNull(uri),
                maxEdge,
                isCancelled,
            )
        }
        SourceKind.PHOTO -> decodeToLinearProPhoto(
            request.context,
            requireNotNull(uri),
            maxEdge,
            isCancelled,
        )
        SourceKind.DEMO -> syntheticLinearImage(256)
    }
    var ownedImage: LinearImage? = image
    try {
        val based = if (applyExifBaseline) image.applyExif(exif, isCancelled) else image
        ownedImage = based
        val rotW = based.width
        val rotH = based.height
        val rotT0 = if (request.rotation == SourceRotation.NONE) 0L else System.currentTimeMillis()
        val rotated = based.rotated(request.rotation, isCancelled)
        ownedImage = rotated
        if (request.rotation != SourceRotation.NONE) {
            Diag.i(
                "rotate ms=${System.currentTimeMillis() - rotT0} angle=${request.rotation.degrees} " +
                    "${rotW}x$rotH workers=${defaultRotWorkers(rotW.toLong() * rotH)}",
            )
        }
        val pixelCount = try {
            Math.multiplyExact(rotated.width, rotated.height)
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException(
                "decoded image dimensions overflow: ${rotated.width}x${rotated.height}",
                failure,
            )
        }
        rotated.acquireDataLease().use { lease ->
            val data = lease.data
            if (!CreativeWhiteBalance.isNeutral(request.creativeTemp, request.creativeTint)) {
                CreativeWhiteBalance.applyInPlace(
                    data,
                    pixelCount,
                    CreativeWhiteBalance.matrix(request.creativeTemp, request.creativeTint),
                    isCancelled,
                )
            }
            if (request.balanceToFilmStock &&
                FilmStockBalance.isMeaningful(request.context, request.filmProfile)
            ) {
                CreativeWhiteBalance.applyInPlace(
                    data,
                    pixelCount,
                    FilmStockBalance.matrix(request.context, request.filmProfile),
                    isCancelled,
                )
            }
        }
        if (isCancelled()) throw CancellationException("source decode cancelled")
        Diag.i("decode kind=${request.kind.name} ${rotated.width}x${rotated.height} maxEdge=$maxEdge")
        val decoded = SourceDecodeResult(rotated, usedPlatformFallback, rawMemoryDegraded.get())
        ownedImage = null
        decoded
    } catch (failure: Throwable) {
        ownedImage?.close()
        throw failure
    }
}

/**
 * [decodeSourceRequest] behind the on-disk proxy cache (#288): a hit returns the stored decode
 * without touching LibRaw or the platform codec; a miss decodes and stores the result. Only the
 * preview and zoom proxies come through here; export decodes stay uncached.
 */
internal suspend fun decodeSourceRequestDiskCached(
    request: SourceDecodeRequest,
    cacheRequest: DecodedSourceCache.Request,
    maxEdge: Int,
    io: CoroutineDispatcher,
): SourceDecodeResult {
    val uri = request.uri
    if (uri == null || request.kind == SourceKind.DEMO) return decodeSourceRequest(request, maxEdge, io)
    val ctx = request.context.applicationContext
    // One owned context: whatever image the block produces is closed if the caller is
    // cancelled before taking it, hit or fresh decode alike.
    return withOwnedContext(
        context = io,
        dispose = { result: SourceDecodeResult -> result.image.close() },
    ) {
        val key = ProxyDiskCache.keyFor(ctx, uri, cacheRequest)
        val startNs = System.nanoTime()
        val hit = key?.let { ProxyDiskCache.read(ctx, it) }
        if (hit != null) {
            Diag.i(
                "proxy disk cache hit ${hit.image.width}x${hit.image.height} " +
                    "ms=${(System.nanoTime() - startNs) / 1_000_000L}",
            )
            SourceDecodeResult(hit.image, hit.usedPlatformFallback)
        } else {
            val decoded = decodeSourceRequest(request, maxEdge, io)
            // Blocking and non-throwing: no cancellation point between the decode and return.
            // A memory-degraded RAW decode is smaller than its key promises; storing it would
            // pin the low-resolution proxy for every later open, so it is used but not kept.
            if (key != null && !decoded.memoryDegraded) {
                ProxyDiskCache.write(ctx, key, decoded.image, decoded.usedPlatformFallback)
            }
            decoded
        }
    }
}

/** Top-level navigation destinations. */
