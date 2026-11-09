/*
 * Spektrafilm for Android — the open placeholder in the photo's film look. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.RenderKind
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The open placeholder ([OpenPlaceholder]) is the file's own embedded thumbnail, so it showed
 * the photo unedited and the film look popped in when the first real render landed. This runs
 * that small thumbnail once through the engine with the photo's current settings, so the
 * stand-in already wears the stock, paper and exposure, and only detail changes when the real
 * render replaces it.
 *
 * Display only, like the placeholder itself: never reaches an export or a cache. Grain and
 * halation are skipped (they are invisible at this size, and the real render brings them).
 */
internal object PlaceholderLook {
    /** Long edge the look is rendered at: enough for a soft stand-in, cheap to render. */
    const val RENDER_EDGE_PX = 640

    /** The edge to render a [width]x[height] thumbnail at: never upscaled past itself. */
    fun renderEdge(width: Int, height: Int, maxEdge: Int = RENDER_EDGE_PX): Int =
        min(maxEdge, max(width, height)).coerceAtLeast(1)

    /** A finished look render. The caller reports [renderId]'s outcome once it decides. */
    class Look(val bitmap: Bitmap, val renderId: Long)

    /**
     * Render [thumbnail] in the look of [state]'s current settings. Returns null on any
     * failure: the plain thumbnail then simply stays up, as before. The render is small and
     * runs to completion even if the caller is cancelled, so the caller always gets the
     * render id back and can report it (see [report]).
     */
    suspend fun render(
        ctx: Context,
        engine: SpektraEngine,
        thumbnail: Bitmap,
        state: ParamsState,
        default: CoroutineDispatcher,
        maxEdge: Int = RENDER_EDGE_PX,
    ): Look? {
        // Read the Compose-backed settings on the caller's (main) thread.
        val params = state.toParams(
            previewMaxSizeOverride = renderEdge(thumbnail.width, thumbnail.height, maxEdge),
            skipGrainHalation = true,
        )
        val cctf = state.savingCctfEncoding
        val saturation = state.saturation
        val vibrance = state.vibrance
        val gamutCompress = state.gamutCompress
        // The pre-engine colour steps the real decode applies (decodeSourceRequest), so the
        // stand-in carries the same creative white balance and film-stock balance.
        val creativeTemp = state.creativeWbTemp
        val creativeTint = state.creativeWbTint
        val balanceToFilmStock = state.balanceToFilmStock
        val filmProfile = state.filmProfile
        return withContext(default + NonCancellable) {
            runCatching {
                val src = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    thumbnail.config == Bitmap.Config.HARDWARE
                ) {
                    thumbnail.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    thumbnail
                }
                val startNs = System.nanoTime()
                bitmapToLinearProPhoto(src).use { linear ->
                    applyPreEngineColour(
                        ctx, linear, creativeTemp, creativeTint, balanceToFilmStock, filmProfile,
                    )
                    engine.simulatePreview(linear, params, RenderKind.PREVIEW).use { res ->
                        val out = runCatching {
                            simResultToBitmapGraded(res, cctf, saturation, vibrance, gamutCompress)
                        }.getOrElse {
                            SimResult.reportOutcome(res.renderId, AppRenderOutcome.FAILED)
                            throw it
                        }
                        Diag.i(
                            "open placeholder look ${out.width}x${out.height} " +
                                "ms=${(System.nanoTime() - startNs) / 1_000_000L}",
                        )
                        Look(out, res.renderId)
                    }
                }
            }.onFailure { Diag.w("open placeholder look failed: ${it.message}") }.getOrNull()
        }
    }

    /** Report a look render as shown ([published]) or dropped, or cancelled with its caller. */
    fun report(look: Look, published: Boolean, cancelled: Boolean) {
        SimResult.reportOutcome(
            look.renderId,
            when {
                published -> AppRenderOutcome.CONSUMED
                cancelled -> AppRenderOutcome.CANCELLED
                else -> AppRenderOutcome.SUPERSEDED
            },
        )
    }

    /** The creative WB and film-stock balance matrices, as [decodeSourceRequest] applies them. */
    private fun applyPreEngineColour(
        ctx: Context,
        linear: LinearImage,
        creativeTemp: Float,
        creativeTint: Float,
        balanceToFilmStock: Boolean,
        filmProfile: String,
    ) {
        val wb = !CreativeWhiteBalance.isNeutral(creativeTemp, creativeTint)
        val stock = balanceToFilmStock && FilmStockBalance.isMeaningful(ctx, filmProfile)
        if (!wb && !stock) return
        val pixelCount = linear.width * linear.height
        linear.acquireDataLease().use { lease ->
            if (wb) {
                CreativeWhiteBalance.applyInPlace(
                    lease.data,
                    pixelCount,
                    CreativeWhiteBalance.matrix(creativeTemp, creativeTint),
                )
            }
            if (stock) {
                CreativeWhiteBalance.applyInPlace(
                    lease.data,
                    pixelCount,
                    FilmStockBalance.matrix(ctx, filmProfile),
                )
            }
        }
    }
}
