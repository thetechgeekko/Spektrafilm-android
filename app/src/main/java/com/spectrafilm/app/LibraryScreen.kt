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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thumbnail decodes share a few IO lanes so a fast fling cannot open dozens of files at once. */
internal val thumbnailLane = ProcessDispatchers.io.limitedParallelism(3)

/**
 * The Library destination: app bar, the Folder and Albums tabs, and an open album. [onOpen]
 * receives the document URI of a tapped frame and the frames on screen around it, in order.
 */
@Composable
internal fun LibraryDestination(
    vm: LibraryViewModel,
    albumsVm: AlbumsViewModel,
    galleryVm: GalleryViewModel,
    /** Photos shared in from another app, to import into the Imported album; null when none. */
    sharedIn: List<String>?,
    onSharedInTaken: () -> Unit,
    /** The source open in the editor, outlined and scrolled to when it is in this folder. */
    currentUri: String?,
    onBack: () -> Unit,
    onOpen: (uri: Uri, frames: List<LibraryEntry>) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.FOLDER) }
    var openAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    val albums by albumsVm.albums.collectAsStateWithLifecycle()
    // Shared with the open album, so an Undo snackbar outlives leaving it.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val importedName = stringResource(R.string.album_imported)
    val importInto: (List<String>, Boolean) -> Unit = { uris, keepGrants ->
        albumsVm.import(uris, keepGrants, importedName) { added, failed ->
            scope.launch {
                val message = when {
                    added == null -> ctx.getString(R.string.album_save_failed)
                    added > 0 && failed > 0 -> ctx.resources.getQuantityString(
                        R.plurals.album_imported_partial, failed, added, importedName, failed,
                    )
                    added > 0 -> ctx.resources.getQuantityString(R.plurals.album_added, added, added, importedName)
                    failed > 0 -> ctx.resources.getQuantityString(R.plurals.album_import_failed, failed, failed)
                    else -> ctx.getString(R.string.album_import_already, importedName)
                }
                val choice = snackbar.showSnackbar(
                    message = message,
                    actionLabel = ctx.getString(R.string.album_view).takeIf { (added ?: 0) > 0 },
                    duration = SnackbarDuration.Long,
                )
                if (choice == SnackbarResult.ActionPerformed) {
                    albumsVm.clearSelection()
                    openAlbumId = AlbumOps.IMPORTED_ID
                }
            }
        }
    }
    // Taken once: cleared before the import runs, so a recomposition cannot start it twice.
    LaunchedEffect(sharedIn) {
        val uris = sharedIn ?: return@LaunchedEffect
        onSharedInTaken()
        importInto(uris, false)
    }
    // The frames a slideshow plays; null when none is up. Not saved: a slideshow is a moment.
    // It is drawn OVER the browsing UI, not instead of it, so the grid's scroll position and
    // loaded state are still there when it closes.
    var slideshow by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    val playSlideshow = { frames: List<LibraryEntry> -> if (frames.isNotEmpty()) slideshow = frames }
    val openAlbum = openAlbumId?.let { id -> albums.firstOrNull { it.id == id } }
    val query by vm.query.collectAsStateWithLifecycle()
    val albumDetail: @Composable (Album) -> Unit = { album ->
        // Keyed: beside the list another album can be picked while this pane stays up, and
        // its loaded frames and rename/selection state must not carry over to the new one.
        key(album.id) {
            AlbumDetail(
                album = album,
                vm = albumsVm,
                query = query,
                currentUri = currentUri,
                snackbar = snackbar,
                scope = scope,
                onBack = { albumsVm.clearSelection(); openAlbumId = null },
                onGoToFolder = { openAlbumId = null; tab = LibraryTab.FOLDER },
                onOpen = onOpen,
                onSlideshow = playSlideshow,
            )
        }
    }
    val browse: @Composable (albumOpen: Boolean) -> Unit = { albumOpen ->
        LibraryFolderAndAlbums(
            vm, albumsVm, galleryVm, albums, currentUri,
            // Beside an open album the list pane is the Albums tab; picking another tab closes it.
            tab = if (albumOpen) LibraryTab.ALBUMS else tab,
            onTab = { picked ->
                if (picked != LibraryTab.ALBUMS && openAlbumId != null) {
                    albumsVm.clearSelection()
                    openAlbumId = null
                }
                tab = picked
            },
            onOpenAlbum = { id -> albumsVm.clearSelection(); openAlbumId = id },
            snackbar, scope, onBack, onOpen, importInto,
            onSlideshow = playSlideshow,
            albumOpen = albumOpen,
        )
    }
    // Compact and medium: an open album replaces the Library. Expanded and wider: M3
    // list-detail (#457), the albums in the leading pane and the open album beside them.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        // Hidden from accessibility services while the slideshow covers it.
        Box(if (slideshow != null) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
            when {
                openAlbum == null -> browse(false)
                listDetailTwoPane(widthDp) -> ListDetailPanes(
                    listWidthDp = sidePaneWidthDp(widthDp),
                    list = { browse(true) },
                    detail = { albumDetail(openAlbum) },
                )
                else -> albumDetail(openAlbum)
            }
        }
        // Composed last, so its BackHandler wins over the album's and the Library's.
        slideshow?.let { frames -> LibrarySlideshow(frames, onClose = { slideshow = null }) }
    }
}

internal enum class LibraryTab { FOLDER, GALLERY, ALBUMS }

@Composable
private fun LibraryFolderAndAlbums(
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
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val paste by vm.paste.collectAsStateWithLifecycle()
    val sidecarSave by vm.sidecarSave.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val aspects by vm.aspects.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val openFrame = { uri: Uri -> onOpen(uri, vm.visibleEntries()) }
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
                    LibraryBarAction(SpectraIcons.Share, R.string.library_share, true) {
                        shareLibraryFrames(ctx, galleryShown().filter { it.documentUri in gallerySelection })
                    }
                    LibraryBarAction(SpectraIcons.Check, R.string.library_select_all, true) {
                        galleryVm.selectAll(galleryShown().map { it.documentUri })
                    }
                }
            }
            // One host per state: beside an open album, the album's pane shows them.
            if (!albumOpen) SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(WindowInsets.navigationBars.asPaddingValues()),
            )
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
                    ctx.getString(R.string.album_save_failed)
                } else if (count == 0) {
                    ctx.getString(R.string.album_full)
                } else {
                    ctx.resources.getQuantityString(R.plurals.album_added, count, count, name)
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
                        id == null -> scope.launch { snackbar.showSnackbar(ctx.getString(R.string.album_create_failed)) }
                        items.isEmpty() -> onOpenAlbum(id)
                        else -> added(AlbumOps.cleanName(name), count)
                    }
                }
            },
        )
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
    query: LibraryQuery = LibraryQuery(),
    onQueryChange: (LibraryQuery) -> Unit = {},
    aspects: Map<String, Float> = emptyMap(),
    onSelectRange: (String, String, Set<String>) -> Unit = { _, _, _ -> },
    onToggleDay: (List<String>) -> Unit = {},
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
        else -> Column(Modifier.fillMaxSize()) {
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
                    shown, query, aspects, currentUri, selection, onOpen, onToggleSelected,
                    onSelectRange = onSelectRange,
                    onToggleDay = onToggleDay,
                )
            }
        }
    }
}

/**
 * Search field and filter chips above the grid (Pen board, "Library"). One filter at a time,
 * like the mockup's chip row: "All" clears it. The chip row scrolls sideways on narrow phones
 * and at large font sizes rather than wrapping into a second row.
 */
@Composable
internal fun LibraryFindBar(query: LibraryQuery, onChange: (LibraryQuery) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        val searchLabel = stringResource(R.string.library_search_hint)
        TextField(
            value = query.text,
            onValueChange = { onChange(query.copy(text = it.take(MAX_SEARCH_CHARS))) },
            singleLine = true,
            placeholder = { Text(searchLabel) },
            leadingIcon = { Icon(SpectraIcons.Search, contentDescription = null) },
            trailingIcon = if (query.text.isEmpty()) null else {
                {
                    IconButton(onClick = { onChange(query.copy(text = "")) }) {
                        Icon(SpectraIcons.Cancel, contentDescription = stringResource(R.string.library_search_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = searchLabel },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LIBRARY_FILTER_CHIPS.forEach { (filter, label) ->
                val selected = query.filter == filter
                FilterChip(
                    selected = selected,
                    onClick = { onChange(query.copy(filter = if (selected && filter != null) null else filter)) },
                    label = { Text(stringResource(label)) },
                    leadingIcon = if (selected && filter != null) {
                        { Icon(SpectraIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private const val MAX_SEARCH_CHARS = 80
private val CELL_CORNER = 8.dp

private val LIBRARY_FILTER_CHIPS: List<Pair<LibraryFilter?, Int>> = listOf(
    null to R.string.library_filter_all,
    LibraryFilter.RAW to R.string.library_filter_raw,
    LibraryFilter.PHOTO to R.string.library_filter_photo,
    LibraryFilter.EDITED to R.string.library_filter_edited,
    LibraryFilter.NOT_EDITED to R.string.library_filter_not_edited,
)

/**
 * Switches between justified rows and the square grid. The icon shows the view a tap gives,
 * as an icon toggle does, and the label says so for TalkBack.
 */
@Composable
private fun LibraryViewToggle(query: LibraryQuery, onChange: (LibraryQuery) -> Unit) {
    val next = if (query.view == LibraryView.JUSTIFIED) LibraryView.SQUARE else LibraryView.JUSTIFIED
    IconButton(onClick = { onChange(query.copy(view = next)) }) {
        Icon(
            if (next == LibraryView.SQUARE) SpectraIcons.GridView else SpectraIcons.JustifiedView,
            contentDescription = stringResource(
                if (next == LibraryView.SQUARE) R.string.library_view_square else R.string.library_view_justified,
            ),
        )
    }
}

/** The app bar's Sort button: what to sort by, then which way. Ticks mark the current pick. */
@Composable
private fun LibrarySortMenu(query: LibraryQuery, onChange: (LibraryQuery) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(SpectraIcons.Sort, contentDescription = stringResource(R.string.library_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val tick: @Composable (Boolean) -> Unit = { on ->
                if (on) Icon(SpectraIcons.Check, contentDescription = null)
            }
            listOf(
                LibrarySort.DATE to R.string.library_sort_date,
                LibrarySort.NAME to R.string.library_sort_name,
                LibrarySort.SIZE to R.string.library_sort_size,
            ).forEach { (sort, label) ->
                val on = query.sort == sort
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { tick(on) },
                    modifier = Modifier.semantics { selected = on },
                    onClick = {
                        open = false
                        // Names read naturally A to Z; dates and sizes newest or largest first.
                        onChange(query.copy(sort = sort, descending = if (on) query.descending else sort != LibrarySort.NAME))
                    },
                )
            }
            HorizontalDivider()
            val orders = when (query.sort) {
                LibrarySort.DATE -> listOf(true to R.string.library_order_newest, false to R.string.library_order_oldest)
                LibrarySort.NAME -> listOf(false to R.string.library_order_a_z, true to R.string.library_order_z_a)
                LibrarySort.SIZE -> listOf(true to R.string.library_order_largest, false to R.string.library_order_smallest)
            }
            orders.forEach { (descending, label) ->
                val on = query.descending == descending
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { tick(on) },
                    modifier = Modifier.semantics { selected = on },
                    onClick = { open = false; onChange(query.copy(descending = descending)) },
                )
            }
        }
    }
}

/** One line of the Library's list: a date header, or a row of frames. */
private sealed interface LibraryLine {
    val key: String

    data class Header(val section: LibrarySection) : LibraryLine {
        override val key: String get() = "day:" + (section.epochDay ?: "none") + ":" + section.entries.first().documentUri
    }

    data class Frames(val row: LibraryRow) : LibraryLine {
        override val key: String get() = row.key
    }
}

private val GRID_GAP = 3.dp
private val GRID_SIDE = 4.dp
private val JUSTIFIED_ROW_HEIGHT = 128.dp
private val SQUARE_MIN_CELL = 104.dp

/**
 * The frames as one LazyColumn: sticky day headers when sorted by date, and under each either
 * justified rows (the photo-book look of the Pen board) or the square grid. Rows are packed
 * off the composition's hot path with remember, keyed on what changes them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryGrid(
    entries: List<LibraryEntry>,
    query: LibraryQuery,
    aspects: Map<String, Float>,
    currentUri: String?,
    selection: Set<String>,
    onOpen: (Uri) -> Unit,
    onToggleSelected: (String) -> Unit,
    onSelectRange: (anchor: String, to: String, base: Set<String>) -> Unit = { _, _, _ -> },
    onToggleDay: (List<String>) -> Unit = {},
) {
    val selecting = selection.isNotEmpty()
    val listState = rememberLazyListState()
    val currentSelection by rememberUpdatedState(selection)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth - GRID_SIDE * 2).toPx() }
        val gapPx = with(density) { GRID_GAP.toPx() }
        val rowPx = with(density) { JUSTIFIED_ROW_HEIGHT.toPx() }
        val minCellPx = with(density) { SQUARE_MIN_CELL.toPx() }
        val zone = remember { java.util.TimeZone.getDefault() }
        val lines = remember(entries, query.sort, query.view, aspects, widthPx) {
            val out = ArrayList<LibraryLine>()
            for (section in librarySections(entries, query.sort, zone)) {
                if (query.sort == LibrarySort.DATE) out += LibraryLine.Header(section)
                val rows = when (query.view) {
                    LibraryView.JUSTIFIED -> justifyRows(
                        section.entries,
                        { aspects[it.documentUri] ?: DEFAULT_FRAME_ASPECT },
                        widthPx,
                        rowPx,
                        gapPx,
                    )
                    LibraryView.SQUARE -> squareRows(
                        section.entries,
                        squareColumns(widthPx, minCellPx, gapPx),
                        widthPx,
                        gapPx,
                    )
                }
                rows.mapTo(out) { LibraryLine.Frames(it) }
            }
            out
        }
        // Find the user's place in the shoot: scroll to the frame open in the editor, once per
        // visit, so a Refresh or a reflow does not yank the list back.
        var placed by remember { mutableStateOf(false) }
        LaunchedEffect(lines, currentUri) {
            if (placed || currentUri == null) return@LaunchedEffect
            val index = lines.indexOfFirst { line ->
                line is LibraryLine.Frames && line.row.entries.any { it.documentUri == currentUri }
            }
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index)
            placed = true
        }
        val sidePx = with(density) { GRID_SIDE.toPx() }
        val edgePx = with(density) { 64.dp.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val currentLines by rememberUpdatedState(lines)
        // The frame under a point of the list, from the laid-out rows: no per-cell modifiers.
        // Rows lay out from the right in RTL, so the hit test reads x from that edge there.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val listWidthPx = with(density) { maxWidth.toPx() }
        val frameAt: (Offset) -> String? = { raw ->
            val pos = if (rtl) Offset(listWidthPx - raw.x, raw.y) else raw
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { pos.y >= it.offset && pos.y < it.offset + it.size }
                ?.let { item -> currentLines.firstOrNull { it.key == item.key } as? LibraryLine.Frames }
                ?.row
                ?.let { row ->
                    var x = sidePx
                    row.entries.indices.firstOrNull { i ->
                        val hit = pos.x >= x && pos.x < x + row.widths[i] + gapPx
                        x += row.widths[i] + gapPx
                        hit
                    }?.let { row.entries[it].documentUri }
                }
        }
        // Drag-select (Photos style): hold a frame, then drag across others. The hold itself is
        // the cell's own long-press (which selects the frame, and stays the TalkBack action);
        // this layer only watches, in the Initial pass so it sees the touch before the cell, and
        // takes over the gesture once the hold has lasted long enough. A move before that is a
        // scroll and is left alone. Near the top or bottom edge the list scrolls along.
        var dragPointer by remember { mutableStateOf<Offset?>(null) }
        var dragAnchor by remember { mutableStateOf<String?>(null) }
        var dragBase by remember { mutableStateOf<Set<String>?>(null) }
        var dragLast by remember { mutableStateOf<String?>(null) }
        val dragTo: (Offset) -> Unit = { pos ->
            val anchor = dragAnchor
            val target = frameAt(pos)
            if (anchor != null && target != null && target != dragLast) {
                if (dragBase == null) dragBase = currentSelection
                dragLast = target
                onSelectRange(anchor, target, dragBase ?: emptySet())
            }
        }
        LaunchedEffect(dragPointer != null) {
            while (dragPointer != null) {
                val y = dragPointer?.y ?: break
                val step = when {
                    y < edgePx -> -(edgePx - y) / 3f
                    y > heightPx - edgePx -> (y - (heightPx - edgePx)) / 3f
                    else -> 0f
                }
                if (step != 0f) {
                    listState.scrollBy(step)
                    dragPointer?.let(dragTo)
                }
                kotlinx.coroutines.delay(16)
            }
        }
        val dragSelect = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val start = frameAt(down.position) ?: return@awaitEachGesture
                // Times out (null) when the finger stayed down and still for the long-press
                // timeout; anything else (a lift, a move past slop) ends the wait early.
                val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var ended = false
                    while (!ended) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                        ended = change == null || !change.pressed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    }
                    true
                }
                if (endedEarly != null) return@awaitEachGesture
                dragAnchor = start
                dragLast = start
                dragBase = null
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        dragPointer = change.position
                        val before = dragLast
                        dragTo(change.position)
                        if (dragLast != before) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                } finally {
                    dragPointer = null
                    dragAnchor = null
                    dragBase = null
                    dragLast = null
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(dragSelect),
            contentPadding = PaddingValues(
                start = GRID_SIDE,
                end = GRID_SIDE,
                // Room under the last row for the Open photo button.
                bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        ) {
            lines.forEach { line ->
                when (line) {
                    is LibraryLine.Header -> stickyHeader(key = line.key, contentType = "header") {
                        LibraryDayHeader(
                            line.section,
                            selecting = selecting,
                            daySelected = line.section.entries.all { it.documentUri in selection },
                            onToggleDay = { onToggleDay(line.section.entries.map { it.documentUri }) },
                        )
                    }
                    is LibraryLine.Frames -> item(key = line.key, contentType = "row") {
                        Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                            line.row.entries.forEachIndexed { i, entry ->
                                val w = with(density) { line.row.widths[i].toDp() }
                                val h = with(density) { line.row.height.toDp() }
                                // Keyed by file: a row named by its first frame can change its later
                                // frames on a filter or reflow, and a cell's thumbnail state must
                                // never carry over to a different file.
                                key(entry.documentUri) {
                                LibraryCell(
                                    entry,
                                    current = entry.documentUri == currentUri,
                                    selected = entry.documentUri in selection,
                                    selecting = selecting,
                                    modifier = Modifier.size(w, h),
                                    // Long-press starts selecting (as in Photos and Files); while
                                    // selecting, a tap toggles instead of opening.
                                    onClick = {
                                        if (selecting) onToggleSelected(entry.documentUri) else onOpen(Uri.parse(entry.documentUri))
                                    },
                                    onLongClick = { onToggleSelected(entry.documentUri) },
                                )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A sticky day header: Today, Yesterday, or the date (with the year only when it is not this
 * year), and how many frames the day holds. Opaque, so rows scroll under it cleanly.
 */
@Composable
private fun LibraryDayHeader(
    section: LibrarySection,
    selecting: Boolean = false,
    daySelected: Boolean = false,
    onToggleDay: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val zone = remember { java.util.TimeZone.getDefault() }
    val today = remember { localEpochDay(System.currentTimeMillis(), zone) }
    val label = when {
        section.undated || section.epochDay == null -> stringResource(R.string.library_day_undated)
        section.epochDay == today -> stringResource(R.string.library_day_today)
        section.epochDay == today - 1 -> stringResource(R.string.library_day_yesterday)
        else -> {
            val ms = section.entries.first().modifiedMs
            val thisYear = java.util.Calendar.getInstance(zone).get(java.util.Calendar.YEAR)
            val year = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
            var flags = android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
                android.text.format.DateUtils.FORMAT_ABBREV_ALL
            flags = flags or if (year == thisYear) {
                android.text.format.DateUtils.FORMAT_NO_YEAR
            } else {
                android.text.format.DateUtils.FORMAT_SHOW_YEAR
            }
            android.text.format.DateUtils.formatDateTime(ctx, ms, flags)
        }
    }
    val count = section.entries.size
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (selecting) {
            TextButton(onClick = onToggleDay) {
                Text(stringResource(if (daySelected) R.string.library_clear_day else R.string.library_select_day))
            }
        } else {
            Text(
                pluralStringResource(R.plurals.library_day_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier.aspectRatio(1f),
) {
    val ctx = LocalContext.current
    val description = if (entry.edited) {
        stringResource(R.string.library_cell_edited_desc, entry.name)
    } else {
        entry.name
    }
    val selectLabel = stringResource(R.string.library_select_action)
    val thumb by produceState<Bitmap?>(LibraryThumbnails.cached(entry.documentUri), entry.documentUri) {
        if (value == null) value = withContext(thumbnailLane) { LibraryThumbnails.load(ctx, entry.documentUri, entry.isRaw) }
    }
    // The kit's photo grid: rounded tiles; a selected tile shrinks inside its cell and takes
    // a filled check, every tile shows an empty check circle while selecting.
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "cellInset")
    val corner by animateDpAsState(if (selected) 16.dp else CELL_CORNER, label = "cellCorner")
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .clip(RoundedCornerShape(CELL_CORNER))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = selectLabel)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                this.selected = selected || current
            },
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(inset)
            .clip(shape)
            .then(
                if (current && !selecting) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
        if (selecting) {
            // Bottom-start, clear of the RAW and Edited badges along the top.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier
                                .background(SpektraBrand.scrim.copy(alpha = 0.3f))
                                .border(2.dp, SpektraBrand.onCanvas, CircleShape)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        SpectraIcons.Confirm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The floating toolbar of selection mode (Pen board, "Library"): what can be done with the
 * selected frames. A pill over the grid, as the editor's stage bar, so the actions sit under
 * the thumb instead of in the app bar.
 */
@Composable
private fun LibrarySelectionBar(
    pasteEnabled: Boolean,
    onPaste: () -> Unit,
    onShare: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            // The look comes from the photo open in the editor; without one there is nothing
            // to paste, and the snackbar says so.
            LibraryBarAction(SpectraIcons.Presets, R.string.library_paste_edit, pasteEnabled, onPaste)
            LibraryBarAction(SpectraIcons.AddToAlbum, R.string.album_add_to, true, onAddToAlbum)
            LibraryBarAction(SpectraIcons.Share, R.string.library_share, true, onShare)
            LibraryBarAction(SpectraIcons.Check, R.string.library_select_all, true, onSelectAll)
        }
    }
}

/** The floating pill shape every selection toolbar in the Library shares. */
@Composable
internal fun LibraryActionPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
internal fun LibraryBarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * Hands the selected originals to the share sheet. The Library holds a read grant on the
 * folder, so the receiving app gets a temporary read grant on each file through the clip.
 */
internal fun shareLibraryFrames(ctx: android.content.Context, frames: List<LibraryEntry>) {
    if (frames.isEmpty()) return
    val uris = ArrayList(frames.map { Uri.parse(it.documentUri) })
    val intent = if (uris.size == 1) {
        android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
    } else {
        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
    }
    intent.type = shareMimeType(frames.map { it.mimeType })
    intent.clipData = android.content.ClipData.newRawUri(null, uris[0]).also { clip ->
        uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
    }
    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        ctx.startActivity(android.content.Intent.createChooser(intent, ctx.getString(R.string.library_share)))
    }.onFailure { Diag.w("library share failed: ${it.message}") }
}

@Composable
internal fun LibraryMessage(title: String, body: String, action: String, onAction: () -> Unit) {
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

/**
 * The Library's M3 Expressive FAB menu: the FAB morphs into a close button and lists the ways
 * to open a photo (Pen board, "Library · FAB menu"). Back closes the open menu first.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryFabMenu(
    onOpenPhoto: () -> Unit,
    onOpenRaw: () -> Unit,
    onImportPhotos: () -> Unit,
    onImportFiles: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    val openLabel = stringResource(R.string.editor_open_photo)
    val closeLabel = stringResource(R.string.screen_close)
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = Modifier.navigationBarsPadding(),
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                modifier = Modifier.semantics {
                    contentDescription = if (expanded) closeLabel else openLabel
                },
            ) {
                Icon(
                    if (checkedProgress > 0.5f) SpectraIcons.Cancel else SpectraIcons.OpenPhoto,
                    contentDescription = null,
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onOpenPhoto() },
            text = { Text(openLabel) },
            icon = { Icon(SpectraIcons.OpenPhoto, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onOpenRaw() },
            text = { Text(stringResource(R.string.editor_source_open_raw)) },
            icon = { Icon(SpectraIcons.ImportRaw, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onImportPhotos() },
            text = { Text(stringResource(R.string.library_import_photos)) },
            icon = { Icon(SpectraIcons.AddToAlbum, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onImportFiles() },
            text = { Text(stringResource(R.string.library_import_files)) },
            icon = { Icon(SpectraIcons.ImportFiles, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onChooseFolder() },
            text = { Text(stringResource(R.string.library_choose_folder)) },
            icon = { Icon(SpectraIcons.Folder, contentDescription = null) },
        )
    }
}
