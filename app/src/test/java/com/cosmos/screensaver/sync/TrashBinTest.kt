package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.FileOps
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.rights.AssetProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrashBinTest {
    private lateinit var dir: File
    private var mono = 1_000L
    private var wall = 1_000_000L
    private var limitValue = 500L

    private class Crash : RuntimeException()

    /** Fails (returns false) or crashes on the Nth mutating operation; shared by files, store and size. */
    private class Faulty(private val failAt: Int, private val crash: Boolean) : FileOps {
        var count = 0
        fun tick(): Boolean {
            count++
            if (count == failAt) {
                if (crash) throw Crash()
                return false
            }
            return true
        }

        override fun rename(from: File, to: File) = tick() && from.renameTo(to)
        override fun delete(file: File) = tick() && file.delete()
        override fun writeSynced(file: File, bytes: ByteArray) {
            if (tick()) FileOps.Real.writeSynced(file, bytes)
        }
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cosmos-trash").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store(ops: FileOps = FileOps.Real) = CatalogueStore(dir, ops = ops)
    private fun users() = UserStateStore(dir)

    private fun limitPort(faulty: Faulty? = null) = object : LimitPort {
        override fun current() = limitValue
        override fun write(value: Long): Boolean {
            if (faulty != null && !faulty.tick()) return false
            limitValue = value
            return true
        }
    }

    private fun bin(ops: FileOps = FileOps.Real, faulty: Faulty? = null, store: CatalogueStore = store(ops)) = TrashBin(
        store = store,
        userState = users(),
        limit = limitPort(faulty),
        ops = ops,
        monotonicMillis = { mono },
        wallMillis = { wall }
    )

    private fun content(entry: CatalogueEntry) = "bytes-${entry.providerId}"

    private fun seed(vararg ids: String): List<CatalogueEntry> {
        val entries = ids.map { CatalogueStoreTest.entry(it) }
        val store = store()
        store.imagesDirectory.mkdirs()
        entries.forEach { File(store.imagesDirectory, it.fileName).writeText(content(it)) }
        store.save(Catalogue(entries = entries))
        return entries
    }

    private fun live() = store().loadStrict().entries

    private fun trashFiles() = File(dir, "trash").walkTopDown().filter { it.isFile }.toList()

    private fun all() = TrashBin.Chooser.everything()

    private fun only(vararg entries: CatalogueEntry) = TrashBin.Chooser { _, _ ->
        entries.map { it.sha256 }.toSet()
    }

    private fun assertOriginal(entries: List<CatalogueEntry>, expectedLimit: Long) {
        assertEquals(entries.map { it.key }.sorted(), live().map { it.key }.sorted())
        entries.forEach {
            assertEquals(content(it), File(File(dir, "images"), it.fileName).readText())
        }
        assertEquals(emptyList<File>(), trashFiles())
        assertEquals(expectedLimit, limitValue)
    }

    @Test
    fun aRemovalMovesFilesAsideAndUndoPutsEverythingBack() {
        val entries = seed("heic9901a", "heic9902a", "heic9903a")
        val bin = bin()

        val preview = bin.preview(only(entries[0], entries[1]))
        assertEquals(2, preview.pictures)
        assertEquals(2, preview.files)

        val started = bin.begin(TrashBin.Kind.TIDY, only(entries[0], entries[1])) as TrashBin.BeginResult.Started
        assertEquals(2, started.pending.pictures)
        assertFalse(started.pending.restoresLimit)
        assertEquals(listOf(entries[2].key), live().map { it.key })
        assertFalse(File(dir, "images/${entries[0].fileName}").exists())
        assertTrue(bin.bytesOnDisk() > 0)
        assertTrue(bin.isStashed(entries[0].key, ""))
        assertTrue(bin.isStashed("other", entries[1].sha256))
        assertFalse(bin.isStashed(entries[2].key, entries[2].sha256))

        val undone = bin.undo()
        assertEquals(TrashBin.UndoResult.Status.DONE, undone.status)
        assertEquals(2, undone.restoredPictures)
        assertNull(bin.pending())
        assertEquals(0L, bin.bytesOnDisk())
        assertOriginal(entries, 500L)
    }

    @Test
    fun aSmallerSizeIsUndoneTogetherWithItsRemoval() {
        val entries = seed("heic9901a", "heic9902a")
        val bin = bin()

        val started = bin.begin(TrashBin.Kind.SHRINK, only(entries[0]), newLimit = 300L) as TrashBin.BeginResult.Started
        assertTrue(started.pending.restoresLimit)
        assertEquals(300L, limitValue)
        assertTrue(bin.begin(TrashBin.Kind.TIDY, all()) is TrashBin.BeginResult.Busy)

        val undone = bin.undo()
        assertEquals(TrashBin.UndoResult.Status.DONE, undone.status)
        assertTrue(undone.limitRestored)
        assertOriginal(entries, 500L)
    }

    @Test
    fun aSizeThatNeedsNoRemovalIsJustSaved() {
        seed("heic9901a")
        val bin = bin()
        val result = bin.begin(TrashBin.Kind.SHRINK, only(), newLimit = 900L)
        assertEquals(TrashBin.BeginResult.NothingToRemove(true), result)
        assertEquals(900L, limitValue)
        assertNull(bin.pending())
    }

    @Test
    fun undoAfterTheWindowIsRefusedAndTheFilesAreFinallyDeleted() {
        val entries = seed("heic9901a", "heic9902a")
        val bin = bin()
        bin.begin(TrashBin.Kind.CLEAR, all())
        mono += TrashBin.UNDO_WINDOW_MILLIS + 1

        assertEquals(TrashBin.UndoResult.Status.EXPIRED, bin.undo().status)
        assertEquals(emptyList<CatalogueEntry>(), live())
        assertEquals(emptyList<File>(), trashFiles())
        assertEquals(0L, bin.bytesOnDisk())
        assertNull(bin.pending())
        assertEquals(TrashBin.UndoResult.Status.NOTHING_TO_UNDO, bin.undo().status)
        assertFalse(File(dir, "images/${entries[0].fileName}").exists())
    }

    @Test
    fun theExpiryTickFinalizesAndTellsListeners() {
        seed("heic9901a")
        val bin = bin()
        var told = 0
        bin.addSettledListener { told++ }
        bin.begin(TrashBin.Kind.CLEAR, all())
        assertEquals(0, told)

        mono += TrashBin.UNDO_WINDOW_MILLIS + 1
        wall += TrashBin.UNDO_WINDOW_MILLIS + 1
        bin.recoverAndExpire()

        assertNull(bin.pending())
        assertEquals(1, told)
        assertEquals(emptyList<File>(), trashFiles())
    }

    @Test
    fun favouritesAreAlwaysKeptEvenWhenMadeWhileAConfirmationIsOpen() {
        val entries = seed("heic9901a", "heic9902a", "heic9903a")
        val bin = bin()
        users().setFavourite(entries[0].sha256, true)

        val preview = bin.preview(all())
        assertEquals(2, preview.pictures)
        assertEquals(1, preview.favouritesKept)

        users().setFavourite(entries[1].sha256, true)
        val started = bin.begin(TrashBin.Kind.CLEAR, all()) as TrashBin.BeginResult.Started
        assertEquals(1, started.pending.pictures)
        assertEquals(listOf(entries[0].key, entries[1].key), live().map { it.key })
    }

    @Test
    fun aSharedFileIsMovedOnceAndEveryAliasComesBack() {
        val first = seed("heic9901a", "heic9902a")[0]
        val alias = first.copy(provider = AssetProvider.NASA, providerId = "PIA1")
        store().update { it.copy(entries = it.entries + alias) }
        val bin = bin()

        val started = bin.begin(TrashBin.Kind.TIDY, only(first)) as TrashBin.BeginResult.Started
        assertEquals(1, started.pending.pictures)
        assertEquals(1, started.pending.files)
        assertTrue(bin.isStashed(alias.key, ""))

        assertEquals(TrashBin.UndoResult.Status.DONE, bin.undo().status)
        assertTrue(live().any { it.key == alias.key })
        assertTrue(live().any { it.key == first.key })
    }

    @Test
    fun aPictureBlockedDuringTheWindowStaysOut() {
        val entries = seed("heic9901a", "heic9902a")
        val bin = bin()
        bin.begin(TrashBin.Kind.CLEAR, all())
        users().block(listOf(entries[0]), 5L)

        val undone = bin.undo()
        assertEquals(TrashBin.UndoResult.Status.DONE, undone.status)
        assertEquals(1, undone.restoredPictures)
        assertEquals(1, undone.skippedPictures)
        assertEquals(listOf(entries[1].key), live().map { it.key })
        assertFalse(File(dir, "images/${entries[0].fileName}").exists())
        assertEquals(emptyList<File>(), trashFiles())
    }

    @Test
    fun picturesSavedMeanwhileSurviveUndo() {
        val entries = seed("heic9901a")
        val bin = bin()
        bin.begin(TrashBin.Kind.CLEAR, all())
        val fresh = CatalogueStoreTest.entry("heic9909a")
        store().imagesDirectory.mkdirs()
        File(dir, "images/${fresh.fileName}").writeText("fresh")
        store().update { it.copy(entries = it.entries + fresh) }

        assertEquals(TrashBin.UndoResult.Status.DONE, bin.undo().status)
        assertEquals(setOf(entries[0].key, fresh.key), live().map { it.key }.toSet())
        assertEquals("fresh", File(dir, "images/${fresh.fileName}").readText())
    }

    @Test
    fun aNewerEntryForTheSameSourceIsNotOverwritten() {
        val entries = seed("heic9901a")
        val bin = bin()
        bin.begin(TrashBin.Kind.CLEAR, all())
        val newer = entries[0].copy(sha256 = "f".repeat(64), fileName = "${"f".repeat(64)}.jpg")
        store().imagesDirectory.mkdirs()
        File(dir, "images/${newer.fileName}").writeText("newer")
        store().update { it.copy(entries = it.entries + newer) }

        val undone = bin.undo()
        assertEquals(1, undone.skippedPictures)
        assertEquals(listOf(newer.fileName), live().map { it.fileName })
        assertEquals(emptyList<File>(), trashFiles())
    }

    @Test
    fun aFileMissingFromTheTrashIsReportedAndTheRestStillComeBack() {
        val entries = seed("heic9901a", "heic9902a")
        val bin = bin()
        bin.begin(TrashBin.Kind.CLEAR, all())
        trashFiles().first { it.name == entries[0].fileName }.delete()

        val undone = bin.undo()
        assertEquals(TrashBin.UndoResult.Status.PARTIAL, undone.status)
        assertEquals(1, undone.failedPictures)
        assertEquals(1, undone.restoredPictures)
        assertEquals(listOf(entries[1].key), live().map { it.key })
    }

    @Test
    fun aLiveChangeSurvivesANewInstanceAndTheWindowIsNotExtended() {
        val entries = seed("heic9901a", "heic9902a")
        bin().begin(TrashBin.Kind.TIDY, only(entries[0]))
        mono += 5_000
        wall += 5_000

        val second = bin()
        assertNull(second.pending())
        second.recoverAndExpire()
        val pending = second.pending()
        assertNotNull(pending)
        assertEquals(10_000L, pending!!.remainingMillis)
        assertTrue(second.isStashed(entries[0].key, ""))

        assertEquals(TrashBin.UndoResult.Status.DONE, second.undo().status)
        assertOriginal(entries, 500L)
    }

    @Test
    fun aChangeThatExpiredWhileTheAppWasClosedIsFinalizedAtStart() {
        val entries = seed("heic9901a", "heic9902a")
        bin().begin(TrashBin.Kind.CLEAR, all())
        wall += TrashBin.UNDO_WINDOW_MILLIS + 1

        val report = bin().recoverAndExpire()
        assertEquals(1, report.finalized)
        assertEquals(emptyList<File>(), trashFiles())
        assertEquals(emptyList<CatalogueEntry>(), live())
        assertFalse(File(dir, "images/${entries[0].fileName}").exists())
    }

    @Test
    fun aClockMovedBackwardsGivesAtMostTheNormalWindow() {
        val entries = seed("heic9901a")
        bin().begin(TrashBin.Kind.CLEAR, all())
        wall -= 600_000

        val second = bin()
        second.recoverAndExpire()
        assertEquals(TrashBin.UNDO_WINDOW_MILLIS, second.pending()!!.remainingMillis)
        assertEquals(TrashBin.UndoResult.Status.DONE, second.undo().status)
        assertOriginal(entries, 500L)
    }

    @Test
    fun unrelatedFilesAreNeverTouched() {
        val entries = seed("heic9901a")
        File(dir, "images/stray.tmp").writeText("stray")
        File(dir, "trash").mkdirs()
        File(dir, "trash/readme.txt").writeText("hello")
        File(dir, "trash/not-a-token").mkdirs()
        File(dir, "trash/not-a-token/keep.jpg").writeText("keep")
        val bin = bin()

        bin.begin(TrashBin.Kind.CLEAR, all())
        bin.undo()
        bin.begin(TrashBin.Kind.CLEAR, all())
        mono += TrashBin.UNDO_WINDOW_MILLIS + 1
        bin.undo()

        assertEquals("stray", File(dir, "images/stray.tmp").readText())
        assertEquals("hello", File(dir, "trash/readme.txt").readText())
        assertEquals("keep", File(dir, "trash/not-a-token/keep.jpg").readText())
        assertFalse(File(dir, "images/${entries[0].fileName}").exists())
    }

    @Test
    fun anUnreadableJournalIsReportedAndItsFilesAreLeftAlone() {
        val token = File(dir, "trash/op-5-0001").apply { mkdirs() }
        File(token, "files").mkdirs()
        File(token, "files/${"a".repeat(64)}.jpg").writeText("precious")
        File(token, "journal.json").writeText("{ not json")
        File(token, "phase.json").writeText("""{"formatVersion":1,"phase":"COMMITTED","deadline":1}""")

        val report = bin().recoverAndExpire()
        assertEquals(1, report.problems.size)
        assertEquals("precious", File(token, "files/${"a".repeat(64)}.jpg").readText())
    }

    @Test
    fun aTornFirstJournalWriteWithNothingMovedIsCleanedUp() {
        val token = File(dir, "trash/op-6-0001").apply { mkdirs() }
        File(token, "files").mkdirs()
        File(token, "journal.json.tmp").writeText("{ torn")

        val report = bin().recoverAndExpire()
        assertTrue(report.problems.isEmpty())
        assertFalse(token.exists())
    }

    @Test
    fun anUnreadableLibraryRefusesWithoutChangingAnything() {
        seed("heic9901a")
        File(dir, UserStateStore.FILE_NAME).writeText("garbage")
        val bin = bin()
        assertTrue(bin.preview(all()).unreadable)
        assertEquals(TrashBin.BeginResult.Unreadable, bin.begin(TrashBin.Kind.CLEAR, all()))
        assertEquals(1, live().size)
        assertEquals(emptyList<File>(), trashFiles())
    }

    @Test
    fun aPictureBeingDownloadedIsNotMoved() {
        val entries = seed("heic9901a")
        val file = File(dir, "images/${entries[0].fileName}")
        com.cosmos.screensaver.catalogue.LibraryMaintenance.claim(file)
        try {
            assertEquals(TrashBin.BeginResult.NothingToRemove(false), bin().begin(TrashBin.Kind.CLEAR, all()))
        } finally {
            com.cosmos.screensaver.catalogue.LibraryMaintenance.release(file)
        }
        assertEquals(1, live().size)
    }

    private fun operationCount(): Int {
        val counting = Faulty(Int.MAX_VALUE, false)
        val entries = seed("heic9901a", "heic9902a", "heic9903a")
        val bin = bin(counting, counting)
        val started = bin.begin(TrashBin.Kind.SHRINK, only(entries[0], entries[1]), newLimit = 300L)
        assertTrue(started is TrashBin.BeginResult.Started)
        return counting.count
    }

    private fun resetLibrary() {
        dir.deleteRecursively()
        dir.mkdirs()
        limitValue = 500L
        mono = 1_000L
        wall = 1_000_000L
    }

    @Test
    fun aCrashAtAnyPointOfARemovalLeavesTheOriginalLibraryOrAnUndoableOne() {
        val total = operationCount()
        assertTrue("expected a meaningful number of steps", total > 10)
        for (point in 1..total) {
            resetLibrary()
            val entries = seed("heic9901a", "heic9902a", "heic9903a")
            val faulty = Faulty(point, crash = true)
            try {
                bin(faulty, faulty).begin(TrashBin.Kind.SHRINK, only(entries[0], entries[1]), newLimit = 300L)
            } catch (expected: Crash) {
                // the process "died" here
            }
            val restarted = bin()
            restarted.recoverAndExpire()
            if (restarted.pending() != null) {
                val undone = restarted.undo()
                assertEquals("undo after crash at $point", TrashBin.UndoResult.Status.DONE, undone.status)
            }
            try {
                assertOriginal(entries, 500L)
            } catch (error: AssertionError) {
                throw AssertionError("crash at step $point: ${error.message}", error)
            }
        }
    }

    @Test
    fun aFailureAtAnyPointOfARemovalRollsBackOrCompletes() {
        val total = operationCount()
        for (point in 1..total) {
            resetLibrary()
            val entries = seed("heic9901a", "heic9902a", "heic9903a")
            val faulty = Faulty(point, crash = false)
            val result = bin(faulty, faulty).begin(TrashBin.Kind.SHRINK, only(entries[0], entries[1]), newLimit = 300L)
            val recovered = bin()
            recovered.recoverAndExpire()
            if (result is TrashBin.BeginResult.Started || recovered.pending() != null) {
                assertEquals("undo after failure at $point", TrashBin.UndoResult.Status.DONE, recovered.undo().status)
            }
            try {
                assertOriginal(entries, 500L)
            } catch (error: AssertionError) {
                throw AssertionError("failure at step $point ($result): ${error.message}", error)
            }
        }
    }

    @Test
    fun aCrashAtAnyPointOfUndoIsFinishedByTheNextStart() {
        resetLibrary()
        val counting = Faulty(Int.MAX_VALUE, false)
        val first = seed("heic9901a", "heic9902a", "heic9903a")
        val counted = bin(counting, counting)
        counted.begin(TrashBin.Kind.SHRINK, only(first[0], first[1]), newLimit = 300L)
        val before = counting.count
        counted.undo()
        val total = counting.count - before
        assertTrue(total > 3)

        for (point in 1..total) {
            resetLibrary()
            val entries = seed("heic9901a", "heic9902a", "heic9903a")
            val setup = Faulty(Int.MAX_VALUE, false)
            val subject = bin(setup, setup)
            subject.begin(TrashBin.Kind.SHRINK, only(entries[0], entries[1]), newLimit = 300L)
            // Rebuild around a faulty set of operations that starts counting at undo.
            val faulty = Faulty(point, crash = true)
            val dying = TrashBin(
                store = store(faulty), userState = users(), limit = limitPort(faulty), ops = faulty,
                monotonicMillis = { mono }, wallMillis = { wall }
            )
            dying.recoverAndExpire()
            try {
                dying.undo()
            } catch (expected: Crash) {
                // the process "died" here
            }
            val restarted = bin()
            restarted.recoverAndExpire()
            if (restarted.pending() != null) restarted.undo()
            try {
                assertOriginal(entries, 500L)
            } catch (error: AssertionError) {
                throw AssertionError("undo crash at step $point: ${error.message}", error)
            }
        }
    }
}
