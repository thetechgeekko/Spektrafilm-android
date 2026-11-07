/*
 * Spektrafilm for Android — the editor's film & print simulation panel. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope

/**
 * The Film & print (simulation) panel with its stock-change callbacks: the snackbar that
 * offers Slide mode for a reversal film or the new stock's defaults, and the hand-off to the
 * profile-curve browser. Moved verbatim out of EditorScreen (#273).
 */
@Composable
internal fun MainActivity.EditorSimulationPanel(
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    snackbarHost: SnackbarHostState,
    photoDateStamp: String?,
    filmGroups: List<DropdownGroup>,
    printGroups: List<DropdownGroup>,
    stockPreviewHost: (vm: StockPreviewViewModel, forFilm: Boolean) -> StockPreviewHost,
    checkpointEditorSession: () -> Boolean,
    onOpenFilmCurves: (id: String, name: String) -> Unit,
    onOpenPrintCurves: (id: String, name: String) -> Unit,
) {
    SimulationSection(
        s = state,
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
        printGroups = printGroups,
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
            val changed = id != state.filmProfile
            state.filmProfile = id
            if (changed) {
                val name = StockCatalog.displayName(ctx, id)
                // A reversal (slide) film is most naturally viewed as a
                // positive — nudge Slide mode if the print is still showing;
                // otherwise offer to drop the previous stock's tweaks.
                if (StockCatalog.isReversalFilm(ctx, id) && !state.scanFilm) {
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
                offerSnackbarSuggestion(scope, snackbarHost,
                    ctx.getString(R.string.editor_snack_switched_to, StockCatalog.displayName(ctx, id)),
                    ctx.getString(R.string.editor_snack_use_defaults)) { state.resetStockCharacter() }
            }
        },
    )
}
