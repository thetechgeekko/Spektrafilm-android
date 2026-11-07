/*
 * Spektrafilm for Android -- the Library screen: a grid of one folder's frames. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Idea from the rikiros fork (a RAW folder browser); see Library.kt.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withContext

/** Thumbnail decodes share a few IO lanes so a fast fling cannot open dozens of files at once. */
private val thumbnailLane = ProcessDispatchers.io.limitedParallelism(3)

/**
 * The Library destination: app bar, folder picker and grid. [onOpen] receives the document URI
 * of a tapped frame.
 */
@Composable
internal fun LibraryDestination(
    vm: LibraryViewModel,
    /** The source open in the editor, outlined and scrolled to when it is in this folder. */
    currentUri: String?,
    onBack: () -> Unit,
    onOpen: (Uri) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val paste by vm.paste.collectAsStateWithLifecycle()
    val sidecarSave by vm.sidecarSave.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // Re-read on every visit, so frames shot since the last one appear.
    LaunchedEffect(Unit) { vm.refresh() }
    val outcome = (paste as? LibraryPasteState.Done)?.result
    LaunchedEffect(outcome) {
        if (outcome == null) return@LaunchedEffect
        val message = when {
            outcome.noSource -> ctx.getString(R.string.library_paste_no_source)
            outcome.skipped == 0 ->
                ctx.resources.getQuantityString(R.plurals.library_paste_done, outcome.applied, outcome.applied)
            else -> ctx.resources.getQuantityString(
                R.plurals.library_paste_partial,
                outcome.skipped,
                outcome.applied,
                outcome.skipped,
            )
        }
        // Cleared only after the snackbar, since clearing changes this effect's key.
        try {
            snackbar.showSnackbar(message)
        } finally {
            vm.pasteShown()
        }
    }
    val selecting = selection.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) vm.chooseFolder(tree.toString())
    }
    val choose = { picker.launch(state.treeUri?.let(Uri::parse)) }
    val saved = (sidecarSave as? LibrarySidecarState.Done)?.result
    LaunchedEffect(saved) {
        if (saved == null) return@LaunchedEffect
        try {
            if (saved.needsWriteAccess) {
                // The folder was granted read-only (or before writing was offered): choosing
                // it again in the system picker grants write, and the save then finishes.
                val choice = snackbar.showSnackbar(
                    message = ctx.getString(R.string.library_sidecars_need_access),
                    actionLabel = ctx.getString(R.string.library_choose_folder),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (choice == SnackbarResult.ActionPerformed) choose()
            } else {
                val message = when {
                    saved.saved == 0 && saved.failed == 0 -> ctx.getString(R.string.library_sidecars_none)
                    saved.failed == 0 ->
                        ctx.resources.getQuantityString(R.plurals.library_sidecars_saved, saved.saved, saved.saved)
                    else -> ctx.resources.getQuantityString(
                        R.plurals.library_sidecars_partial,
                        saved.failed,
                        saved.saved,
                        saved.failed,
                    )
                }
                snackbar.showSnackbar(message)
            }
        } finally {
            vm.sidecarSaveShown()
        }
    }
    val running = paste is LibraryPasteState.Running
    NavScaffold(
        title = if (selecting) {
            pluralStringResource(R.plurals.library_selected_count, selection.size, selection.size)
        } else {
            state.folderName ?: stringResource(R.string.library_title)
        },
        onBack = if (selecting) vm::clearSelection else onBack,
        actions = {
            if (selecting) {
                // The look comes from the photo open in the editor; without one there is
                // nothing to paste, and the snackbar says so.
                TextButton(onClick = { vm.pasteLookFrom(currentUri) }, enabled = !running) {
                    Text(stringResource(R.string.library_paste_edit))
                }
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(SpectraIcons.More, contentDescription = stringResource(R.string.editor_action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_select_all)) },
                            onClick = { menuOpen = false; vm.selectAll() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_save_sidecars)) },
                            onClick = { menuOpen = false; vm.saveEditsBesidePhotos() },
                        )
                    }
                }
            } else if (state.treeUri != null) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(SpectraIcons.More, contentDescription = stringResource(R.string.editor_action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_refresh)) },
                            onClick = { menuOpen = false; vm.refresh() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_save_sidecars)) },
                            onClick = { menuOpen = false; vm.saveEditsBesidePhotos() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_change_folder)) },
                            onClick = { menuOpen = false; choose() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_forget_folder)) },
                            onClick = { menuOpen = false; vm.forgetFolder() },
                        )
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LibraryScreen(
                state,
                currentUri = currentUri,
                selection = selection,
                onChooseFolder = choose,
                onOpen = onOpen,
                onToggleSelected = vm::toggleSelected,
            )
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(WindowInsets.navigationBars.asPaddingValues()),
            )
        }
    }
}

/** The Library body: an empty state, or the grid of [LibraryUiState.entries]. */
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    currentUri: String?,
    onChooseFolder: () -> Unit,
    onOpen: (Uri) -> Unit,
    selection: Set<String> = emptySet(),
    onToggleSelected: (String) -> Unit = {},
) {
    val choose = onChooseFolder
    when {
        state.treeUri == null -> LibraryMessage(
            title = stringResource(R.string.library_empty_title),
            body = stringResource(R.string.library_empty_body),
            action = stringResource(R.string.library_choose_folder),
            onAction = choose,
        )
        state.unreadable -> LibraryMessage(
            title = stringResource(R.string.library_unreadable_title),
            body = stringResource(R.string.library_unreadable_body),
            action = stringResource(R.string.library_choose_folder),
            onAction = choose,
        )
        state.entries.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val loading = stringResource(R.string.library_loading)
            SpectraLoadingIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = loading },
            )
        }
        state.entries.isEmpty() -> LibraryMessage(
            title = stringResource(R.string.library_no_photos_title),
            body = stringResource(R.string.library_no_photos_body, state.folderName ?: ""),
            action = stringResource(R.string.library_change_folder),
            onAction = choose,
        )
        else -> LibraryGrid(state.entries, currentUri, selection, onOpen, onToggleSelected)
    }
}

@Composable
private fun LibraryGrid(
    entries: List<LibraryEntry>,
    currentUri: String?,
    selection: Set<String>,
    onOpen: (Uri) -> Unit,
    onToggleSelected: (String) -> Unit,
) {
    val selecting = selection.isNotEmpty()
    val gridState = rememberLazyGridState()
    // Find the user's place in the shoot: scroll to the frame open in the editor, once per
    // visit, so a Refresh does not yank the grid back.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(entries, currentUri) {
        if (placed) return@LaunchedEffect
        val index = entries.indexOfFirst { it.documentUri == currentUri }
        if (index < 0) return@LaunchedEffect
        gridState.scrollToItem(index)
        placed = true
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 4.dp,
            end = 4.dp,
            top = 4.dp,
            bottom = 4.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.documentUri }) { entry ->
            LibraryCell(
                entry,
                current = entry.documentUri == currentUri,
                selected = entry.documentUri in selection,
                // Long-press starts selecting (as in Photos and Files); while selecting, a tap
                // toggles instead of opening.
                onClick = {
                    if (selecting) onToggleSelected(entry.documentUri) else onOpen(Uri.parse(entry.documentUri))
                },
                onLongClick = { onToggleSelected(entry.documentUri) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCell(
    entry: LibraryEntry,
    current: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val description = if (entry.edited) {
        stringResource(R.string.library_cell_edited_desc, entry.name)
    } else {
        entry.name
    }
    val selectLabel = stringResource(R.string.library_select_action)
    val thumb by produceState<Bitmap?>(LibraryThumbnails.cached(entry.documentUri), entry.documentUri) {
        if (value == null) value = withContext(thumbnailLane) { LibraryThumbnails.load(ctx, entry.documentUri) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .then(
                when {
                    selected -> Modifier.border(3.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp))
                    current -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    else -> Modifier
                },
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = selectLabel)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                this.selected = selected || current
            },
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(
            SpectraIcons.SourceImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).size(32.dp),
        )
        if (entry.isRaw) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            ) {
                Text(
                    stringResource(R.string.library_badge_raw),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        if (selected) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            ) {
                Icon(
                    SpectraIcons.Confirm,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.padding(2.dp).size(18.dp),
                )
            }
        }
        if (entry.edited) {
            // Mirrors the RAW badge in the opposite corner: a saved edit reopens with the photo.
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Text(
                    stringResource(R.string.library_badge_edited),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryMessage(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            SpectraIcons.SourceImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction) { Text(action) }
    }
}
