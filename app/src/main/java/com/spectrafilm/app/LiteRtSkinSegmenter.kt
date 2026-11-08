/*
 * Spektrafilm for Android — the on-device skin finder, on Google's LiteRT runtime. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Runs the Selfie Multiclass model (fetched by [SkinModelStore]) on the CPU. On the test phone a run
 * takes ~50 ms in a release build; the GPU delegate is faster per run but costs over a second to set
 * up, so it is not shipped (docs/MASKING_SPEC.md §5). Everything around the model call lives in
 * [SkinSegmentation] and is tested on the JVM; this class only moves buffers in and out of LiteRT.
 *
 * Any failure (model not downloadable, runtime missing for this ABI, inference error) returns null,
 * which leaves every skin mask inert instead of wrong.
 */
package com.spectrafilm.app

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.spectrafilm.app.masks.Letterbox
import com.spectrafilm.app.masks.SemanticField
import com.spectrafilm.app.masks.SkinModelSpec
import com.spectrafilm.app.masks.SkinSegmentation
import com.spectrafilm.app.masks.SkinSegmenter
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LiteRtSkinSegmenter(
    private val store: SkinModelStore,
    private val dispatcher: CoroutineDispatcher,
) : SkinSegmenter {

    private class Loaded(
        val model: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
    )

    private val lock = Mutex()
    private var loaded: Loaded? = null
    private val input = FloatArray(SkinModelSpec.INPUT_SIZE * SkinModelSpec.INPUT_SIZE * 3)

    override suspend fun segment(argb: IntArray, width: Int, height: Int): SemanticField? =
        withContext(dispatcher) {
            lock.withLock {
                runCatching {
                    val model = load() ?: return@runCatching null
                    coroutineContext.ensureActive()
                    val box = Letterbox.fit(width, height)
                    SkinSegmentation.fillInput(argb, box, input)
                    model.inputs[0].writeFloat(input)
                    model.model.run(model.inputs, model.outputs)
                    val coarse = SkinSegmentation.toSkinField(model.outputs[0].readFloat(), box)
                    coroutineContext.ensureActive()
                    val (guide, gw, gh) = guideFor(argb, width, height)
                    SkinSegmentation.refine(coarse, guide, gw, gh)
                }.onFailure { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Diag.w("skin segmentation failed: ${t.javaClass.simpleName}")
                }.getOrNull()
            }
        }

    /**
     * Release the model and its buffers. Takes the same lock as [segment], so it waits for a running
     * inference to finish instead of freeing native memory under it. A later [segment] loads them again.
     */
    suspend fun close() = lock.withLock {
        val l = loaded ?: return@withLock
        loaded = null
        l.inputs.forEach { it.close() }
        l.outputs.forEach { it.close() }
        l.model.close()
    }

    private suspend fun load(): Loaded? {
        loaded?.let { return it }
        val file = store.ensure() ?: return null
        val model = CompiledModel.create(file.absolutePath, CompiledModel.Options(Accelerator.CPU), null)
        return Loaded(model, model.createInputBuffers(), model.createOutputBuffers()).also { loaded = it }
    }

    /**
     * Luma guide for the edge refine, at most [MAX_GUIDE] px on the long side (nearest sampling):
     * past that the refine costs more than it shows, and the field is bilinear-sampled anyway.
     */
    private fun guideFor(argb: IntArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
        val long = max(w, h)
        if (long <= MAX_GUIDE) return Triple(SkinSegmentation.luma(argb), w, h)
        val gw = max(1, (w.toLong() * MAX_GUIDE / long).toInt())
        val gh = max(1, (h.toLong() * MAX_GUIDE / long).toInt())
        val small = IntArray(gw * gh)
        for (y in 0 until gh) {
            val sy = ((y + 0.5f) * h / gh).toInt().coerceIn(0, h - 1)
            for (x in 0 until gw) {
                val sx = ((x + 0.5f) * w / gw).toInt().coerceIn(0, w - 1)
                small[y * gw + x] = argb[sy * w + sx]
            }
        }
        return Triple(SkinSegmentation.luma(small), gw, gh)
    }

    private companion object {
        const val MAX_GUIDE = 1024
    }
}
