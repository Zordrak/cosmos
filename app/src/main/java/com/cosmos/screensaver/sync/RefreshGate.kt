package com.cosmos.screensaver.sync

/** Serialises refresh ownership and coalesces forced requests made during a refresh. */
internal class RefreshGate {
    private var running = false
    private var rerun = false

    val isRunning: Boolean
        @Synchronized get() = running

    @Synchronized
    fun request(force: Boolean): Boolean {
        if (running) {
            if (force) rerun = true
            return false
        }
        running = true
        return true
    }

    /** Returns true when another pass is needed; otherwise releases refresh ownership. */
    @Synchronized
    fun finishPass(): Boolean {
        check(running)
        if (rerun) {
            rerun = false
            return true
        }
        running = false
        return false
    }

    @Synchronized
    fun abort() {
        running = false
        rerun = false
    }
}
