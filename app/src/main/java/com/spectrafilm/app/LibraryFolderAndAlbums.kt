/*
 * Spektrafilm for Android -- the Library's folder and albums tabs (split from LibraryScreen.kt for #273). GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryFolderAndAlbums(
    vm: LibraryViewModel,
    albumsVm: AlbumsViewModel,
    galleryVm: GalleryViewModel,
    albums: List<Album>,
    currentUri: String?,
    tab: LibraryTab,
    onTab: (LibraryTab) -> Unit,
    onOpenAlbum: (String) -> Unit,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    onOpen: (uri: Uri, frames: List<LibraryEntry>) -> Unit,
    importInto: (uris: List<String>, keepGrants: Boolean) -> Unit,
    onSlideshow: (List<LibraryEntry>) -> Unit,
    /** An album is open in the detail pane beside this one: Back and snackbars are its. */
    albumOpen: Boolean = false,
    /** Export these frames, each with its own edit; the callback runs once it has started. */
    onExport: (frames: List<LibraryEntry>, onStarted: () -> Unit) -> Unit = { _, _ -> },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val paste by vm.paste.collectAsStateWithLifecycle()
    val sidecarSave by vm.sidecarSave.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val aspects by vm.aspects.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val res = LocalResources.current
    val openFrame = { uri: Uri -> onOpen(uri, vm.visibleEntries()) }
    // Re-read on every visit, so frames shot since the last one appear.
    LaunchedEffect(Unit) { vm.refresh() }
    // A batch export that wrote files: list them now (Pictures/Spektrafilm is in the gallery,
    // and may be the chosen folder) instead of on the next visit.
    val exportWritten by remember {
        BatchExportTracker.state.map { p -> p?.takeIf { it.finished && it.done > 0 } }
    }.collectAsStateWithLifecycle(null)
    LaunchedEffect(exportWritten) {
        if (exportWritten == null) return@LaunchedEffect
        vm.refresh()
        galleryVm.refresh()
    }
    val outcome = (paste as? LibraryPasteState.Done)?.result
    LaunchedEffect(outcome) {
        if (outcome == null) return@LaunchedEffect
        val message = when {
            outcome.noSource -> res.getString(R.string.library_paste_no_source)
            outcome.skipped == 0 ->
                res.getQuantityString(R.plurals.library_paste_done, outcome.applied, outcome.applied)
            else -> res.getQuantityString(
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
    val selecting = selection.isNotEmpty() && tab == LibraryTab.FOLDER
    val galleryState by galleryVm.state.collectAsStateWithLifecycle()
    val gallerySelection by galleryVm.selection.collectAsStateWithLifecycle()
    val gallerySelecting = gallerySelection.isNotEmpty() && tab == LibraryTab.GALLERY
    val galleryShown = { applyLibraryQuery(galleryState.entries, query) }
    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = gallerySelecting) { galleryVm.clearSelection() }
    BackHandler(enabled = !selecting && !gallerySelecting && tab != LibraryTab.FOLDER && !albumOpen) {
        onTab(LibraryTab.FOLDER)
    }
    var addToAlbum by remember { mutableStateOf<List<AlbumItem>?>(null) }
    var newAlbumFor by remember { mutableStateOf<List<AlbumItem>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) vm.chooseFolder(tree.toString())
    }
    val choose = { picker.launch(state.treeUri?.let(Uri::parse)) }
    // The Library is where photos are opened from: one photo from anywhere on the phone,
    // beside the folder of frames. Routed through onOpen like a tapped frame.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openFrame(uri)
    }
    // RAW/DNG files are not in the photo picker; the document picker reaches them.
    val rawPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openFrame(uri)
    }
    // Import: many at once, into the Imported album. Both pickers grant reads the app may keep.
    val importPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        importInto(uris.map(Uri::toString), true)
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importInto(uris.map(Uri::toString), true)
    }
    val saved = (sidecarSave as? LibrarySidecarState.Done)?.result
    LaunchedEffect(saved) {
        if (saved == null) return@LaunchedEffect
        try {
            if (saved.needsWriteAccess) {
                // The folder was granted read-only (or before writing was offered): choosing
                // it again in the system picker grants write, and the save then finishes.
                val choice = snackbar.showSnackbar(
                    message = res.getString(R.string.library_sidecars_need_access),
                    actionLabel = res.getString(R.string.library_choose_folder),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (choice == SnackbarResult.ActionPerformed) choose()
            } else {
                val message = when {
                    saved.saved == 0 && saved.failed == 0 -> res.getString(R.string.library_sidecars_none)
                    saved.failed == 0 ->
                        res.getQuantityString(R.plurals.library_sidecars_saved, saved.saved, saved.saved)
                    else -> res.getQuantityString(
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
        } else if (gallerySelecting) {
            pluralStringResource(R.plurals.library_selected_count, gallerySelection.size, gallerySelection.size)
        } else if (tab == LibraryTab.FOLDER) {
            state.folderName ?: stringResource(R.string.library_title)
        } else {
            stringResource(R.string.library_title)
        },
        onBack = when {
            selecting -> vm::clearSelection
            gallerySelecting -> galleryVm::clearSelection
            else -> onBack
        },
        closeNavigation = selecting || gallerySelecting,
        // One host per state: beside an open album, the album's pane shows them.
        snackbarHost = { if (!albumOpen) SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!selecting && !gallerySelecting) {
                LibraryFabMenu(
                    onOpenPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onOpenRaw = { rawPicker.launch(arrayOf("*/*")) },
                    onImportPhotos = {
                        importPhotos.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onImportFiles = { importFiles.launch(arrayOf("*/*")) },
                    onChooseFolder = choose,
                )
            }
        },
        actions = {
            if (selecting) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(SpectraIcons.More, contentDescription = stringResource(R.string.editor_action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_save_sidecars)) },
                            onClick = { menuOpen = false; vm.saveEditsBesidePhotos() },
                        )
                    }
                }
            } else if (tab == LibraryTab.GALLERY && !gallerySelecting && galleryState.entries.isNotEmpty()) {
                IconButton(onClick = { onSlideshow(galleryShown()) }) {
                    Icon(SpectraIcons.Slideshow, contentDescription = stringResource(R.string.slideshow))
                }
                LibraryViewToggle(query, onChange = vm::setQuery)
                LibrarySortMenu(query, onChange = vm::setQuery)
            } else if (state.treeUri != null && tab == LibraryTab.FOLDER) {
                LibraryViewToggle(query, onChange = vm::setQuery)
                LibrarySortMenu(query, onChange = vm::setQuery)
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(SpectraIcons.More, contentDescription = stringResource(R.string.editor_action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.slideshow)) },
                            leadingIcon = { Icon(SpectraIcons.Slideshow, contentDescription = null) },
                            enabled = state.entries.isNotEmpty(),
                            onClick = { menuOpen = false; onSlideshow(vm.visibleEntries()) },
                        )
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
            Column(Modifier.fillMaxSize()) {
                if (!selecting && !gallerySelecting) LibraryTabs(tab, onTab)
                // A running export (and then its result), under the tabs so it never covers
                // them; beside an open album, that pane shows it.
                if (!albumOpen) {
                    LibraryExportProgress(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                    )
                }
                when (tab) {
                    LibraryTab.FOLDER -> LibraryScreen(
                        state,
                        currentUri = currentUri,
                        selection = selection,
                        onChooseFolder = choose,
                        onOpen = openFrame,
                        onToggleSelected = vm::toggleSelected,
                        query = query,
                        onQueryChange = vm::setQuery,
                        aspects = aspects,
                        onSelectRange = vm::selectRange,
                        onToggleDay = vm::toggleDay,
                    )
                    LibraryTab.GALLERY -> GalleryTab(
                        vm = galleryVm,
                        query = query,
                        onQueryChange = vm::setQuery,
                        currentUri = currentUri,
                        onOpen = onOpen,
                    )
                    LibraryTab.ALBUMS -> AlbumsTab(
                        albums = albums,
                        onOpenAlbum = onOpenAlbum,
                        onNewAlbum = { newAlbumFor = emptyList() },
                    )
                }
            }
            if (selecting) {
                LibrarySelectionBar(
                    pasteEnabled = !running,
                    onPaste = { vm.pasteLookFrom(currentUri) },
                    onShare = {
                        val picked = vm.visibleEntries().filter { it.documentUri in selection }
                        shareLibraryFrames(ctx, picked)
                    },
                    onExport = {
                        onExport(vm.visibleEntries().filter { it.documentUri in selection }, vm::clearSelection)
                    },
                    onSelectAll = vm::selectAll,
                    onAddToAlbum = {
                        addToAlbum = vm.visibleEntries()
                            .filter { it.documentUri in selection }
                            .map { AlbumItem.of(it, state.treeUri) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(bottom = 16.dp),
                )
            }
            if (gallerySelecting) {
                // Add to album, Share and Select all; pasting a look stays a folder action for now.
                LibraryActionPill(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(bottom = 16.dp),
                ) {
                    LibraryBarAction(SpectraIcons.AddToAlbum, R.string.album_add_to, true) {
                        addToAlbum = galleryShown()
                            .filter { it.documentUri in gallerySelection }
                            .map { AlbumItem.of(it, treeUri = null) }
                    }
                    LibraryBarAction(SpectraIcons.Export, R.string.tool_export_action, true) {
                        onExport(galleryShown().filter { it.documentUri in gallerySelection }, galleryVm::clearSelection)
                    }
                    LibraryBarAction(SpectraIcons.Share, R.string.library_share, true) {
                        shareLibraryFrames(ctx, galleryShown().filter { it.documentUri in gallerySelection })
                    }
                    LibraryBarAction(SpectraIcons.Check, R.string.library_select_all, true) {
                        galleryVm.selectAll(galleryShown().map { it.documentUri })
                    }
                }
            }
        }
    }
    val added = { name: String, count: Int? ->
        // Unsaved: the selection stays, so trying again needs no reselecting.
        if (count != null) {
            vm.clearSelection()
            galleryVm.clearSelection()
        }
        scope.launch {
            snackbar.showSnackbar(
                if (count == null) {
                    res.getString(R.string.album_save_failed)
                } else if (count == 0) {
                    res.getString(R.string.album_full)
                } else {
                    res.getQuantityString(R.plurals.album_added, count, count, name)
                },
            )
        }
        Unit
    }
    addToAlbum?.let { items ->
        AddToAlbumSheet(
            albums = albums,
            onDismiss = { addToAlbum = null },
            onPick = { album ->
                addToAlbum = null
                albumsVm.add(album.id, items) { count -> added(album.name, count) }
            },
            onNew = { addToAlbum = null; newAlbumFor = items },
        )
    }
    newAlbumFor?.let { items ->
        val word = stringResource(R.string.album_default_name)
        AlbumNameDialog(
            title = stringResource(R.string.album_new),
            initial = remember(albums) { AlbumOps.defaultName(albums, word) },
            confirm = stringResource(R.string.album_create),
            onDismiss = { newAlbumFor = null },
            onConfirm = { name ->
                newAlbumFor = null
                albumsVm.create(name, items) { id, count ->
                    when {
                        id == null -> scope.launch { snackbar.showSnackbar(res.getString(R.string.album_create_failed)) }
                        items.isEmpty() -> onOpenAlbum(id)
                        else -> added(AlbumOps.cleanName(name), count)
                    }
                }
            },
        )
    }
}
