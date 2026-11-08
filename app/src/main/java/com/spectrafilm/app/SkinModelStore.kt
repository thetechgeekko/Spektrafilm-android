/*
 * Spektrafilm for Android — on-demand download of the skin segmentation model. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The model (Google MediaPipe "Selfie Multiclass 256x256", Apache 2.0, ~16 MB) is not bundled: most
 * people never make a skin mask, and the APK is GitHub-distributed. It is fetched the first time a skin
 * mask is asked for, into no-backup app storage, under a fixed policy:
 *   - exactly one URL, a VERSIONED path on Google's model bucket (never "latest");
 *   - HTTPS, no redirects followed;
 *   - the body must be exactly [Pin.sizeBytes] long and hash to [Pin.sha256], or it is discarded.
 * Unlike the APK updater, the pinned digest IS the integrity gate here: it is compiled into the app,
 * not delivered alongside the bytes, so a changed or swapped file at that URL is rejected.
 */
package com.spectrafilm.app

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SkinModelStore(
    private val directory: File,
    private val io: CoroutineDispatcher,
    private val pin: Pin = Pin.SELFIE_MULTICLASS,
    private val warn: (String) -> Unit = Diag::w,
    private val open: (URI) -> HttpURLConnection = { uri ->
        uri.toURL().openConnection() as HttpURLConnection
    },
) {
    /** The one file this store accepts: where it comes from and the bytes it must be. */
    data class Pin(val url: String, val fileName: String, val sizeBytes: Long, val sha256: String) {
        companion object {
            val SELFIE_MULTICLASS = Pin(
                url = "https://storage.googleapis.com/mediapipe-models/image_segmenter/" +
                    "selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite",
                fileName = "selfie_multiclass_256x256.tflite",
                sizeBytes = 16_371_837L,
                sha256 = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0",
            )
        }
    }

    private val lock = Mutex()

    @Volatile
    private var verified: File? = null

    private val target: File get() = File(directory, pin.fileName)

    /** The verified model file if it is already on disk, without any network access. */
    suspend fun cached(): File? = lock.withLock { withContext(io) { verifiedOrNull() } }

    /**
     * The verified model file, downloading it first when absent or damaged. Null when the download
     * fails or is cancelled; nothing partial is ever left under the final name.
     */
    suspend fun ensure(onProgress: (Long) -> Unit = {}): File? = lock.withLock {
        withContext(io) {
            verifiedOrNull()?.let { return@withContext it }
            if (!directory.isDirectory && !directory.mkdirs()) return@withContext null
            val partial = File(directory, "${pin.fileName}.part")
            val context = coroutineContext
            val ok = runCatching {
                val connection = open(URI(pin.url))
                try {
                    UpdateDownloadPolicy.configure(connection)
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        warn("skin model download HTTP ${connection.responseCode}")
                        return@runCatching false
                    }
                    val declared = connection.contentLengthLong
                    if (declared != -1L && declared != pin.sizeBytes) return@runCatching false
                    val sha = connection.inputStream.use { body ->
                        partial.outputStream().use { out ->
                            UpdateDownloadPolicy.copyExact(
                                input = body,
                                output = out,
                                expectedSize = pin.sizeBytes,
                                keepGoing = { context.isActive },
                                onProgress = onProgress,
                            )
                        }
                    }
                    if (sha != pin.sha256) {
                        if (sha != null) warn("skin model digest mismatch")
                        return@runCatching false
                    }
                    partial.renameTo(target)
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            partial.delete()
            if (ok) target.also { verified = it } else null
        }
    }

    /** Remove the model (e.g. from a storage setting). The next [ensure] downloads it again. */
    suspend fun clear() = lock.withLock {
        withContext(io) {
            verified = null
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    private fun verifiedOrNull(): File? {
        val file = target
        if (verified == file && file.length() == pin.sizeBytes) return file
        verified = null
        if (!file.isFile || file.length() != pin.sizeBytes) return null
        if (sha256(file) != pin.sha256) {
            warn("skin model on disk is damaged; discarding")
            file.delete()
            return null
        }
        verified = file
        return file
    }

    companion object {
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
