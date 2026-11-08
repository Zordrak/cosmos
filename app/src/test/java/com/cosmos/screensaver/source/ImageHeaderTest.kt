package com.cosmos.screensaver.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHeaderTest {
    @Test
    fun readsJpegDimensionsPastOtherSegments() {
        val bytes = jpeg(7000, 3150)

        assertTrue(ImageHeader.isJpeg(bytes))
        assertFalse(ImageHeader.isPng(bytes))
        assertEquals(7000 to 3150, ImageHeader.dimensions(bytes))
    }

    @Test
    fun readsPngDimensions() {
        val bytes = png(3840, 2160)

        assertTrue(ImageHeader.isPng(bytes))
        assertEquals(3840 to 2160, ImageHeader.dimensions(bytes))
    }

    @Test
    fun rejectsTruncatedAndUnknownData() {
        assertNull(ImageHeader.dimensions(jpeg(100, 100).copyOf(10)))
        assertNull(ImageHeader.dimensions(png(100, 100).copyOf(20)))
        assertNull(ImageHeader.dimensions("GIF89a".toByteArray()))
        assertFalse(ImageHeader.isJpeg(byteArrayOf(0xFF.toByte())))
    }

    companion object {
        private fun be(value: Int, bytes: Int) = ByteArray(bytes) { (value shr (8 * (bytes - 1 - it)) and 0xFF).toByte() }

        /** SOI, an APP0 segment, then a baseline SOF0 frame header. */
        fun jpeg(width: Int, height: Int): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + be(6, 2) + ByteArray(4) +
                byteArrayOf(0xFF.toByte(), 0xC0.toByte()) + be(11, 2) + byteArrayOf(8) + be(height, 2) + be(width, 2) +
                byteArrayOf(1, 1, 0x11, 0)

        fun png(width: Int, height: Int): ByteArray =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be(13, 4) + "IHDR".toByteArray() +
                be(width, 4) + be(height, 4) + byteArrayOf(8, 2, 0, 0, 0)
    }
}
