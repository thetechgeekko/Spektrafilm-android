/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Output sharpening for 8-bit exports: a light unsharp mask applied ONCE to the final,
 * already-resized bitmap, just before encoding. Downscaling a full-resolution render softens
 * it; this restores some edge contrast for the size the picture will actually be seen at.
 *
 * Deliberately outside the engine: it never touches the parity path, it sees the whole image
 * after tiled rendering (no tile seams), and the 16/32-bit writers (which archive the render
 * itself) never get it. The kernel is a 3x3 binomial blur, and differences smaller than
 * [SHARPEN_THRESHOLD] are left alone so flat areas and fine grain are not turned into noise.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.roundToInt

/** How much output sharpening an export gets. */
enum class OutputSharpen(@StringRes val labelRes: Int, val amount: Float) {
    NONE(R.string.tool_export_sharpen_none, 0f),
    LOW(R.string.tool_export_sharpen_low, 0.35f),
    STANDARD(R.string.tool_export_sharpen_standard, 0.7f),
    HIGH(R.string.tool_export_sharpen_high, 1.1f),
}

/** Per-channel differences at or below this (out of 255) are not sharpened. */
internal const val SHARPEN_THRESHOLD = 2

@Suppress("NOTHING_TO_INLINE")
private inline fun ch(p: Int, shift: Int): Int = (p shr shift) and 0xFF

/**
 * Sharpen row [cur] into [out] given its neighbours [prev] and [next] (pass [cur] itself for a
 * missing neighbour at the top or bottom edge). All rows are packed ARGB of width [w]; alpha is
 * copied through untouched.
 */
internal fun sharpenRow(prev: IntArray, cur: IntArray, next: IntArray, out: IntArray, w: Int, amount: Float) {
    for (x in 0 until w) {
        val xl = if (x > 0) x - 1 else x
        val xr = if (x < w - 1) x + 1 else x
        val c = cur[x]
        var result = c and 0xFF000000.toInt()
        var shift = 16
        while (shift >= 0) {
            // 3x3 binomial: [1 2 1; 2 4 2; 1 2 1] / 16
            val blur = (
                ch(prev[xl], shift) + 2 * ch(prev[x], shift) + ch(prev[xr], shift) +
                    2 * ch(cur[xl], shift) + 4 * ch(c, shift) + 2 * ch(cur[xr], shift) +
                    ch(next[xl], shift) + 2 * ch(next[x], shift) + ch(next[xr], shift) + 8
                ) shr 4
            val v = ch(c, shift)
            val diff = v - blur
            val sharpened = if (abs(diff) <= SHARPEN_THRESHOLD) {
                v
            } else {
                (v + amount * diff).roundToInt().coerceIn(0, 255)
            }
            result = result or (sharpened shl shift)
            shift -= 8
        }
        out[x] = result
    }
}

/**
 * Run the row kernel over an image of [w]x[h] reached through [readRow] / [writeRow], holding
 * only three ORIGINAL rows at a time. Row y is written only after row y+1 has been read, so
 * every row's neighbours are always the unsharpened source.
 */
internal inline fun sharpenRows(
    w: Int,
    h: Int,
    amount: Float,
    readRow: (y: Int, into: IntArray) -> Unit,
    writeRow: (y: Int, from: IntArray) -> Unit,
) {
    if (w <= 0 || h <= 0 || amount <= 0f) return
    var prev = IntArray(w)
    var cur = IntArray(w)
    var next = IntArray(w)
    val out = IntArray(w)
    readRow(0, cur)
    cur.copyInto(prev)
    for (y in 0 until h) {
        if (y + 1 < h) readRow(y + 1, next) else cur.copyInto(next)
        sharpenRow(prev, cur, next, out, w, amount)
        writeRow(y, out)
        val recycled = prev
        prev = cur
        cur = next
        next = recycled
    }
}

/** Sharpen a mutable [bmp] in place. A no-op for [OutputSharpen.NONE] or an immutable bitmap. */
fun sharpenBitmapInPlace(bmp: Bitmap, sharpen: OutputSharpen) {
    if (sharpen == OutputSharpen.NONE || !bmp.isMutable) return
    val w = bmp.width
    sharpenRows(
        w,
        bmp.height,
        sharpen.amount,
        readRow = { y, into -> bmp.getPixels(into, 0, w, 0, y, w, 1) },
        writeRow = { y, from -> bmp.setPixels(from, 0, w, 0, y, w, 1) },
    )
}
