/*
 * Spektrafilm for Android — runtime binding for semantic (AI) mask components. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A saved [MaskComponent.Semantic] holds only intent. Before compositing, the caller binds it to a
 * [SemanticField] (the segmenter's per-class probabilities for the CURRENT photo, in source-photo
 * space) and a [FrameMap] (output frame -> source photo, i.e. the crop). The result, a
 * [BoundSemantic], is a normal [MaskComponent]: it folds, inverts and range-refines like any shape.
 * Bound components are runtime-only; serialization writes back their intent.
 *
 * Pure Kotlin, no engine touched. The segmenter that fills the field lands separately.
 */
package com.spectrafilm.app.masks

/**
 * Per-class probabilities on a [width]x[height] grid covering the whole SOURCE photo (after EXIF
 * orientation, before crop). Cell (x, y) is centred at normalized ((x+0.5)/width, (y+0.5)/height).
 * Each plane is row-major, values in 0..1.
 */
class SemanticField(
    val width: Int,
    val height: Int,
    private val planes: Map<SemanticClass, FloatArray>,
) {
    init {
        require(width > 0 && height > 0) { "semantic field dimensions must be positive" }
        val n = Math.multiplyExact(width, height)
        for ((cls, plane) in planes) require(plane.size == n) { "semantic plane $cls has ${plane.size} cells, expected $n" }
    }

    fun has(cls: SemanticClass): Boolean = cls in planes

    /** The highest summed probability of [classes] in any cell: below 0.5 means none were found. */
    fun peak(classes: Set<SemanticClass>): Float {
        val present = classes.mapNotNull { planes[it] }
        if (present.isEmpty()) return 0f
        var best = 0f
        for (i in 0 until width * height) {
            var sum = 0f
            for (p in present) sum += p[i]
            if (sum.isFinite() && sum > best) best = sum
        }
        return best.coerceIn(0f, 1f)
    }

    /** SHA-256 over the planes, so a cache key can tell two fields apart without holding them. */
    fun fingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = java.nio.ByteBuffer.allocate(4 * width * height)
        digest.update("${width}x$height".toByteArray())
        for (cls in SemanticClass.entries) {
            val plane = planes[cls] ?: continue
            digest.update(cls.wire.toByteArray())
            bytes.clear()
            bytes.asFloatBuffer().put(plane)
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Bilinear sample of the summed probability of [classes] at source-normalized ([sx], [sy]),
     * clamped to 0..1. Outside the photo, or for classes the field lacks, the probability is 0.
     */
    fun sample(classes: Set<SemanticClass>, sx: Float, sy: Float): Float {
        if (!sx.isFinite() || !sy.isFinite() || sx < 0f || sy < 0f || sx > 1f || sy > 1f) return 0f
        val fx = (sx * width - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val fy = (sy * height - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = minOf(x0 + 1, width - 1); val y1 = minOf(y0 + 1, height - 1)
        val tx = fx - x0; val ty = fy - y0
        var sum = 0f
        for (cls in classes) {
            val p = planes[cls] ?: continue
            val top = p[y0 * width + x0] * (1f - tx) + p[y0 * width + x1] * tx
            val bottom = p[y1 * width + x0] * (1f - tx) + p[y1 * width + x1] * tx
            sum += top * (1f - ty) + bottom * ty
        }
        return if (sum.isFinite()) sum.coerceIn(0f, 1f) else 0f
    }
}

/**
 * Affine map from output-frame normalized coordinates to source-photo normalized coordinates:
 * `sx = offsetX + nx * scaleX`, `sy = offsetY + ny * scaleY`. Shape masks live in the output frame;
 * a semantic field lives in the source photo, so this is what keeps a skin mask on the skin when the
 * photo is cropped (or when only a zoomed region is rendered).
 */
data class FrameMap(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
) {
    fun sourceX(nx: Float): Float = offsetX + nx * scaleX
    fun sourceY(ny: Float): Float = offsetY + ny * scaleY

    /** This map applied after [inner]: for a region (normalized in this frame) rendered on its own. */
    fun compose(inner: FrameMap): FrameMap = FrameMap(
        offsetX = offsetX + inner.offsetX * scaleX,
        offsetY = offsetY + inner.offsetY * scaleY,
        scaleX = inner.scaleX * scaleX,
        scaleY = inner.scaleY * scaleY,
    )

    companion object {
        val IDENTITY = FrameMap()

        /** The output frame is the pixel box [x],[y],[w],[h] cut from a [srcW]x[srcH] source photo. */
        fun forCrop(srcW: Int, srcH: Int, x: Int, y: Int, w: Int, h: Int): FrameMap {
            require(srcW > 0 && srcH > 0 && w > 0 && h > 0) { "crop dimensions must be positive" }
            return FrameMap(
                offsetX = x.toFloat() / srcW,
                offsetY = y.toFloat() / srcH,
                scaleX = w.toFloat() / srcW,
                scaleY = h.toFloat() / srcH,
            )
        }
    }
}

/** A [MaskComponent.Semantic] bound to this photo's [field] through [frame]. Runtime-only. */
class BoundSemantic(
    val intent: MaskComponent.Semantic,
    val field: SemanticField,
    val frame: FrameMap,
) : MaskComponent {
    override fun alphaAt(nx: Float, ny: Float): Float =
        intent.coverage(field.sample(intent.classes, frame.sourceX(nx), frame.sourceY(ny)))
}

/** True when any component needs a semantic field to select anything. */
val Mask.hasSemantic: Boolean
    get() = components.any { it.shape is MaskComponent.Semantic || it.shape is BoundSemantic }

/**
 * True when every component is semantic: the mask means the same thing on any photo (unlike a
 * radial or gradient placed on this one), so a look can carry it to other photos.
 */
val Mask.isSemanticOnly: Boolean
    get() = components.isNotEmpty() && components.all { it.shape is MaskComponent.Semantic || it.shape is BoundSemantic }

/**
 * Replace each unbound semantic component with what [bind] returns for it. A null from [bind] (field
 * not computed yet, no person found) leaves the component unbound, so it selects nothing. Returns this
 * mask unchanged when it has no semantic components.
 */
fun Mask.bindSemantic(bind: (MaskComponent.Semantic) -> BoundSemantic?): Mask {
    if (components.none { it.shape is MaskComponent.Semantic }) return this
    return copy(
        components = components.map { c ->
            val s = c.shape as? MaskComponent.Semantic ?: return@map c
            bind(s)?.let { c.copy(shape = it) } ?: c
        },
    )
}

/** Bind every semantic component in [adjustments] to one photo's [field] seen through [frame]. */
fun List<LocalAdjustment>.bindSemantic(field: SemanticField?, frame: FrameMap): List<LocalAdjustment> {
    if (field == null || none { it.mask.hasSemantic }) return this
    return map { adj -> adj.copy(mask = adj.mask.bindSemantic { BoundSemantic(it, field, frame) }) }
}
