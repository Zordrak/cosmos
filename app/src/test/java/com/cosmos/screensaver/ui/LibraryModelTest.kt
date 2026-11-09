package com.cosmos.screensaver.ui

import com.cosmos.screensaver.catalogue.BlockedRecord
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.PictureStats
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.sync.ProgressError
import com.cosmos.screensaver.sync.ProgressErrorKind
import com.cosmos.screensaver.sync.ProgressKind
import com.cosmos.screensaver.sync.ProgressPhase
import com.cosmos.screensaver.sync.ProgressSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryModelTest {
    private fun entry(id: String, sha: String? = null, admitted: Long = 3L, title: String? = null): CatalogueEntry {
        val base = CatalogueStoreTest.entry(id)
        return base.copy(
            sha256 = sha ?: base.sha256,
            admittedAtEpochMillis = admitted,
            title = title ?: base.title
        )
    }

    @Test
    fun formatSpeedAndFraction() {
        assertEquals("", LibraryFormat.formatSpeed(0))
        assertEquals(ByteSizes.format(2_500_000) + "/s", LibraryFormat.formatSpeed(2_500_000))
        assertNull(LibraryFormat.progressFraction(10, -1))
        assertNull(LibraryFormat.progressFraction(10, 0))
        assertEquals(0.25f, LibraryFormat.progressFraction(25, 100)!!, 0.0001f)
        assertEquals(1f, LibraryFormat.progressFraction(500, 100)!!, 0.0001f)
        assertEquals(0f, LibraryFormat.progressFraction(-5, 100)!!, 0.0001f)
    }

    @Test
    fun thumbnailSampleSizeNeverDropsBelowTheTargetAndIsAPowerOfTwo() {
        assertEquals(1, LibraryFormat.thumbnailSampleSize(100, 80, 192))
        assertEquals(1, LibraryFormat.thumbnailSampleSize(300, 200, 192))
        assertEquals(2, LibraryFormat.thumbnailSampleSize(400, 300, 192))
        assertEquals(16, LibraryFormat.thumbnailSampleSize(4000, 2250, 192))
        assertEquals(1, LibraryFormat.thumbnailSampleSize(0, 0, 192))
        assertEquals(1, LibraryFormat.thumbnailSampleSize(4000, 2250, 0))
        // Portrait pictures use the longer side.
        assertEquals(16, LibraryFormat.thumbnailSampleSize(2250, 4000, 192))
        listOf(1000, 4096, 6000, 20000).forEach { edge ->
            val s = LibraryFormat.thumbnailSampleSize(edge, edge / 2, 960)
            assertTrue(s and (s - 1) == 0)
            assertTrue(edge / s >= 960)
        }
    }

    @Test
    fun agesAndShownCountsReadNaturally() {
        assertEquals("just now", LibraryFormat.describeAge(-5))
        assertEquals("just now", LibraryFormat.describeAge(30_000))
        assertEquals("1 minute ago", LibraryFormat.describeAge(60_000))
        assertEquals("5 minutes ago", LibraryFormat.describeAge(5 * 60_000L))
        assertEquals("3 hours ago", LibraryFormat.describeAge(3 * 3_600_000L))
        assertEquals("2 days ago", LibraryFormat.describeAge(2 * 86_400_000L))
        assertEquals("Not shown yet", LibraryFormat.describeShown(0, 0, 1000))
        assertEquals("Shown 1 time, last 1 hour ago", LibraryFormat.describeShown(1, 1_000L, 1_000L + 3_600_000L))
        assertEquals("Shown 4 times", LibraryFormat.describeShown(4, 0, 1000))
    }

    @Test
    fun aliasesOfOnePictureAreOneRowAndOrderIsNewestFirstThenStable() {
        val sha = "a".repeat(64)
        val state = UserState(favourites = setOf(sha), stats = mapOf(sha to PictureStats(3, 500, 9)))
        val rows = LibraryRows.build(
            listOf(entry("x1", sha, admitted = 10), entry("x2", sha, admitted = 20), entry("older", admitted = 5), entry("tie", admitted = 5)),
            state,
            eligibleIdentities = setOf(sha)
        )

        assertEquals(3, rows.size)
        val merged = rows.first()
        assertEquals(sha, merged.id)
        assertEquals(2, merged.aliases.size)
        assertTrue(merged.favourite)
        assertTrue(merged.inCurrentChoices)
        assertEquals(3L, merged.shownCount)
        assertEquals(20L, merged.admittedAtEpochMillis)
        assertFalse(rows[1].inCurrentChoices)
        assertEquals(rows.drop(1).map { it.id }.sorted(), rows.drop(1).map { it.id })
    }

    @Test
    fun aBlankHashFallsBackToTheSourceKeyAsIdentity() {
        val e = entry("nohash", sha = "")
        val rows = LibraryRows.build(listOf(e), UserState(), emptySet())
        assertEquals(e.key, rows.single().id)
    }

    @Test
    fun selectionFollowsThePictureNotThePosition() {
        val old = listOf("a", "b", "c", "d")
        // A new picture arrives at the top: the selection stays on "c".
        assertEquals(3, LibraryRows.resolveSelection(listOf("n", "a", "b", "c", "d"), "c", old))
        // The selected picture is removed: the next one takes its place.
        assertEquals(2, LibraryRows.resolveSelection(listOf("a", "b", "d"), "c", old))
        // The last picture is removed: the previous one is selected.
        assertEquals(2, LibraryRows.resolveSelection(listOf("a", "b", "c"), "d", old))
        // The neighbours are gone too: nearest surviving one after, else before.
        assertEquals(0, LibraryRows.resolveSelection(listOf("d"), "b", old))
        assertEquals(-1, LibraryRows.resolveSelection(emptyList(), "a", old))
        assertEquals(0, LibraryRows.resolveSelection(listOf("a"), null, old))
        assertEquals(0, LibraryRows.resolveSelection(listOf("a", "b"), "unknown", old))
    }

    @Test
    fun blockedPicturesAreListedMostRecentFirst() {
        val state = UserState(
            blocked = listOf(
                BlockedRecord("1", setOf("k1"), null, "One", "S", 10),
                BlockedRecord("2", setOf("k2"), "h", "Two", "S", 30),
                BlockedRecord("3", setOf("k3"), null, "Three", "S", 20)
            )
        )
        assertEquals(listOf("2", "3", "1"), LibraryRows.blocked(state).map { it.id })
    }

    private fun details(row: LibraryRow, exists: Boolean = true) =
        LibraryDetails.sections(row, nowMillis = 1_000_000L, formatDate = { "D$it" }, fileExists = exists)

    private fun rowOf(e: CatalogueEntry, favourite: Boolean = false, inChoices: Boolean = true) =
        LibraryRow(e.sha256, listOf(e), favourite, inChoices, 0, 0)

    @Test
    fun detailsShowFullProvenanceForAValidatedPicture() {
        val e = entry("a")
        val text = details(rowOf(e)).flatMap { it.lines }.joinToString("\n")
        assertTrue(text.contains("Credit: ESA/Synthetic"))
        assertTrue(text.contains("Licence: CC BY 4.0"))
        assertTrue(text.contains(e.sourcePageUrl))
        assertTrue(text.contains(e.assetUrl))
        assertTrue(text.contains("Evidence: statement"))
        assertTrue(text.contains("Not shown yet"))
        assertEquals("ESA/Hubble", details(rowOf(e)).last().heading)
    }

    @Test
    fun detailsExplainEveryUnusualState() {
        val unchecked = entry("a").copy(
            rightsBasis = CatalogueEntry.RIGHTS_NOT_CHECKED, licenceName = "", licenceUrl = "", evidence = emptyList(), credit = ""
        )
        val text = details(rowOf(unchecked, favourite = true, inChoices = false), exists = false).flatMap { it.lines }.joinToString("\n")
        assertTrue(text.contains("Licence: not checked"))
        assertTrue(text.contains("Credit: not recorded"))
        assertTrue(text.contains("Favourite"))
        assertTrue(text.contains("Not in your current choices"))
        assertTrue(text.contains("missing"))

        val hidden = unchecked.copy(hiddenReason = "NOT_A_PHOTOGRAPH")
        val hiddenText = details(rowOf(hidden)).flatMap { it.lines }.joinToString("\n")
        assertTrue(hiddenText.contains("Kept but not shown: NOT_A_PHOTOGRAPH"))
        assertFalse(hiddenText.contains("Not in your current choices"))
    }

    @Test
    fun aliasesGetOneProvenanceSectionEach() {
        val sha = "b".repeat(64)
        val row = LibraryRow(sha, listOf(entry("a", sha), entry("b", sha)), false, true, 0, 0)
        assertEquals(3, details(row).size)
    }

    private fun snapshot(
        phase: ProgressPhase,
        done: Long = 0, total: Long = -1, speed: Long = 0, admitted: Int = 0, skipped: Int = 0,
        ended: Long = 0, error: ProgressError? = null, source: String? = null, title: String? = null
    ) = ProgressSnapshot(ProgressKind.SAVING, 1, phase, source, title, done, total, speed, admitted, skipped, 1, ended, error)

    @Test
    fun statusSaysWhatCosmosIsDoing() {
        val now = 100_000_000L
        val never = LibraryStatus.saving(snapshot(ProgressPhase.IDLE), 0L, now)
        assertEquals(StatusKind.NEVER_CHECKED, never.kind)
        assertFalse(never.busy)

        val idle = LibraryStatus.saving(snapshot(ProgressPhase.IDLE), now - 3 * 3_600_000L, now)
        assertEquals("Idle \u00B7 last checked 3 hours ago", LibraryStatus.describe(idle).headline)

        val discovering = LibraryStatus.describe(LibraryStatus.saving(snapshot(ProgressPhase.DISCOVERING, source = "NASA"), 0, now))
        assertEquals("Looking for new pictures in NASA\u2026", discovering.headline)

        val downloading = LibraryStatus.saving(
            snapshot(ProgressPhase.DOWNLOADING, 100, 400, 50, admitted = 3, skipped = 5, source = "ESA", title = "Pillars"), 0, now
        )
        val words = LibraryStatus.describe(downloading)
        assertEquals("Downloading Pillars from ESA", words.headline)
        assertEquals(ByteSizes.format(100) + " of " + ByteSizes.format(400) + " \u00B7 " + ByteSizes.format(50) + "/s", words.detail)
        assertEquals("This run: 3 added, 5 skipped", words.runLine)
        assertEquals(0.25f, downloading.fraction!!, 0.0001f)
        assertTrue(downloading.busy)

        val unknownSize = LibraryStatus.describe(LibraryStatus.saving(snapshot(ProgressPhase.DOWNLOADING, 100, -1), 0, now))
        assertEquals(ByteSizes.format(100) + " so far", unknownSize.detail)
        assertNull(LibraryStatus.saving(snapshot(ProgressPhase.DOWNLOADING, 100, -1), 0, now).fraction)
    }

    @Test
    fun anErrorSurvivesGoingIdleAndStreamingIsOnlyShownWhileActive() {
        val problem = ProgressError(ProgressErrorKind.DOWNLOAD, "NASA did not answer", "NASA")
        val idle = LibraryStatus.saving(snapshot(ProgressPhase.IDLE, ended = 5, error = problem), 4, 100)
        assertEquals("NASA did not answer", LibraryStatus.describe(idle).problem)

        assertNull(LibraryStatus.streaming(snapshot(ProgressPhase.IDLE), 100))
        val streaming = LibraryStatus.streaming(snapshot(ProgressPhase.DOWNLOADING, 1, 2, title = "Nebula"), 100)!!
        assertTrue(LibraryStatus.describe(streaming, "Online playback \u00B7 ").headline.startsWith("Online playback"))
    }
}
