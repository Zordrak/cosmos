package com.cosmos.screensaver.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensaverSelectionTest {
    private val pkg = "com.cosmos.screensaver.debug"
    private val dream = "com.cosmos.screensaver.screensaver.CosmosDreamService"

    @Test
    fun `null is unknown, not none`() {
        assertEquals(ScreensaverSelection.UNKNOWN, ScreensaverSelection.parse(null, pkg))
    }

    @Test
    fun `blank is none`() {
        assertEquals(ScreensaverSelection.NONE, ScreensaverSelection.parse("", pkg))
        assertEquals(ScreensaverSelection.NONE, ScreensaverSelection.parse(" , ", pkg))
    }

    @Test
    fun `matches the exact dream service of this package`() {
        assertEquals(ScreensaverSelection.COSMOS, ScreensaverSelection.parse("$pkg/$dream", pkg))
        assertEquals(ScreensaverSelection.COSMOS, ScreensaverSelection.parse("com.cosmos.screensaver/$dream", "com.cosmos.screensaver"))
    }

    @Test
    fun `another package is other, including a look-alike prefix`() {
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse("com.google.android.backdrop/.Dream", pkg))
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse("com.cosmos.screensaver.debugger/.X", pkg))
    }

    @Test
    fun `another class in this package is not the dream service`() {
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse("$pkg/.D", pkg))
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse("$pkg/com.cosmos.screensaver.MainActivity", pkg))
    }

    @Test
    fun `a leading dot resolves against the package in the entry`() {
        assertEquals(
            ScreensaverSelection.COSMOS,
            ScreensaverSelection.parse("com.cosmos.screensaver/.screensaver.CosmosDreamService", "com.cosmos.screensaver")
        )
        assertEquals(
            ScreensaverSelection.OTHER,
            ScreensaverSelection.parse("$pkg/.screensaver.CosmosDreamService", pkg)
        )
    }

    @Test
    fun `any entry matching counts`() {
        assertEquals(ScreensaverSelection.COSMOS, ScreensaverSelection.parse("a.b/.C,$pkg/$dream", pkg))
    }

    @Test
    fun `an entry without a class is not a match`() {
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse(pkg, pkg))
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse("$pkg/", pkg))
    }

    @Test
    fun `switched-off screensavers disable a selected Cosmos only`() {
        assertEquals(ScreensaverSelection.DISABLED, ScreensaverSelection.parse("$pkg/$dream", pkg, enabled = 0))
        assertEquals(ScreensaverSelection.COSMOS, ScreensaverSelection.parse("$pkg/$dream", pkg, enabled = 1))
        assertEquals(ScreensaverSelection.COSMOS, ScreensaverSelection.parse("$pkg/$dream", pkg, enabled = null))
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse("a.b/.C", pkg, enabled = 0))
        assertEquals(ScreensaverSelection.NONE, ScreensaverSelection.parse("", pkg, enabled = 0))
        assertEquals(ScreensaverSelection.UNKNOWN, ScreensaverSelection.parse(null, pkg, enabled = 0))
    }
}
