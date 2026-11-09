package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueJson
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.FileOps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryMigrationTest {
    private lateinit var root: File
    private lateinit var oldDir: File
    private lateinit var newDir: File

    private class Crash : RuntimeException()

    /** Fails (returns false) or crashes on the Nth mutating operation. */
    private class Faulty(private val failAt: Int, private val crash: Boolean) : FileOps {
        var count = 0
        private fun tick(): Boolean {
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
        root = Files.createTempDirectory("cosmos-migration").toFile()
        oldDir = File(root, "cache").apply { mkdirs() }
        newDir = File(root, "files").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun seedOld(vararg ids: String): List<CatalogueEntry> {
        val entries = ids.map { CatalogueStoreTest.entry(it) }
        val store = CatalogueStore(oldDir)
        store.imagesDirectory.mkdirs()
        entries.forEach { store.imageFile(it).writeText("bytes-${it.providerId}") }
        store.save(Catalogue(entries = entries, lastSummary = "kept"))
        return entries
    }

    private fun assertLibraryIntact(entries: List<CatalogueEntry>) {
        val store = CatalogueStore(newDir)
        assertEquals(entries.map { it.key }, store.loadStrict().entries.map { it.key })
        assertEquals("kept", store.loadStrict().lastSummary)
        entries.forEach { assertEquals("bytes-${it.providerId}", store.imageFile(it).readText()) }
        assertFalse(File(oldDir, "catalogue.json").exists())
        assertTrue(File(oldDir, "images").list().isNullOrEmpty())
    }

    @Test
    fun movesALibraryAndLeavesTheStreamScratchAlone() {
        val entries = seedOld("heic9901a", "heic9902a")
        File(oldDir, "stream/images").apply { mkdirs() }.resolve("scratch.jpg").writeText("s")
        File(oldDir, "unrelated.txt").writeText("u")

        val result = LibraryMigration.migrate(oldDir, newDir) as LibraryMigration.Result.Ready

        assertEquals(2, result.movedImages)
        assertEquals(0, result.missingFiles)
        assertLibraryIntact(entries)
        assertTrue(File(oldDir, "stream/images/scratch.jpg").exists())
        assertTrue(File(oldDir, "unrelated.txt").exists())
    }

    @Test
    fun notNeededWhenThereIsNothingToMoveAndRepeatedCallsAreHarmless() {
        assertEquals(LibraryMigration.Result.NotNeeded, LibraryMigration.migrate(oldDir, newDir))
        val entries = seedOld("heic9901a")
        LibraryMigration.migrate(oldDir, newDir)
        assertEquals(LibraryMigration.Result.NotNeeded, LibraryMigration.migrate(oldDir, newDir))
        assertLibraryIntact(entries)
    }

    @Test
    fun resumesAfterAnInterruptionAtEveryFilesystemOperation() {
        for (crashAt in 1..12) {
            tearDown(); setUp()
            val entries = seedOld("heic9901a", "heic9902a", "heic9903a")
            val faulty = Faulty(crashAt, crash = true)
            try {
                LibraryMigration.migrate(oldDir, newDir, faulty)
            } catch (_: Crash) {
            }

            val resumed = LibraryMigration.migrate(oldDir, newDir)

            assertTrue("crash at $crashAt: $resumed", resumed !is LibraryMigration.Result.Failed)
            assertLibraryIntact(entries)
        }
    }

    @Test
    fun aFailedImageRenameFailsWithoutLosingAnything() {
        val entries = seedOld("heic9901a", "heic9902a")

        val failed = LibraryMigration.migrate(oldDir, newDir, Faulty(2, crash = false))

        assertTrue(failed is LibraryMigration.Result.Failed)
        assertTrue(File(oldDir, "catalogue.json").exists())
        assertFalse(File(newDir, "catalogue.json").exists())
        LibraryMigration.migrate(oldDir, newDir)
        assertLibraryIntact(entries)
    }

    @Test
    fun aFailedCatalogueRenameIsRetryable() {
        val entries = seedOld("heic9901a")

        val failed = LibraryMigration.migrate(oldDir, newDir, Faulty(2, crash = false))

        assertTrue(failed is LibraryMigration.Result.Failed)
        assertTrue(File(oldDir, "catalogue.json").exists())
        LibraryMigration.migrate(oldDir, newDir)
        assertLibraryIntact(entries)
    }

    @Test
    fun aLibraryAlreadyInAppDataIsNeverOverwritten() {
        val existing = CatalogueStoreTest.entry("heic9909a")
        val newStore = CatalogueStore(newDir)
        newStore.imagesDirectory.mkdirs()
        newStore.imageFile(existing).writeText("keep")
        newStore.save(Catalogue(entries = listOf(existing)))
        seedOld("heic9901a")

        val result = LibraryMigration.migrate(oldDir, newDir) as LibraryMigration.Result.Ready

        assertEquals(0, result.movedImages)
        assertTrue(result.leftover > 0)
        assertEquals(listOf(existing.key), newStore.loadStrict().entries.map { it.key })
        assertTrue(File(oldDir, "catalogue.json").exists())
    }

    @Test
    fun conflictingFilesArePreservedAndReported() {
        val entries = seedOld("heic9901a")
        val name = entries[0].fileName
        File(newDir, "images").apply { mkdirs() }.resolve(name).writeText("different-length-content")

        val result = LibraryMigration.migrate(oldDir, newDir) as LibraryMigration.Result.Ready

        assertEquals(1, result.leftover)
        assertEquals("different-length-content", File(newDir, "images/$name").readText())
        assertEquals("bytes-heic9901a", File(oldDir, "images/$name").readText())
    }

    @Test
    fun reportsCatalogueEntriesWhoseFilesAreMissing() {
        val entries = seedOld("heic9901a", "heic9902a")
        File(oldDir, "images/${entries[1].fileName}").delete()

        val result = LibraryMigration.migrate(oldDir, newDir) as LibraryMigration.Result.Ready

        assertEquals(1, result.missingFiles)
        assertEquals(2, CatalogueStore(newDir).loadStrict().entries.size)
    }

    @Test
    fun imagesWithoutAnyCatalogueAreMovedAndPreserved() {
        File(oldDir, "images").apply { mkdirs() }.resolve("a".repeat(64) + ".jpg").writeText("orphan")

        val result = LibraryMigration.migrate(oldDir, newDir) as LibraryMigration.Result.Ready

        assertEquals(1, result.movedImages)
        assertEquals("orphan", File(newDir, "images/${"a".repeat(64)}.jpg").readText())
    }

    @Test
    fun aDamagedOldCatalogueKeepsPicturesAndIsPreserved() {
        val entries = seedOld("heic9901a")
        File(oldDir, "catalogue.json").writeText("{torn")
        File(oldDir, "catalogue.json.bak").delete()

        val result = LibraryMigration.migrate(oldDir, newDir) as LibraryMigration.Result.Ready

        assertEquals(1, result.movedImages)
        assertTrue(result.notes.isNotEmpty())
        assertTrue(File(newDir, "catalogue.json.corrupt-migrated").exists())
        assertTrue(File(newDir, "images/${entries[0].fileName}").exists())
    }

    @Test
    fun recoversAnOldCatalogueFromItsBackup() {
        val entries = seedOld("heic9901a")
        File(oldDir, "catalogue.json").renameTo(File(oldDir, "catalogue.json.bak"))

        val result = LibraryMigration.migrate(oldDir, newDir) as LibraryMigration.Result.Ready

        assertTrue(result.notes.any { "backup" in it })
        assertEquals(entries.map { it.key }, CatalogueStore(newDir).loadStrict().entries.map { it.key })
    }

    @Test
    fun repeatedRunsFromManyThreadsMoveEachFileOnce() {
        val entries = seedOld("heic9901a", "heic9902a")
        val results = java.util.Collections.synchronizedList(mutableListOf<LibraryMigration.Result>())
        val gate = java.util.concurrent.CountDownLatch(1)
        val threads = (1..6).map {
            Thread {
                gate.await()
                synchronized(LibraryMigrationTest::class.java) {
                    results += LibraryMigration.migrate(oldDir, newDir)
                }
            }.also { it.start() }
        }
        gate.countDown()
        threads.forEach { it.join() }

        assertEquals(1, results.count { it is LibraryMigration.Result.Ready && it.movedImages == 2 })
        assertLibraryIntact(entries)
        assertEquals(CatalogueJson.decode(CatalogueJson.encode(CatalogueStore(newDir).loadStrict())).entries.size, 2)
    }
}
