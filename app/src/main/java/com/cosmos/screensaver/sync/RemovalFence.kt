package com.cosmos.screensaver.sync

/**
 * Remembers pictures the owner just removed, so a refresh that was already running cannot put one
 * straight back (contract C4). The picture is allowed to return on a LATER refresh: a run only
 * honours marks made after it started. Times come from a monotonic clock; marks are in memory
 * because a restart ends every in-flight run anyway.
 */
internal object RemovalFence {
    private class Mark(val keys: Set<String>, val hash: String, val atMillis: Long)

    private const val MAX_MARKS = 200
    private const val KEEP_MILLIS = 6L * 60 * 60 * 1000

    private val marks = ArrayDeque<Mark>()

    @Synchronized
    fun note(keys: Set<String>, hash: String, atMillis: Long = ElapsedRealtimeClock.elapsedMillis()) {
        marks.addLast(Mark(keys, hash, atMillis))
        while (marks.size > MAX_MARKS || (marks.isNotEmpty() && atMillis - marks.first().atMillis > KEEP_MILLIS)) {
            marks.removeFirst()
        }
    }

    /** True when [key] or [hash] was removed at or after [sinceMillis]. A blank [hash] matches by key only. */
    @Synchronized
    fun removedSince(key: String, hash: String, sinceMillis: Long): Boolean = marks.any { mark ->
        mark.atMillis >= sinceMillis && (key in mark.keys || (hash.isNotBlank() && hash == mark.hash))
    }

    @Synchronized
    fun clearForTests() = marks.clear()
}
