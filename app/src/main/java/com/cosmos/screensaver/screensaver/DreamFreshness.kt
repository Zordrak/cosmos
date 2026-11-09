package com.cosmos.screensaver.screensaver

/**
 * Decides when a running screensaver should ask again whether the library is due a refresh, so a TV
 * that stays in screensaver mode for days still gets fresh pictures. It never asks more often than
 * [intervalMillis]. The actual request is still made by the refresh coordinator, which applies the
 * usual staleness and download-mode rules.
 */
internal class DreamFreshness(
    private val intervalMillis: Long = CHECK_INTERVAL_MILLIS,
    private val clock: () -> Long
) {
    private var lastCheckMillis: Long? = null

    /** True when a check is due now; records it so the next one waits a full interval. */
    fun due(): Boolean {
        val now = clock()
        val last = lastCheckMillis
        if (last != null && now - last in 0 until intervalMillis) return false
        lastCheckMillis = now
        return true
    }

    companion object {
        const val CHECK_INTERVAL_MILLIS = 60L * 1000
    }
}
