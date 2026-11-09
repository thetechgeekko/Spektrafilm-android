/*
 * Spektrafilm for Android -- Library albums: the Albums tab, an open album, and the sheets
 * that add frames to one. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ALBUM_CARD_MIN = 156.dp
private val ALBUM_CORNER = 16.dp

/** Folder | Gallery | Albums, under the app bar (Pen board, "Library"). */
@Composable
internal fun LibraryTabs(tab: LibraryTab, onTab: (LibraryTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
        Tab(
            selected = tab == LibraryTab.FOLDER,
            onClick = { onTab(LibraryTab.FOLDER) },
            text = { Text(stringResource(R.string.library_tab_folder)) },
        )
        Tab(
            selected = tab == LibraryTab.GALLERY,
            onClick = { onTab(LibraryTab.GALLERY) },
            text = { Text(stringResource(R.string.library_tab_gallery)) },
        )
        Tab(
            selected = tab == LibraryTab.ALBUMS,
            onClick = { onTab(LibraryTab.ALBUMS) },
            text = { Text(stringResource(R.string.library_tab_albums)) },
        )
    }
}

/** The Albums tab: a New album card, then one card per album with its cover and count. */
@Composable
internal fun AlbumsTab(albums: List<Album>, onOpenAlbum: (String) -> Unit, onNewAlbum: () -> Unit) {
    if (albums.isEmpty()) {
        LibraryMessage(
            title = stringResource(R.string.album_none_title),
            body = stringResource(R.string.album_none_body),
            action = stringResource(R.string.album_new),
            onAction = onNewAlbum,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(ALBUM_CARD_MIN),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 12.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "new", span = { GridItemSpan(1) }) { NewAlbumCard(onNewAlbum) }
        items(albums, key = { it.id }) { album -> AlbumCard(album, onClick = { onOpenAlbum(album.id) }) }
    }
}

@Composable
private fun NewAlbumCard(onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(ALBUM_CORNER))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(ALBUM_CORNER))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(ALBUM_CORNER)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SpectraIcons.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            stringResource(R.string.album_new),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    val count = pluralStringResource(R.plurals.album_count, album.items.size, album.items.size)
    val description = "${album.name}, $count"
    Column(
        Modifier
            .clip(RoundedCornerShape(ALBUM_CORNER))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
    ) {
        AlbumCover(album.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
        Text(
            count,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** An album's cover, or the album glyph while it has none or it is still loading. */
@Composable
private fun AlbumCover(item: AlbumItem?, modifier: Modifier) {
    val ctx = LocalContext.current
    val uri = item?.uri
    val thumb by produceState<Bitmap?>(uri?.let(LibraryThumbnails::cached), uri) {
        // produceState keeps its last value when the key changes, so a new cover starts over.
        value = uri?.let(LibraryThumbnails::cached)
        if (value == null && item != null) {
            val raw = item.toEntry().isRaw
            value = withContext(thumbnailLane) { LibraryThumbnails.load(ctx, item.uri, raw) }
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(ALBUM_CORNER))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(
            SpectraIcons.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** Which album the selected frames go to, or a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToAlbumSheet(
    albums: List<Album>,
    onDismiss: () -> Unit,
    onPick: (Album) -> Unit,
    onNew: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.album_add_to),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "new") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.album_new)) },
                    leadingContent = {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                SpectraIcons.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    },
                    modifier = Modifier.clickable(onClick = onNew),
                )
            }
            items(albums, key = { it.id }) { album ->
                ListItem(
                    headlineContent = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.album_count, album.items.size, album.items.size))
                    },
                    leadingContent = { AlbumCover(album.cover, Modifier.size(48.dp)) },
                    modifier = Modifier.clickable { onPick(album) },
                )
            }
        }
    }
}

/** Names a new album or renames one; the field starts selected so typing replaces it. */
@Composable
internal fun AlbumNameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val clean = AlbumOps.cleanName(field.text)
    val submit = { if (clean.isNotEmpty()) onConfirm(clean) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { if (it.text.length <= AlbumOps.MAX_NAME_CHARS) field = it },
                label = { Text(stringResource(R.string.album_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = clean.isNotEmpty()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) } },
    )
}

/**
 * One album, full screen: the Library's grid in the Library's view and sort, with its own
 * selection (Remove, Set as cover, Share, Select all) and Rename / Delete in the overflow.
 */
@Composable
internal fun AlbumDetail(
    album: Album,
    vm: AlbumsViewModel,
    query: LibraryQuery,
    currentUri: String?,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    onBack: () -> Unit,
    onGoToFolder: () -> Unit,
    onOpen: (uri: Uri, frames: List<LibraryEntry>) -> Unit,
    onSlideshow: (List<LibraryEntry>) -> Unit,
    /** Export these frames, each with its own edit; the callback runs once it has started. */
    onExport: (frames: List<LibraryEntry>, onStarted: () -> Unit) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val selection by vm.selection.collectAsStateWithLifecycle()
    val aspects by vm.aspects.collectAsStateWithLifecycle()
    val entries by produceState<List<LibraryEntry>?>(null, album.items) { value = vm.entriesOf(album) }
    // The album keeps the Library's view and order; filter and search belong to the folder.
    val albumQuery = query.copy(filter = null, text = "")
    val shown = remember(entries, albumQuery) { entries?.let { applyLibraryQuery(it, albumQuery) } }
    val selecting = selection.isNotEmpty()
    var renaming by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (selecting) vm.clearSelection() else onBack() }
    NavScaffold(
        title = if (selecting) {
            pluralStringResource(R.plurals.library_selected_count, selection.size, selection.size)
        } else {
            album.name
        },
        onBack = if (selecting) vm::clearSelection else onBack,
        closeNavigation = selecting,
        actions = {
            if (!selecting) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(SpectraIcons.More, contentDescription = stringResource(R.string.editor_action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.slideshow)) },
                            leadingIcon = { Icon(SpectraIcons.Slideshow, contentDescription = null) },
                            enabled = !shown.isNullOrEmpty(),
                            onClick = { menuOpen = false; shown?.let(onSlideshow) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.album_rename)) },
                            onClick = { menuOpen = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.album_delete)) },
                            onClick = {
                                menuOpen = false
                                onBack()
                                vm.delete(album.id) { deleted, index ->
                                    var undone = false
                                    scope.launch {
                                        val choice = snackbar.showSnackbar(
                                            message = res.getString(R.string.album_deleted),
                                            actionLabel = res.getString(R.string.album_undo),
                                            duration = SnackbarDuration.Long,
                                        )
                                        if (choice == SnackbarResult.ActionPerformed) {
                                            undone = true
                                            vm.undoDelete(deleted, index)
                                        }
                                    }.invokeOnCompletion {
                                        // Also when the Library was left first, or with the offer up.
                                        if (!undone) vm.undoExpired(deleted)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // A running export (and then its result) above the grid, pushing it down so the
                // day header and first row stay visible.
                LibraryExportProgress(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                )
                when {
                    shown == null -> Unit
                    shown.isEmpty() -> LibraryMessage(
                        title = stringResource(R.string.album_empty_title),
                        body = stringResource(R.string.album_empty_body),
                        action = stringResource(R.string.album_go_to_folder),
                        onAction = onGoToFolder,
                    )
                    else -> LibraryGrid(
                        shown,
                        albumQuery,
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
            if (selecting && shown != null) {
                LibraryActionPill(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(bottom = 16.dp),
                ) {
                    LibraryBarAction(SpectraIcons.RemoveFromAlbum, R.string.album_remove, true) {
                        val removed = vm.removeSelected(album.id)
                        scope.launch {
                            snackbar.showSnackbar(
                                res.getQuantityString(R.plurals.album_removed, removed, removed),
                            )
                        }
                    }
                    LibraryBarAction(SpectraIcons.SetCover, R.string.album_set_cover, selection.size == 1) {
                        vm.setCover(album.id, selection.first())
                        scope.launch { snackbar.showSnackbar(res.getString(R.string.album_cover_set)) }
                    }
                    LibraryBarAction(SpectraIcons.Export, R.string.tool_export_action, true) {
                        onExport(shown.filter { it.documentUri in selection }, vm::clearSelection)
                    }
                    LibraryBarAction(SpectraIcons.Share, R.string.library_share, true) {
                        shareLibraryFrames(ctx, shown.filter { it.documentUri in selection })
                    }
                    LibraryBarAction(SpectraIcons.Check, R.string.library_select_all, true) {
                        vm.selectAll(shown.map { it.documentUri })
                    }
                }
            }
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(WindowInsets.navigationBars.asPaddingValues()),
            )
        }
    }
    if (renaming) {
        AlbumNameDialog(
            title = stringResource(R.string.album_rename),
            initial = album.name,
            confirm = stringResource(R.string.album_rename_done),
            onDismiss = { renaming = false },
            onConfirm = { name -> renaming = false; vm.rename(album.id, name) },
        )
    }
}
