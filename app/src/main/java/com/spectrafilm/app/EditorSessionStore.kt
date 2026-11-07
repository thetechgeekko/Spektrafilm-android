/*
 * Spektrafilm for Android — editor session store. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Durable session storage: write access, recovery conflicts, recipe access, the store
 * and checkpoint coordination. Split out of EditorSession.kt (#273).
 */
package com.spectrafilm.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Deterministic checkpoint retention for a structurally dense but otherwise valid live cursor.
 * The current/source/committed cursor is never discarded. Oldest undo entries are removed first,
 * then oldest redo entries, then the clipboard, then the paired preset-amount anchors. Dropping
 * anchors resets amount to 1 so a restored editor cannot display an unbacked interpolation value.
 */
internal data class EncodableEditorSession(
    val document: EditorSessionDocument,
    val encoded: String,
)

internal fun retainEncodableEditorSession(
    document: EditorSessionDocument,
    onFinalRootMaterialization: () -> Unit = {},
): EncodableEditorSession = EditorSessionDocumentCodec.retainAndEncode(
    document,
    onFinalRootMaterialization,
)

internal sealed interface EditorSessionReadResult {
    data object Missing : EditorSessionReadResult
    data class Loaded(val document: EditorSessionDocument) : EditorSessionReadResult
    data class CorruptQuarantined(val reason: String) : EditorSessionReadResult
    data class Unsupported(val reason: String) : EditorSessionReadResult
    data class Unavailable(val reason: String) : EditorSessionReadResult
}

internal enum class EditorSessionWriteAccess { WRITABLE, RECOVERING, PROTECTED }

/** A future-version document permanently protects disk; transient IO must prove recovery first. */
internal fun editorSessionWriteAccess(result: EditorSessionReadResult): EditorSessionWriteAccess =
    when (result) {
        is EditorSessionReadResult.Unsupported -> EditorSessionWriteAccess.PROTECTED
        is EditorSessionReadResult.Unavailable -> EditorSessionWriteAccess.RECOVERING
        else -> EditorSessionWriteAccess.WRITABLE
    }

internal fun editorSessionWritesProtected(result: EditorSessionReadResult): Boolean =
    editorSessionWriteAccess(result) == EditorSessionWriteAccess.PROTECTED

internal enum class EditorRecoveryConflictPolicy { RECOVERED_DURABLE, LIVE_MUTATION }

internal data class EditorRecoveryResolution(
    val restoration: EditorRestoration,
    val policy: EditorRecoveryConflictPolicy,
)

/**
 * Resolves a transient-read recovery before writes are enabled. A recovered durable document wins
 * unless the current editor has recorded a real user mutation; even then the live document must
 * reconcile to the currently durable source identity before it can win.
 */
internal fun resolveLoadedEditorSessionRecovery(
    recovered: EditorSessionDocument,
    sourceRestore: SourceRestoreResult,
    savedFallback: EditorSavedFallback,
    liveDocument: EditorSessionDocument?,
    liveMutated: Boolean,
): EditorRecoveryResolution {
    val recoveredRestoration = reconcileEditorRestoration(
        session = recovered,
        sourceRestore = sourceRestore,
        savedFallback = savedFallback,
    )
    if (liveMutated && liveDocument != null) {
        val liveRestoration = reconcileEditorRestoration(
            session = liveDocument,
            sourceRestore = sourceRestore,
            savedFallback = savedFallback,
        )
        if (liveRestoration.document != null) {
            return EditorRecoveryResolution(
                restoration = liveRestoration,
                policy = EditorRecoveryConflictPolicy.LIVE_MUTATION,
            )
        }
    }
    return EditorRecoveryResolution(
        restoration = recoveredRestoration,
        policy = EditorRecoveryConflictPolicy.RECOVERED_DURABLE,
    )
}

/** Per-source recipe authority. Source identity alone is deliberately never writable. */
internal sealed interface EditorRecipeAccess {
    data object None : EditorRecipeAccess
    data class Pending(val key: String, val generation: Long) : EditorRecipeAccess
    data class Writable(val key: String, val generation: Long) : EditorRecipeAccess
    data class Protected(val key: String, val generation: Long) : EditorRecipeAccess
}

internal fun classifyEditorRecipeAccess(
    key: String,
    generationBeforeRead: Long,
    generationAfterRead: Long,
    result: RecipeReadResult,
): EditorRecipeAccess {
    if (generationBeforeRead != generationAfterRead) {
        return EditorRecipeAccess.Pending(key, generationAfterRead)
    }
    return when (result) {
        is RecipeReadResult.Loaded,
        RecipeReadResult.Missing,
        is RecipeReadResult.CorruptQuarantined,
        -> EditorRecipeAccess.Writable(key, generationAfterRead)
        is RecipeReadResult.Unsupported,
        is RecipeReadResult.CorruptQuarantineFailed,
        is RecipeReadResult.IoFailure,
        -> EditorRecipeAccess.Protected(key, generationAfterRead)
    }
}

internal fun EditorRecipeAccess.writableGenerationFor(key: String): Long? =
    (this as? EditorRecipeAccess.Writable)
        ?.takeIf { it.key == key }
        ?.generation

/** Retry a transient startup read until storage itself proves whether writes are safe. */
internal suspend fun awaitEditorSessionWriteRecovery(
    read: suspend () -> EditorSessionReadResult,
    onUnavailable: suspend () -> Unit,
): EditorSessionReadResult {
    while (true) {
        val result = read()
        if (result !is EditorSessionReadResult.Unavailable) return result
        onUnavailable()
    }
}

/**
 * Keep an exactly-once owner unclaimed until its newest session marker is physically durable.
 * A transient app-private IO failure is retried from the coordinator's retained newest value;
 * cancellation and deterministic/programming failures still propagate to the caller.
 */
internal suspend fun awaitDurableEditorSessionCheckpoint(
    checkpoint: () -> Boolean,
    flush: suspend () -> Unit,
    onRetryableFailure: suspend (IOException) -> Unit,
): Boolean {
    while (true) {
        if (!checkpoint()) return false
        try {
            flush()
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DocumentLimitException) {
            throw failure
        } catch (failure: IOException) {
            onRetryableFailure(failure)
        }
    }
}

internal object EditorSessionStore {
    private const val DIRECTORY = "editor-session"
    private const val FILE_NAME = "current.json"

    fun read(context: Context): EditorSessionReadResult {
        val file = try {
            file(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return EditorSessionReadResult.Unavailable("editor-session storage is unavailable")
        }
        return read(file)
    }

    /**
     * File-level classification seam; read, decode, classification and any quarantine are one
     * path-locked transaction. A valid app replacement therefore cannot land after corrupt bytes
     * were observed and then be mistaken for those bytes during quarantine.
     */
    internal fun read(
        file: File,
        readText: (File) -> String = { target ->
            AtomicJsonStore.readUtf8(target, EditorSessionDocumentCodec.MAX_DOCUMENT_BYTES)
        },
        quarantine: (File) -> Unit = { target ->
            AtomicJsonStore.quarantine(target)
            Unit
        },
    ): EditorSessionReadResult = AtomicJsonStore.withPathLock(file) {
        try {
            // Do not preflight only the base path. AtomicFile.openRead() must first get the chance
            // to promote a valid legacy .bak left by an interrupted commit.
            EditorSessionReadResult.Loaded(EditorSessionDocumentCodec.decode(readText(file)))
        } catch (missing: java.io.FileNotFoundException) {
            // FileNotFoundException can also represent a transient access problem. It is Missing
            // only when neither readable AtomicFile generation exists after openRead recovery.
            if (!file.exists() && !File(file.path + ".bak").exists()) {
                EditorSessionReadResult.Missing
            } else {
                EditorSessionReadResult.Unavailable(
                    "editor-session storage is unavailable (${missing.javaClass.simpleName})",
                )
            }
        } catch (future: UnsupportedEditorSessionException) {
            // A downgrade must not overwrite a valid newer document.
            EditorSessionReadResult.Unsupported(future.message ?: "newer editor session")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!failure.isDeterministicEditorSessionContentFailure()) {
                return@withPathLock EditorSessionReadResult.Unavailable(
                    "editor-session storage is unavailable (${failure.javaClass.simpleName})",
                )
            }
            try {
                quarantine(file)
                EditorSessionReadResult.CorruptQuarantined(
                    "invalid editor session (${failure.javaClass.simpleName})",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (quarantineFailure: Exception) {
                EditorSessionReadResult.Unavailable(
                    "editor-session quarantine unavailable " +
                        "(${failure.javaClass.simpleName}/${quarantineFailure.javaClass.simpleName})",
                )
            }
        }
    }

    private fun Exception.isDeterministicEditorSessionContentFailure(): Boolean =
        this is DocumentLimitException ||
            this is java.nio.charset.CharacterCodingException ||
            this is IllegalArgumentException ||
            this is org.json.JSONException

    fun write(context: Context, document: EditorSessionDocument) {
        writeEncoded(context, EditorSessionDocumentCodec.encode(document))
    }

    internal fun writeEncoded(context: Context, encoded: String) {
        AtomicJsonStore.writeUtf8(
            file(context),
            encoded,
            EditorSessionDocumentCodec.MAX_DOCUMENT_BYTES,
        )
    }

    private fun file(context: Context): File = File(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY),
        FILE_NAME,
    )
}

/**
 * Linearizable latest-only checkpoint boundary. An offer completed before [flushThenRead] starts is
 * necessarily written before that read. Concurrent offers may linearize immediately after the read,
 * while offers arriving during a blocked write are drained before a waiting read acquires the lock.
 */
internal class LatestCheckpointCoordinator<T>(
    private val writer: (T) -> Unit,
    private val afterGenerationAssigned: (Long) -> Unit = {},
) {
    private data class Versioned<T>(val generation: Long, val value: T)

    private val offeredGeneration = AtomicLong(0L)
    private val acceptedGeneration = AtomicLong(0L)
    private val pending = AtomicReference<Versioned<T>?>(null)
    private val diskLock = Any()

    fun offer(value: T) {
        val generation = offeredGeneration.incrementAndGet()
        afterGenerationAssigned(generation)
        while (true) {
            val accepted = acceptedGeneration.get()
            // Keep a monotonic high-water mark even after the slot has been drained. Otherwise an
            // older producer paused before publication could resume into the now-null slot and
            // regress disk after a newer checkpoint had already been written.
            if (generation <= accepted) return
            if (acceptedGeneration.compareAndSet(accepted, generation)) break
        }
        val offered = Versioned(generation, value)
        while (true) {
            val current = pending.get()
            // Producers overlap: a delayed older producer must never overwrite a newer value that
            // already published. Generation comparison makes the latest-only slot monotonic.
            if (current != null && current.generation > generation) return
            if (pending.compareAndSet(current, offered)) return
        }
    }

    fun flush() = synchronized(diskLock) {
        flushLatestLocked()
    }

    fun <R> flushThenRead(reader: () -> R): R = synchronized(diskLock) {
        while (true) {
            flushLatestLocked()
            val generationBeforeRead = acceptedGeneration.get()
            val result = reader()
            val generationAfterRead = acceptedGeneration.get()
            // A checkpoint offered after the drain but before/during the physical read must be
            // included. Retry under the same disk lock; the signal worker cannot reorder a write.
            if (generationBeforeRead == generationAfterRead && pending.get() == null) {
                return@synchronized result
            }
        }
        error("unreachable")
    }

    private fun flushLatestLocked() {
        while (true) {
            // Do not clear before the physical write. On failure the same checkpoint (or a newer
            // concurrently offered replacement) remains pending, and flush/flushThenRead propagate
            // instead of returning a stale disk read as success.
            val checkpoint = pending.get() ?: return
            writer(checkpoint.value)
            pending.compareAndSet(checkpoint, null)
        }
    }
}

/**
 * Serializes checkpoint ownership changes with the offer they authorize. Activity replacement can
 * overlap the outgoing host's final Compose effects; without this fence, that logically stale
 * callback can arrive after the replacement has restored or seeded a newer document and become the
 * latest physical write merely because it ran later on the main thread.
 */
internal class EditorSessionCheckpointOwners {
    private val lock = Any()
    private var activeOwner = 0L

    fun acquire(): Long = synchronized(lock) {
        check(activeOwner != Long.MAX_VALUE) { "editor-session owner generation exhausted" }
        ++activeOwner
    }

    fun retire() = synchronized(lock) {
        check(activeOwner != Long.MAX_VALUE) { "editor-session owner generation exhausted" }
        ++activeOwner
        Unit
    }

    fun offerIfCurrent(owner: Long, offer: () -> Unit): Boolean = synchronized(lock) {
        if (owner <= 0L || owner != activeOwner) return@synchronized false
        offer()
        true
    }
}

/**
 * Latest-only, process-owned checkpoint writer. A slider can settle repeatedly while storage is
 * slow, but only the newest immutable document is retained; no unbounded queue of multi-megabyte
 * history closures is possible. [read] shares the disk lock and flushes the pending document first,
 * so an Activity created immediately after recreation cannot observe the previous checkpoint.
 */
internal object EditorSessionCheckpointRuntime {
    private data class Pending(
        val context: Context,
        val document: EditorSessionDocument,
    )

    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers.io)
    private val owners = EditorSessionCheckpointOwners()
    private val coordinator = LatestCheckpointCoordinator<Pending>(writer = { checkpoint ->
        val retained = retainEncodableEditorSession(checkpoint.document)
        EditorSessionStore.writeEncoded(checkpoint.context, retained.encoded)
    })

    init {
        scope.launch {
            for (ignored in signal) {
                try {
                    coordinator.flush()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // The coordinator retains the failed/newest value. A future checkpoint signal,
                    // explicit flush or restoration read retries it; never log document contents.
                    Diag.w("editor session save failed (${failure.javaClass.simpleName})")
                }
            }
        }
    }

    /** Acquires the sole checkpoint lease for a newly composed Activity host. */
    fun acquireOwner(): Long = owners.acquire()

    /**
     * Invalidates every existing host before a direct fixture/recovery replacement is written.
     * Callers must then [flush] the already accepted slot before installing that replacement.
     */
    fun retireOwners() = owners.retire()

    fun checkpoint(context: Context, document: EditorSessionDocument, owner: Long): Boolean {
        val accepted = owners.offerIfCurrent(owner) {
            coordinator.offer(Pending(context.applicationContext, document))
        }
        if (!accepted) return false
        if (signal.trySend(Unit).isFailure) {
            Diag.w("editor session checkpoint signal was rejected")
        }
        return true
    }

    /** Call from an IO dispatcher. */
    fun read(context: Context): EditorSessionReadResult = try {
        coordinator.flushThenRead { EditorSessionStore.read(context.applicationContext) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // A pending checkpoint could not become durable. Returning Unavailable is explicit and
        // prevents startup from treating the older on-disk generation as a successful restore.
        EditorSessionReadResult.Unavailable(
            "pending editor-session checkpoint is unavailable (${failure.javaClass.simpleName})",
        )
    }

    /** Call from an IO dispatcher before consuming an exactly-once terminal result. */
    fun flush() = coordinator.flush()
}
