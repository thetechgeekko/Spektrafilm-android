/*
 * Spektrafilm for Android — ticket #139 engine memory signature. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import com.spectrafilm.engine.SpektraEngine
import org.json.JSONObject

/** The engine's current-bytes accounting the #139 completed-preview probe compares across phases. */
internal data class MemoryCurrentSignature(
    val totalBytes: Long,
    val domainBytes: List<Long>,
    val stageBytes: List<Long>,
)

/** Reads [MemoryCurrentSignature] from the engine's memory budget snapshot. */
internal fun currentMemorySignature(): MemoryCurrentSignature {
    val root = JSONObject(SpektraEngine.memoryBudgetSnapshotJson())
    fun currentBytes(arrayName: String): List<Long> {
        val values = root.getJSONArray(arrayName)
        return List(values.length()) { index ->
            values.getJSONObject(index).getLong("current_bytes")
        }
    }
    return MemoryCurrentSignature(
        totalBytes = root.getJSONObject("total").getLong("current_bytes"),
        domainBytes = currentBytes("domains"),
        stageBytes = currentBytes("stages"),
    )
}
