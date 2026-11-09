package com.cosmos.screensaver.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ByteSizesTest {
    private fun f(bytes: Long) = ByteSizes.format(bytes, Locale.US)

    @Test
    fun formatsBinaryUnitsInPlainWords() {
        assertEquals("0 B", f(0))
        assertEquals("512 B", f(512))
        assertEquals("512 KB", f(512L * 1024))
        assertEquals("379 MB", f(379L * 1024 * 1024))
        assertEquals("2.5 GB", f(2560L * 1024 * 1024))
        assertEquals("4 GB", f(4096L * 1024 * 1024))
        assertEquals("512 MB", f(512L * 1024 * 1024))
        assertEquals("16 GB", f(16384L * 1024 * 1024))
    }

    @Test
    fun sizeChoicesAgreeWithTheAmountsShownBesideThem() {
        // 4 GB on the size row must be the same words as exactly 4 GB of pictures.
        assertEquals(f(4096L * 1024 * 1024), "4 GB")
        assertEquals("1 GB", f(1024L * 1024 * 1024))
    }

    @Test
    fun roundingNeverShows1024OfAUnit() {
        assertEquals("1 GB", f(1024L * 1024 * 1024 - 1))
        assertEquals("1 MB", f(1024L * 1024 - 1))
    }

    @Test
    fun negativeSizesAreShownAsZero() {
        assertEquals("0 B", f(-5))
    }

    @Test
    fun usesTheLocalesDecimalSeparator() {
        assertEquals("2,5 GB", ByteSizes.format(2560L * 1024 * 1024, Locale.GERMANY))
    }

    @Test
    fun hugeValuesStayInGigabytes() {
        assertEquals("8192 GB", f(8192L * 1024 * 1024 * 1024))
    }
}
