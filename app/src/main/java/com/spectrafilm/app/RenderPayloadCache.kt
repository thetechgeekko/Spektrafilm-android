/*
 * Spektrafilm for Android — idle pre-render payload store (issue #179). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The export cache in ExportCache.kt keys FINISHED CONTAINER BYTES, so it only pays off on an
 * export the user has already performed once. This store covers the other half of #179: the
 * expensive part of a first export is decode (~0.98 s) plus the engine (~5 s on BASE, ~13.5 s
 * on HEAVY), and both are already done while the editor sits idle. Keeping the engine's float
 * output means the export still runs the real encoder, so the published bytes are produced by
 * exactly the same code as an uncached export — the pre-render can never make the file differ.
 *
 * One entry, deliberately. The user exports what is on screen; a second slot would cost another
 * ~150 MB of storage to serve an edit state they have already navigated away from.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.NativeBufferOwner
import com.spectrafilm.engine.SimResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.json.JSONObject

/** A payload buffer and the one call that gives its memory back. */
internal class OwnedPayload(val data: ByteBuffer, val release: () -> Unit)

/**
 * The production payload buffer: coordinator-admitted native memory (#176), so a 150 MB
 * restore is counted against the process ceiling and freed exactly when the restored
 * [SimResult]'s last lease closes, instead of whenever a mapping happens to be collected.
 */
internal fun nativeOwnedPayload(bytes: Long): OwnedPayload {
    val owner = NativeBufferOwner.allocate(bytes)
    val lease = try {
        owner.acquireDataLease()
    } finally {
        // The lease is the only remaining authority over the allocation.
        owner.close()
    }
    return OwnedPayload(lease.data) { lease.close() }
}

internal class RenderPayloadCache(
    private val root: File,
    private val contractVersion: String,
    private val maxAgeMillis: Long = 24L * 60 * 60 * 1000,
    private val allocate: (Long) -> OwnedPayload = ::nativeOwnedPayload,
    private val log: (String, Throwable?) -> Unit = { message, failure ->
        if (failure == null) Diag.i(message) else Diag.w("$message: $failure")
    },
) {
    private val lock = Any()

    private val payloadFile get() = File(root, "payload.bin")
    private val metaFile get() = File(root, "payload.json")

    /**
     * The stored render for [key], or null for any doubt at all. Never throws: a miss costs a
     * re-render, while a wrong hit publishes someone else's image.
     *
     * The payload is read into a private buffer of its own (never mapped), so the export can
     * grade it in place for the native writers without those writes reaching the file, and the
     * buffer is released through the returned [SimResult]'s close, not by the collector.
     */
    fun get(key: String): SimResult? = synchronized(lock) {
        runCatching { read(key) }.getOrElse { failure ->
            log("pre-render payload unreadable; re-rendering", failure)
            discard()
            null
        }
    }

    private fun read(key: String): SimResult? {
        if (!metaFile.isFile || !payloadFile.isFile) return null
        val meta = JSONObject(metaFile.readText())
        if (meta.optString("key") != key) return null
        if (meta.optString("contract") != contractVersion) return null
        if (System.currentTimeMillis() - metaFile.lastModified() > maxAgeMillis) {
            discard()
            return null
        }
        val width = meta.optInt("width")
        val height = meta.optInt("height")
        val bytes = meta.optLong("bytes")
        if (width <= 0 || height <= 0) return null
        // A payload truncated by a killed write or a full disk is a miss, not a crash.
        if (bytes != payloadFile.length() || bytes != width.toLong() * height * 3L * 4L) {
            discard()
            return null
        }
        val colorSpace = runCatching { ColorSpace.valueOf(meta.optString("color_space")) }
            .getOrNull() ?: return null
        // #176: a plain read into an explicitly owned buffer replaced a PRIVATE mapping whose
        // release callback was empty. The export grades in place, so a mapping paid the
        // copy-on-write for every page anyway; this pays one copy and gets a real release.
        // RandomAccessFile rather than FileChannel.open because minSdk is 24.
        val owned = allocate(bytes)
        try {
            val target = owned.data.duplicate().order(ByteOrder.nativeOrder())
            target.position(0)
            target.limit(bytes.toInt())
            java.io.RandomAccessFile(payloadFile, "r").use { file ->
                val channel: FileChannel = file.channel
                while (target.hasRemaining()) {
                    if (channel.read(target) < 0) break
                }
            }
            if (target.hasRemaining()) {
                // Shorter than its own metadata claimed a moment ago: treat as truncated.
                owned.release()
                discard()
                return null
            }
            return SimResult.fromExternalBuffer(owned.data, width, height, colorSpace) {
                owned.release()
            }
        } catch (failure: Throwable) {
            owned.release()
            throw failure
        }
    }

    /**
     * Store [result] under [key], replacing whatever was there. Metadata is written LAST, so a
     * process killed mid-write leaves a payload no reader will accept rather than a valid-looking
     * entry holding a partial image.
     */
    fun put(key: String, result: SimResult): Boolean = synchronized(lock) {
        runCatching { write(key, result) }.getOrElse { failure ->
            log("pre-render payload not stored", failure)
            discard()
            false
        }
    }

    private fun write(key: String, result: SimResult): Boolean {
        root.mkdirs()
        metaFile.delete()
        val staging = File(root, "payload.tmp")
        val written = result.acquireDataLease().use { lease ->
            val source = lease.data.duplicate().order(ByteOrder.nativeOrder())
            source.position(0)
            source.limit(result.width * result.height * 3 * 4)
            // FileOutputStream truncates on open and needs no java.nio.file (API 26).
            java.io.FileOutputStream(staging).use { out ->
                var total = 0L
                while (source.hasRemaining()) total += out.channel.write(source)
                total
            }
        }
        if (!staging.renameTo(payloadFile)) {
            staging.delete()
            return false
        }
        metaFile.writeText(
            JSONObject()
                .put("key", key)
                .put("contract", contractVersion)
                .put("width", result.width)
                .put("height", result.height)
                .put("bytes", written)
                .put("color_space", result.colorSpace.name)
                .toString()
        )
        return true
    }

    fun discard() = synchronized(lock) {
        metaFile.delete()
        payloadFile.delete()
        File(root, "payload.tmp").delete()
        Unit
    }

    /**
     * Whether [key] is already stored, without mapping it. The idle pre-render asks this before
     * spending ~6 s of engine time, so it must not pay for a 150 MB mapping to find out.
     */
    fun holds(key: String): Boolean = synchronized(lock) {
        runCatching {
            metaFile.isFile && payloadFile.isFile &&
                JSONObject(metaFile.readText()).let {
                    it.optString("key") == key && it.optString("contract") == contractVersion
                }
        }.getOrDefault(false)
    }

    /** Bytes currently held, for diagnostics. */
    fun sizeBytes(): Long = if (payloadFile.isFile) payloadFile.length() else 0L
}

internal object RenderPayloads {
    private const val DIR = "prerender"
    @Volatile private var instance: RenderPayloadCache? = null

    /** How long the editor must sit still before speculating a full-resolution render. */
    const val IDLE_DEBOUNCE_MS = 5_000L

    /**
     * Whether speculative work is appropriate right now. A pre-render costs ~6 s of all-core
     * engine time and ~1.5 GB of peak RSS for an export the user may never ask for, so it is
     * skipped on a hot, memory-pressured or power-saving device — where the cost is real and the
     * saving is hypothetical.
     */
    fun shouldPrerender(context: android.content.Context): Boolean {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        if (power?.isPowerSaveMode == true) return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            (power?.currentThermalStatus ?: 0) > android.os.PowerManager.THERMAL_STATUS_LIGHT
        ) {
            return false
        }
        val activity = context.getSystemService(android.app.ActivityManager::class.java)
            ?: return true
        val info = android.app.ActivityManager.MemoryInfo()
        activity.getMemoryInfo(info)
        return !info.lowMemory
    }

    fun of(context: android.content.Context): RenderPayloadCache {
        val app = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: RenderPayloadCache(
                root = File(app.cacheDir, DIR),
                contractVersion = ExportCaches.contractVersionOf(app),
            ).also { instance = it }
        }
    }

    /**
     * The pre-render key. Deliberately NOT the export cache key: this payload is pre-encode, so
     * it is independent of container, quality, resize and the post-engine grade — the same
     * payload serves a JPEG and a TIFF of the same edit.
     */
    fun key(
        source: ExportCacheKey.Source,
        params: com.spectrafilm.engine.SpektraParams,
        engineColorSpace: ColorSpace?,
        contractVersion: String,
    ): String = ExportCacheKey.sha256Hex(
        buildString {
            append("prerender-v1\n")
            append("source=").append(source.contentSha256).append('\n')
            append("bytes=").append(source.bytes).append('\n')
            append("edge=").append(source.decodeMaxEdge).append('\n')
            append("decode=").append(source.decodeIdentity).append('\n')
            append("params=").append(params.toString()).append('\n')
            append("engine_cs=").append(engineColorSpace?.name ?: "null").append('\n')
            append("contract=").append(contractVersion).append('\n')
        }.toByteArray(Charsets.UTF_8)
    )
}
