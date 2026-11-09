package com.cosmos.screensaver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class RefreshGateTest {
    @Test
    fun requestsCoalesceIntoOneNextPassWithTheStrongestReason() {
        val gate = RefreshGate()
        assertTrue(gate.request(RefreshReason.AUTO))
        assertFalse(gate.request(RefreshReason.AUTO))
        assertFalse(gate.request(RefreshReason.EXPLICIT_SEARCH))
        assertFalse(gate.request(RefreshReason.SETTINGS_CHANGED))
        assertEquals(RefreshReason.EXPLICIT_SEARCH, gate.finishPass())
        assertTrue(gate.isRunning)
        assertNull(gate.finishPass())
        assertFalse(gate.isRunning)
    }

    @Test
    fun automaticRequestsDuringARefreshAddNoPass() {
        val gate = RefreshGate()
        assertTrue(gate.request(RefreshReason.AUTO))
        assertFalse(gate.request(RefreshReason.AUTO))
        assertNull(gate.finishPass())
        assertFalse(gate.isRunning)
    }

    @Test
    fun anOverrideIsConsumedByExactlyOnePass() {
        val gate = RefreshGate()
        assertTrue(gate.request(RefreshReason.AUTO))
        gate.request(RefreshReason.EXPLICIT_SEARCH)
        assertEquals(RefreshReason.EXPLICIT_SEARCH, gate.finishPass())
        gate.request(RefreshReason.SETTINGS_CHANGED)
        assertEquals(RefreshReason.SETTINGS_CHANGED, gate.finishPass())
        assertNull(gate.finishPass())
    }

    @Test
    fun driveReChecksThePolicyBeforeEveryPass() {
        val gate = RefreshGate()
        assertTrue(gate.request(RefreshReason.AUTO))
        var paused = false
        val passes = mutableListOf<RefreshReason>()
        gate.request(RefreshReason.SETTINGS_CHANGED)
        val result = gate.drive(
            RefreshReason.AUTO,
            { reason -> reason == RefreshReason.EXPLICIT_SEARCH || !paused },
            { reason ->
                passes += reason
                paused = true
                gate.request(RefreshReason.SETTINGS_CHANGED)
                passes.size
            }
        )
        assertEquals(listOf(RefreshReason.AUTO), passes)
        assertEquals(1, result)
        assertFalse(gate.isRunning)
    }

    @Test
    fun driveLetsAnExplicitSearchThroughWhilePaused() {
        val gate = RefreshGate()
        assertTrue(gate.request(RefreshReason.AUTO))
        gate.request(RefreshReason.EXPLICIT_SEARCH)
        val passes = mutableListOf<RefreshReason>()
        gate.drive(
            RefreshReason.AUTO,
            { reason -> reason == RefreshReason.EXPLICIT_SEARCH },
            { reason -> passes += reason; true }
        )
        assertEquals(listOf(RefreshReason.EXPLICIT_SEARCH), passes)
    }

    @Test
    fun forcedRequestRacingCompletionIsNeverLost() {
        repeat(200) {
            val gate = RefreshGate()
            assertTrue(gate.request(RefreshReason.AUTO))
            val start = CountDownLatch(1)
            var anotherPass = false
            var anotherWorker = false
            val finishing = Thread {
                start.await()
                anotherPass = gate.finishPass() != null
            }
            val requesting = Thread {
                start.await()
                anotherWorker = gate.request(RefreshReason.EXPLICIT_SEARCH)
            }
            finishing.start()
            requesting.start()
            start.countDown()
            finishing.join()
            requesting.join()

            assertTrue(anotherPass.xor(anotherWorker))
            assertTrue(gate.isRunning)
            assertNull(gate.finishPass())
            assertFalse(gate.isRunning)
        }
    }
}
