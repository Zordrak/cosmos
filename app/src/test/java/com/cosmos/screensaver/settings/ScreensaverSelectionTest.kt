package com.cosmos.screensaver.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensaverSelectionTest {
    private val pkg = "com.cosmos.screensaver.debug"

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
    fun `matches by package`() {
        assertEquals(
            ScreensaverSelection.COSMOS,
            ScreensaverSelection.parse("com.cosmos.screensaver.debug/com.cosmos.screensaver.screensaver.CosmosDreamService", pkg)
        )
    }

    @Test
    fun `another package is other, including a look-alike prefix`() {
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse("com.google.android.backdrop/.Dream", pkg))
        assertEquals(ScreensaverSelection.OTHER, ScreensaverSelection.parse("com.cosmos.screensaver.debugger/.X", pkg))
    }

    @Test
    fun `any entry matching counts`() {
        assertEquals(
            ScreensaverSelection.COSMOS,
            ScreensaverSelection.parse("a.b/.C,$pkg/.D", pkg)
        )
    }
}
