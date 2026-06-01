/*
 * Spektrafilm for Android — MotionCam .mcraw container pre-flight parser. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A pure-Kotlin reader for the MotionCam (.mcraw) RAW-video container, reverse-
 * engineered from the public Apache-2.0 decoder (mirsadm/motioncam-decoder); see
 * docs/RESEARCH_MCRAW.md. This does NOT decode pixels — the frame payloads use
 * MotionCam's custom lossless codec, which is decoded natively by a vendored lib in
 * the import path. This parser validates a file is a real .mcraw and lists its frame
 * timestamps so the UI can pick a frame (and reject junk) before paying for a native
 * decode.
 *
 * On-disk layout (CONTAINER_VERSION 3, native little-endian):
 *   Header        { uint8 ident[7]="MOTION "; uint8 version=3 }
 *   Item          { int32 type; uint32 size }                       // precedes each chunk
 *   ... camera METADATA chunk, then BUFFER/METADATA chunks per frame ...
 *   <index data>  BufferOffset[] { int64 offset; int64 timestamp }  // at indexDataOffset
 *   <footer>      Item{ type=BUFFER_INDEX } + BufferIndex
 *   BufferIndex   { int32 magicNumber=0x8A905612; int32 numOffsets; int64 indexDataOffset }
 * The decoder locates the index by seeking to EOF-(sizeof(Item)+sizeof(BufferIndex)).
 */
package com.spectrafilm.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thrown when a buffer is not a valid/supported .mcraw container. */
class McrawParseException(message: String) : Exception(message)

/** One video frame entry from the container index. */
data class McrawFrame(val timestampNs: Long, val offset: Long)

object McrawContainer {

    /** Header ident: 'M','O','T','I','O','N',' ' (CONTAINER_ID). */
    private val IDENT = byteArrayOf(0x4D, 0x4F, 0x54, 0x49, 0x4F, 0x4E, 0x20)
    const val VERSION = 3
    const val INDEX_MAGIC_NUMBER = 0x8A905612.toInt()

    /** Item.type enum ordinal for the trailing BUFFER_INDEX chunk. */
    private const val TYPE_BUFFER_INDEX = 0

    private const val HEADER_SIZE = 8        // ident[7] + version[1]
    private const val ITEM_SIZE = 8          // int32 type + uint32 size
    private const val BUFFER_INDEX_SIZE = 16 // int32 magic + int32 num + int64 offset
    private const val BUFFER_OFFSET_SIZE = 16 // int64 offset + int64 timestamp
    private const val FOOTER_SIZE = ITEM_SIZE + BUFFER_INDEX_SIZE

    /** Cheap extension check for routing a picked file. */
    fun isMcrawFileName(name: String): Boolean =
        name.substringAfterLast('.', "").equals("mcraw", ignoreCase = true)

    /**
     * Validate [data] as an .mcraw container and return its frame index (ordered as
     * stored). Throws [McrawParseException] on any structural problem.
     *
     * [data] must be the whole file. The container is addressable only up to 2 GiB
     * here (ByteBuffer positions are Int); real clips fit a single still per frame so
     * the pre-flight read is cheap, but a fd/streaming native reader owns the actual
     * decode (see docs/RESEARCH_MCRAW.md).
     */
    fun parse(data: ByteArray): List<McrawFrame> {
        if (data.size < HEADER_SIZE + FOOTER_SIZE) {
            throw McrawParseException("too small to be an .mcraw (${data.size} bytes)")
        }
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // --- Header ---
        val ident = ByteArray(7).also { bb.get(it) }
        if (!ident.contentEquals(IDENT)) throw McrawParseException("bad container magic")
        val version = bb.get().toInt() and 0xFF
        if (version != VERSION) {
            throw McrawParseException("unsupported container version $version (want $VERSION)")
        }

        // --- Footer: trailing Item{BUFFER_INDEX} + BufferIndex ---
        bb.position(data.size - FOOTER_SIZE)
        val itemType = bb.int
        bb.int // Item.size (unused for indexing)
        if (itemType != TYPE_BUFFER_INDEX) {
            throw McrawParseException("missing trailing buffer index (type=$itemType)")
        }
        val magic = bb.int
        if (magic != INDEX_MAGIC_NUMBER) throw McrawParseException("bad index magic number")
        val numOffsets = bb.int
        val indexDataOffset = bb.long
        if (numOffsets < 0) throw McrawParseException("negative frame count")
        val indexEnd = indexDataOffset + numOffsets.toLong() * BUFFER_OFFSET_SIZE
        if (indexDataOffset < HEADER_SIZE || indexEnd > data.size - FOOTER_SIZE) {
            throw McrawParseException("index out of bounds")
        }

        // --- Frame index: BufferOffset[] ---
        bb.position(indexDataOffset.toInt())
        val frames = ArrayList<McrawFrame>(numOffsets)
        repeat(numOffsets) {
            val offset = bb.long
            val timestamp = bb.long
            frames.add(McrawFrame(timestampNs = timestamp, offset = offset))
        }
        return frames
    }

    /** True if [data] looks like a parseable .mcraw (no throw). */
    fun isMcraw(data: ByteArray): Boolean = runCatching { parse(data) }.isSuccess
}
