package com.cosmos.screensaver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class RefreshProgressTest {
    private var mono = 0L
    private var wall = 1_000_000L

    private fun tracker(kind: ProgressKind = ProgressKind.SAVING) =
        ProgressTracker(kind, clock = { mono }, wallClock = { wall })

    private fun ProgressTracker.feed(done: Long, total: Long, finished: Boolean = false) =
        observer.onProgress(done, total, finished)

    @Test
    fun startsIdleAndBeginsARunBeforeDiscovery() {
        val tracker = tracker()
        assertEquals(ProgressPhase.IDLE, tracker.snapshot().phase)
        assertEquals(0L, tracker.snapshot().runId)

        val run = tracker.begin()

        val snapshot = tracker.snapshot()
        assertEquals(ProgressPhase.STARTING, snapshot.phase)
        assertEquals(run.id, snapshot.runId)
        assertEquals(wall, snapshot.startedAtEpochMillis)
        assertTrue(snapshot.active)
    }

    @Test
    fun followsTheWalkThroughDiscoveryDownloadAndChecking() {
        val tracker = tracker()
        val run = tracker.begin()

        run.discovering("ESA Webb")
        assertEquals(ProgressPhase.DISCOVERING, tracker.snapshot().phase)
        assertEquals("ESA Webb", tracker.snapshot().source)

        run.downloading("Pillars", "ESA Webb")
        assertEquals(ProgressPhase.DOWNLOADING, tracker.snapshot().phase)
        assertEquals("Pillars", tracker.snapshot().title)

        run.checking()
        assertEquals(ProgressPhase.CHECKING, tracker.snapshot().phase)

        run.admitted()
        run.skipped()
        run.skipped()
        run.end()
        val done = tracker.snapshot()
        assertEquals(ProgressPhase.IDLE, done.phase)
        assertEquals(1, done.admitted)
        assertEquals(2, done.skipped)
        assertEquals("Pillars", done.title)
        assertEquals(wall, done.endedAtEpochMillis)
        assertFalse(done.active)
    }

    @Test
    fun aStaleRunCannotEndOrChangeANewerRun() {
        val tracker = tracker()
        val old = tracker.begin()
        val newer = tracker.begin()
        newer.discovering("NASA APOD")

        old.end()
        old.admitted()
        old.error(ProgressErrorKind.DOWNLOAD, "late")

        val snapshot = tracker.snapshot()
        assertEquals(newer.id, snapshot.runId)
        assertEquals(ProgressPhase.DISCOVERING, snapshot.phase)
        assertEquals(0, snapshot.admitted)
        assertNull(snapshot.error)
    }

    @Test
    fun callsAfterEndAreIgnored() {
        val tracker = tracker()
        val run = tracker.begin()
        run.end()
        run.discovering("late")
        run.admitted()
        assertEquals(ProgressPhase.IDLE, tracker.snapshot().phase)
        assertEquals(0, tracker.snapshot().admitted)
    }

    @Test
    fun aNewRunClearsCountersAndTheEarlierError() {
        val tracker = tracker()
        val first = tracker.begin()
        first.admitted()
        first.error(ProgressErrorKind.DISCOVERY, "offline", "NASA")
        first.end()
        assertNotNull(tracker.snapshot().error)

        tracker.begin()

        assertEquals(0, tracker.snapshot().admitted)
        assertNull(tracker.snapshot().error)
    }

    @Test
    fun theLastErrorSurvivesGoingIdle() {
        val tracker = tracker()
        val run = tracker.begin()
        run.error(ProgressErrorKind.DOWNLOAD, "HTTP 500", "ESA Webb")
        run.end()
        val error = tracker.snapshot().error!!
        assertEquals(ProgressErrorKind.DOWNLOAD, error.kind)
        assertEquals("HTTP 500", error.message)
        assertEquals("ESA Webb", error.source)
    }

    @Test
    fun failRecordsAnUnexpectedErrorEvenWithoutAMessage() {
        val tracker = tracker()
        val run = tracker.begin()
        run.fail(IllegalStateException())
        assertEquals(ProgressErrorKind.UNEXPECTED, tracker.snapshot().error!!.kind)
        assertEquals("IllegalStateException", tracker.snapshot().error!!.message)
    }

    @Test
    fun bytesAreIgnoredOutsideADownload() {
        val tracker = tracker()
        val run = tracker.begin()
        tracker.feed(500, 1000)
        assertEquals(0L, tracker.snapshot().doneBytes)
        run.discovering("x")
        tracker.feed(500, 1000)
        assertEquals(0L, tracker.snapshot().doneBytes)
    }

    @Test
    fun publishesTheDeclaredTotalAndTheFinalByteCount() {
        val tracker = tracker()
        tracker.begin().downloading("A", "S")

        tracker.feed(0, 4_000_000)
        assertEquals(4_000_000L, tracker.snapshot().totalBytes)
        assertEquals(0f, tracker.snapshot().fraction!!, 0f)

        mono += 10
        tracker.feed(4_000_000, 4_000_000, finished = true)
        assertEquals(4_000_000L, tracker.snapshot().doneBytes)
        assertEquals(1f, tracker.snapshot().fraction!!, 0f)
    }

    @Test
    fun unknownOrInvalidLengthStaysIndeterminate() {
        val tracker = tracker()
        tracker.begin().downloading("A", "S")

        tracker.feed(0, -1)
        assertEquals(-1L, tracker.snapshot().totalBytes)
        assertNull(tracker.snapshot().fraction)

        mono += 1000
        tracker.feed(1000, 0)
        assertEquals(-1L, tracker.snapshot().totalBytes)
        assertNull(tracker.snapshot().fraction)
    }

    @Test
    fun aTotalTheDataOvertakesBecomesIndeterminateRatherThanBeingTrusted() {
        val tracker = tracker()
        tracker.begin().downloading("A", "S")
        tracker.feed(0, 1000)
        mono += 1000
        tracker.feed(1500, 1000)

        val snapshot = tracker.snapshot()
        assertEquals(1500L, snapshot.doneBytes)
        assertEquals(-1L, snapshot.totalBytes)
        assertNull(snapshot.fraction)
    }

    @Test
    fun theBarIsClampedEvenIfTheSnapshotIsBuiltOversized() {
        val oversized = tracker().snapshot().copy(doneBytes = 20, totalBytes = 10)
        assertEquals(1f, oversized.fraction!!, 0f)
    }

    @Test
    fun updatesAreThrottledButTheFinalOneAlwaysLands() {
        val tracker = tracker()
        tracker.begin().downloading("A", "S")
        tracker.feed(0, 10_000_000)

        mono += 10
        tracker.feed(1000, 10_000_000)
        assertEquals(0L, tracker.snapshot().doneBytes)

        mono += 300
        tracker.feed(2000, 10_000_000)
        assertEquals(2000L, tracker.snapshot().doneBytes)

        mono += 1
        tracker.feed(2000 + 300 * 1024, 10_000_000)
        assertEquals(2000L + 300 * 1024, tracker.snapshot().doneBytes)

        mono += 1
        tracker.feed(2500 + 300 * 1024, 10_000_000, finished = true)
        assertEquals(2500L + 300 * 1024, tracker.snapshot().doneBytes)
    }

    @Test
    fun speedIsMeasuredOverTheRecentWindow() {
        val tracker = tracker()
        tracker.begin().downloading("A", "S")
        tracker.feed(0, 100_000_000)
        for (step in 1..8) {
            mono += 250
            tracker.feed(step * 500_000L, 100_000_000)
        }
        assertEquals(2_000_000L, tracker.snapshot().bytesPerSecond)
    }

    @Test
    fun speedFallsToZeroWhenSamplesStop() {
        val tracker = tracker()
        tracker.begin().downloading("A", "S")
        tracker.feed(0, 100_000_000)
        mono += 500
        tracker.feed(1_000_000, 100_000_000)
        assertTrue(tracker.snapshot().bytesPerSecond > 0)

        mono += ProgressTracker.STALL_MILLIS + 1
        assertEquals(0L, tracker.snapshot().bytesPerSecond)
    }

    @Test
    fun speedWithoutElapsedTimeIsZeroNotAnError() {
        val tracker = tracker()
        tracker.begin().downloading("A", "S")
        tracker.feed(0, 100)
        tracker.feed(100, 100, finished = true)
        assertEquals(0L, tracker.snapshot().bytesPerSecond)
    }

    @Test
    fun eachNewFileStartsAFreshSpeedWindow() {
        val tracker = tracker()
        val run = tracker.begin()
        run.downloading("A", "S")
        tracker.feed(0, 100_000_000)
        mono += 500
        tracker.feed(5_000_000, 100_000_000)
        assertEquals(10_000_000L, tracker.snapshot().bytesPerSecond)

        run.checking()
        run.downloading("B", "S")
        assertEquals(0L, tracker.snapshot().bytesPerSecond)
        assertEquals(0L, tracker.snapshot().doneBytes)
        tracker.feed(0, 1_000_000)
        mono += 500
        tracker.feed(100_000, 1_000_000)
        assertEquals(200_000L, tracker.snapshot().bytesPerSecond)
    }

    @Test
    fun speedIsZeroOutsideDownloadingAndAfterEnd() {
        val tracker = tracker()
        val run = tracker.begin()
        run.downloading("A", "S")
        tracker.feed(0, 100_000_000)
        mono += 500
        tracker.feed(5_000_000, 100_000_000)
        run.checking()
        assertEquals(0L, tracker.snapshot().bytesPerSecond)
        run.end()
        assertEquals(0L, tracker.snapshot().bytesPerSecond)
        assertEquals(0L, tracker.snapshot().doneBytes)
    }

    @Test
    fun theWallClockMovingDoesNotAffectSpeed() {
        val tracker = tracker()
        tracker.begin().downloading("A", "S")
        tracker.feed(0, 100_000_000)
        mono += 500
        wall -= 3_600_000L
        tracker.feed(1_000_000, 100_000_000)
        assertEquals(2_000_000L, tracker.snapshot().bytesPerSecond)
    }

    @Test
    fun savingAndStreamingSlotsAreIndependent() {
        val saving = tracker(ProgressKind.SAVING)
        val streaming = tracker(ProgressKind.STREAMING)
        val a = saving.begin()
        val b = streaming.begin()
        a.downloading("Saved one", "NASA")
        saving.observer.onProgress(0, 100, false)
        b.downloading("Streamed one", "ESA")
        mono += 1000
        streaming.observer.onProgress(900, 1000, false)

        assertEquals("Saved one", saving.snapshot().title)
        assertEquals(0L, saving.snapshot().doneBytes)
        assertEquals("Streamed one", streaming.snapshot().title)
        assertEquals(900L, streaming.snapshot().doneBytes)
        assertEquals(ProgressKind.SAVING, saving.snapshot().kind)
        assertEquals(ProgressKind.STREAMING, streaming.snapshot().kind)

        b.end()
        assertEquals(ProgressPhase.DOWNLOADING, saving.snapshot().phase)
    }

    @Test
    fun snapshotsStayCoherentWhileAnotherThreadUpdates() {
        val real = ProgressTracker(ProgressKind.SAVING)
        val run = real.begin()
        val start = CountDownLatch(1)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val bad = java.util.concurrent.atomic.AtomicInteger()
        val writer = thread {
            start.await()
            var n = 0
            while (!stop.get()) {
                n++
                // Title and total always move together: the total is 1000 * the title's number.
                run.downloading("T$n", "S$n")
                real.observer.onProgress(0, n * 1000L, false)
            }
        }
        val reader = thread {
            start.await()
            repeat(20_000) {
                val s = real.snapshot()
                val title = s.title
                if (title != null && s.totalBytes > 0) {
                    val n = title.substring(1).toLong()
                    if (s.totalBytes != n * 1000L || s.source != "S$n") bad.incrementAndGet()
                }
            }
        }
        start.countDown()
        reader.join()
        stop.set(true)
        writer.join()
        assertEquals(0, bad.get())
    }
}
