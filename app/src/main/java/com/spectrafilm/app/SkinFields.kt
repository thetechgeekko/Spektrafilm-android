/*
 * Spektrafilm for Android — the skin field for the photo being edited. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A skin mask stores only intent (MaskComponent.Semantic). This holder owns the one thing that turns
 * intent into pixels: the segmenter's skin probabilities for the CURRENT photo, in source-photo space
 * (after orientation and rotation, before crop). The editor activates the photo it shows, asks for the
 * field when a skin mask exists, and every render site binds its adjustments through
 * [withSkinField] with the map from the frame it renders back to the source photo.
 *
 * Nothing here runs unless a skin mask exists: the model is downloaded and loaded on first use only.
 * While the field is missing (downloading, finding, failed, no person) a skin mask selects nothing.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spectrafilm.app.masks.FrameMap
import com.spectrafilm.app.masks.LocalAdjustment
import com.spectrafilm.app.masks.SemanticClass
import com.spectrafilm.app.masks.SemanticField
import com.spectrafilm.app.masks.bindSemantic
import com.spectrafilm.app.masks.hasSemantic
import com.spectrafilm.engine.IoParams
import com.spectrafilm.engine.LinearImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What identifies the pixels a field was found on: the photo and how it is turned, by quarter
 * turns and by the free straighten angle (both change where the skin sits in the decode).
 */
internal data class SkinSourceKey(
    val uri: String?,
    val kind: String,
    val rotationDegrees: Int,
    val straightenDegrees: Float = 0f,
)

/** The field for one photo, or null [field] when the finder ran and found no person. */
internal class SkinFieldEntry(
    val key: SkinSourceKey,
    val field: SemanticField?,
    /** Size of the image the field was found on (same aspect as the source photo). */
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    /** Stable identity for cache keys: equal for the same field, different otherwise. */
    val fingerprint: String = field?.fingerprint() ?: "none"
}

internal sealed interface SkinFieldStatus {
    data object Idle : SkinFieldStatus
    /** Fetching the model the first time; [fraction] in 0..1. */
    data class Downloading(val fraction: Float) : SkinFieldStatus
    data object Finding : SkinFieldStatus
    data class Ready(val found: Boolean) : SkinFieldStatus
    /** The model could not be fetched or run; [SkinFields.retry] tries again. */
    data object Unavailable : SkinFieldStatus
}

internal object SkinFields {
    private val SKIN = setOf(SemanticClass.FACE_SKIN, SemanticClass.BODY_SKIN)

    /** For the Masks panel. Written from worker threads too; Compose snapshot state allows that. */
    var status: SkinFieldStatus by mutableStateOf(SkinFieldStatus.Idle)
        private set

    /** Bumped by [retry]; the editor keys its compute effect on it. */
    var retryToken: Int by mutableIntStateOf(0)
        private set

    @Volatile private var activeKey: SkinSourceKey? = null
    @Volatile private var entry: SkinFieldEntry? = null

    private val computeLock = Mutex()
    private var segmenter: LiteRtSkinSegmenter? = null
    private var store: SkinModelStore? = null

    /**
     * The editor now shows [key]. Called during composition, before any render effect for the new
     * photo starts, so a render never binds the previous photo's skin.
     */
    fun activate(key: SkinSourceKey) {
        if (key == activeKey) return
        activeKey = key
        if (entry?.key != key) {
            entry = null
            status = SkinFieldStatus.Idle
        }
    }

    /** The field for the active photo, or null while there is none. */
    fun current(): SkinFieldEntry? = entry?.takeIf { it.key == activeKey }

    fun retry() {
        if (status == SkinFieldStatus.Unavailable) {
            status = SkinFieldStatus.Idle
            retryToken++
        }
    }

    /**
     * Find skin on the active photo [key] unless already done. [loadArgb] returns the photo as
     * display-sRGB ARGB with its size. Returns true when a NEW entry was stored (render again).
     */
    suspend fun ensure(
        context: Context,
        key: SkinSourceKey,
        dispatchers: AppDispatchers,
        loadArgb: suspend () -> Triple<IntArray, Int, Int>,
    ): Boolean = computeLock.withLock {
        if (key != activeKey) return@withLock false
        if (current() != null) return@withLock false
        val (store, segmenter) = runtime(context, dispatchers)
        try {
            if (store.cached() == null) {
                status = SkinFieldStatus.Downloading(0f)
                val total = SkinModelStore.Pin.SELFIE_MULTICLASS.sizeBytes.toFloat()
                var shownPercent = 0
                val file = store.ensure { done ->
                    val percent = (done * 100f / total).toInt().coerceIn(0, 100)
                    if (percent != shownPercent) {
                        shownPercent = percent
                        status = SkinFieldStatus.Downloading(percent / 100f)
                    }
                }
                if (file == null) {
                    status = SkinFieldStatus.Unavailable
                    return@withLock false
                }
            }
            status = SkinFieldStatus.Finding
            val (argb, w, h) = loadArgb()
            val raw = segmenter.segment(argb, w, h)
            if (raw == null) {
                status = SkinFieldStatus.Unavailable
                return@withLock false
            }
            // The presence scan and the fingerprint hash read every cell: off the main thread.
            val made = withContext(dispatchers.default) {
                SkinFieldEntry(key, raw.takeIf { it.peak(SKIN) >= PRESENCE }, w, h)
            }
            if (key != activeKey) return@withLock false
            entry = made
            status = SkinFieldStatus.Ready(made.field != null)
            true
        } catch (c: CancellationException) {
            if (status !is SkinFieldStatus.Ready) status = SkinFieldStatus.Idle
            throw c
        } catch (t: Exception) {
            Diag.w("skin field failed: ${t.javaClass.simpleName}")
            status = SkinFieldStatus.Unavailable
            false
        }
    }

    /**
     * Find skin on [image] for a one-off render outside the editor (batch export, the second
     * half-frame): not cached, no status shown. Downloads the model if needed. Null when the model
     * cannot run; an entry with a null field when no person is in the photo.
     */
    suspend fun findOn(context: Context, image: LinearImage, dispatchers: AppDispatchers = AppDispatchers()): SkinFieldEntry? {
        val (store, segmenter) = runtime(context, dispatchers)
        return try {
            if (store.ensure() == null) return null
            val (argb, w, h) = withContext(dispatchers.default) {
                downsampleLinearBox(image, FIND_EDGE).use { small ->
                    Triple(linearToDisplayArgb(small), small.width, small.height)
                }
            }
            val raw = segmenter.segment(argb, w, h) ?: return null
            withContext(dispatchers.default) {
                SkinFieldEntry(ONE_OFF, raw.takeIf { it.peak(SKIN) >= PRESENCE }, w, h)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            Diag.w("skin field (one-off) failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private val runtimeLock = Any()

    /** The model store and segmenter, made once per process. */
    private fun runtime(context: Context, dispatchers: AppDispatchers): Pair<SkinModelStore, LiteRtSkinSegmenter> =
        synchronized(runtimeLock) {
            val st = store ?: SkinModelStore(File(context.applicationContext.noBackupFilesDir, MODEL_DIR), dispatchers.io)
                .also { store = it }
            val seg = segmenter ?: LiteRtSkinSegmenter(st, dispatchers.default).also { segmenter = it }
            st to seg
        }

    private val ONE_OFF = SkinSourceKey(null, "one-off", 0)

    /** Long edge a one-off find decodes at, like the editor's preview proxy. */
    private const val FIND_EDGE = 1024

    private const val MODEL_DIR = "skinmodel"

    /** Peak skin probability a photo needs before it counts as having a person in it. */
    private const val PRESENCE = 0.5f
}

/**
 * Where the frame the engine outputs sits in the source photo. [region] is the part of the source
 * fed to the engine (normalized), [inputW]x[inputH] that input's pixel size; the engine's own crop
 * ([io]) is cut inside it exactly as the crop stage does.
 */
internal fun engineOutputFrame(
    io: IoParams,
    inputW: Int,
    inputH: Int,
    region: FrameMap = FrameMap.IDENTITY,
): FrameMap {
    if (!io.crop || inputW < 1 || inputH < 1) return region
    val box = engineCropBox(inputW, inputH, io.cropCenter, io.cropSize)
    if (box.width < 1 || box.height < 1) return region
    return region.compose(FrameMap.forCrop(inputW, inputH, box.x0, box.y0, box.width, box.height))
}

/** Just the crop part of [ParamsState]'s engine I/O, all [engineOutputFrame] reads. */
internal fun ParamsState.cropIo(): IoParams = IoParams(crop = crop, cropCenter = cropCenter, cropSize = cropSize)

/** [box] of a [srcW]x[srcH] image as a normalized region. */
internal fun regionOf(box: CropBox, srcW: Int, srcH: Int): FrameMap =
    FrameMap.forCrop(srcW, srcH, box.x0, box.y0, box.width, box.height)

/**
 * These adjustments with every skin mask bound to the active photo's field, for a render whose
 * engine input is [region] of the source photo at [inputW]x[inputH] (null: the whole photo at the
 * size the field was found on). Unchanged when no mask needs skin or no field exists yet.
 */
internal fun List<LocalAdjustment>.withSkinField(
    io: IoParams,
    inputW: Int? = null,
    inputH: Int? = null,
    region: FrameMap = FrameMap.IDENTITY,
    entry: SkinFieldEntry? = SkinFields.current(),
): List<LocalAdjustment> {
    if (none { it.mask.hasSemantic }) return this
    val e = entry ?: return this
    val field = e.field ?: return this
    val frame = engineOutputFrame(io, inputW ?: e.sourceWidth, inputH ?: e.sourceHeight, region)
    return bindSemantic(field, frame)
}

/**
 * For a render outside the editor (batch export, half-frame pairs): these adjustments with their
 * skin masks bound to skin found on [image] itself, the uncropped decode the engine is about to
 * render with [io]. Unchanged when no mask needs skin or none could be found.
 */
internal suspend fun List<LocalAdjustment>.withSkinFoundOn(
    context: Context,
    image: LinearImage,
    io: IoParams,
): List<LocalAdjustment> {
    if (none { it.mask.hasSemantic }) return this
    val entry = SkinFields.findOn(context, image) ?: return this
    return withSkinField(io, image.width, image.height, entry = entry)
}
