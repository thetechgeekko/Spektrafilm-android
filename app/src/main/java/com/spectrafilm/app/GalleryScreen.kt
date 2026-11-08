/*
 * Spektrafilm for Android -- the Library's Gallery tab: the phone's photos. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Gallery tab body: asks for photo access when the app has none, says so when the user
 * allowed only some photos (Android 14), else the phone's photos in the Library's grid with the
 * Library's sort, filter, search and view.
 */
@Composable
internal fun GalleryTab(
    vm: GalleryViewModel,
    query: LibraryQuery,
    onQueryChange: (LibraryQuery) -> Unit,
    currentUri: String?,
    onOpen: (uri: Uri, frames: List<LibraryEntry>) -> Unit,
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val aspects by vm.aspects.collectAsStateWithLifecycle()
    // Access can change in system settings while the app is away: read it again on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    // After one refusal Android may stop showing the prompt; the button then opens settings.
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refused = result.values.none { it }
        vm.refresh()
    }
    val request = {
        if (refused) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
                )
            }.onFailure { Diag.w("gallery settings open failed: ${it.message}") }
        } else {
            ask.launch(PhoneGallery.permissions())
        }
        Unit
    }
    when {
        state.access == GalleryAccess.NONE -> LibraryMessage(
            title = stringResource(R.string.gallery_access_title),
            body = stringResource(R.string.gallery_access_body),
            action = stringResource(if (refused) R.string.gallery_open_settings else R.string.gallery_allow),
            onAction = request,
        )
        state.failed -> LibraryMessage(
            title = stringResource(R.string.gallery_failed_title),
            body = stringResource(R.string.gallery_failed_body),
            action = stringResource(R.string.library_refresh),
            onAction = vm::refresh,
        )
        state.entries.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val loading = stringResource(R.string.library_loading)
            SpectraLoadingIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = loading },
            )
        }
        else -> Column(Modifier.fillMaxSize()) {
            if (state.access == GalleryAccess.PARTIAL) {
                GalleryPartialBanner(onChange = { ask.launch(PhoneGallery.permissions()) })
            }
            if (state.entries.isEmpty()) {
                LibraryMessage(
                    title = stringResource(R.string.gallery_empty_title),
                    body = stringResource(R.string.gallery_empty_body),
                    action = stringResource(R.string.library_refresh),
                    onAction = vm::refresh,
                )
                return@Column
            }
            LibraryFindBar(query, onQueryChange)
            val shown = remember(state.entries, query) { applyLibraryQuery(state.entries, query) }
            if (shown.isEmpty()) {
                LibraryMessage(
                    title = stringResource(R.string.library_no_match_title),
                    body = stringResource(R.string.library_no_match_body),
                    action = stringResource(R.string.library_clear_filters),
                    onAction = { onQueryChange(query.copy(filter = null, text = "")) },
                )
            } else {
                LibraryGrid(
                    shown,
                    query,
                    aspects,
                    currentUri,
                    selection,
                    onOpen = { uri -> onOpen(uri, shown) },
                    onToggleSelected = vm::toggleSelected,
                    onSelectRange = { anchor, to, base -> vm.selectRange(shown, anchor, to, base) },
                    onToggleDay = vm::toggleDay,
                )
            }
        }
    }
}

/** Android 14's "Select photos": only the chosen photos are visible; Change picks again. */
@Composable
private fun GalleryPartialBanner(onChange: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.gallery_partial),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChange) { Text(stringResource(R.string.gallery_partial_change)) }
        }
    }
}
