/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import com.spectrafilm.engine.SpektraEngine

/**
 * Return the GPU context's grow-only buffers to the process memory budget after a
 * full-resolution render (#269).
 *
 * The Vulkan kernels keep their staging/work buffers sized for the largest frame
 * they have seen. On SM-S948W one 12.5 MP export left ~1.1 GiB of gpu_host reserved
 * afterwards, leaving the 1.5 GiB budget ~380 MiB for everything that followed, and a
 * later export in the same session failed with "native allocation failed". Releasing
 * is output-neutral (test_gpu_host gates byte-identity across a release); the cost is
 * rebuilding pipelines on the next GPU call, paid once after an export rather than on
 * every preview.
 *
 * Call from a background thread: it waits for the GPU lock if a render holds it.
 */
internal fun releaseGpuAfterFullResRender(reason: String) {
    runCatching { SpektraEngine.releaseGpuResources() }
        .onSuccess { released -> if (released) Diag.i("gpu resources released after $reason") }
        .onFailure { Diag.w("gpu resource release after $reason failed: ${it.message}") }
}

/** True for the failures a reclaim can plausibly cure: budget denial or a native/ART OOM. */
internal fun isMemoryExhaustion(failure: Throwable): Boolean =
    failure is OutOfMemoryError ||
        (failure is RuntimeException &&
            failure.message?.contains("native allocation failed") == true)

/** Engine budget snapshot for a failure log; never throws. */
internal fun memoryBudgetSnapshotForLog(): String =
    runCatching { SpektraEngine.memoryBudgetSnapshotJson() }.getOrElse { "unavailable (${it.message})" }

/**
 * Run a full-resolution render, and if it runs out of memory, reclaim once and retry once
 * (#269).
 *
 * #258's export failed with ~470 MB of GPU host memory still reserved from earlier renders and
 * ~40 MB left for the CPU fallback. [reclaim] (by default: release the GPU context's buffers,
 * which is output-neutral, then ask ART to collect) gives that memory back before the single
 * retry. A second failure is thrown with the first attached as suppressed, and both are
 * logged with the engine's budget snapshot so a report carries it without a diagnostics dump.
 * Cancellation is never retried.
 */
internal suspend fun <T> retryOnceAfterMemoryReclaim(
    reason: String,
    reclaim: () -> Unit = {
        releaseGpuAfterFullResRender("$reason out of memory")
        Runtime.getRuntime().gc()
    },
    log: (String) -> Unit = { Diag.w(it) },
    snapshot: () -> String = ::memoryBudgetSnapshotForLog,
    block: suspend () -> T,
): T {
    val first = try {
        return block()
    } catch (failure: Throwable) {
        if (failure is kotlinx.coroutines.CancellationException || !isMemoryExhaustion(failure)) {
            throw failure
        }
        failure
    }
    log("$reason ran out of memory (${first.message}); reclaiming and retrying once; budget=${snapshot()}")
    reclaim()
    return try {
        block()
    } catch (second: Throwable) {
        if (second !== first) second.addSuppressed(first)
        if (isMemoryExhaustion(second)) {
            log("$reason retry also ran out of memory (${second.message}); budget=${snapshot()}")
        }
        throw second
    }
}
