/*
 * Spektrafilm for Android — default-off ticket #139 device probe implementation. GPLv3.
 */
package com.spectrafilm.app

import android.content.Context
import android.os.Process
import com.spectrafilm.app.Ticket139Contracts.verifyTransientDurabilityRecoveryContract
import com.spectrafilm.app.Ticket139Fixtures.EXPECTED_MASK_INDEX
import com.spectrafilm.app.Ticket139Fixtures.EXPECTED_ROTATION
import com.spectrafilm.app.Ticket139Fixtures.clearDurableSource
import com.spectrafilm.app.Ticket139Fixtures.richDocument
import com.spectrafilm.app.Ticket139ProcessFiles.buildIdentity
import com.spectrafilm.app.Ticket139ProcessFiles.processMarker
import com.spectrafilm.app.Ticket139ProcessFiles.processOracle
import com.spectrafilm.app.Ticket139ProcessFiles.readProcessOracle
import com.spectrafilm.app.Ticket139ProcessFiles.requireLoaded
import com.spectrafilm.app.Ticket139ProcessFiles.writeProcessOracle
import com.spectrafilm.app.masks.MaskComponent
import com.spectrafilm.engine.SpektraEngine
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Internal, default-off implementation behind the small kept release-test ABI facade. */
internal object Ticket139EditorProbe : EditorProbe {
    internal data class NavigationRequest(val sequence: Long, val destination: String)
    internal data class SourceProbeRequest(val sequence: Long, val label: String)
    internal data class ExportProbeRequest(val sequence: Long)
    internal data class PreviewCompletionRequest(val sequence: Long)

    private data class PreviewCompletionProbe(
        val sequence: Long,
        val baseline: MemoryCurrentSignature,
        val release: CompletableDeferred<Unit>,
        var entered: MemoryCurrentSignature? = null,
        var sourceBPublished: MemoryCurrentSignature? = null,
        var ownerCancellationObserved: Boolean = false,
        var stalePublicationRejected: Boolean = false,
        var cleanup: MemoryCurrentSignature? = null,
    )

    private data class OverlayDraftEvidence(
        val generation: Long,
        val tool: EditorOverlayTool,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
    )

    private val armed = AtomicBoolean(false)
    private val commandSequence = AtomicLong(0L)
    private val commands = MutableStateFlow(NavigationRequest(0L, ""))
    private val hostGeneration = AtomicLong(0L)
    private val checkpointGeneration = AtomicLong(0L)
    private val currentDestination = AtomicReference("")
    private val sourceProbeSequence = AtomicLong(0L)
    private val sourceProbeCommands = Channel<SourceProbeRequest>(capacity = 4)
    private val currentProbeSource = AtomicReference("")
    private val sourceRetirementGeneration = AtomicLong(0L)
    private val exportProbeSequence = AtomicLong(0L)
    private val exportProbeCommands = Channel<ExportProbeRequest>(capacity = 2)
    private val exportProbeHandledGeneration = AtomicLong(0L)
    private val exportProbeSheetOpen = AtomicReference(false)
    private val editorInstanceSequence = AtomicLong(0L)
    private val activeEditorInstance = AtomicLong(0L)
    private val latestReadyEditorInstance = AtomicLong(0L)
    private val liveEditorReadyGeneration = AtomicLong(0L)
    private val liveEditorSnapshot = AtomicReference<EditorSessionDocument?>(null)
    private val expectedEditorOracle = AtomicReference<EditorSessionDocument?>(null)
    private val expectedOracleSeed = AtomicReference("")
    private val expectedExportRunId = AtomicLong(0L)
    private val livePreviewSource = AtomicReference("")
    private val livePreviewGeneration = AtomicLong(0L)
    private val sourceABaseline = AtomicReference<MemoryCurrentSignature?>(null)
    private val previewCompletionSequence = AtomicLong(0L)
    private val previewCompletionCommands = Channel<PreviewCompletionRequest>(capacity = 2)
    private val previewCompletionEnteredGeneration = AtomicLong(0L)
    private val previewCompletionCleanupGeneration = AtomicLong(0L)
    private val overlayDraftSequence = AtomicLong(0L)
    private val overlayDraftEvidence = AtomicReference<OverlayDraftEvidence?>(null)
    private val previewCompletionLock = Any()
    private var previewCompletionProbe: PreviewCompletionProbe? = null

    override val navigationRequests: StateFlow<NavigationRequest> = commands.asStateFlow()
    override val sourceProbeRequests: Flow<SourceProbeRequest> = sourceProbeCommands.receiveAsFlow()
    override val exportProbeRequests: Flow<ExportProbeRequest> = exportProbeCommands.receiveAsFlow()
    override val previewCompletionRequests: Flow<PreviewCompletionRequest> =
        previewCompletionCommands.receiveAsFlow()

    fun arm() {
        clearState()
        armed.set(true)
    }

    fun reset() {
        armed.set(false)
        clearState()
    }

    override fun isArmed(): Boolean = armed.get()

    internal fun requireArmed() {
        check(armed.get()) { "ticket #139 probe is not armed" }
    }

    private fun clearState() {
        synchronized(previewCompletionLock) {
            previewCompletionProbe?.release?.complete(Unit)
            previewCompletionProbe = null
        }
        currentDestination.set("")
        currentProbeSource.set("")
        sourceRetirementGeneration.set(0L)
        exportProbeHandledGeneration.set(0L)
        exportProbeSheetOpen.set(false)
        checkpointGeneration.set(0L)
        editorInstanceSequence.set(0L)
        activeEditorInstance.set(0L)
        latestReadyEditorInstance.set(0L)
        liveEditorReadyGeneration.set(0L)
        liveEditorSnapshot.set(null)
        expectedEditorOracle.set(null)
        expectedOracleSeed.set("")
        expectedExportRunId.set(0L)
        livePreviewSource.set("")
        livePreviewGeneration.set(0L)
        sourceABaseline.set(null)
        previewCompletionEnteredGeneration.set(0L)
        previewCompletionCleanupGeneration.set(0L)
        overlayDraftEvidence.set(null)
        while (sourceProbeCommands.tryReceive().isSuccess) Unit
        while (exportProbeCommands.tryReceive().isSuccess) Unit
        while (previewCompletionCommands.tryReceive().isSuccess) Unit
        commands.value = NavigationRequest(commandSequence.incrementAndGet(), "")
    }

    @JvmStatic
    fun requestDestination(destination: String): Long {
        require(destination in Screen.entries.map(Screen::name)) { "unsupported destination" }
        val sequence = commandSequence.incrementAndGet()
        commands.value = NavigationRequest(sequence, destination)
        return sequence
    }

    @JvmStatic
    fun requestSourceProbe(label: String): Long {
        require(label == "A" || label == "B") { "unsupported source probe" }
        val sequence = sourceProbeSequence.incrementAndGet()
        check(sourceProbeCommands.trySend(SourceProbeRequest(sequence, label)).isSuccess) {
            "source probe command buffer is full"
        }
        return sequence
    }

    @JvmStatic
    fun requestExportProbe(): Long {
        val sequence = exportProbeSequence.incrementAndGet()
        check(exportProbeCommands.trySend(ExportProbeRequest(sequence)).isSuccess) {
            "export probe command buffer is full"
        }
        return sequence
    }

    @JvmStatic fun currentDestination(): String = currentDestination.get()
    @JvmStatic fun hostGeneration(): Long = hostGeneration.get()
    @JvmStatic fun checkpointGeneration(): Long = checkpointGeneration.get()
    @JvmStatic fun currentProbeSource(): String = currentProbeSource.get()
    @JvmStatic fun sourceRetirementGeneration(): Long = sourceRetirementGeneration.get()
    @JvmStatic fun exportProbeHandledGeneration(): Long = exportProbeHandledGeneration.get()
    @JvmStatic fun liveEditorReadyGeneration(): Long = liveEditorReadyGeneration.get()
    @JvmStatic fun livePreviewGeneration(): Long = livePreviewGeneration.get()
    @JvmStatic fun currentLivePreviewSource(): String = livePreviewSource.get()
    @JvmStatic fun previewCompletionEnteredGeneration(): Long =
        previewCompletionEnteredGeneration.get()
    @JvmStatic fun previewCompletionCleanupGeneration(): Long =
        previewCompletionCleanupGeneration.get()
    @JvmStatic fun overlayDraftGeneration(): Long = overlayDraftEvidence.get()?.generation ?: 0L
    @JvmStatic fun currentOverlayDraftTool(): String = overlayDraftEvidence.get()?.tool?.name ?: ""

    /** Bind a real process-owned export to the immutable Activity-recreation oracle. */
    @JvmStatic
    fun expectActivityExportRun(runId: Long) {
        require(runId > 0L) { "invalid Activity export run id" }
        expectedExportRunId.set(runId)
    }

    /**
     * Called once by the real CropCanvas after its remembered CropDraft exists. This is
     * inert outside the fixed #139 oracle and independently verifies that the draft equals the
     * committed crop passed into the Composable.
     */
    override fun publishCropDraftInitialized(
        bitmapWidth: Int,
        bitmapHeight: Int,
        initialCrop: Boolean,
        initialCenter: Pair<Float, Float>,
        initialSize: Pair<Float, Float>,
        actualLeft: Float,
        actualTop: Float,
        actualRight: Float,
        actualBottom: Float,
    ) {
        if (!armed.get()) return
        if (expectedEditorOracle.get() == null) return
        require(bitmapWidth > 0 && bitmapHeight > 0) { "crop draft has no rendered preview" }
        val imgW = bitmapWidth.toFloat()
        val imgH = bitmapHeight.toFloat()
        val maxDim = maxOf(imgW, imgH)
        val expected = if (initialCrop) {
            val halfW = (initialSize.first * maxDim / imgW) / 2f
            val halfH = (initialSize.second * maxDim / imgH) / 2f
            floatArrayOf(
                (initialCenter.first - halfW).coerceIn(0f, 1f),
                (initialCenter.second - halfH).coerceIn(0f, 1f),
                (initialCenter.first + halfW).coerceIn(0f, 1f),
                (initialCenter.second + halfH).coerceIn(0f, 1f),
            )
        } else {
            floatArrayOf(0f, 0f, 1f, 1f)
        }
        val actual = floatArrayOf(actualLeft, actualTop, actualRight, actualBottom)
        check(actual.indices.all { kotlin.math.abs(actual[it] - expected[it]) <= 1e-6f }) {
            "restored crop overlay did not initialize a clean committed draft"
        }
        publishOverlayDraft(EditorOverlayTool.CROP, bitmapWidth, bitmapHeight)
    }

    /** Same real-composition proof for the mask geometry editor's remembered local draft. */
    override fun publishMaskDraftInitialized(
        bitmapWidth: Int,
        bitmapHeight: Int,
        committed: MaskComponent,
        actual: MaskComponent,
    ) {
        if (!armed.get()) return
        if (expectedEditorOracle.get() == null) return
        require(bitmapWidth > 0 && bitmapHeight > 0) { "mask draft has no rendered preview" }
        check(actual == committed) {
            "restored mask overlay did not initialize a clean committed draft"
        }
        publishOverlayDraft(EditorOverlayTool.MASK_GEOMETRY, bitmapWidth, bitmapHeight)
    }

    @JvmStatic
    fun verifyOverlayDraft(generation: Long, expectedTool: String) {
        val evidence = checkNotNull(overlayDraftEvidence.get()) { "overlay draft never initialized" }
        check(evidence.generation == generation) { "overlay draft generation changed before verification" }
        check(evidence.tool == EditorOverlayTool.valueOf(expectedTool)) {
            "overlay draft initialized ${evidence.tool}, expected $expectedTool"
        }
        check(evidence.bitmapWidth > 0 && evidence.bitmapHeight > 0) {
            "overlay draft was not backed by a rendered preview"
        }
    }

    private fun publishOverlayDraft(tool: EditorOverlayTool, bitmapWidth: Int, bitmapHeight: Int) {
        val generation = overlayDraftSequence.incrementAndGet()
        overlayDraftEvidence.set(
            OverlayDraftEvidence(generation, tool, bitmapWidth, bitmapHeight),
        )
    }

    @JvmStatic
    fun prepareActivityProbe(context: Context) {
        val app = context.applicationContext
        requireArmed()
        verifyTransientDurabilityRecoveryContract(app)
        clearDurableSource(app)
        AppSettings.from(app).apply {
            seenOnboarding = true
            seenEditorCoach = true
            gpuPreview = false
        }
        // A previously finishing Activity can still run its final Compose checkpoint callback.
        // Fence it first, then drain everything accepted before installing the immutable oracle.
        EditorSessionCheckpointRuntime.retireOwners()
        EditorSessionCheckpointRuntime.flush()
        val fixture = activityFixtureOracle()
        expectedEditorOracle.set(fixture)
        EditorSessionStore.write(app, fixture)
    }

    @JvmStatic
    fun prepareAuthorizedMaskActivityProbe(context: Context) {
        val app = context.applicationContext
        requireArmed()
        clearDurableSource(app)
        AppSettings.from(app).apply {
            seenOnboarding = true
            seenEditorCoach = true
            gpuPreview = false
        }
        EditorSessionCheckpointRuntime.retireOwners()
        EditorSessionCheckpointRuntime.flush()
        val fixture = activityFixtureOracle().copy(
            tool = EditorToolState(
                Category.MASKS,
                EditorOverlayTool.MASK_GEOMETRY,
                EXPECTED_MASK_INDEX,
            ),
        )
        check(!fixture.source.authorizationRequired) { "mask fixture must be render-authorized" }
        expectedEditorOracle.set(fixture)
        EditorSessionStore.write(app, fixture)
    }

    @JvmStatic
    fun verifyActivityCursor(context: Context, expectedExportPhase: String) {
        EditorSessionCheckpointRuntime.flush()
        val document = requireLoaded(context)
        verifyAgainstExpectedOracle(document, expectedExportPhase)
        verifyRichCursor(document)
        check(document.source.kind == SourceKind.DEMO) { "activity probe source changed" }
        check(!document.source.authorizationRequired) { "demo unexpectedly needs authorization" }
        val expected = EditorExportPhase.valueOf(expectedExportPhase)
        check(document.export.phase == expected) {
            "activity export cursor was ${document.export.phase}, expected $expected"
        }
        if (expected != EditorExportPhase.RUNNING) {
            check(document.export.runtimeRunId == null) {
                "terminal activity export retained a runtime generation"
            }
        }
    }

    @JvmStatic
    fun prepareProcessDeathProbe(context: Context) {
        val app = context.applicationContext
        requireArmed()
        clearDurableSource(app)
        AppSettings.from(app).apply {
            seenOnboarding = true
            seenEditorCoach = true
            gpuPreview = false
        }
        EditorSessionCheckpointRuntime.retireOwners()
        EditorSessionCheckpointRuntime.flush()
        val seeded = richDocument(durableSource = true, runningExport = false)
        val expected = seeded.copy(
            source = seeded.source.copy(authorizationRequired = true),
        )
        val seedIdentity = UUID.randomUUID().toString()
        writeProcessOracle(app, seedIdentity, expected)
        expectedEditorOracle.set(expected)
        expectedOracleSeed.set(seedIdentity)
        EditorSessionStore.write(app, seeded)
    }

    /** Real process-owned RUNNING state that the host kills between seed and recovery phases. */
    @JvmStatic
    fun beginProcessDeathExportProbe(context: Context): Long {
        // Launch as the seeded editor's exact current source binding, never the legacy UNBOUND
        // ABI: the source-fenced UI refuses to track an unbound run, and this probe must appear
        // in the durable cursor as the editor's own in-flight export (ticket #139).
        val sourceIdentity = ExportSourceIdentityAuthority.current()
        check(sourceIdentity != ExportSourceIdentity.UNBOUND) {
            "process-death export probe requires a live editor source binding"
        }
        val runId = requireNotNull(
            ExportWorkRuntime.launch(
                context = context.applicationContext,
                format = ExportFormat.PNG16,
                startedAtMillis = System.currentTimeMillis(),
                sourceIdentity = sourceIdentity,
            ) {
                awaitCancellation()
            },
        ) { "process-death export probe could not start" }
        expectedExportRunId.set(runId)
        return runId
    }

    @JvmStatic
    fun verifyProcessDeathSeedAfterLaunch(context: Context, runId: Long): String {
        val app = context.applicationContext
        EditorSessionCheckpointRuntime.flush()
        val saved = requireLoaded(app)
        check(expectedExportRunId.get() == runId) { "process export oracle has the wrong run id" }
        verifyAgainstExpectedOracle(saved, EditorExportPhase.RUNNING.name)
        verifyRichCursor(saved)
        check(saved.source.kind == SourceKind.RAW) { "seeded editor changed source kind" }
        check(saved.source.authorizationRequired) { "seeded revoked source was not gated" }
        check(saved.export.phase == EditorExportPhase.RUNNING) {
            "seeded editor did not checkpoint RUNNING"
        }
        check(saved.export.runtimeRunId == runId) { "seeded editor checkpointed the wrong export" }
        val payload = JSONObject()
            .put("schema", "org.spektrafilm.ticket139-process-probe")
            .put("version", 1)
            .put("pid", Process.myPid())
            .put("rotation", EXPECTED_ROTATION)
            .put("maskIndex", EXPECTED_MASK_INDEX)
            .put("runId", runId)
            .put("oracleSeed", expectedOracleSeed.get())
            .put("buildIdentity", buildIdentity(app))
        AtomicJsonStore.writeUtf8(processMarker(app), payload.toString(), 4 * 1024)
        return "pid=${Process.myPid()} cursor=$EXPECTED_ROTATION/$EXPECTED_MASK_INDEX " +
            "completeOracle=true tool=MASK_GEOMETRY"
    }

    @JvmStatic
    fun abortProcessDeathExportProbe(runId: Long) {
        ExportWorkRuntime.cancel(runId)
    }

    @JvmStatic
    fun verifyProcessRecoveryBeforeLaunch(context: Context): String {
        val app = context.applicationContext
        val payload = AtomicJsonStore.parseObject(
            AtomicJsonStore.readUtf8(processMarker(app), 4 * 1024),
        )
        check(payload.getString("schema") == "org.spektrafilm.ticket139-process-probe")
        check(payload.getInt("version") == 1)
        check(payload.getString("buildIdentity") == buildIdentity(app)) {
            "ticket #139 process marker belongs to another build"
        }
        val oracleSeed = payload.getString("oracleSeed")
        val oracle = readProcessOracle(app, oracleSeed)
        expectedEditorOracle.set(oracle)
        expectedOracleSeed.set(oracleSeed)
        expectedExportRunId.set(payload.getLong("runId"))
        val seedPid = payload.getInt("pid")
        check(seedPid != Process.myPid()) {
            "ticket #139 recovery did not start in a new process"
        }
        val saved = requireLoaded(app)
        verifyCompleteEditorCursor(
            saved,
            oracle,
            EditorExportPhase.RUNNING,
            expectedRuntimeRunId = payload.getLong("runId"),
        )
        verifyRichCursor(saved)
        check(saved.export.phase == EditorExportPhase.RUNNING)
        check(saved.export.runtimeRunId == payload.getLong("runId"))
        val reconciled = reconcileEditorRestoration(
            saved,
            SourceRestoreResult.None,
            EditorSavedFallback.Empty,
        )
        check(reconciled.document != null) { "revoked session cursor was discarded" }
        check(reconciled.source.kind == SourceKind.RAW) { "revoked RAW changed source kind" }
        check(reconciled.source.authorizationRequired) { "revoked source was not gated" }
        check(reconciled.source.kind != SourceKind.DEMO) { "revoked source silently became demo" }
        val export = reconcileRestoredExport(saved.export, activeRuntimeRunId = null)
        check(export.phase == EditorExportPhase.RECONCILING)
        check(export.runtimeRunId == null)
        return "seedPid=$seedPid recoverPid=${Process.myPid()} completeOracle=true"
    }

    @JvmStatic
    fun verifyProcessRecoveryAfterLaunch(context: Context) {
        EditorSessionCheckpointRuntime.flush()
        val restored = requireLoaded(context)
        verifyAgainstExpectedOracle(restored, EditorExportPhase.RECONCILING.name)
        verifyRichCursor(restored)
        check(restored.source.kind == SourceKind.RAW) { "restored source kind changed" }
        check(restored.source.authorizationRequired) { "restored source was rendered without grant" }
        check(restored.export.phase == EditorExportPhase.RECONCILING) {
            "orphaned export did not reconcile"
        }
        check(restored.export.runtimeRunId == null) { "orphaned export retained a run id" }
        check(AtomicJsonStore.delete(processMarker(context.applicationContext))) {
            "ticket #139 process marker cleanup failed"
        }
        check(AtomicJsonStore.delete(processOracle(context.applicationContext))) {
            "ticket #139 process oracle cleanup failed"
        }
        reset()
    }

    @JvmStatic
    fun verifyLiveEditorCursor(
        context: Context,
        expectedFixture: String,
        expectedExportPhase: String,
    ) {
        EditorSessionCheckpointRuntime.flush()
        val live = checkNotNull(liveEditorSnapshot.get()) { "live editor cursor was never published" }
        val expectedPhase = EditorExportPhase.valueOf(expectedExportPhase)
        if (expectedFixture == "ACTIVITY" || expectedFixture == "PROCESS") {
            // The pre-launch oracle is the first authority consulted. A restored editor is never
            // allowed to checkpoint itself and then use that same disk value as its sole oracle.
            verifyAgainstExpectedOracle(live, expectedExportPhase)
        }
        val persisted = requireLoaded(context.applicationContext)
        check(live == persisted) { "live editor cursor differs from its durable checkpoint" }
        check(EditorSessionDocumentCodec.decode(EditorSessionDocumentCodec.encode(live)) == live) {
            "live editor cursor is not a complete round-trippable session"
        }
        check(live.export.phase == expectedPhase) {
            "live export cursor was ${live.export.phase}, expected $expectedPhase"
        }
        when (expectedFixture) {
            "ACTIVITY" -> {
                verifyRichCursor(live)
                check(live.source.kind == SourceKind.DEMO)
                check(!live.source.authorizationRequired)
            }
            "PROCESS" -> {
                verifyRichCursor(live)
                check(live.source.kind == SourceKind.RAW)
                check(live.source.authorizationRequired)
                check(live.source.kind != SourceKind.DEMO)
            }
            "SOURCE_B" -> {
                check(live.source.uri == sourceProbeUri("B"))
                check(live.source.kind == SourceKind.PHOTO)
                check(!live.source.authorizationRequired)
                check(live.history.undo.isEmpty() && live.history.redo.isEmpty())
                check(live.tool.maskIndex in 0 until com.spectrafilm.app.masks.MaskJson.MAX_ADJUSTMENTS)
            }
            else -> error("unsupported live editor fixture")
        }
    }

    /**
     * Force one real settle render through EditorScreen, then pause its completed source-A native
     * result immediately before the production publication gate. No synthetic gate is involved.
     */
    @JvmStatic
    fun armCompletedPreviewProbe(): Long {
        check(currentProbeSource.get() == "A" && livePreviewSource.get() == "A") {
            "source A must be live before arming the completed-preview probe"
        }
        val baseline = checkNotNull(sourceABaseline.get()) {
            "source-A live admission baseline is missing"
        }
        val sequence = previewCompletionSequence.incrementAndGet()
        synchronized(previewCompletionLock) {
            check(previewCompletionProbe == null) { "completed-preview probe is already armed" }
            previewCompletionProbe = PreviewCompletionProbe(
                sequence = sequence,
                baseline = baseline,
                release = CompletableDeferred(),
            )
        }
        if (previewCompletionCommands.trySend(PreviewCompletionRequest(sequence)).isFailure) {
            synchronized(previewCompletionLock) {
                previewCompletionProbe?.takeIf { it.sequence == sequence }?.release?.complete(Unit)
                previewCompletionProbe = null
            }
            error("completed-preview command buffer is full")
        }
        return sequence
    }

    @JvmStatic
    fun releaseCompletedPreviewProbe(sequence: Long) {
        val release = synchronized(previewCompletionLock) {
            previewCompletionProbe?.takeIf { it.sequence == sequence }?.release
        }
        checkNotNull(release) { "completed-preview probe is not armed" }.complete(Unit)
    }

    @JvmStatic
    fun verifyCompletedPreviewProbe(sequence: Long) {
        val probe = synchronized(previewCompletionLock) {
            checkNotNull(previewCompletionProbe?.takeIf { it.sequence == sequence }) {
                "completed-preview probe is missing"
            }.copy()
        }
        val entered = checkNotNull(probe.entered) { "source-A real render never reached the barrier" }
        val bPublished = checkNotNull(probe.sourceBPublished) {
            "source B did not publish while source A was held"
        }
        val cleanup = checkNotNull(probe.cleanup) { "source-A cleanup did not complete" }
        check(probe.stalePublicationRejected) { "stale source-A publication was not rejected" }
        check(probe.ownerCancellationObserved) {
            "source B did not cancel the held source-A settle coroutine"
        }
        check(entered.totalBytes > probe.baseline.totalBytes) {
            "source-A barrier did not hold a live native result/admission"
        }
        check(bPublished.totalBytes > probe.baseline.totalBytes) {
            "source B was not live concurrently with the held source-A result"
        }
        check(cleanup == probe.baseline) {
            "source-A cleanup did not return every native/cache admission to the B-held baseline"
        }
        check(livePreviewSource.get() == "B") { "source B is not the published preview" }
    }

    override fun onHostCreated() {
        if (!armed.get()) return
        hostGeneration.incrementAndGet()
    }

    override fun beginLiveEditorInstance(): Long {
        if (!armed.get()) return 0L
        return editorInstanceSequence.incrementAndGet().also { activeEditorInstance.set(it) }
    }

    override fun publishLiveEditorSnapshot(instance: Long, document: EditorSessionDocument) {
        if (!armed.get() || instance == 0L) return
        if (instance == activeEditorInstance.get()) liveEditorSnapshot.set(document)
    }

    override fun publishLiveEditorReady(instance: Long, document: EditorSessionDocument) {
        if (!armed.get() || instance == 0L) return
        if (instance != activeEditorInstance.get()) return
        publishLiveEditorSnapshot(instance, document)
        while (true) {
            val previous = latestReadyEditorInstance.get()
            if (instance <= previous) return
            if (latestReadyEditorInstance.compareAndSet(previous, instance)) {
                liveEditorReadyGeneration.incrementAndGet()
                return
            }
        }
    }

    override fun publishDestination(destination: Screen) {
        if (!armed.get()) return
        currentDestination.set(destination.name)
    }

    override fun publishCheckpoint() {
        if (!armed.get()) return
        checkpointGeneration.incrementAndGet()
    }

    override fun publishProbeSource(label: String) {
        if (!armed.get()) return
        currentProbeSource.set(label)
    }

    override fun publishSourceRetirement() {
        if (!armed.get()) return
        sourceRetirementGeneration.incrementAndGet()
    }

    override suspend fun awaitCompletedPreviewBeforePublication(label: String): Boolean {
        if (!armed.get()) return false
        if (label != "A") return false
        val signature = currentMemorySignature()
        val ownerJob = currentCoroutineContext()[Job]
        val release = synchronized(previewCompletionLock) {
            val probe = previewCompletionProbe ?: return false
            if (probe.entered != null) return false
            probe.entered = signature
            previewCompletionEnteredGeneration.set(probe.sequence)
            probe.release
        }
        // Source B cancels the old LaunchedEffect by design. Keep this one test-owned result alive
        // until the host releases it so production invalidation/claim/cleanup are actually tested.
        withContext(NonCancellable) {
            withTimeout(PREVIEW_COMPLETION_TIMEOUT_MILLIS) { release.await() }
            // Record this before returning to the cancelled owner context. Returning from the
            // outer Dispatchers.Default boundary has prompt-cancellation semantics, so evidence
            // collected after that boundary could itself be discarded.
            synchronized(previewCompletionLock) {
                previewCompletionProbe
                    ?.takeIf { it.release === release }
                    ?.ownerCancellationObserved = ownerJob?.isCancelled == true
            }
        }
        return true
    }

    override fun publishLivePreview(label: String) {
        if (!armed.get()) return
        if (label != "A" && label != "B") return
        livePreviewSource.set(label)
        livePreviewGeneration.incrementAndGet()
        val signature = currentMemorySignature()
        if (label == "A") {
            sourceABaseline.set(signature)
        } else {
            synchronized(previewCompletionLock) {
                previewCompletionProbe
                    ?.takeIf { it.entered != null }
                    ?.sourceBPublished = signature
            }
        }
    }

    override fun publishPreviewDecision(label: String, claimed: Boolean) {
        if (!armed.get()) return
        if (label != "A") return
        synchronized(previewCompletionLock) {
            previewCompletionProbe
                ?.takeIf { it.entered != null }
                ?.stalePublicationRejected = !claimed
        }
    }

    override fun publishPreviewCleanup(label: String) {
        if (!armed.get()) return
        if (label != "A") return
        val signature = currentMemorySignature()
        synchronized(previewCompletionLock) {
            previewCompletionProbe?.takeIf { it.entered != null }?.let { probe ->
                probe.cleanup = signature
                previewCompletionCleanupGeneration.set(probe.sequence)
            }
        }
    }

    override fun publishExportProbeResult(sequence: Long, sheetOpen: Boolean) {
        if (!armed.get()) return
        exportProbeSheetOpen.set(sheetOpen)
        exportProbeHandledGeneration.updateAndGet { handled -> maxOf(handled, sequence) }
    }

    @JvmStatic
    fun verifyRevokedExportProbe(sequence: Long) {
        check(exportProbeHandledGeneration.get() >= sequence) { "export probe was not handled" }
        check(!exportProbeSheetOpen.get()) { "revoked source opened the export sheet" }
    }

    override fun sourceProbeUri(label: String): String {
        requireArmed()
        require(label == "A" || label == "B") { "unsupported source probe" }
        return "content://com.spectrafilm.app.test.ticket139.sources/source/${label.lowercase()}.png"
    }

    override fun sourceProbeLabel(uri: String?): String {
        if (!armed.get()) return ""
        return when (uri) {
            sourceProbeUri("A") -> "A"
            sourceProbeUri("B") -> "B"
            else -> ""
        }
    }

    @JvmStatic
    fun verifySourceProbeAfterSwitch(context: Context, expectedLabel: String, retirementBefore: Long) {
        val expectedUri = sourceProbeUri(expectedLabel)
        EditorSessionCheckpointRuntime.flush()
        val restored = requireLoaded(context)
        check(restored.source.uri == expectedUri) { "source probe checkpoint has the wrong identity" }
        check(restored.source.kind == SourceKind.PHOTO) { "source probe checkpoint changed kind" }
        check(!restored.source.authorizationRequired) { "replacement source was not authorized" }
        val durable = sourceAccessRuntime(context.applicationContext).coordinator.restore()
        check(durable is SourceRestoreResult.Ready && durable.ref.uri == expectedUri) {
            "replacement source was not the durable SourceAccess authority"
        }
        check(currentProbeSource.get() == expectedLabel) { "replacement source did not reach editor state" }
        check(sourceRetirementGeneration.get() > retirementBefore) {
            "replacement source did not retire prior render resources"
        }
    }

    private const val PREVIEW_COMPLETION_TIMEOUT_MILLIS = 90_000L

    /** Canonical immutable value written before an Activity is allowed to launch. */
    internal fun activityFixtureOracle(): EditorSessionDocument =
        richDocument(durableSource = false, runningExport = false)

    private fun verifyRichCursor(document: EditorSessionDocument) = Ticket139Fixtures.verifyRichCursor(
        document,
        checkNotNull(expectedEditorOracle.get()) { "complete tool oracle is missing" }.tool,
    )

    /** Delegates to [Ticket139ParamsOracle.verifyPersistedParamsSentinels]. */
    internal fun verifyPersistedParamsSentinels(
        paramsJson: String,
        expectedExposure: String = "1.25",
    ) = Ticket139ParamsOracle.verifyPersistedParamsSentinels(paramsJson, expectedExposure)

    private fun verifyAgainstExpectedOracle(
        document: EditorSessionDocument,
        expectedExportPhase: String,
    ) {
        val oracle = checkNotNull(expectedEditorOracle.get()) {
            "pre-launch complete cursor oracle is missing"
        }
        val phase = EditorExportPhase.valueOf(expectedExportPhase)
        val runId = if (phase == EditorExportPhase.RUNNING) {
            expectedExportRunId.get().takeIf { it > 0L }
                ?: error("pre-launch cursor oracle is missing the running export id")
        } else {
            null
        }
        verifyCompleteEditorCursor(document, oracle, phase, runId)
    }
}
