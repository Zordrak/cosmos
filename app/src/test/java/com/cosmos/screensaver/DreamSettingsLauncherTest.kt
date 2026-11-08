package com.cosmos.screensaver

import android.content.ActivityNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamSettingsLauncherTest {
    @Test
    fun launchesSettingsWithoutReportingFailure() {
        var launched = false
        var reported = false

        launchDreamSettings(
            launch = { launched = true },
            onUnavailable = { reported = true }
        )

        assertTrue(launched)
        assertFalse(reported)
    }

    @Test
    fun reportsMissingSettingsActivity() {
        val expected = ActivityNotFoundException()
        var reported: RuntimeException? = null

        launchDreamSettings(
            launch = { throw expected },
            onUnavailable = { reported = it }
        )

        assertSame(expected, reported)
    }

    @Test
    fun reportsDeniedSettingsActivity() {
        val expected = SecurityException()
        var reported: RuntimeException? = null

        launchDreamSettings(
            launch = { throw expected },
            onUnavailable = { reported = it }
        )

        assertSame(expected, reported)
    }

    @Test
    fun propagatesUnexpectedErrors() {
        val expected = IllegalStateException()

        val actual = assertThrows(IllegalStateException::class.java) {
            launchDreamSettings(
                launch = { throw expected },
                onUnavailable = { throw AssertionError("Unexpected failure callback", it) }
            )
        }

        assertSame(expected, actual)
    }
}
