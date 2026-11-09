package com.cosmos.screensaver.settings

import com.cosmos.screensaver.settings.SetupIssue.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupHealthTest {
    private val now = 100L * 24 * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000

    private fun facts(
        loaded: Boolean = true,
        unreadable: Boolean = false,
        selection: ScreensaverSelection = ScreensaverSelection.COSMOS,
        enabledSources: Int = 3,
        eligible: Int = 50,
        lowOnSpace: Boolean = false,
        favouritesAboveBudget: Boolean = false,
        lastSuccess: Long = now - 1000,
        lastAttempt: Long = now - 1000,
        refreshing: Boolean = false,
        paused: Boolean = false,
        playingWithoutSaving: Boolean = false
    ) = SetupFacts(
        loaded, unreadable, selection, enabledSources, eligible, lowOnSpace, favouritesAboveBudget,
        lastSuccess, lastAttempt, now, refreshing, paused, playingWithoutSaving
    )

    private fun issue(issue: SetupIssue, days: Int = 0) = SetupState.Issue(issue, days)
    private fun quiet(state: SetupQuiet) = SetupState.Quiet(state)

    @Test
    fun `healthy setup is quiet and ready`() {
        assertEquals(quiet(SetupQuiet.READY), SetupHealth.evaluate(facts()))
    }

    @Test
    fun `nothing is judged before the library has loaded`() {
        assertEquals(quiet(SetupQuiet.LOADING), SetupHealth.evaluate(facts(loaded = false, enabledSources = 0)))
    }

    @Test
    fun `an unreadable library outranks everything`() {
        assertEquals(
            issue(UNREADABLE),
            SetupHealth.evaluate(facts(unreadable = true, selection = ScreensaverSelection.NONE, enabledSources = 0))
        )
    }

    @Test
    fun `a wrong or disabled screensaver is reported but an unknown one is not`() {
        assertEquals(issue(SCREENSAVER_NOT_SELECTED), SetupHealth.evaluate(facts(selection = ScreensaverSelection.NONE)))
        assertEquals(issue(SCREENSAVER_OTHER), SetupHealth.evaluate(facts(selection = ScreensaverSelection.OTHER)))
        assertEquals(issue(SCREENSAVER_DISABLED), SetupHealth.evaluate(facts(selection = ScreensaverSelection.DISABLED)))
        assertEquals(quiet(SetupQuiet.READY), SetupHealth.evaluate(facts(selection = ScreensaverSelection.UNKNOWN)))
    }

    @Test
    fun `all sources off is never ready`() {
        assertEquals(issue(NO_SOURCES), SetupHealth.evaluate(facts(enabledSources = 0)))
        assertEquals(issue(NO_SOURCES), SetupHealth.evaluate(facts(enabledSources = 0, eligible = 0)))
    }

    @Test
    fun `an empty library that is still being filled is quiet`() {
        assertEquals(quiet(SetupQuiet.FETCHING), SetupHealth.evaluate(facts(eligible = 0, refreshing = true)))
        assertEquals(quiet(SetupQuiet.FETCHING), SetupHealth.evaluate(facts(eligible = 0, lastAttempt = 0, lastSuccess = 0)))
    }

    @Test
    fun `no matching pictures after a finished look is an issue unless saving is paused or skipped`() {
        assertEquals(issue(NO_MATCHING_PICTURES), SetupHealth.evaluate(facts(eligible = 0)))
        assertEquals(quiet(SetupQuiet.PAUSED), SetupHealth.evaluate(facts(eligible = 0, paused = true)))
        assertEquals(
            quiet(SetupQuiet.PLAYING_WITHOUT_SAVING),
            SetupHealth.evaluate(facts(eligible = 0, playingWithoutSaving = true))
        )
    }

    @Test
    fun `low space is reported unless pictures are played without saving`() {
        assertEquals(issue(LOW_SPACE), SetupHealth.evaluate(facts(lowOnSpace = true)))
        assertEquals(
            quiet(SetupQuiet.PLAYING_WITHOUT_SAVING),
            SetupHealth.evaluate(facts(lowOnSpace = true, playingWithoutSaving = true))
        )
    }

    @Test
    fun `favourites above the size limit are reported after low space`() {
        assertEquals(issue(FAVOURITES_FILL_SPACE), SetupHealth.evaluate(facts(favouritesAboveBudget = true)))
        assertEquals(issue(LOW_SPACE), SetupHealth.evaluate(facts(favouritesAboveBudget = true, lowOnSpace = true)))
    }

    @Test
    fun `sustained contact failure is reported with whole days and never a 1970 date`() {
        assertEquals(
            issue(CANNOT_REACH_SOURCES, days = 3),
            SetupHealth.evaluate(facts(lastSuccess = now - 3 * day - 1, lastAttempt = now - 1000))
        )
        assertEquals(quiet(SetupQuiet.READY), SetupHealth.evaluate(facts(lastSuccess = now - 3 * day, lastAttempt = now - 3 * day)))
        assertEquals(quiet(SetupQuiet.READY), SetupHealth.evaluate(facts(lastSuccess = 0, lastAttempt = now - 1000)))
        assertEquals(quiet(SetupQuiet.READY), SetupHealth.evaluate(facts(lastSuccess = now - 1 * day, lastAttempt = now - 1000)))
    }

    @Test
    fun `paused saving is a quiet status and never an offline warning`() {
        assertEquals(
            quiet(SetupQuiet.PAUSED),
            SetupHealth.evaluate(facts(paused = true, lastSuccess = now - 30 * day, lastAttempt = now - 29 * day))
        )
    }
}
