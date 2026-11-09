package com.cosmos.screensaver.screensaver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamFreshnessTest {
    @Test
    fun `asks at once and then not again until the interval has passed`() {
        var now = 1_000L
        val freshness = DreamFreshness(intervalMillis = 60_000L) { now }
        assertTrue(freshness.due())
        now += 30_000
        assertFalse(freshness.due())
        now += 29_999
        assertFalse(freshness.due())
        now += 1
        assertTrue(freshness.due())
        assertFalse(freshness.due())
    }

    @Test
    fun `a clock that goes backwards is checked again rather than waiting forever`() {
        var now = 500_000L
        val freshness = DreamFreshness(intervalMillis = 60_000L) { now }
        assertTrue(freshness.due())
        now = 100_000L
        assertTrue(freshness.due())
    }
}
