package com.spectrafilm.libraw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPrecisionDescriptorTest {

    @Test
    fun `native v1 words retain levels layout and explicit HDR presence`() {
        val words = validWords()
        val reals = floatArrayOf(-0.5f, 0.75f)
        val descriptor = RawPrecisionDescriptor.fromNative(words, reals)

        assertEquals(RawSampleFormat.UNSIGNED_INTEGER, descriptor.sampleFormat)
        assertEquals(14, descriptor.declaredBitsPerSample)
        assertEquals(14, descriptor.effectiveBitsPerSample)
        assertEquals(16, descriptor.processedBitsPerSample)
        assertEquals(RawByteOrder.LITTLE_ENDIAN, descriptor.byteOrder)
        assertEquals(RawPacking.TIFF_PACKED_BITS, descriptor.packing)
        assertEquals(RawPixelLayout.BAYER_2X2, descriptor.pixelLayout)
        assertEquals(2, descriptor.cfaPatternRows)
        assertEquals(2, descriptor.cfaPatternColumns)
        assertEquals(listOf(0, 1, 1, 2), descriptor.cfaPattern)
        assertEquals(64L, descriptor.blackLevelCommon)
        assertEquals(listOf(0L, 0L, 0L, 0L), descriptor.blackLevelChannels)
        assertEquals(listOf(16000L, 16000L, 16000L, 16000L), descriptor.whiteLevels)
        assertEquals(-0.5f, descriptor.baselineExposure)
        assertEquals(0.75f, descriptor.linearResponseLimit)
        assertEquals(1, descriptor.containerCompression)
        assertEquals(RawDecoderRoute.LIBRAW_NATIVE, descriptor.decoderRoute)
        assertEquals(RawLinearSpace.LINEAR_PROPHOTO_RGB, descriptor.linearSpace)
        assertEquals(false, descriptor.halfSizeRequested)
        assertNull(descriptor.requestedMaxLongEdge)
        assertEquals(1, descriptor.outputSubsampleStep)

        // Published collections are detached from the mutable JNI carrier.
        words[93] = 3
        assertEquals(listOf(0, 1, 1, 2), descriptor.cfaPattern)
    }

    @Test
    fun `rounded fractional DNG black level provenance crosses JNI (#271)`() {
        val descriptor = RawPrecisionDescriptor.fromNative(
            validWords().apply { this[14] = 4 },
            floatArrayOf(-0.5f, 0.75f),
        )
        assertEquals(RawLevelProvenance.DNG_METADATA_ROUNDED, descriptor.blackLevelProvenance)
        assertEquals(64L, descriptor.blackLevelCommon)
    }

    @Test
    fun `three-plane LinearRaw derives levels from three active channels only`() {
        val descriptor = RawPrecisionDescriptor.fromNative(
            validWords().apply {
                this[2] = 12
                this[3] = 2
                this[7] = RawPixelLayout.LINEAR.code
                this[8] = 3
                this[9] = 0
                this[16] = 0
                this[17] = 4092
                this[18] = 4093
                this[19] = 4094
                this[20] = 0 // Fixed JNI carrier padding, not a sensor plane.
                repeat(4) { channel -> this[88 + channel] = 4095 }
                this[92] = 0
                repeat(4) { cell -> this[93 + cell] = 0 }
                this[134] = 0
                this[135] = 0
            },
            floatArrayOf(-0.5f, 0.75f),
        )

        assertEquals(2, descriptor.effectiveBitsPerSample)
        assertEquals(listOf(4092L, 4093L, 4094L), descriptor.blackLevelChannels)
        assertEquals(listOf(4095L, 4095L, 4095L), descriptor.whiteLevels)
    }

    @Test
    fun `absent HDR fields remain distinct from a real zero exposure`() {
        val absent = RawPrecisionDescriptor.fromNative(
            validWords().apply {
                this[129] = 0
                this[130] = 0
            },
            floatArrayOf(0.0f, 0.0f),
        )
        assertNull(absent.baselineExposure)
        assertNull(absent.linearResponseLimit)

        val explicitZero = RawPrecisionDescriptor.fromNative(
            validWords(),
            floatArrayOf(0.0f, 1.0f),
        )
        assertEquals(0.0f, explicitZero.baselineExposure)
        assertEquals(1.0f, explicitZero.linearResponseLimit)
    }

    @Test
    fun `descriptor ABI fails closed on shape route and float-source ambiguity`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(IntArray(135), FloatArray(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply { this[10] = RawDecoderRoute.PLATFORM_DISPLAY_REFERRED.code },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply { this[1] = RawSampleFormat.FLOATING_POINT.code },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply {
                    this[2] = 7
                    this[3] = 7
                },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply {
                    this[21] = 8
                    this[22] = 8
                    this[23] = 63
                },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply {
                    this[21] = 1
                    this[22] = 64
                    this[23] = 64
                },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply { this[134] = 8 },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply { this[88] = 17000 },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply { this[3] = 13 },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply { this[4] = 8 },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply { this[6] = RawPacking.LOSSLESS_COMPRESSED.code },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply { this[133] = 2 },
                floatArrayOf(-0.5f, 0.75f),
            )
        }
    }

    @Test
    fun `public fallback factory can only describe display-referred output`() {
        val fallback = RawPrecisionDescriptor.forPlatformDisplayReferredFallback("Display P3")

        assertEquals(RawDecoderRoute.PLATFORM_DISPLAY_REFERRED, fallback.decoderRoute)
        assertEquals(RawPostprocessRoute.PLATFORM_DISPLAY_REFERRED, fallback.postprocessRoute)
        assertEquals(RawLinearSpace.DISPLAY_REFERRED, fallback.linearSpace)
        assertEquals("Display P3", fallback.displayColorSpace)
        assertNull(fallback.declaredBitsPerSample)
        assertTrue(fallback.whiteLevels.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.forPlatformDisplayReferredFallback(" ")
        }
    }

    @Test
    fun `lossy-JPEG LinearRaw crosses JNI as LOSSY_COMPRESSED in the 16-bit decoder domain`() {
        val descriptor = RawPrecisionDescriptor.fromNative(
            lossyWords(),
            floatArrayOf(-0.5f, 0.75f),
        )
        assertEquals(RawPacking.LOSSY_COMPRESSED, descriptor.packing)
        assertTrue(descriptor.isLossy)
        assertEquals(0x884C, descriptor.containerCompression)
        assertEquals(16, descriptor.declaredBitsPerSample)
        assertEquals(16, descriptor.effectiveBitsPerSample)
        assertEquals(RawPixelLayout.LINEAR, descriptor.pixelLayout)
        assertEquals(listOf(65535L, 65535L, 65535L), descriptor.whiteLevels)
        assertEquals(RawLevelProvenance.DECODER_METADATA, descriptor.whiteLevelProvenance)
        assertEquals(false, RawPrecisionDescriptor.fromNative(validWords(), floatArrayOf(-0.5f, 0.75f)).isLossy)
    }

    @Test
    fun `lossy packing is refused outside its exact contract`() {
        val reals = floatArrayOf(-0.5f, 0.75f)
        val violations = listOf<IntArray.() -> Unit>(
            { this[131] = 7 },                                       // not 0x884C
            { this[2] = 8; this[3] = 8; repeat(3) { this[88 + it] = 255 } }, // container bits
            { this[13] = RawLevelProvenance.DNG_METADATA.code },     // white not decoder's
            { this[16] = 16; this[3] = 16 },                         // non-zero black
            { this[6] = RawPacking.LOSSLESS_COMPRESSED.code; this[131] = 1 }, // mismatch
        )
        violations.forEach { mutate ->
            assertThrows(IllegalArgumentException::class.java) {
                RawPrecisionDescriptor.fromNative(lossyWords().apply(mutate), reals)
            }
        }
        // 0x884C may never be labelled lossless.
        assertThrows(IllegalArgumentException::class.java) {
            RawPrecisionDescriptor.fromNative(
                validWords().apply {
                    this[6] = RawPacking.TIFF_PACKED_BITS.code
                    this[131] = 0x884C
                },
                reals,
            )
        }
    }

    private fun lossyWords(): IntArray = validWords().apply {
        this[2] = 16
        this[3] = 16
        this[6] = RawPacking.LOSSY_COMPRESSED.code
        this[7] = RawPixelLayout.LINEAR.code
        this[8] = 3
        this[9] = 0
        this[13] = RawLevelProvenance.DECODER_METADATA.code
        this[14] = RawLevelProvenance.DECLARED_BITS_DEFAULT.code
        this[16] = 0
        repeat(4) { channel -> this[88 + channel] = if (channel < 3) 65535 else 0 }
        this[92] = 0
        repeat(4) { this[93 + it] = 0 }
        this[131] = 0x884C
        this[134] = 0
        this[135] = 0
    }

    private fun validWords(): IntArray = IntArray(136).apply {
        this[0] = RawPrecisionDescriptor.CURRENT_VERSION
        this[1] = RawSampleFormat.UNSIGNED_INTEGER.code
        this[2] = 14
        this[3] = 14
        this[4] = 16
        this[5] = RawByteOrder.LITTLE_ENDIAN.code
        this[6] = RawPacking.TIFF_PACKED_BITS.code
        this[7] = RawPixelLayout.BAYER_2X2.code
        this[8] = 3
        this[9] = 0x94949494L.toInt()
        this[10] = RawDecoderRoute.LIBRAW_NATIVE.code
        this[11] = RawPostprocessRoute.LIBRAW_ACES_TO_FLOAT32_PROPHOTO.code
        this[12] = RawLinearSpace.LINEAR_PROPHOTO_RGB.code
        this[13] = RawLevelProvenance.DNG_METADATA.code
        this[14] = RawLevelProvenance.DNG_METADATA.code
        this[15] = 0
        this[16] = 64
        this[21] = 0
        this[22] = 0
        this[23] = 0
        repeat(4) { channel -> this[88 + channel] = 16000 }
        this[92] = 4
        this[93] = 0
        this[94] = 1
        this[95] = 1
        this[96] = 2
        this[129] = 1
        this[130] = 1
        this[131] = 1
        this[133] = 1
        this[134] = 2
        this[135] = 2
    }
}
