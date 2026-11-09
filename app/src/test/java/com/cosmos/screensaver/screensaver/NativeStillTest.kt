package com.cosmos.screensaver.screensaver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStillTest {
    private fun u8(b: Byte) = b.toInt() and 0xFF

    @Test
    fun convertsReferenceColoursToBt709LimitedRange() {
        val planes = Yuv709.Planes(2, 2)
        fun one(argb: Int): Triple<Int, Int, Int> {
            val row = intArrayOf(argb, argb)
            Yuv709.convertRowPair(row, row, 2, 0, planes)
            return Triple(u8(planes.y[0]), u8(planes.cb[0]), u8(planes.cr[0]))
        }
        assertEquals(Triple(16, 128, 128), one(0xFF000000.toInt()))
        assertEquals(Triple(235, 128, 128), one(0xFFFFFFFF.toInt()))
        // BT.709 limited-range red, green and blue.
        assertEquals(Triple(63, 102, 240), one(0xFFFF0000.toInt()))
        assertEquals(Triple(173, 42, 26), one(0xFF00FF00.toInt()))
        assertEquals(Triple(32, 240, 118), one(0xFF0000FF.toInt()))
    }

    @Test
    fun chromaIsTheAverageOfEach2x2Block() {
        val planes = Yuv709.Planes(2, 2)
        Yuv709.convertRowPair(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
            intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            2, 0, planes
        )
        assertArrayEquals(byteArrayOf(235.toByte(), 16, 16, 235.toByte()), planes.y)
        assertEquals(128, u8(planes.cb[0]))
        assertEquals(128, u8(planes.cr[0]))
    }

    @Test
    fun buildsBaselineLevel51ParameterSetsForTheExactSize() {
        val stream = frameOf(64, 32, 16)
        // Start code, then the NAL header (nal_ref_idc 3, type 7/8/5).
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 66, 0xC0.toByte(), 51), stream.sps.copyOf(8))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68), stream.pps.copyOf(5))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), stream.slice.copyOf(5))
    }

    @Test
    fun carriesEverySampleUncompressedInMacroblockOrder() {
        val width = 32
        val height = 16
        val y = ByteArray(width * height) { (16 + it % 200).toByte() }
        val cb = ByteArray(width * height / 4) { 100 }
        val cr = ByteArray(width * height / 4) { 150.toByte() }
        val slice = PcmH264.frame(width, height, y, cb, cr).slice
        // Two macroblocks of 384 samples each, plus a short header and per-macroblock type bits.
        assertTrue(slice.size in 2 * 384 until 2 * 384 + 40)
        val firstLumaRow = y.copyOf(16)
        assertTrue(indexOf(slice, firstLumaRow) > 0)
        val secondMacroblockFirstRow = y.copyOfRange(16, 32)
        assertTrue(indexOf(slice, secondMacroblockFirstRow) > indexOf(slice, firstLumaRow))
    }

    @Test
    fun preventsStartCodeEmulationInTheSamples() {
        val stream = frameOf(16, 16, 0)
        val body = stream.slice.copyOfRange(5, stream.slice.size)
        for (i in 0 until body.size - 2) {
            val run = body[i].toInt() == 0 && body[i + 1].toInt() == 0
            assertTrue("emulated start code at $i", !run || (body[i + 2].toInt() and 0xFF) >= 3)
        }
    }

    @Test
    fun capacityCoversA4kFrame() {
        val needed = 240 * 135 * (384 + 2) + 64
        assertTrue(PcmH264.sliceCapacity(3840, 2160) >= needed)
    }

    private fun frameOf(width: Int, height: Int, luma: Int) = PcmH264.frame(
        width, height,
        ByteArray(width * height) { luma.toByte() },
        ByteArray(width * height / 4) { 128.toByte() },
        ByteArray(width * height / 4) { 128.toByte() }
    )

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
