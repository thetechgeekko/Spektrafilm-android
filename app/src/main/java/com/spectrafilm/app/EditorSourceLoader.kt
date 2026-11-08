/*
 * Spektrafilm for Android — the editor's source decode requests and its cached preview / zoom
 * source loaders. GPLv3. Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273). Built per composition, as the local functions
 * were; source URI, kind and rotation are read through their state holders, so a request
 * built later in a launched job sees the current values, as before.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

internal class EditorSourceLoader(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val state: ParamsState,
    private val sourceRenderAllowed: Boolean,
    private val sourceUriState: State<Uri?>,
    private val sourceKindState: State<SourceKind>,
    private val rotationState: State<SourceRotation>,
    private val sourceCache: DecodedSourceCache,
    private val zoomSourceCache: DecodedSourceCache,
    private val previewDecodeFlight: SingleFlight<Unit>,
    private val zoomDecodeFlight: SingleFlight<Unit>,
    dngFallbackNoticeState: MutableState<Boolean>,
) {
    private val sourceUri: Uri? get() = sourceUriState.value
    private val sourceKind: SourceKind get() = sourceKindState.value
    private val rotation: SourceRotation get() = rotationState.value
    private var dngFallbackNotice by dngFallbackNoticeState

    // Decode the current source to a LinearImage capped to [maxEdge], applying the
    // EXIF orientation baseline THEN the user's manual rotate steps so imports appear
    // upright in both the preview and the export. The demo image has no EXIF.
    //
    // RAW/DNG: a JPEG-XL Samsung/Pixel Expert-RAW DNG, or a lossy-JPEG one outside the
    // qualified libjpeg-turbo route (corrupt or progressive tiles, Compression 6), makes
    // LibRaw throw RawDecodeException; we fall back to the platform ImageDecoder
    // (display-referred) and flag a one-shot snackbar. EXIF is then applied to the
    // fallback bitmap too.
    fun currentSourceDecodeRequest(): SourceDecodeRequest {
        check(sourceRenderAllowed) { "Source authorization required" }
        return SourceDecodeRequest(
            context = ctx.applicationContext,
            uri = sourceUri,
            kind = sourceKind,
            authorizationRequired = false,
            rawWhiteBalance = state.rawWhiteBalance,
            rawTemperature = state.rawTemperature,
            rawTint = state.rawTint,
            rotation = rotation,
            creativeTemp = state.creativeWbTemp,
            creativeTint = state.creativeWbTint,
            balanceToFilmStock = state.balanceToFilmStock,
            filmProfile = state.filmProfile,
            rawHotPixels = state.rawHotPixels,
            rawChromaNoise = state.rawChromaNoise,
            straightenDegrees = state.straightenDegrees,
        )
    }

    suspend fun cachedSourceDecodeRequest(
        cache: DecodedSourceCache,
        maxEdge: Int,
    ): CachedSourceDecodeRequest =
        withContext(dispatchers.mainImmediate) {
            val decode = currentSourceDecodeRequest()
            val filmBalance = if (
                decode.balanceToFilmStock &&
                FilmStockBalance.isMeaningful(decode.context, decode.filmProfile)
            ) {
                decode.filmProfile
            } else {
                ""
            }
            val cacheRequest = DecodedSourceCache.Request(
                uri = decode.uri?.toString(),
                kind = decode.kind.name,
                authorizationRequired = decode.authorizationRequired,
                whiteBalance = decode.rawWhiteBalance,
                temperature = decode.rawTemperature,
                tint = decode.rawTint,
                creativeTemp = decode.creativeTemp,
                creativeTint = decode.creativeTint,
                filmBalance = filmBalance,
                rotationDegrees = decode.rotation.degrees,
                maxEdge = maxEdge,
                hotPixels = decode.rawHotPixels,
                chromaNoise = decode.rawChromaNoise,
                straightenDegrees = decode.straightenDegrees,
            )
            // Begin publication authority in the same main-thread turn as the snapshot. An
            // older caller cannot resume later and reorder itself ahead of a newer UI state.
            CachedSourceDecodeRequest(decode, cache.beginRequest(cacheRequest))
        }

    private suspend fun loadSourceCached(
        cache: DecodedSourceCache,
        flight: SingleFlight<Unit>,
        maxEdge: Int,
        label: String,
    ): DecodedSourceCache.Lease {
        // Capture both the decoder inputs and cache identity in one main-thread snapshot.
        // The detached lifecycle flight below must never re-read mutable Compose state.
        val snapshot = cachedSourceDecodeRequest(cache, maxEdge)
        val ticket = snapshot.ticket
        cache.acquire(ticket)?.let { return it }
        flight.run(ticket, scope) {
            val existing = cache.acquire(ticket)
            if (existing != null) {
                existing.close()
            } else {
                val decoded = decodeSourceRequestDiskCached(
                    snapshot.decode,
                    ticket.request,
                    maxEdge,
                    dispatchers.io,
                )
                // publish() consumes the image even when this ticket became stale. An old
                // detached decode therefore cannot evict the newest source generation.
                val accepted = cache.publish(ticket, decoded.image)
                if (accepted && decoded.usedPlatformFallback) {
                    withContext(dispatchers.mainImmediate) {
                        if (cache.isCurrent(ticket)) dngFallbackNotice = true
                    }
                }
            }
        }
        return cache.acquire(ticket)
            ?: throw CancellationException("decoded $label source was superseded")
    }

    // PERF: proxy-source loader used ONLY by the interactive preview path. Consults the
    // single-entry DecodedSourceCache keyed by the decode-affecting inputs (URI + kind +
    // RAW WB/temp/tint + manual rotation + target edge). On a hit we re-feed the SAME
    // cached LinearImage to the engine — proven safe because spk_simulate/_preview take
    // `const spk_image* in` and only read it (see DecodedSourceCache for the full proof),
    // so no defensive copy is required. On a miss we run the captured decode request and
    // store the result, dropping any previous cached source (one entry only). When ONLY
    // look/film params change (the common case while editing sliders) the key is unchanged
    // and we skip the expensive LibRaw/bitmap decode + linearization + rotation entirely.
    // EXPORT calls decodeSourceRequest(EXPORT_MAX_EDGE_PX) directly — full-resolution, never
    // cached. The magnifier uses the separate zoom cache, so neither path uses this cache.
    suspend fun loadSourceCachedForPreview(maxEdge: Int): DecodedSourceCache.Lease {
        return loadSourceCached(
            cache = sourceCache,
            flight = previewDecodeFlight,
            maxEdge = maxEdge,
            label = "preview",
        )
    }

    // PERF/OOM: cached counterpart of loadSourceCachedForPreview for the MAX_EDGE_PX zoom/
    // magnifier proxy, backed by the dedicated zoomSourceCache. The Lightroom zoom (renderRoi)
    // and the 100% magnifier both crop this whole-image proxy; without the cache each gesture
    // settle re-decoded it (LibRaw + 36MB managed alloc) and the pileup OOM'd the ART heap.
    // Same read-only-reuse proof as the preview cache (the engine treats `in` as const), so the
    // single cached LinearImage is safely re-fed to every crop.
    suspend fun loadSourceCachedForZoom(maxEdge: Int): DecodedSourceCache.Lease {
        return loadSourceCached(
            cache = zoomSourceCache,
            flight = zoomDecodeFlight,
            maxEdge = maxEdge,
            label = "zoom",
        )
    }
}
