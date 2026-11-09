package com.cosmos.screensaver.sync

import com.cosmos.screensaver.settings.DownloadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingPolicyTest {
    @Test
    fun `automatic saving is always allowed in automatic mode`() {
        for (meteredness in Meteredness.entries) {
            for (reason in RefreshReason.entries) {
                assertTrue("$meteredness $reason", savingAllowed(DownloadMode.ALWAYS, meteredness, reason))
            }
        }
    }

    @Test
    fun `paused denies everything except an explicit search`() {
        for (meteredness in Meteredness.entries) {
            assertFalse(savingAllowed(DownloadMode.PAUSED, meteredness, RefreshReason.AUTO))
            assertFalse(savingAllowed(DownloadMode.PAUSED, meteredness, RefreshReason.SETTINGS_CHANGED))
            assertTrue(savingAllowed(DownloadMode.PAUSED, meteredness, RefreshReason.EXPLICIT_SEARCH))
        }
    }

    @Test
    fun `limited-data mode denies only a positively metered connection`() {
        val mode = DownloadMode.UNMETERED_ONLY
        for (reason in listOf(RefreshReason.AUTO, RefreshReason.SETTINGS_CHANGED)) {
            assertTrue(savingAllowed(mode, Meteredness.UNMETERED, reason))
            assertFalse(savingAllowed(mode, Meteredness.METERED, reason))
            assertTrue(savingAllowed(mode, Meteredness.UNAVAILABLE, reason))
            assertTrue(savingAllowed(mode, Meteredness.UNKNOWN_NOW, reason))
        }
        assertTrue(savingAllowed(mode, Meteredness.METERED, RefreshReason.EXPLICIT_SEARCH))
    }

    @Test
    fun `the job follows the mode and fails open when limits cannot be checked`() {
        assertEquals(JobNetwork.ANY, jobNetworkFor(DownloadMode.ALWAYS, Meteredness.METERED))
        assertNull(jobNetworkFor(DownloadMode.PAUSED, Meteredness.UNMETERED))
        assertEquals(JobNetwork.UNMETERED, jobNetworkFor(DownloadMode.UNMETERED_ONLY, Meteredness.UNMETERED))
        assertEquals(JobNetwork.UNMETERED, jobNetworkFor(DownloadMode.UNMETERED_ONLY, Meteredness.METERED))
        assertEquals(JobNetwork.UNMETERED, jobNetworkFor(DownloadMode.UNMETERED_ONLY, Meteredness.UNKNOWN_NOW))
        assertEquals(JobNetwork.ANY, jobNetworkFor(DownloadMode.UNMETERED_ONLY, Meteredness.UNAVAILABLE))
    }

    @Test
    fun `a network without the not-metered capability is metered whatever its transport`() {
        assertEquals(Meteredness.UNKNOWN_NOW, meterednessOf(hasActiveNetwork = false, notMetered = false))
        assertEquals(Meteredness.METERED, meterednessOf(hasActiveNetwork = true, notMetered = false))
        assertEquals(Meteredness.UNMETERED, meterednessOf(hasActiveNetwork = true, notMetered = true))
    }

    @Test
    fun `job ownership ignores a late callback from a stopped invocation`() {
        val ownership = JobOwnership<Any>()
        val first = Any()
        val second = Any()
        ownership.start(first)
        ownership.stop(first)
        ownership.start(second)
        assertFalse(ownership.finish(first))
        assertTrue(ownership.finish(second))
        assertFalse(ownership.finish(second))
    }

    @Test
    fun `a mode change mid-run applies to the next pass and an override only to its own`() {
        val gate = RefreshGate()
        var mode = DownloadMode.ALWAYS
        val passes = mutableListOf<RefreshReason>()
        assertTrue(gate.request(RefreshReason.AUTO))
        gate.drive(
            RefreshReason.AUTO,
            { reason -> savingAllowed(mode, Meteredness.UNMETERED, reason) },
            { reason ->
                passes += reason
                mode = DownloadMode.PAUSED
                if (passes.size == 1) {
                    gate.request(RefreshReason.SETTINGS_CHANGED)
                    gate.request(RefreshReason.EXPLICIT_SEARCH)
                }
                true
            }
        )
        assertEquals(listOf(RefreshReason.AUTO, RefreshReason.EXPLICIT_SEARCH), passes)
        assertTrue(gate.request(RefreshReason.AUTO))
        assertFalse(savingAllowed(mode, Meteredness.UNMETERED, RefreshReason.SETTINGS_CHANGED))
    }
}
