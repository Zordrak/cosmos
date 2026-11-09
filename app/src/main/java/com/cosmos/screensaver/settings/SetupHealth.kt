package com.cosmos.screensaver.settings

/** What a setup check knows. Plain values only, so every state can be tested without a device. */
internal data class SetupFacts(
    val loaded: Boolean,
    val unreadable: Boolean,
    val selection: ScreensaverSelection,
    val enabledSources: Int,
    /** Saved pictures that match what the user wants to see. */
    val eligible: Int,
    val lowOnSpace: Boolean,
    /** Favourites alone take more than the size the user allows (only meaningful while that size is known). */
    val favouritesAboveBudget: Boolean,
    val lastSuccessMillis: Long,
    val lastAttemptMillis: Long,
    val nowMillis: Long,
    val refreshing: Boolean,
    val savingPaused: Boolean,
    val playingWithoutSaving: Boolean
)

internal enum class SetupIssue {
    UNREADABLE,
    SCREENSAVER_NOT_SELECTED,
    SCREENSAVER_OTHER,
    SCREENSAVER_DISABLED,
    NO_SOURCES,
    NO_MATCHING_PICTURES,
    LOW_SPACE,
    FAVOURITES_FILL_SPACE,
    CANNOT_REACH_SOURCES
}

/** States that are healthy or still settling: they never raise a warning. */
internal enum class SetupQuiet { LOADING, FETCHING, PAUSED, PLAYING_WITHOUT_SAVING, READY }

internal sealed interface SetupState {
    data class Issue(val issue: SetupIssue, val days: Int = 0) : SetupState
    data class Quiet(val state: SetupQuiet) : SetupState
}

/**
 * Chooses the single most useful thing to tell the user about setup, or nothing. An unknown
 * screensaver selection is never reported as a problem, an empty library that is still being
 * filled is not a fault, and paused saving or Play without saving are quiet statuses, not errors.
 */
internal object SetupHealth {
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    const val UNREACHABLE_AFTER_MILLIS = 2 * DAY_MILLIS

    fun evaluate(facts: SetupFacts): SetupState {
        if (!facts.loaded) return SetupState.Quiet(SetupQuiet.LOADING)
        if (facts.unreadable) return SetupState.Issue(SetupIssue.UNREADABLE)
        when (facts.selection) {
            ScreensaverSelection.NONE -> return SetupState.Issue(SetupIssue.SCREENSAVER_NOT_SELECTED)
            ScreensaverSelection.OTHER -> return SetupState.Issue(SetupIssue.SCREENSAVER_OTHER)
            ScreensaverSelection.DISABLED -> return SetupState.Issue(SetupIssue.SCREENSAVER_DISABLED)
            ScreensaverSelection.COSMOS, ScreensaverSelection.UNKNOWN -> Unit
        }
        if (facts.enabledSources == 0) return SetupState.Issue(SetupIssue.NO_SOURCES)
        if (facts.eligible == 0) {
            return when {
                facts.refreshing || facts.lastAttemptMillis <= 0L -> SetupState.Quiet(SetupQuiet.FETCHING)
                facts.savingPaused -> SetupState.Quiet(SetupQuiet.PAUSED)
                facts.playingWithoutSaving -> SetupState.Quiet(SetupQuiet.PLAYING_WITHOUT_SAVING)
                else -> SetupState.Issue(SetupIssue.NO_MATCHING_PICTURES)
            }
        }
        if (facts.lowOnSpace && !facts.playingWithoutSaving) return SetupState.Issue(SetupIssue.LOW_SPACE)
        if (facts.favouritesAboveBudget) return SetupState.Issue(SetupIssue.FAVOURITES_FILL_SPACE)
        val sinceSuccess = facts.nowMillis - facts.lastSuccessMillis
        val unreachable = facts.lastSuccessMillis > 0L &&
            facts.lastAttemptMillis > facts.lastSuccessMillis &&
            sinceSuccess > UNREACHABLE_AFTER_MILLIS
        if (unreachable && !facts.savingPaused && !facts.playingWithoutSaving && !facts.refreshing) {
            return SetupState.Issue(SetupIssue.CANNOT_REACH_SOURCES, days = (sinceSuccess / DAY_MILLIS).toInt())
        }
        return when {
            facts.playingWithoutSaving -> SetupState.Quiet(SetupQuiet.PLAYING_WITHOUT_SAVING)
            facts.savingPaused -> SetupState.Quiet(SetupQuiet.PAUSED)
            facts.refreshing -> SetupState.Quiet(SetupQuiet.FETCHING)
            else -> SetupState.Quiet(SetupQuiet.READY)
        }
    }
}
