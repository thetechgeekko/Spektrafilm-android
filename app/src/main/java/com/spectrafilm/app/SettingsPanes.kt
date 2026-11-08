/*
 * Spektrafilm for Android -- Settings as an M3 list-detail layout on wide windows. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** The Settings rows that open a page of their own: the detail side of list-detail. */
internal enum class SettingsDetail { ABOUT, HOW_TO, DIAGNOSTICS }

/**
 * Whether Settings shows list and detail side by side: from the M3 expanded width class up,
 * the same breakpoint the editor uses for its side pane. Medium windows stay single-pane
 * because the detail pages (About, How to use) are long reading text that would be squeezed
 * under 480dp beside a 360dp list.
 */
internal fun settingsTwoPane(widthDp: Float): Boolean = listDetailTwoPane(widthDp)

/**
 * Settings, adapted to the window (#293, M3 canonical list-detail).
 *
 * Compact and medium: the single-pane Settings list, and its About / How to use /
 * Diagnostics rows push their own screens via [onOpenDetail].
 *
 * Expanded and wider: the material3-adaptive list-detail scaffold ([ListDetailPanes], #457),
 * the list in a fixed-width leading pane (360dp, 412dp from the large
 * class) and the selected page beside it, with the open row highlighted in the list. About
 * is shown until another row is picked, so the wide pane is never an empty placeholder.
 * Back leaves Settings as a whole, because both panes are one destination.
 */
@Composable
internal fun SettingsDestination(
    onBack: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
    detailContent: @Composable (SettingsDetail, open: (SettingsDetail) -> Unit) -> Unit =
        { detail, open -> SettingsDetailContent(detail, open) },
    settings: @Composable (selected: SettingsDetail?, open: (SettingsDetail) -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val title = stringResource(R.string.editor_action_settings)
        // Read here: inside the pane lambdas the BoxWithConstraints receiver is not implicitly reachable.
        val windowWidthDp = maxWidth.value
        // Held across a fold/unfold so the wide layout comes back on the page it showed.
        var detail by rememberSaveable { mutableStateOf(SettingsDetail.ABOUT) }
        if (!settingsTwoPane(windowWidthDp)) {
            NavScaffold(title, onBack = onBack) { settings(null, onOpenDetail) }
        } else ListDetailPanes(
            listWidthDp = sidePaneWidthDp(windowWidthDp),
            list = { NavScaffold(title, onBack = onBack) { settings(detail) { detail = it } } },
            detail = {
                DetailPane(stringResource(detail.titleRes)) {
                    detailContent(detail) { detail = it }
                }
            },
        )
    }
}

/** Title of each detail page; the same strings its single-pane screen uses. */
internal val SettingsDetail.titleRes: Int
    get() = when (this) {
        SettingsDetail.ABOUT -> R.string.editor_action_about
        SettingsDetail.HOW_TO -> R.string.screen_howto_title
        SettingsDetail.DIAGNOSTICS -> R.string.editor_nav_diagnostics
    }

/** The body of a detail page, shared by the single-pane routes and the detail pane. */
@Composable
internal fun SettingsDetailContent(detail: SettingsDetail, onOpen: (SettingsDetail) -> Unit) {
    when (detail) {
        SettingsDetail.ABOUT -> AboutScreen(onOpenHowTo = { onOpen(SettingsDetail.HOW_TO) })
        // Header supplied by the surrounding bar, so the guide draws none.
        SettingsDetail.HOW_TO -> HowToUseScreen(onBack = null)
        SettingsDetail.DIAGNOSTICS -> DiagnosticsScreen()
    }
}

/**
 * The detail pane's frame: a small app bar with the page title and no Back arrow (the pane
 * is not a destination of its own; Back belongs to the list pane's bar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPane(title: String, content: @Composable () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) { content() }
    }
}
