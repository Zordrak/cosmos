package com.cosmos.screensaver.sync

import android.os.SystemClock
import com.cosmos.screensaver.source.DownloadObserver

/** Which operation a tracker follows. Saving fills the library; streaming shows pictures without keeping them. */
internal enum class ProgressKind { SAVING, STREAMING }

internal enum class ProgressPhase {
    IDLE,
    STARTING,
    DISCOVERING,
    DOWNLOADING,

    /** The file is down; it is being checked (format, size, rights) before it is kept. */
    CHECKING
}

internal enum class ProgressErrorKind { DISCOVERY, DOWNLOAD, UNEXPECTED }

internal data class ProgressError(val kind: ProgressErrorKind, val message: String, val source: String?)

/**
 * One coherent, immutable picture of an operation. Every field comes from the same moment, so a
 * title is never shown with another image's speed. [totalBytes] is -1 when it is not known (or the
 * server's figure turned out to be wrong).
 */
internal data class ProgressSnapshot(
    val kind: ProgressKind,
    /** 0 until the first run. */
    val runId: Long,
    val phase: ProgressPhase,
    val source: String?,
    val title: String?,
    val doneBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val admitted: Int,
    val skipped: Int,
    val startedAtEpochMillis: Long,
    /** 0 while running or before any run. */
    val endedAtEpochMillis: Long,
    val error: ProgressError?
) {
    val active: Boolean get() = phase != ProgressPhase.IDLE

    /** 0..1, or null when the size is not known (show an indeterminate bar). */
    val fraction: Float?
        get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/** A monotonic duration clock: it never jumps when the wall clock is changed. */
internal fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

internal object ElapsedRealtimeClock : MonotonicClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}

/**
 * Tracks one kind of operation. Runs are handles: a run that was superseded or has ended can no
 * longer change what is shown, so a late `end()` never clears a newer run.
 */
internal class ProgressTracker(
    private val kind: ProgressKind,
    private val clock: MonotonicClock = ElapsedRealtimeClock,
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    private class Sample(val atMillis: Long, val bytes: Long)

    private val lock = Any()
    private var state = ProgressSnapshot(
        kind, 0L, ProgressPhase.IDLE, null, null, 0L, -1L, 0L, 0, 0, 0L, 0L, null
    )
    private var nextRunId = 1L
    private val samples = ArrayDeque<Sample>()
    private var recordedAt = 0L
    private var recordedBytes = 0L
    private var recorded = false

    /** Feeds a fetcher; applies only while a file is downloading. */
    val observer: DownloadObserver = DownloadObserver { done, total, finished -> bytes(done, total, finished) }

    /** The current picture. Speed is worked out now, so a stalled transfer decays to zero. */
    fun snapshot(): ProgressSnapshot = synchronized(lock) {
        state.copy(bytesPerSecond = if (state.phase == ProgressPhase.DOWNLOADING) speedNow() else 0L)
    }

    fun begin(): ProgressRun = synchronized(lock) {
        val id = nextRunId++
        samples.clear()
        recorded = false
        state = state.copy(
            runId = id,
            phase = ProgressPhase.STARTING,
            source = null,
            title = null,
            doneBytes = 0L,
            totalBytes = -1L,
            bytesPerSecond = 0L,
            admitted = 0,
            skipped = 0,
            startedAtEpochMillis = wallClock(),
            endedAtEpochMillis = 0L,
            error = null
        )
        ProgressRun(this, id)
    }

    internal fun discovering(runId: Long, source: String?) = mutate(runId) {
        it.copy(phase = ProgressPhase.DISCOVERING, source = source ?: it.source, doneBytes = 0L, totalBytes = -1L)
    }

    internal fun downloading(runId: Long, title: String, source: String) = mutate(runId) {
        samples.clear()
        recorded = false
        it.copy(
            phase = ProgressPhase.DOWNLOADING, title = title, source = source, doneBytes = 0L, totalBytes = -1L
        )
    }

    internal fun checking(runId: Long) = mutate(runId) { it.copy(phase = ProgressPhase.CHECKING) }

    internal fun admitted(runId: Long) = mutate(runId) { it.copy(admitted = it.admitted + 1) }

    internal fun skipped(runId: Long) = mutate(runId) { it.copy(skipped = it.skipped + 1) }

    internal fun error(runId: Long, error: ProgressError) = mutate(runId) { it.copy(error = error) }

    internal fun end(runId: Long) = mutate(runId) {
        samples.clear()
        it.copy(
            phase = ProgressPhase.IDLE,
            doneBytes = 0L,
            totalBytes = -1L,
            bytesPerSecond = 0L,
            endedAtEpochMillis = wallClock()
        )
    }

    private fun mutate(runId: Long, change: (ProgressSnapshot) -> ProgressSnapshot) = synchronized(lock) {
        if (state.runId == runId && state.phase != ProgressPhase.IDLE) state = change(state)
    }

    private fun bytes(done: Long, total: Long, finished: Boolean) = synchronized(lock) {
        if (state.phase != ProgressPhase.DOWNLOADING) return@synchronized
        val now = clock.elapsedMillis()
        val bytes = maxOf(done, state.doneBytes, 0L)
        if (!finished && recorded && now - recordedAt < UPDATE_MILLIS && bytes - recordedBytes < UPDATE_BYTES) {
            return@synchronized
        }
        recorded = true
        recordedAt = now
        recordedBytes = bytes
        samples.addLast(Sample(now, bytes))
        while (samples.isNotEmpty() && now - samples.first().atMillis > WINDOW_MILLIS) samples.removeFirst()
        // A figure the data has already overtaken cannot be trusted; show an indeterminate bar instead.
        val trusted = if (total > 0 && bytes <= total) total else -1L
        state = state.copy(doneBytes = bytes, totalBytes = trusted)
    }

    private fun speedNow(): Long {
        val now = clock.elapsedMillis()
        if (samples.size < 2 || now - samples.last().atMillis > STALL_MILLIS) return 0L
        val first = samples.firstOrNull { now - it.atMillis <= WINDOW_MILLIS } ?: return 0L
        val last = samples.last()
        val span = last.atMillis - first.atMillis
        if (span <= 0L || last.bytes < first.bytes) return 0L
        return (last.bytes - first.bytes) * 1000L / span
    }

    companion object {
        const val UPDATE_MILLIS = 250L
        const val UPDATE_BYTES = 256L * 1024
        const val WINDOW_MILLIS = 3_000L
        const val STALL_MILLIS = 2_000L
    }
}

/** What a refresher or streamer reports to. Calls after [end] (or after a newer run began) are ignored. */
internal class ProgressRun internal constructor(private val tracker: ProgressTracker, val id: Long) {
    fun discovering(source: String? = null) = tracker.discovering(id, source)

    fun downloading(title: String, source: String) = tracker.downloading(id, title, source)

    fun checking() = tracker.checking(id)

    fun admitted() = tracker.admitted(id)

    fun skipped() = tracker.skipped(id)

    fun error(kind: ProgressErrorKind, message: String, source: String? = null) =
        tracker.error(id, ProgressError(kind, message, source))

    fun fail(error: Throwable) = error(
        ProgressErrorKind.UNEXPECTED, error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    )

    fun end() = tracker.end(id)
}

/** The app-wide trackers, one per kind, so saving and streaming never share a slot. */
internal object RefreshProgress {
    val saving: ProgressTracker by lazy { ProgressTracker(ProgressKind.SAVING) }
    val streaming: ProgressTracker by lazy { ProgressTracker(ProgressKind.STREAMING) }
}
