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
    val res = LocalResources.current
    // Export of selected frames, each with its own edit (LibraryExport.kt): one flow for the
    // folder, gallery and album bars, and the finished run's snackbar while the Library is up.
    val export = rememberLibraryExportFlow(snackbar, scope)
    LibraryExportSheet(export)
    val importedName = stringResource(R.string.album_imported)
    val importInto: (List<String>, Boolean) -> Unit = { uris, keepGrants ->
        albumsVm.import(uris, keepGrants, importedName) { added, failed ->
            scope.launch {
                val message = when {
                    added == null -> res.getString(R.string.album_save_failed)
                    added > 0 && failed > 0 -> res.getQuantityString(
                        R.plurals.album_imported_partial, failed, added, importedName, failed,
                    )
                    added > 0 -> res.getQuantityString(R.plurals.album_added, added, added, importedName)
                    failed > 0 -> res.getQuantityString(R.plurals.album_import_failed, failed, failed)
                    else -> res.getString(R.string.album_import_already, importedName)
                }
                val choice = snackbar.showSnackbar(
                    message = message,
                    actionLabel = res.getString(R.string.album_view).takeIf { (added ?: 0) > 0 },
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
                onExport = export::request,
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
            onExport = export::request,
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
internal val CELL_CORNER = 8.dp

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
internal fun LibraryViewToggle(query: LibraryQuery, onChange: (LibraryQuery) -> Unit) {
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
internal fun LibrarySortMenu(query: LibraryQuery, onChange: (LibraryQuery) -> Unit) {
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

/**
 * The floating toolbar of selection mode (Pen board, "Library"): what can be done with the
 * selected frames. A pill over the grid, as the editor's stage bar, so the actions sit under
 * the thumb instead of in the app bar.
 */
@Composable
internal fun LibrarySelectionBar(
    pasteEnabled: Boolean,
    onPaste: () -> Unit,
    onShare: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToAlbum: () -> Unit,
    onExport: () -> Unit,
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
            // Each selected frame with its own edit, unlike Share, which sends the originals.
            LibraryBarAction(SpectraIcons.Export, R.string.tool_export_action, true, onExport)
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
internal fun LibraryFabMenu(
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
