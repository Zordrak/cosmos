package com.cosmos.screensaver.sync

import com.cosmos.screensaver.settings.DownloadMode

/**
 * Why a refresh was requested. Strongest wins when requests are coalesced. Only [EXPLICIT_SEARCH]
 * (the user pressing "Look for new pictures now") may override the download mode, and only for the
 * one pass it asked for.
 */
internal enum class RefreshReason {
    /** The app opened, a screensaver started, or the daily job ran: saving only if due and allowed. */
    AUTO,

    /** A viewing choice changed: new pictures are wanted, but the download mode still applies. */
    SETTINGS_CHANGED,

    /** The user asked to search now. */
    EXPLICIT_SEARCH
}

/** What the TV says about the current connection. A transport name such as Wi-Fi is never evidence either way. */
internal enum class Meteredness {
    UNMETERED,
    METERED,

    /** There is no active network right now. */
    UNKNOWN_NOW,

    /** This TV cannot be asked (no permission or no service); not a fact about the connection. */
    UNAVAILABLE
}

/** The network requirement of the periodic job. */
internal enum class JobNetwork { ANY, UNMETERED }

/** Whether [mode] lets Cosmos save new pictures right now. */
internal fun savingAllowed(mode: DownloadMode, meteredness: Meteredness, explicitSearch: Boolean): Boolean = when {
    explicitSearch -> true
    mode == DownloadMode.PAUSED -> false
    mode == DownloadMode.ALWAYS -> true
    else -> meteredness != Meteredness.METERED
}

internal fun savingAllowed(mode: DownloadMode, meteredness: Meteredness, reason: RefreshReason): Boolean =
    savingAllowed(mode, meteredness, reason == RefreshReason.EXPLICIT_SEARCH)

/**
 * The network the periodic job should require, or null when it must not be scheduled at all. A job
 * restricted to unmetered networks is used only while the TV can report meteredness; the run-time
 * check in [savingAllowed] stays authoritative either way.
 */
internal fun jobNetworkFor(mode: DownloadMode, meteredness: Meteredness): JobNetwork? = when (mode) {
    DownloadMode.PAUSED -> null
    DownloadMode.ALWAYS -> JobNetwork.ANY
    DownloadMode.UNMETERED_ONLY ->
        if (meteredness == Meteredness.UNAVAILABLE) JobNetwork.ANY else JobNetwork.UNMETERED
}

/** Runs refresh passes while the gate asks for more, re-checking the policy before each one. */
internal fun <R> RefreshGate.drive(
    first: RefreshReason,
    allowed: (RefreshReason) -> Boolean,
    pass: (RefreshReason) -> R?
): R? {
    var reason: RefreshReason? = first
    var result: R? = null
    while (reason != null) {
        if (allowed(reason)) result = pass(reason)
        reason = finishPass()
    }
    return result
}
