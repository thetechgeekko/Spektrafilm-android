/*
 * Spektrafilm for Android — ticket #139 release-path contracts. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import com.spectrafilm.app.Ticket139Fixtures.richDocument
import com.spectrafilm.app.Ticket139Fixtures.richParams
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Release-path contracts the #139 probe runs once it is armed.
 * Split out of [Ticket139EditorProbe] (#273).
 */
internal object Ticket139Contracts {
    /** Runs on the physical release path, but only after the explicit default-off probe arm. */
    internal fun verifyTransientDurabilityRecoveryContract(context: Context) = runBlocking {
        check(
            editorSessionWriteAccess(EditorSessionReadResult.Unavailable("injected")) ==
                EditorSessionWriteAccess.RECOVERING,
        )
        check(editorSessionWritesProtected(EditorSessionReadResult.Unsupported("injected")))
        val recoveryReads = AtomicInteger(0)
        val recovered = awaitEditorSessionWriteRecovery(
            read = {
                if (recoveryReads.incrementAndGet() == 1) {
                    EditorSessionReadResult.Unavailable("injected fail once")
                } else {
                    EditorSessionReadResult.Missing
                }
            },
            onUnavailable = { },
        )
        check(recoveryReads.get() == 2)
        check(editorSessionWriteAccess(recovered) == EditorSessionWriteAccess.WRITABLE)
        val attempts = AtomicInteger(0)
        val retries = AtomicInteger(0)
        check(
            awaitDurableEditorSessionCheckpoint(
                checkpoint = { true },
                flush = {
                    if (attempts.incrementAndGet() == 1) throw IOException("injected fail once")
                },
                onRetryableFailure = { retries.incrementAndGet() },
            ),
        )
        check(attempts.get() == 2 && retries.get() == 1) {
            "transient editor-session durability did not retry exactly once"
        }
        verifyRecipeClassificationContract(context)
        verifyRecoveryConflictContract()
        verifyDemoTombstoneContract(context)
        verifySourceBoundExportContract(context)
    }

    internal fun verifyRecipeClassificationContract(context: Context) {
        val futureKey = "1391391391391391391391391391391391391391391391391391391391391391"
        val loadedKey = "2392392392392392392392392392392392392392392392392392392392392392"
        try {
            Recipes.delete(context, futureKey)
            Recipes.saveJson(
                context,
                futureKey,
                Presets.toJsonString(richParams(1.25f)),
                "future.dng",
                expectedGeneration = Recipes.generation(futureKey),
            )
            val futureFile = File(File(context.filesDir, "recipes"), "$futureKey.json")
            val futureRoot = AtomicJsonStore.parseObject(
                AtomicJsonStore.readUtf8(futureFile, AtomicJsonStore.MAX_RECIPE_BYTES),
            ).put("recipeVersion", RecipeDocumentCodec.SCHEMA_VERSION + 100)
            AtomicJsonStore.writeUtf8(
                futureFile,
                futureRoot.toString(),
                AtomicJsonStore.MAX_RECIPE_BYTES,
            )
            val protectedBytes = futureFile.readBytes()
            repeat(2) {
                val before = Recipes.generation(futureKey)
                val read = Recipes.readResult(context, futureKey)
                val access = classifyEditorRecipeAccess(
                    futureKey,
                    before,
                    Recipes.generation(futureKey),
                    read,
                )
                check(read is RecipeReadResult.Unsupported)
                check(access is EditorRecipeAccess.Protected)
                check(access.writableGenerationFor(futureKey) == null)
                check(futureFile.readBytes().contentEquals(protectedBytes)) {
                    "future recipe bytes changed across recreate classification"
                }
            }
            val ioProtected = classifyEditorRecipeAccess(
                futureKey,
                Recipes.generation(futureKey),
                Recipes.generation(futureKey),
                RecipeReadResult.IoFailure("injected"),
            )
            check(ioProtected is EditorRecipeAccess.Protected)

            Recipes.delete(context, loadedKey)
            val richJson = Presets.toJsonString(richParams(1.25f))
            Recipes.saveJson(
                context,
                loadedKey,
                richJson,
                "source-b.dng",
                expectedGeneration = Recipes.generation(loadedKey),
            )
            val pending: EditorRecipeAccess = EditorRecipeAccess.Pending(
                loadedKey,
                Recipes.generation(loadedKey),
            )
            check(pending.writableGenerationFor(loadedKey) == null)
            val before = Recipes.generation(loadedKey)
            val loaded = Recipes.readResult(context, loadedKey)
            val writable = classifyEditorRecipeAccess(
                loadedKey,
                before,
                Recipes.generation(loadedKey),
                loaded,
            )
            check(loaded is RecipeReadResult.Loaded)
            check(writable.writableGenerationFor(loadedKey) == before)
            check(loaded.document.params.toString() == AtomicJsonStore.parseObject(richJson).toString())
        } finally {
            runCatching { Recipes.delete(context, futureKey) }
            runCatching { Recipes.delete(context, loadedKey) }
        }
    }

    internal fun verifyRecoveryConflictContract() {
        val recovered = richDocument(durableSource = false, runningExport = false)
        val live = recovered.copy(
            current = recovered.current.copy(paramsJson = Presets.toJsonString(richParams(2f))),
        )
        val durableWins = resolveLoadedEditorSessionRecovery(
            recovered,
            SourceRestoreResult.Demo,
            EditorSavedFallback.Empty,
            live,
            liveMutated = false,
        )
        check(durableWins.policy == EditorRecoveryConflictPolicy.RECOVERED_DURABLE)
        check(durableWins.restoration.document == recovered)
        val liveWins = resolveLoadedEditorSessionRecovery(
            recovered,
            SourceRestoreResult.Demo,
            EditorSavedFallback.Empty,
            live,
            liveMutated = true,
        )
        check(liveWins.policy == EditorRecoveryConflictPolicy.LIVE_MUTATION)
        check(liveWins.restoration.document == live)
    }

    internal fun verifyDemoTombstoneContract(context: Context) {
        val coordinator = sourceAccessRuntime(context).coordinator
        coordinator.selectDemo()
        val restored = coordinator.restore()
        check(restored == SourceRestoreResult.Demo)
        val staleUriSession = richDocument(durableSource = true, runningExport = false)
        val reconciled = reconcileEditorRestoration(
            staleUriSession,
            restored,
            EditorSavedFallback.Empty,
        )
        check(reconciled.source.kind == SourceKind.DEMO && reconciled.document == null) {
            "demo tombstone resurrected a stale URI session"
        }
    }

    internal suspend fun verifySourceBoundExportContract(context: Context) {
        suspend fun runMismatch(revoke: Boolean) {
            check(ExportWorkRuntime.state.value is ExportRuntimeState.Idle)
            val uri = if (revoke) "content://ticket139/revoke" else "content://ticket139/a"
            val sourceA = ExportSourceIdentityAuthority.bind(uri, SourceKind.PHOTO, false)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val runId = withTimeout(5_000) {
                var accepted: Long?
                do {
                    accepted = ExportWorkRuntime.launch(
                        context,
                        ExportFormat.PNG16,
                        System.currentTimeMillis(),
                        sourceA,
                    ) {
                        ExportTerminalOutcome.Success(
                            ExportFormat.PNG16,
                            139L,
                            bitmap,
                            1L,
                            ExportPhaseSnapshot(0, 0, 0, 0, 0, 0),
                            publishedUri = null,
                            publishedMimeType = null,
                        )
                    }
                    if (accepted == null) delay(1)
                } while (accepted == null)
                requireNotNull(accepted)
            }
            val replacement = ExportSourceIdentityAuthority.bind(
                if (revoke) uri else "content://ticket139/b",
                SourceKind.PHOTO,
                authorizationRequired = revoke,
            )
            val finished = withTimeout(5_000) {
                ExportWorkRuntime.state.filter {
                    it is ExportRuntimeState.Finished && it.runId == runId
                }.first() as ExportRuntimeState.Finished
            }
            check(!exportPublicationAuthorized(finished.sourceIdentity, replacement))
            val claims = coroutineScope {
                (0 until 16).map {
                    async(ProcessDispatchers.default) { ExportWorkRuntime.claimFinished(runId) }
                }.awaitAll()
            }
            check(claims.count { it != null } == 1) { "mismatched export was not claimed once" }
            claims.filterNotNull().forEach(::recycleUnpublishedExport)
            check(bitmap.isRecycled) { "mismatched export bitmap was not recycled" }
            check(ExportWorkRuntime.claimFinished(runId) == null)
        }
        runMismatch(revoke = false)
        runMismatch(revoke = true)
    }
}
