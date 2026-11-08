/*
 * Spektrafilm for Android — editor stock and Look strip preview sessions. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Split out of [EditorScreen] (#273): the caller passes the live reads as lambdas so each
 * host still sees the state at the moment a strip opens, as before.
 */
package com.spectrafilm.app

import android.content.Context
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The film-balance matrix the decode applies for [film] when [balance] is on, else null. */
internal fun filmBalanceFor(ctx: Context, balance: Boolean, film: String): FloatArray? =
    if (balance && FilmStockBalance.isMeaningful(ctx, film)) FilmStockBalance.matrix(ctx, film) else null

/**
 * The stock browser's preview session (StockPreviews.kt). Built when the sheet opens, from a
 * snapshot of the look with only the stock left free. It reads the proxy the settle render
 * already cached -- the same request, so it never moves the cache's generation -- and never
 * decodes: with no cached proxy the tiles simply stay dark.
 */
internal fun editorStockPreviewHost(
    vm: StockPreviewViewModel,
    forFilm: Boolean,
    ctx: Context,
    state: ParamsState,
    sourceCache: DecodedSourceCache,
    dispatchers: AppDispatchers,
    canOpen: () -> Boolean,
    cachedSourceDecodeRequest: suspend (DecodedSourceCache, Int) -> CachedSourceDecodeRequest,
) = StockPreviewHost(
    previews = vm.previews,
    request = vm::request,
    open = open@{ owner ->
        if (!canOpen()) return@open
        val fullEdge = state.previewMaxSize.coerceAtLeast(256)
        val snapshot = runCatching { cachedSourceDecodeRequest(sourceCache, fullEdge) }
            .getOrNull() ?: return@open
        // Each tile renders its stock as a tap would apply it, so the paper-dependent
        // settings (a silver paper's grade, the colour-pack lookup) and the film-dependent
        // ones (grain sub-layers, the silver film's move onto silver paper) follow the
        // candidate. They depend only on whether the film and paper are silver and on which
        // paper a film tap would pick, so the few distinct variants are built here, from
        // this snapshot of the state, and each tile copies its own stock id in.
        val currentFilm = state.filmProfile
        val currentPrint = state.printProfile
        val lastColourPrint = state.lastColourPrint
        fun variant(film: String?, print: String?) = state.toParams(
            previewMaxSizeOverride = STOCK_PREVIEW_EDGE,
            skipGrainHalation = true,
            filmOverride = film,
            printOverride = print,
        ).copy(filmProfile = "", printProfile = "")
        val silverFilm = SilverPrint.FILMS.first()
        // A film tap re-pairs the paper (SilverPrint.printAfterFilmChange); a paper tap
        // keeps the film. Every print a film tap can land on is in this set.
        val pairable = listOfNotNull(currentPrint, SilverPrint.MULTIGRADE, lastColourPrint)
            .distinct()
        fun pairedPrint(film: String) = SilverPrint.printAfterFilmChange(
            oldFilm = currentFilm, newFilm = film, print = currentPrint,
            lastColourPrint = lastColourPrint, available = pairable,
        )
        val variants: Map<Pair<Boolean, String>, SpektraParams> = if (forFilm) {
            buildMap {
                for (silver in listOf(false, true)) {
                    for (print in pairable) {
                        put(silver to print, variant(if (silver) silverFilm else "", print))
                    }
                }
            }
        } else {
            // Only silver-or-not matters for a paper, so one representative of each.
            mapOf(
                (true to SilverPrint.MULTIGRADE) to variant(null, SilverPrint.MULTIGRADE),
                (false to "") to variant(null, ""),
            )
        }
        val free = variants.entries.sortedBy { it.key.toString() }.map { it.value }
        val balance = state.balanceToFilmStock
        val cctf = state.savingCctfEncoding
        val saturation = state.saturation
        val vibrance = state.vibrance
        val gamut = state.gamutCompress
        val local = state.localAdjustments.toList()
        // Skin masks bind to this photo's skin field; its fingerprint is in the key so the
        // tiles redraw once skin is found.
        val skin = SkinFields.current()
        val cropIo = state.cropIo()
        vm.open(
            StockPreviewSession(
                key = listOf(
                    snapshot.ticket.request, free, currentFilm, currentPrint, lastColourPrint,
                    cctf, saturation, vibrance, gamut, local, skin?.fingerprint,
                ),
                source = {
                    sourceCache.acquire(snapshot.ticket)?.use { lease ->
                        withContext(dispatchers.default) {
                            downsampleLinearBox(lease.image, STOCK_PREVIEW_SOURCE_EDGE)
                        }
                    }
                },
                paramsFor = { id ->
                    if (forFilm) {
                        val print = pairedPrint(id)
                        variants.getValue(SilverPrint.isSilverFilm(id) to print)
                            .copy(filmProfile = id, printProfile = print)
                    } else {
                        val silver = SilverPrint.isSilverPaper(id)
                        variants.getValue(silver to if (silver) SilverPrint.MULTIGRADE else "")
                            .copy(filmProfile = currentFilm, printProfile = id)
                    }
                },
                toBitmap = { res ->
                    simResultToBitmapGraded(
                        res, cctf, saturation, vibrance, gamut,
                        local.withSkinField(cropIo, entry = skin),
                    )
                },
                // "Balance to film stock" adapts the decoded photo to the CURRENT film; a
                // tile for another film must show that film's adaptation, as a tap would.
                retarget = if (forFilm) {
                    { id -> filmBalanceRetarget(filmBalanceFor(ctx, balance, currentFilm), filmBalanceFor(ctx, balance, id)) }
                } else {
                    null
                },
            ),
            owner,
        )
    },
    close = { owner -> vm.close(owner) },
)

/**
 * The Look strip's preview session: the photo through each built-in look, rendered as a
 * tap would apply it -- the run's baseline (presetRunBaseline) with the look laid over,
 * so a tile never stacks on the look applied a moment ago. Same rules as the stock tiles:
 * the cached proxy only, grain and halation skipped, one tile at a time.
 */
internal fun editorPresetPreviewHost(
    vm: StockPreviewViewModel,
    ctx: Context,
    state: ParamsState,
    sourceCache: DecodedSourceCache,
    dispatchers: AppDispatchers,
    canOpen: () -> Boolean,
    cachedSourceDecodeRequest: suspend (DecodedSourceCache, Int) -> CachedSourceDecodeRequest,
    builtInGroups: () -> Map<String, List<BuiltInPreset>>,
    lastBuiltInOutput: () -> String?,
    presetBaselineJson: () -> String?,
) = StockPreviewHost(
    previews = vm.previews,
    request = vm::request,
    open = open@{ owner ->
        if (!canOpen()) return@open
        val fullEdge = state.previewMaxSize.coerceAtLeast(256)
        val snapshot = runCatching { cachedSourceDecodeRequest(sourceCache, fullEdge) }
            .getOrNull() ?: return@open
        val now = runCatching { Presets.toJsonString(state) }.getOrNull() ?: return@open
        val baseline = presetRunBaseline(now, lastBuiltInOutput(), presetBaselineJson()) ?: now
        val looks = builtInGroups().values.flatten().associateBy { it.id }
        val balanceNow = state.balanceToFilmStock
        val filmNow = state.filmProfile
        // Called on the main thread (StockPreviewViewModel), so a scratch ParamsState
        // is safe to build here.
        fun lookFor(id: String): ParamsState = ParamsState().also { look ->
            Presets.decode(JSONObject(baseline), look)
            looks[id]?.let { BuiltInPresets.apply(it, look) }
        }
        val cctf = state.savingCctfEncoding
        val saturation = state.saturation
        val vibrance = state.vibrance
        val gamut = state.gamutCompress
        val local = state.localAdjustments.toList()
        val skin = SkinFields.current()
        val cropIo = state.cropIo()
        vm.open(
            StockPreviewSession(
                key = listOf(
                    snapshot.ticket.request, baseline, looks.keys, cctf, saturation, vibrance, gamut, local,
                    skin?.fingerprint,
                ),
                source = {
                    sourceCache.acquire(snapshot.ticket)?.use { lease ->
                        withContext(dispatchers.default) {
                            downsampleLinearBox(lease.image, STOCK_PREVIEW_SOURCE_EDGE)
                        }
                    }
                },
                paramsFor = { id ->
                    lookFor(id).toParams(
                        previewMaxSizeOverride = STOCK_PREVIEW_EDGE,
                        skipGrainHalation = true,
                    )
                },
                // The shared source carries the current film's "Balance to film stock"
                // adaptation; a look on another film (or flipping the switch) gets that
                // look's own, as the decode after a tap would (Codex review on #435).
                retarget = { id ->
                    val look = lookFor(id)
                    filmBalanceRetarget(
                        filmBalanceFor(ctx, balanceNow, filmNow),
                        filmBalanceFor(ctx, look.balanceToFilmStock, look.filmProfile),
                    )
                },
                toBitmap = { res ->
                    simResultToBitmapGraded(
                        res, cctf, saturation, vibrance, gamut,
                        local.withSkinField(cropIo, entry = skin),
                    )
                },
            ),
            owner,
        )
    },
    close = { owner -> vm.close(owner) },
)
