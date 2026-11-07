/*
 * Spektrafilm for Android — app root and top-level navigation. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Onboarding, startup restore and the Navigation 3 host around the editor, moved out of
 * MainActivity.kt (#273).
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal enum class Screen { EDITOR, SETTINGS, ABOUT, HOWTO, CURVES_FILM, CURVES_PRINT, DIAGNOSTICS, LIBRARY }

private data class EditorStartupRead(
    val session: EditorSessionReadResult,
    val source: SourceRestoreResult,
)

/** Waits behind any process-owned grant mutation and returns only its newest durable identity. */
private suspend fun restoreLatestSourceAccess(context: Context): SourceRestoreResult {
    val runtime = context.appContainer.sources
    while (currentCoroutineContext().isActive) {
        val generation = runtime.mutations.snapshot()
        val restored = try {
            runtime.submit(generation) {
                runtime.coordinator.restore().also { source ->
                    if (source is SourceRestoreResult.Invalid) {
                        runCatching { runtime.coordinator.clear() }
                    }
                }
            }.await()?.takeIf { runtime.mutations.isCurrent(generation) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SourceRestoreResult.Invalid("source access unavailable")
        }
        if (restored != null) return restored
    }
    throw CancellationException("source restoration cancelled")
}

private val EditorSavedFallbackSaver = listSaver<EditorSavedFallback, Any>(
    save = { fallback ->
        val source = fallback.source
        if (source == null) {
            emptyList()
        } else {
            listOf(
                source.uri.orEmpty(),
                source.kind.name,
                source.displayName,
                source.authorizationRequired,
                fallback.rotationDegrees,
            )
        }
    },
    restore = { values ->
        if (values.size != 5) {
            EditorSavedFallback.Empty
        } else {
            try {
                val uri = (values[0] as String).ifEmpty { null }
                val kind = SourceKind.valueOf(values[1] as String)
                val displayName = values[2] as String
                val authorizationRequired = values[3] as Boolean
                val rotationDegrees = values[4] as Int
                require(uri == null || uri.length <= 16 * 1024)
                require(displayName.isNotBlank() && displayName.length <= 512)
                require(rotationDegrees in setOf(0, 90, 180, 270))
                if (kind == SourceKind.DEMO) {
                    require(uri == null && !authorizationRequired)
                } else {
                    val parsed = Uri.parse(requireNotNull(uri))
                    require(parsed.scheme.equals("content", ignoreCase = true))
                    require(!parsed.authority.isNullOrBlank())
                }
                EditorSavedFallback(
                    source = EditorSourceState(uri, kind, displayName, authorizationRequired),
                    rotationDegrees = rotationDegrees,
                )
            } catch (_: Exception) {
                EditorSavedFallback.Empty
            }
        }
    },
)

/** Adjustment categories shown in the bottom bar; each maps to an existing section. */

@Composable
internal fun MainActivity.AppRoot(
    settings: AppSettings,
    settingsUi: SettingsUiState,
    onSettingsAction: (SettingsAction) -> Unit,
    updateUi: UpdateUiState,
    onUpdateAction: (UpdateAction) -> Unit,
) {
    val ctx = LocalContext.current
    // Reads evaluated DURING composition go through LocalResources, not ctx: unlike
    // LocalContext.current.getString it recomposes on a configuration change (locale,
    // font scale). ctx stays for everything else it is used for.
    val res = LocalResources.current
    val appContext = ctx.applicationContext
    // Acquire before startup read: once this host exists, callbacks from an overlapping
    // outgoing Activity may no longer publish a logically older cursor after restoration.
    val sessionCheckpointOwner = remember(appContext) {
        EditorSessionCheckpointRuntime.acquireOwner()
    }
    var startupRead by remember { mutableStateOf<EditorStartupRead?>(null) }
    var savedFallback by rememberSaveable(stateSaver = EditorSavedFallbackSaver) {
        mutableStateOf(EditorSavedFallback.Empty)
    }
    LaunchedEffect(appContext) {
        val sessionRead = withContext(dispatchers.io) {
            EditorSessionCheckpointRuntime.read(appContext)
        }
        startupRead = EditorStartupRead(sessionRead, restoreLatestSourceAccess(appContext))
    }

    // A session document can contain several megabytes of bounded undo state. Read it from
    // no-backup storage off-main before constructing the editor; rendering defaults and then
    // replaying the restored cursor would publish a wrong-source/default frame in between.
    val startup = startupRead
    if (startup == null) {
        Box(
            Modifier.fillMaxSize().background(SpectraIcons.nearBlackCanvas),
            contentAlignment = Alignment.Center,
        ) {
            SpectraLoadingIndicator(
                color = SpektraBrand.onCanvas,
                modifier = Modifier.semantics { contentDescription = res.getString(R.string.editor_loading) },
            )
        }
        return
    }
    val sessionReadResult = startup.session
    val loadedSession = (sessionReadResult as? EditorSessionReadResult.Loaded)?.document
    val initialRestoration = remember(loadedSession, startup.source, savedFallback) {
        reconcileEditorRestoration(
            session = loadedSession,
            sourceRestore = startup.source,
            savedFallback = savedFallback,
        )
    }
    // Unsupported is a durable downgrade guard. Unavailable starts read-only until a real
    // AtomicFile read proves that transient storage IO recovered.
    var sessionWriteAccess by remember(sessionReadResult) {
        mutableStateOf(editorSessionWriteAccess(sessionReadResult))
    }
    val sessionRestoreNotice = when {
        loadedSession != null && !initialRestoration.retainedSessionCursor ->
            stringResource(R.string.editor_session_source_changed)
        initialRestoration.source.authorizationRequired ->
            stringResource(R.string.editor_session_access_expired)
        sessionReadResult is EditorSessionReadResult.CorruptQuarantined ->
            stringResource(R.string.editor_session_quarantined)
        sessionReadResult is EditorSessionReadResult.Unsupported ->
            stringResource(R.string.editor_session_newer_version)
        sessionReadResult is EditorSessionReadResult.Unavailable ->
            stringResource(R.string.editor_session_unavailable)
        else -> null
    }
    var pendingSessionRestoreNotice by remember { mutableStateOf(sessionRestoreNotice) }
    var editorRestoration by remember { mutableStateOf(initialRestoration) }
    var latestEditorSession by remember { mutableStateOf(initialRestoration.document) }
    var liveSessionMutatedDuringRecovery by remember { mutableStateOf(false) }
    var editorAuthorityReady by remember { mutableStateOf(true) }
    var editorEntryGeneration by remember { mutableLongStateOf(0L) }
    var editorCompositionGeneration by remember { mutableLongStateOf(0L) }
    var editorCallbackGeneration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(appContext, sessionReadResult) {
        if (sessionWriteAccess != EditorSessionWriteAccess.RECOVERING) return@LaunchedEffect
        val recovered = awaitEditorSessionWriteRecovery(
            read = {
                withContext(dispatchers.io) {
                    EditorSessionCheckpointRuntime.read(appContext)
                }
            },
            onUnavailable = { delay(500) },
        )
        val recoveredAccess = editorSessionWriteAccess(recovered)
        when (recoveredAccess) {
            EditorSessionWriteAccess.WRITABLE -> {
                // Fence every callback owned by the outgoing composition before adopting the
                // recovery decision. Its lifecycle onDispose must not overwrite that decision.
                editorCallbackGeneration++
                editorAuthorityReady = false
                val source = restoreLatestSourceAccess(appContext)
                val resolution = (recovered as? EditorSessionReadResult.Loaded)?.let { loaded ->
                    resolveLoadedEditorSessionRecovery(
                        recovered = loaded.document,
                        sourceRestore = source,
                        savedFallback = savedFallback,
                        liveDocument = latestEditorSession,
                        liveMutated = liveSessionMutatedDuringRecovery,
                    )
                }
                val reconciled = resolution?.restoration ?: reconcileEditorRestoration(
                    session = latestEditorSession,
                    sourceRestore = source,
                    savedFallback = savedFallback,
                )
                editorRestoration = reconciled
                latestEditorSession = reconciled.document
                savedFallback = EditorSavedFallback(
                    source = reconciled.source,
                    rotationDegrees = reconciled.rotationDegrees,
                )
                // If the explicit live-mutation policy won (or recovery found no document),
                // enqueue that exact reconciled cursor before exposing WRITABLE to callbacks.
                if (
                    resolution?.policy == EditorRecoveryConflictPolicy.LIVE_MUTATION ||
                    recovered !is EditorSessionReadResult.Loaded
                ) reconciled.document?.let { document ->
                    EditorSessionCheckpointRuntime.checkpoint(
                        appContext,
                        document,
                        sessionCheckpointOwner,
                    )
                }
                editorCompositionGeneration++
                liveSessionMutatedDuringRecovery = false
                sessionWriteAccess = EditorSessionWriteAccess.WRITABLE
                editorAuthorityReady = true
                pendingSessionRestoreNotice = res.getString(R.string.editor_session_recovered)
            }
            EditorSessionWriteAccess.PROTECTED -> {
                sessionWriteAccess = EditorSessionWriteAccess.PROTECTED
                pendingSessionRestoreNotice = res.getString(R.string.editor_session_newer_version)
            }
            EditorSessionWriteAccess.RECOVERING -> error("recovery returned unavailable")
        }
    }
    val activeRestoration = latestEditorSession?.let { document ->
        EditorRestoration(
            document = document,
            source = document.source,
            rotationDegrees = document.current.rotationDegrees,
            retainedSessionCursor = true,
            usedSavedFallback = false,
        )
    } ?: editorRestoration
    SideEffect {
        if (editorAuthorityReady) {
            val fallback = EditorSavedFallback(
                source = activeRestoration.source,
                rotationDegrees = activeRestoration.rotationDegrees,
            )
            if (savedFallback != fallback) savedFallback = fallback
        }
    }
    val acceptEditorSession: (EditorSessionDocument, Boolean) -> Boolean = remember(
        appContext,
        sessionWriteAccess,
        sessionCheckpointOwner,
        editorCallbackGeneration,
    ) {
        val acceptedCallbackGeneration = editorCallbackGeneration
        checkpoint@{ document, liveMutation ->
            if (acceptedCallbackGeneration != editorCallbackGeneration) {
                return@checkpoint false
            }
            latestEditorSession = document
            if (
                sessionWriteAccess == EditorSessionWriteAccess.RECOVERING &&
                liveMutation
            ) liveSessionMutatedDuringRecovery = true
            Ticket139EditorProbe.publishCheckpoint()
            savedFallback = EditorSavedFallback(
                source = document.source,
                rotationDegrees = document.current.rotationDegrees,
            )
            if (sessionWriteAccess == EditorSessionWriteAccess.WRITABLE) {
                EditorSessionCheckpointRuntime.checkpoint(
                    appContext,
                    document,
                    sessionCheckpointOwner,
                )
            } else {
                false
            }
        }
    }
    var showOnboarding by remember { mutableStateOf(!settings.seenOnboarding) }
    // One-time editor coach marks, shown once onboarding is out of the way.
    var showEditorCoach by remember { mutableStateOf(!settings.seenEditorCoach) }
    // Navigation 3 back stack (#274): the editor is the root entry and is never popped,
    // so its saved state outlives every round-trip to a sub-screen. Routes carry their
    // own arguments and the stack is saved across rotation and process death.
    val backStack = rememberNavBackStack(EditorRoute)
    val currentRoute = backStack.lastOrNull() ?: EditorRoute
    val screen = currentRoute.screen

    /**
     * Every stack change goes through here. Returning to the editor from a sub-screen
     * must not compose it with its last in-memory identity: a source acquire or clear may
     * have completed while it was off screen, so the FIFO restore below waits behind that
     * and reconciles the latest session first.
     */
    fun navigate(change: () -> Unit) {
        val wasEditor = backStack.lastOrNull() == EditorRoute
        change()
        if (!wasEditor && backStack.lastOrNull() == EditorRoute) {
            editorAuthorityReady = false
            editorEntryGeneration++
        }
    }
    fun push(route: NavKey) = navigate { backStack.add(route) }
    fun pop() = navigate { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    fun popToEditor() = navigate {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    LaunchedEffect(editorEntryGeneration) {
        if (editorEntryGeneration == 0L) return@LaunchedEffect
        val source = restoreLatestSourceAccess(appContext)
        val reconciled = reconcileEditorRestoration(
            session = latestEditorSession,
            sourceRestore = source,
            savedFallback = savedFallback,
        )
        editorRestoration = reconciled
        latestEditorSession = reconciled.document
        pendingSessionRestoreNotice = when {
            !reconciled.retainedSessionCursor -> res.getString(R.string.editor_session_source_changed)
            reconciled.source.authorizationRequired -> res.getString(R.string.editor_session_access_expired)
            else -> pendingSessionRestoreNotice
        }
        editorAuthorityReady = true
    }
    DisposableEffect(Unit) {
        Ticket139EditorProbe.onHostCreated()
        onDispose { }
    }

    // Profile IDs and names last opened in the Curves screens, for the test probe, which
    // names a destination without arguments.
    var lastFilmCurves by remember { mutableStateOf(FilmCurvesRoute("", "")) }
    var lastPrintCurves by remember { mutableStateOf(PrintCurvesRoute("", "")) }

    LaunchedEffect(Unit) {
        if (!Ticket139EditorProbe.isArmed()) return@LaunchedEffect
        Ticket139EditorProbe.navigationRequests.collect { request ->
            if (request.destination.isEmpty()) return@collect
            val destination = try {
                Screen.valueOf(request.destination)
            } catch (_: IllegalArgumentException) {
                return@collect
            }
            val route: NavKey = when (destination) {
                Screen.EDITOR -> EditorRoute
                Screen.SETTINGS -> SettingsRoute
                Screen.DIAGNOSTICS -> DiagnosticsRoute
                Screen.ABOUT -> AboutRoute
                Screen.HOWTO -> HowToRoute
                Screen.LIBRARY -> LibraryRoute
                Screen.CURVES_FILM -> lastFilmCurves
                Screen.CURVES_PRINT -> lastPrintCurves
            }
            // The probe jumps straight to a destination: the editor plus that one screen.
            navigate {
                while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                if (route != EditorRoute) backStack.add(route)
            }
        }
    }
    SideEffect {
        if (screen != Screen.EDITOR || editorAuthorityReady) {
            Ticket139EditorProbe.publishDestination(screen)
        }
    }
    // Hoisted here (not inside EditorScreen) so the open adjustment category survives a
    // round-trip to Settings/About and back — you return to where you were editing,
    // Lightroom-style, instead of a collapsed panel.
    val editorCategory = remember {
        mutableStateOf(activeRestoration.document?.tool?.category)
    }
    LaunchedEffect(editorEntryGeneration, editorAuthorityReady) {
        if (editorAuthorityReady) {
            editorCategory.value = activeRestoration.document?.tool?.category
        }
    }

    // Catalog-grouped profile options for the Settings default-profile pickers.
    var settingsFilmGroups by remember { mutableStateOf<List<DropdownGroup>>(emptyList()) }
    var settingsPrintGroups by remember { mutableStateOf<List<DropdownGroup>>(emptyList()) }
    // The editor publishes these once its engine is up, but a restored back stack can
    // open straight onto Settings without composing the editor. Load them here as well so
    // the pickers are never empty; a later editor publish replaces them.
    LaunchedEffect(Unit) {
        if (settingsFilmGroups.isNotEmpty()) return@LaunchedEffect
        val (film, print) = withContext(dispatchers.io) {
            val list = runCatching { EngineHolder.get(appContext).listProfiles() }
                .getOrDefault(emptyList())
            StockCatalog.optionsFor(appContext, list, forFilm = true).toGroups() to
                StockCatalog.optionsFor(appContext, list, forFilm = false).toGroups()
        }
        if (settingsFilmGroups.isEmpty()) {
            settingsFilmGroups = film
            settingsPrintGroups = print
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SpectraIcons.nearBlackCanvas),
    ) {
        // NavDisplay gives each entry its own rememberSaveable bucket, so EditorScreen's
        // source identity and rotation survive a trip to Settings and back (without that
        // the editor came back on the synthetic demo image). System and predictive back
        // pop one entry; the editor root has nothing below it, so back there stays with
        // the editor's own BackHandlers.
        NavDisplay(
            backStack = backStack,
            onBack = { pop() },
            entryProvider = entryProvider {
                entry<EditorRoute> {
                    // Only the top entry may show the editor: a predictive-back preview
                    // composes it before the pop commits, while its identity is still
                    // unreconciled.
                    if (!editorAuthorityReady || backStack.lastOrNull() != EditorRoute) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SpectraLoadingIndicator(
                                color = SpektraBrand.onCanvas,
                                modifier = Modifier.semantics {
                                    contentDescription = res.getString(R.string.editor_loading)
                                },
                            )
                        }
                    } else key(editorCompositionGeneration) {
                        EditorScreen(
                            settings = settings,
                            restoration = activeRestoration,
                            sessionRestoreNotice = pendingSessionRestoreNotice,
                            onSessionRestoreNoticeConsumed = { pendingSessionRestoreNotice = null },
                            onSessionCheckpoint = acceptEditorSession,
                            activeCategoryState = editorCategory,
                            onOpenSettings = { push(SettingsRoute) },
                            onOpenAbout = { push(AboutRoute) },
                            onOpenLibrary = { push(LibraryRoute) },
                            onProfileGroups = { f, p -> settingsFilmGroups = f; settingsPrintGroups = p },
                            onOpenFilmCurves = { id, name ->
                                FilmCurvesRoute(id, name).let { lastFilmCurves = it; push(it) }
                            },
                            onOpenPrintCurves = { id, name ->
                                PrintCurvesRoute(id, name).let { lastPrintCurves = it; push(it) }
                            },
                            onSettingsAction = onSettingsAction,
                        )
                    }
                }
                entry<SettingsRoute> {
                    NavScaffold(stringResource(R.string.editor_action_settings), onBack = { pop() }) {
                        SettingsScreen(
                            state = settingsUi,
                            onAction = onSettingsAction,
                            filmGroups = settingsFilmGroups,
                            printGroups = settingsPrintGroups,
                            onShowOnboarding = { showOnboarding = true; popToEditor() },
                            onOpenDiagnostics = { push(DiagnosticsRoute) },
                            onOpenAbout = { push(AboutRoute) },
                            onOpenHowTo = { push(HowToRoute) },
                            updateUi = updateUi,
                            onUpdateAction = onUpdateAction,
                        )
                    }
                }
                entry<DiagnosticsRoute> {
                    NavScaffold(stringResource(R.string.editor_nav_diagnostics), onBack = { pop() }) {
                        DiagnosticsScreen()
                    }
                }
                entry<AboutRoute> {
                    NavScaffold(stringResource(R.string.editor_action_about), onBack = { pop() }) {
                        AboutScreen(onOpenHowTo = { push(HowToRoute) })
                    }
                }
                entry<HowToRoute> {
                    NavScaffold(stringResource(R.string.screen_howto_title), onBack = { pop() }) {
                        // Header supplied by the scaffold above, so the guide draws none.
                        HowToUseScreen(onBack = null)
                    }
                }
                entry<LibraryRoute> {
                    LibraryDestination(
                        vm = libraryVm,
                        currentUri = activeRestoration.source.uri,
                        onBack = { pop() },
                        onOpen = { uri ->
                            // The same dispatch as a shared or picked file: the editor
                            // consumes it once it is the top entry again. Popping (not
                            // pushing) the editor keeps Back from reopening the Library.
                            incomingImageIntent.value = Intent(Intent.ACTION_VIEW, uri)
                            popToEditor()
                        },
                    )
                }
                entry<FilmCurvesRoute> { route ->
                    ProfileCurvesScreen(
                        profileId = route.profileId,
                        displayName = route.displayName,
                        onBack = { pop() },
                    )
                }
                entry<PrintCurvesRoute> { route ->
                    ProfileCurvesScreen(
                        profileId = route.profileId,
                        displayName = route.displayName,
                        onBack = { pop() },
                    )
                }
            },
        )

        if (showOnboarding && screen == Screen.EDITOR) {
            WelcomeFlow(
                onFinish = { settings.seenOnboarding = true; showOnboarding = false },
                onOpenSettings = {
                    settings.seenOnboarding = true; showOnboarding = false; push(SettingsRoute)
                },
                onReportIssue = { Links.open(ctx, Links.NEW_ISSUE) },
            )
        }

        // Editor coach marks: after onboarding, the first time the editor is shown.
        if (!showOnboarding && showEditorCoach && screen == Screen.EDITOR) {
            EditorCoachOverlay(
                onDismiss = { settings.seenEditorCoach = true; showEditorCoach = false },
            )
        }
    }
}

/** A simple back-arrow top bar wrapping a sub-screen (inset for status bar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            SpectraIcons.Back,
                            contentDescription = stringResource(R.string.editor_nav_back),
                        )
                    }
                },
                actions = actions,
            )
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
