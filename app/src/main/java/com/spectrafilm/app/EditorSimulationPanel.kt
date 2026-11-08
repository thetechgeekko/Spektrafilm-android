/*
 * Spektrafilm for Android — the editor's film & print simulation panel. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope

/**
 * The Film & print (simulation) panel with its stock-change callbacks: the snackbar that
 * offers Slide mode for a reversal film or the new stock's defaults, and the hand-off to the
 * profile-curve browser. Moved verbatim out of EditorScreen (#273).
 */
@Composable
internal fun EditorSimulationPanel(
    filmPreviewVm: StockPreviewViewModel,
    printPreviewVm: StockPreviewViewModel,
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    snackbarHost: SnackbarHostState,
    photoDateStamp: String?,
    filmGroups: List<DropdownGroup>,
    printGroups: List<DropdownGroup>,
    stockPreviewHost: (vm: StockPreviewViewModel, forFilm: Boolean) -> StockPreviewHost,
    checkpointEditorSession: () -> Boolean,
    sourceKey: Any?,
    previewBusy: Boolean,
    onOpenFilmCurves: (id: String, name: String) -> Unit,
    onOpenPrintCurves: (id: String, name: String) -> Unit,
    part: SimPart = SimPart.FILM_STOCK,
) {
    val printIds = remember(printGroups) { printGroups.flatMap { g -> g.options.map { it.id } } }
    // A black-and-white film only prints on silver paper (on a colour paper it renders orange),
    // so its print list is the silver papers alone.
    val filmIsSilver = SilverPrint.isSilverFilm(state.filmProfile)
    val shownPrintGroups = remember(printGroups, filmIsSilver) {
        if (!filmIsSilver) printGroups else printGroups.mapNotNull { g ->
            g.copy(options = g.options.filter { SilverPrint.isSilverPaper(it.id) })
                .takeIf { it.options.isNotEmpty() }
        }
    }
    SimulationSection(
        s = state,
        part = part,
        photoDateStamp = photoDateStamp,
        filmPreviewHost = stockPreviewHost(filmPreviewVm, true),
        // In slide mode the print stage is skipped, so every paper would
        // render the same tile: show the text-only cards instead.
        printPreviewHost = if (state.scanFilm) {
            null
        } else {
            stockPreviewHost(printPreviewVm, false)
        },
        filmGroups = filmGroups,
        printGroups = shownPrintGroups,
        filmStripSourceKey = sourceKey,
        filmStripPaused = previewBusy,
        onOpenFilmCurves = {
            checkpointEditorSession()
            onOpenFilmCurves(
                state.filmProfile,
                StockCatalog.displayName(ctx, state.filmProfile),
            )
        },
        onOpenPrintCurves = {
            checkpointEditorSession()
            onOpenPrintCurves(
                state.printProfile,
                StockCatalog.displayName(ctx, state.printProfile),
            )
        },
        onFilmProfileChange = { id ->
            val oldFilm = state.filmProfile
            val changed = id != oldFilm
            state.filmProfile = id
            if (changed) {
                val oldPrint = state.printProfile
                val newPrint = SilverPrint.printAfterFilmChange(
                    oldFilm = oldFilm, newFilm = id, print = oldPrint,
                    lastColourPrint = state.lastColourPrint, available = printIds,
                )
                state.lastColourPrint = SilverPrint.colourPrintToRemember(
                    newFilm = id, oldPrint = oldPrint, newPrint = newPrint,
                    remembered = state.lastColourPrint,
                )
                if (newPrint != oldPrint) state.printProfile = newPrint
                val name = StockCatalog.displayName(ctx, id)
                // A reversal (slide) film is most naturally viewed as a
                // positive — nudge Slide mode if the print is still showing;
                // otherwise offer to drop the previous stock's tweaks.
                if (SilverPrint.isSilverFilm(id) && newPrint != oldPrint) {
                    offerSnackbarSuggestion(scope, snackbarHost,
                        ctx.getString(R.string.editor_snack_silver_paper, StockCatalog.displayName(ctx, newPrint)),
                        ctx.getString(R.string.editor_snack_use_defaults)) { state.resetStockCharacter() }
                } else if (StockCatalog.isReversalFilm(ctx, id) && !state.scanFilm) {
                    offerSnackbarSuggestion(scope, snackbarHost,
                        ctx.getString(R.string.editor_snack_slide_film, name),
                        ctx.getString(R.string.editor_snack_slide_mode)) { state.scanFilm = true }
                } else {
                    offerSnackbarSuggestion(scope, snackbarHost,
                        ctx.getString(R.string.editor_snack_switched_to, name),
                        ctx.getString(R.string.editor_snack_use_defaults)) { state.resetStockCharacter() }
                }
            }
        },
        onPrintProfileChange = { id ->
            val changed = id != state.printProfile
            state.printProfile = id
            if (changed) {
                // A paper the user picks is theirs: nothing to restore on the way back.
                state.lastColourPrint = null
                offerSnackbarSuggestion(scope, snackbarHost,
                    ctx.getString(R.string.editor_snack_switched_to, StockCatalog.displayName(ctx, id)),
                    ctx.getString(R.string.editor_snack_use_defaults)) { state.resetStockCharacter() }
            }
        },
    )
}
