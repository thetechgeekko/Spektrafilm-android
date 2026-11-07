/*
 * Spektrafilm for Android -- the Library screen: a grid of one folder's frames. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Idea from the rikiros fork (a RAW folder browser); see Library.kt.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
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
    // Re-read on every visit, so frames shot since the last one appear.
    LaunchedEffect(Unit) { vm.refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) vm.chooseFolder(tree.toString())
    }
    val choose = { picker.launch(state.treeUri?.let(Uri::parse)) }
    NavScaffold(
        title = state.folderName ?: stringResource(R.string.library_title),
        onBack = onBack,
        actions = {
            if (state.treeUri != null) {
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
        LibraryScreen(state, currentUri = currentUri, onChooseFolder = choose, onOpen = onOpen)
    }
}

/** The Library body: an empty state, or the grid of [LibraryUiState.entries]. */
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    currentUri: String?,
    onChooseFolder: () -> Unit,
    onOpen: (Uri) -> Unit,
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
        else -> LibraryGrid(state.entries, currentUri, onOpen)
    }
}

@Composable
private fun LibraryGrid(entries: List<LibraryEntry>, currentUri: String?, onOpen: (Uri) -> Unit) {
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
                onClick = { onOpen(Uri.parse(entry.documentUri)) },
            )
        }
    }
}

@Composable
private fun LibraryCell(entry: LibraryEntry, current: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val description = if (entry.edited) {
        stringResource(R.string.library_cell_edited_desc, entry.name)
    } else {
        entry.name
    }
    val thumb by produceState<Bitmap?>(LibraryThumbnails.cached(entry.documentUri), entry.documentUri) {
        if (value == null) value = withContext(thumbnailLane) { LibraryThumbnails.load(ctx, entry.documentUri) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (current) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                selected = current
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
