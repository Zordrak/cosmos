package com.cosmos.screensaver.sync

/**
 * Serialises refresh ownership and coalesces requests made during a refresh. Settings changes and
 * explicit searches queue exactly one more pass, carrying the strongest reason asked for; automatic
 * requests add nothing because the running pass already covers them. A manual override is
 * therefore consumed by the one pass it queued and never leaks into a later automatic pass.
 */
internal class RefreshGate {
    private var running = false
    private var pending: RefreshReason? = null

    val isRunning: Boolean
        @Synchronized get() = running

    /** Returns true when the caller now owns the refresh; otherwise the request was queued (or dropped, for AUTO). */
    @Synchronized
    fun request(reason: RefreshReason): Boolean {
        if (running) {
            if (reason != RefreshReason.AUTO) pending = pending?.let { maxOf(it, reason) } ?: reason
            return false
        }
        running = true
        return true
    }

    /** Returns the reason of the next pass, or null after releasing refresh ownership. */
    @Synchronized
    fun finishPass(): RefreshReason? {
        check(running)
        val next = pending
        pending = null
        if (next == null) running = false
        return next
    }

    @Synchronized
    fun abort() {
        running = false
        pending = null
    }
}
