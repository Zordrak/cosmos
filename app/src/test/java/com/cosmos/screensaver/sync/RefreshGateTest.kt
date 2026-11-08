package com.cosmos.screensaver.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class RefreshGateTest {
    @Test
    fun forcedRequestsCoalesceUntilTheNextPass() {
        val gate = RefreshGate()
        assertTrue(gate.request(force = false))
        assertFalse(gate.request(force = true))
        assertFalse(gate.request(force = true))
        assertTrue(gate.finishPass())
        assertTrue(gate.isRunning)
        assertFalse(gate.finishPass())
        assertFalse(gate.isRunning)
    }

    @Test
    fun forcedRequestRacingCompletionIsNeverLost() {
        repeat(200) {
            val gate = RefreshGate()
            assertTrue(gate.request(force = false))
            val start = CountDownLatch(1)
            var anotherPass = false
            var anotherWorker = false
            val finishing = Thread {
                start.await()
                anotherPass = gate.finishPass()
            }
            val requesting = Thread {
                start.await()
                anotherWorker = gate.request(force = true)
            }
            finishing.start()
            requesting.start()
            start.countDown()
            finishing.join()
            requesting.join()

            assertTrue(anotherPass.xor(anotherWorker))
            assertTrue(gate.isRunning)
            assertFalse(gate.finishPass())
            assertFalse(gate.isRunning)
        }
    }
}
