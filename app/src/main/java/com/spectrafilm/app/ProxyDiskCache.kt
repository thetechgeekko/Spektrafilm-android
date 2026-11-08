/*
 * Spektrafilm for Android — on-disk cache of the decoded preview proxy (#288). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.spectrafilm.engine.LinearImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Persists the bounded linear preview proxy (the [LinearImage] the preview and zoom paths feed
 * the engine) in `cacheDir`, so reopening a recent source, or coming back after process death,
 * skips LibRaw / the platform codec, the linearisation and the rotation.
 *
 * The cached bytes are the decoded float32 buffer itself, not a re-encoding, so a hit hands the
 * engine exactly what a fresh decode would have: the render is unchanged.
 *
 * Identity. The key covers every decode-affecting input ([DecodedSourceCache.Request], which
 * already includes the target edge, rotation and every pre-engine white balance), the source's
 * bytes (size, provider last-modified, and SHA-256 of its first and last 64 KiB), the cache
 * format, and the installed app build (version code + install/update time, so a reinstall that
 * changes the decoder can never serve an old decode). A source whose identity cannot be read is
 * not cached at all.
 *
 * Bounded: an LRU by file mtime, trimmed to [MAX_TOTAL_BYTES] after each write. Only proxies at
 * or below [MAX_EDGE_PX] are cached; full-resolution export decodes never are. Every failure is
 * a miss: a corrupt, truncated or foreign file is deleted and the source decoded normally.
 */
internal object ProxyDiskCache {
    internal const val FORMAT_VERSION = 1
    private const val DIR_NAME = "proxy-cache-v$FORMAT_VERSION"
    internal const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    private const val IDENTITY_SAMPLE_BYTES = 64 * 1024

    class Hit(val image: LinearImage, val usedPlatformFallback: Boolean)

    /** Cache key for [request] on [uri], or null when this decode must not be cached. */
    fun keyFor(ctx: Context, uri: Uri, request: DecodedSourceCache.Request): String? {
        if (request.authorizationRequired || request.maxEdge > MAX_EDGE_PX) return null
        val identity = sourceIdentity(ctx, uri) ?: return null
        val app = appBuildIdentity(ctx) ?: return null
        return cacheKey(request, identity, app)
    }

    fun read(ctx: Context, key: String): Hit? {
        val file = File(dir(ctx), "$key.bin")
        if (!file.isFile) return null
        val hit = runCatching { readFile(file) }.getOrNull()
        if (hit == null) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return hit
    }

    /** Store [image] under [key]. Never throws; a failed write leaves no partial file behind. */
    fun write(ctx: Context, key: String, image: LinearImage, usedPlatformFallback: Boolean) {
        val dir = dir(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) return
        val target = File(dir, "$key.bin")
        val temp = File(dir, "$key.${android.os.Process.myPid()}.${Thread.currentThread().id}.part")
        try {
            image.acquireDataLease().use { lease ->
                val header = encodeHeader(
                    ProxyHeader(image.width, image.height, image.colorSpace, usedPlatformFallback),
                )
                val payload = lease.data.duplicate().order(ByteOrder.nativeOrder())
                RandomAccessFile(temp, "rw").use { raf ->
                    raf.setLength(0)
                    val channel = raf.channel
                    while (header.hasRemaining()) channel.write(header)
                    while (payload.hasRemaining()) channel.write(payload)
                }
            }
            if (!temp.renameTo(target)) temp.delete()
        } catch (_: Throwable) {
            temp.delete()
            return
        }
        trim(dir, MAX_TOTAL_BYTES)
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, DIR_NAME)

    private fun readFile(file: File): Hit? = RandomAccessFile(file, "r").use { raf ->
        val channel = raf.channel
        val fixed = ByteBuffer.allocate(ProxyHeader.FIXED_BYTES)
        while (fixed.hasRemaining()) if (channel.read(fixed) < 0) return null
        fixed.flip()
        val csLen = ProxyHeader.colorSpaceLength(fixed) ?: return null
        val whole = ByteBuffer.allocate(ProxyHeader.FIXED_BYTES + csLen)
        fixed.rewind()
        whole.put(fixed)
        while (whole.hasRemaining()) if (channel.read(whole) < 0) return null
        whole.flip()
        val header = decodeHeader(whole) ?: return null
        val payloadBytes = header.payloadBytes()
        if (raf.length() != whole.capacity().toLong() + payloadBytes) return null
        val data = ByteBuffer.allocateDirect(payloadBytes.toInt()).order(ByteOrder.nativeOrder())
        while (data.hasRemaining()) if (channel.read(data) < 0) return null
        data.flip()
        Hit(LinearImage(data, header.width, header.height, colorSpace = header.colorSpace), header.usedPlatformFallback)
    }

    /** Delete least-recently-used entries (by mtime) until the directory fits [maxBytes]. */
    internal fun trim(dir: File, maxBytes: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        // Stale temp files from a killed write are always garbage once older than a minute.
        val now = System.currentTimeMillis()
        files.filter { it.name.endsWith(".part") && now - it.lastModified() > 60_000L }
            .forEach { it.delete() }
        val entries = files.filter { it.name.endsWith(".bin") && it.exists() }
            .sortedBy { it.lastModified() }
        var total = entries.sumOf { it.length() }
        for (entry in entries) {
            if (total <= maxBytes) break
            val size = entry.length()
            if (entry.delete()) total -= size
        }
    }

    /** Size, provider mtime and a hash of the first and last 64 KiB; null if unreadable. */
    private fun sourceIdentity(ctx: Context, uri: Uri): String? = runCatching {
        val resolver = ctx.contentResolver
        var size = -1L
        var modified = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { size = c.getLong(it) }
                for (name in arrayOf("last_modified", "date_modified")) {
                    val i = c.getColumnIndex(name)
                    if (i >= 0 && !c.isNull(i)) {
                        modified = c.getLong(i)
                        break
                    }
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val length = ch.size()
                if (size < 0) size = length
                if (length != size) return@runCatching null
                val head = ByteBuffer.allocate(minOf(length, IDENTITY_SAMPLE_BYTES.toLong()).toInt())
                ch.position(0)
                while (head.hasRemaining()) if (ch.read(head) < 0) break
                digest.update(head.array(), 0, head.position())
                val tailStart = maxOf(0L, length - IDENTITY_SAMPLE_BYTES)
                val tail = ByteBuffer.allocate((length - tailStart).toInt())
                ch.position(tailStart)
                while (tail.hasRemaining()) if (ch.read(tail) < 0) break
                digest.update(tail.array(), 0, tail.position())
            }
        } ?: return@runCatching null
        if (size <= 0) return@runCatching null
        "size=$size;mtime=$modified;sample=${digest.digest().toHex()}"
    }.getOrNull()

    private fun appBuildIdentity(ctx: Context): String? = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "app=$code@${info.lastUpdateTime}"
    }.getOrNull()

    /** Pure: the canonical key for one decode request on one source in one app build. */
    internal fun cacheKey(request: DecodedSourceCache.Request, sourceIdentity: String, appIdentity: String): String {
        val canonical = buildString {
            append("format=").append(FORMAT_VERSION).append('\n')
            append(appIdentity).append('\n')
            append(sourceIdentity).append('\n')
            append("uri=").append(request.uri).append('\n')
            append("kind=").append(request.kind).append('\n')
            append("wb=").append(request.whiteBalance.name).append('\n')
            append("temp=").append(request.temperature.toRawBits()).append('\n')
            append("tint=").append(request.tint.toRawBits()).append('\n')
            append("ctemp=").append(request.creativeTemp.toRawBits()).append('\n')
            append("ctint=").append(request.creativeTint.toRawBits()).append('\n')
            append("film=").append(request.filmBalance).append('\n')
            append("rot=").append(request.rotationDegrees).append('\n')
            append("edge=").append(request.maxEdge).append('\n')
            // #259 clean-up lines only when on: every pre-#259 proxy keeps its key.
            if (request.hotPixels) append("hot=1").append('\n')
            if (request.chromaNoise > 0f) {
                append("cnr=").append(request.chromaNoise.toRawBits()).append('\n')
            }
            // Straighten likewise only when non-zero.
            if (request.straightenDegrees != 0f) {
                append("str=").append(request.straightenDegrees.toRawBits()).append('\n')
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return hash.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    internal fun encodeHeader(header: ProxyHeader): ByteBuffer = header.encode()

    internal fun decodeHeader(buffer: ByteBuffer): ProxyHeader? = ProxyHeader.decode(buffer)
}

/**
 * The cache file header: magic, format, dimensions, flags, colour-space name, byte order.
 * The float32 payload follows immediately and must be exactly width*height*3*4 bytes.
 */
internal data class ProxyHeader(
    val width: Int,
    val height: Int,
    val colorSpace: String,
    val usedPlatformFallback: Boolean,
) {
    fun payloadBytes(): Long = width.toLong() * height.toLong() * 3L * Float.SIZE_BYTES

    fun encode(): ByteBuffer {
        val cs = colorSpace.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(FIXED_BYTES + cs.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putInt(ProxyDiskCache.FORMAT_VERSION)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putInt(if (usedPlatformFallback) 1 else 0)
        buffer.putInt(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) LITTLE else BIG)
        buffer.putInt(cs.size)
        buffer.put(cs)
        buffer.flip()
        return buffer
    }

    companion object {
        private val MAGIC = "SPKPRXY\u0000".toByteArray(StandardCharsets.US_ASCII)
        private const val LITTLE = 0x4C
        private const val BIG = 0x42
        private const val MAX_COLOR_SPACE_BYTES = 256
        private const val MAX_EDGE = 1 shl 15
        const val FIXED_BYTES = 8 + 4 * 6

        /** Colour-space name length from a fixed-size prefix, or null if it is not ours. */
        fun colorSpaceLength(fixed: ByteBuffer): Int? {
            if (fixed.remaining() < FIXED_BYTES) return null
            val b = fixed.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size).also { b.get(it) }
            if (!magic.contentEquals(MAGIC)) return null
            b.position(b.position() + 4 * 5)
            val len = b.int
            return len.takeIf { it in 0..MAX_COLOR_SPACE_BYTES }
        }

        fun decode(buffer: ByteBuffer): ProxyHeader? {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (b.remaining() < FIXED_BYTES) return null
            val magic = ByteArray(MAGIC.size).also { b.get(it) }
            if (!magic.contentEquals(MAGIC)) return null
            if (b.int != ProxyDiskCache.FORMAT_VERSION) return null
            val width = b.int
            val height = b.int
            val flags = b.int
            val order = b.int
            val csLen = b.int
            if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE) return null
            if (flags !in 0..1) return null
            val native = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) LITTLE else BIG
            if (order != native) return null
            if (csLen !in 0..MAX_COLOR_SPACE_BYTES || b.remaining() != csLen) return null
            val cs = ByteArray(csLen).also { b.get(it) }
            return ProxyHeader(width, height, String(cs, StandardCharsets.UTF_8), flags == 1)
        }
    }
}
