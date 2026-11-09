package com.cosmos.screensaver.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupStatusTest {
    private fun started(at: Long = 1_000L) = StartupStatus().also { it.start(at, "Preparing") }

    @Test
    fun `nothing is shown before the delay`() {
        val status = started()
        assertNull(status.visibleText(1_000L))
        assertNull(status.visibleText(3_499L))
    }

    @Test
    fun `the generic text appears once the delay has passed`() {
        assertEquals("Preparing", started().visibleText(3_500L))
        assertEquals("Preparing", started().visibleText(60_000L))
    }

    @Test
    fun `an update replaces the wording before and after it is due`() {
        val early = started().also { it.update("Offline") }
        assertEquals("Offline", early.visibleText(4_000L))

        val late = started()
        assertEquals("Preparing", late.visibleText(4_000L))
        late.update("No space")
        assertEquals("No space", late.visibleText(4_100L))
    }

    @Test
    fun `a picture cancels the status and later updates are ignored`() {
        val status = started()
        status.pictureShown()
        status.update("Offline")
        assertNull(status.visibleText(10_000L))
        assertNull(status.millisUntilDue(10_000L))
    }

    @Test
    fun `stop hides everything and ignores updates`() {
        val status = started()
        status.stop()
        status.update("Offline")
        assertNull(status.visibleText(10_000L))
        assertNull(status.millisUntilDue(1_000L))
    }

    @Test
    fun `restarting resets a shown picture and the clock`() {
        val status = started(1_000L)
        status.pictureShown()
        status.stop()
        status.start(20_000L, "Again")
        assertNull(status.visibleText(21_000L))
        assertEquals("Again", status.visibleText(22_500L))
    }

    @Test
    fun `millisUntilDue counts down and floors at zero`() {
        val status = started(1_000L)
        assertEquals(2_500L, status.millisUntilDue(1_000L))
        assertEquals(500L, status.millisUntilDue(3_000L))
        assertEquals(0L, status.millisUntilDue(9_000L))
    }
}
