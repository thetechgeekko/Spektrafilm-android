/*
 * Spektrafilm for Android — the open placeholder in the photo's film look. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.RenderKind
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineDispatcher
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
    fun renderEdge(width: Int, height: Int): Int =
        min(RENDER_EDGE_PX, max(width, height)).coerceAtLeast(1)

    /**
     * Render [thumbnail] in the look of [state]'s current settings. Returns null on any
     * failure: the plain thumbnail then simply stays up, as before.
     */
    suspend fun render(
        engine: SpektraEngine,
        thumbnail: Bitmap,
        state: ParamsState,
        default: CoroutineDispatcher,
    ): Bitmap? {
        // Read the Compose-backed settings on the caller's (main) thread.
        val params = state.toParams(
            previewMaxSizeOverride = renderEdge(thumbnail.width, thumbnail.height),
            skipGrainHalation = true,
        )
        val cctf = state.savingCctfEncoding
        val saturation = state.saturation
        val vibrance = state.vibrance
        val gamutCompress = state.gamutCompress
        return withContext(default) {
            runCatching {
                val src = if (thumbnail.config == Bitmap.Config.HARDWARE) {
                    thumbnail.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    thumbnail
                }
                val startNs = System.nanoTime()
                bitmapToLinearProPhoto(src).use { linear ->
                    engine.simulatePreview(linear, params, RenderKind.PREVIEW).use { res ->
                        val out = simResultToBitmapGraded(
                            res, cctf, saturation, vibrance, gamutCompress,
                        )
                        SimResult.reportOutcome(res.renderId, AppRenderOutcome.CONSUMED)
                        Diag.i(
                            "open placeholder look ${out.width}x${out.height} " +
                                "ms=${(System.nanoTime() - startNs) / 1_000_000L}",
                        )
                        out
                    }
                }
            }.onFailure { Diag.w("open placeholder look failed: ${it.message}") }.getOrNull()
        }
    }
}
