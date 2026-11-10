/*
 * Spektrafilm for Android — memory admission for full-size RAW export decodes. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.app.ActivityManager
import android.content.Context
import com.spectrafilm.engine.MemoryBudgetStage
import com.spectrafilm.engine.SpektraEngine
import org.json.JSONObject

/**
 * Bytes a full-size RAW export decode may use: what the system can give without dropping below
 * its own low-memory threshold (plus a margin), and what the process MemoryBudget has free.
 * The native decoder turns them into a pixel limit at its measured peak bytes per pixel
 * (raw_decode_budget.h), instead of the fixed free-memory tiers that refused 41-48 MP
 * panoramas on a 12 GB phone with ~2.2 GB available.
 */
internal fun rawExportAdmissionBytes(availMem: Long, lowMemoryThreshold: Long, budgetFree: Long): Long {
    val system = availMem - lowMemoryThreshold - RAW_ADMISSION_SAFETY_BYTES
    return maxOf(0L, minOf(system, budgetFree))
}

/** The admitted bytes, held in the process MemoryBudget until the decode returns. */
internal class RawDecodeAdmission(val bytes: Long, private val reservation: AutoCloseable?) : AutoCloseable {
    override fun close() {
        reservation?.close()
    }
}

/**
 * Admits a full-size RAW export decode. The admitted bytes are reserved in the MemoryBudget
 * for the decode's duration; a denied reservation halves down to the smallest useful size, and
 * below that the decoder keeps its own free-memory tiers (bytes == 0).
 */
internal fun admitRawExportDecode(
    context: Context,
    reserve: (Long) -> AutoCloseable = { JvmMemoryReservation.acquire(it, MemoryBudgetStage.DECODE) },
): RawDecodeAdmission {
    val info = ActivityManager.MemoryInfo()
    context.applicationContext.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
    var bytes = rawExportAdmissionBytes(info.availMem, info.threshold, memoryBudgetFreeBytes())
    while (bytes >= RAW_ADMISSION_MIN_BYTES) {
        val held = runCatching { reserve(bytes) }.getOrNull()
        if (held != null) {
            Diag.i(
                "raw export decode admitted ${bytes / MIB} MiB (avail=${info.availMem / MIB} MiB " +
                    "threshold=${info.threshold / MIB} MiB)",
            )
            return RawDecodeAdmission(bytes, held)
        }
        bytes /= 2
    }
    Diag.w("raw export decode admission below ${RAW_ADMISSION_MIN_BYTES / MIB} MiB; decoder tiers apply")
    return RawDecodeAdmission(0L, null)
}

/** Free bytes in the process MemoryBudget, or [Long.MAX_VALUE] when it cannot be read. */
internal fun memoryBudgetFreeBytes(snapshotJson: String? = null): Long = runCatching {
    val json = JSONObject(snapshotJson ?: SpektraEngine.memoryBudgetSnapshotJson())
    val limit = json.getLong("limit_bytes")
    val current = json.getJSONObject("total").getLong("current_bytes")
    maxOf(0L, limit - current)
}.getOrDefault(Long.MAX_VALUE)

private const val MIB = 1_048_576L

/** Kept free above the system's low-memory threshold, so other apps are not pushed out. */
internal const val RAW_ADMISSION_SAFETY_BYTES = 256L * MIB

/** The smallest admission worth passing: the decoder's lowest 64-bit tier (12 MiP x 28 B). */
internal const val RAW_ADMISSION_MIN_BYTES = 336L * MIB
