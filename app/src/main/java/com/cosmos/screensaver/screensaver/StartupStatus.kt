package com.cosmos.screensaver.screensaver

/**
 * Decides when the screensaver may show a status line while it has no picture yet. The screen
 * stays plain black for [delayMillis] so a normal start (a saved picture ready within moments)
 * never flashes any text, and the line disappears for good once a picture has been shown.
 *
 * Pure: the caller supplies the clock, so every transition is unit tested.
 */
internal class StartupStatus(private val delayMillis: Long = DEFAULT_DELAY_MILLIS) {
    private var startedAt = 0L
    private var running = false
    private var pictureShown = false
    private var text = ""

    /** [generic] is the truthful wording used until something more specific is known. */
    fun start(now: Long, generic: String) {
        startedAt = now
        running = true
        pictureShown = false
        text = generic
    }

    fun stop() {
        running = false
    }

    /** Replaces the wording, e.g. once it is known why there is nothing to show yet. */
    fun update(newText: String) {
        if (running && !pictureShown) text = newText
    }

    fun pictureShown() {
        pictureShown = true
    }

    /** The line to display now, or null while it is too early, a picture is up, or the session has stopped. */
    fun visibleText(now: Long): String? =
        if (running && !pictureShown && now - startedAt >= delayMillis) text else null

    /** Milliseconds until [visibleText] may first become non-null; null when no wait is useful. */
    fun millisUntilDue(now: Long): Long? =
        if (running && !pictureShown) (startedAt + delayMillis - now).coerceAtLeast(0L) else null

    companion object {
        const val DEFAULT_DELAY_MILLIS = 2_500L
    }
}
