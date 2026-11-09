package com.cosmos.screensaver.ui

import com.cosmos.screensaver.sync.ProgressError
import com.cosmos.screensaver.sync.ProgressPhase
import com.cosmos.screensaver.sync.ProgressSnapshot

/** Plain-text helpers for the Saved pictures page. Pure, so they are unit-tested without Android. */
internal object LibraryFormat {
    private const val SECOND = 1000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** "2.4 MB/s", or "" when no speed is known yet (callers leave it out rather than show 0). */
    fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0L) "" else ByteSizes.format(bytesPerSecond) + "/s"

    /** 0..1, or null when the total is unknown (show an indeterminate bar). */
    fun progressFraction(done: Long, total: Long): Float? =
        if (total <= 0L) null else (done.coerceAtLeast(0L).toDouble() / total).toFloat().coerceIn(0f, 1f)

    /**
     * The `inSampleSize` that keeps the decoded picture at least [targetLongEdge] pixels on its
     * longer side without decoding more pixels than needed: a power of two, never below 1.
     */
    fun thumbnailSampleSize(width: Int, height: Int, targetLongEdge: Int): Int {
        val longEdge = maxOf(width, height)
        if (longEdge <= 0 || targetLongEdge <= 0) return 1
        var sample = 1
        while (longEdge / (sample * 2) >= targetLongEdge) sample *= 2
        return sample
    }

    /** "just now", "5 minutes ago", "3 hours ago", "2 days ago". A time in the future reads as "just now". */
    fun describeAge(elapsedMillis: Long): String {
        val value = elapsedMillis.coerceAtLeast(0L)
        return when {
            value < MINUTE -> "just now"
            value < HOUR -> plural(value / MINUTE, "minute") + " ago"
            value < DAY -> plural(value / HOUR, "hour") + " ago"
            else -> plural(value / DAY, "day") + " ago"
        }
    }

    /** "Not shown yet" or "Shown 3 times, last 2 hours ago". */
    fun describeShown(count: Long, lastShownAtEpochMillis: Long, nowMillis: Long): String = when {
        count <= 0L -> "Not shown yet"
        lastShownAtEpochMillis <= 0L -> "Shown " + plural(count, "time")
        else -> "Shown " + plural(count, "time") + ", last " + describeAge(nowMillis - lastShownAtEpochMillis)
    }

    private fun plural(count: Long, unit: String) = if (count == 1L) "1 $unit" else "$count ${unit}s"
}

internal enum class StatusKind { NEVER_CHECKED, IDLE, STARTING, DISCOVERING, DOWNLOADING, CHECKING }

/** What the status panel says, before it is turned into words. */
internal data class StatusModel(
    val kind: StatusKind,
    val source: String?,
    val title: String?,
    val doneBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val added: Int,
    val skipped: Int,
    /** For [StatusKind.IDLE]: how long ago the last look for new pictures ended. */
    val ageMillis: Long,
    val problem: ProgressError?
) {
    val busy: Boolean get() = kind != StatusKind.IDLE && kind != StatusKind.NEVER_CHECKED
    val fraction: Float? get() = LibraryFormat.progressFraction(doneBytes, totalBytes)
}

internal data class StatusText(val headline: String, val detail: String, val runLine: String, val problem: String)

internal object LibraryStatus {
    /** The words for [model]. [prefix] labels a second status line, e.g. for online playback. */
    fun describe(model: StatusModel, prefix: String = ""): StatusText {
        val source = model.source?.takeIf { it.isNotBlank() }
        val title = model.title?.takeIf { it.isNotBlank() }
        val headline = prefix + when (model.kind) {
            StatusKind.NEVER_CHECKED -> "Cosmos has not looked for pictures yet"
            StatusKind.IDLE -> "Idle \u00B7 last checked " + LibraryFormat.describeAge(model.ageMillis)
            StatusKind.STARTING -> "Starting\u2026"
            StatusKind.DISCOVERING -> if (source != null) "Looking for new pictures in $source\u2026" else "Looking for new pictures\u2026"
            StatusKind.DOWNLOADING -> listOfNotNull(
                "Downloading", title ?: "a picture", source?.let { "from $it" }
            ).joinToString(" ")
            StatusKind.CHECKING -> if (title != null) "Checking $title\u2026" else "Checking the picture\u2026"
        }
        val detail = if (model.kind == StatusKind.DOWNLOADING) {
            val speed = LibraryFormat.formatSpeed(model.bytesPerSecond)
            val amount = if (model.totalBytes > 0L) {
                ByteSizes.format(model.doneBytes) + " of " + ByteSizes.format(model.totalBytes)
            } else {
                ByteSizes.format(model.doneBytes) + " so far"
            }
            if (speed.isEmpty()) amount else "$amount \u00B7 $speed"
        } else {
            ""
        }
        val runLine = if (model.busy && (model.added > 0 || model.skipped > 0)) {
            "This run: ${model.added} added, ${model.skipped} skipped"
        } else {
            ""
        }
        return StatusText(headline, detail, runLine, model.problem?.message.orEmpty())
    }

    /**
     * The saving status. [lastAttemptAtEpochMillis] is the catalogue's record of the last attempt;
     * it covers earlier app sessions when this session has not run one yet.
     */
    fun saving(snapshot: ProgressSnapshot, lastAttemptAtEpochMillis: Long, nowMillis: Long): StatusModel {
        val kind = when (snapshot.phase) {
            ProgressPhase.STARTING -> StatusKind.STARTING
            ProgressPhase.DISCOVERING -> StatusKind.DISCOVERING
            ProgressPhase.DOWNLOADING -> StatusKind.DOWNLOADING
            ProgressPhase.CHECKING -> StatusKind.CHECKING
            ProgressPhase.IDLE -> {
                if (maxOf(lastAttemptAtEpochMillis, snapshot.endedAtEpochMillis) > 0L) StatusKind.IDLE else StatusKind.NEVER_CHECKED
            }
        }
        val last = maxOf(lastAttemptAtEpochMillis, snapshot.endedAtEpochMillis)
        return model(kind, snapshot, if (last > 0L) nowMillis - last else -1L)
    }

    /** Online playback status, or null while it is not doing anything worth showing. */
    fun streaming(snapshot: ProgressSnapshot, nowMillis: Long): StatusModel? {
        if (!snapshot.active) return null
        val kind = when (snapshot.phase) {
            ProgressPhase.DISCOVERING -> StatusKind.DISCOVERING
            ProgressPhase.DOWNLOADING -> StatusKind.DOWNLOADING
            ProgressPhase.CHECKING -> StatusKind.CHECKING
            else -> StatusKind.STARTING
        }
        return model(kind, snapshot, -1L)
    }

    private fun model(kind: StatusKind, snapshot: ProgressSnapshot, ageMillis: Long) = StatusModel(
        kind = kind,
        source = snapshot.source,
        title = snapshot.title,
        doneBytes = snapshot.doneBytes,
        totalBytes = snapshot.totalBytes,
        bytesPerSecond = snapshot.bytesPerSecond,
        added = snapshot.admitted,
        skipped = snapshot.skipped,
        ageMillis = ageMillis,
        problem = snapshot.error
    )
}
