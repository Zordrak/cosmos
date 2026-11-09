package com.cosmos.screensaver.catalogue

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryMaintenanceTest {
    private lateinit var directory: File
    private lateinit var store: CatalogueStore
    private lateinit var userState: UserStateStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("cosmos-maintenance").toFile()
        store = CatalogueStore(directory)
        userState = UserStateStore(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun save(id: String, title: String = "Synthetic $id", sha: String? = null, provider: AssetProvider = AssetProvider.ESA_HUBBLE, withFile: Boolean = true): CatalogueEntry {
        val base = CatalogueStoreTest.entry(id)
        val hash = sha ?: base.sha256
        val entry = base.copy(provider = provider, title = title, sha256 = hash, fileName = "$hash.jpg")
        store.update { it.copy(entries = it.entries + entry) }
        store.imagesDirectory.mkdirs()
        if (withFile) File(store.imagesDirectory, entry.fileName).writeBytes(byteArrayOf(1, 2, 3))
        return entry
    }

    private fun file(entry: CatalogueEntry) = File(store.imagesDirectory, entry.fileName)

    @Test
    fun screenLocalRemovesBlockedChartsAndMissingFilesButKeepsGoodPictures() {
        val blocked = save("a")
        val chart = save("b", title = "Orbit diagram")
        val missing = save("c", withFile = false)
        val good = save("d")
        userState.block(listOf(blocked), 1L)

        val report = LibraryMaintenance.screenLocal(store, userState)

        assertEquals(3, report.removed)
        assertEquals(listOf(good.key), store.load().entries.map { it.key })
        assertFalse(file(blocked).exists())
        assertFalse(file(chart).exists())
        assertTrue(file(good).isFile)
        assertFalse(missing.key in store.load().entries.map { it.key })
    }

    @Test
    fun screenLocalHidesAFavouriteThatFailsTheContentRulesInsteadOfDeletingIt() {
        val chart = save("b", title = "Orbit diagram")
        userState.setFavourite(chart.sha256, true)

        val report = LibraryMaintenance.screenLocal(store, userState)

        assertEquals(1, report.hidden)
        assertEquals("NOT_A_PHOTOGRAPH", store.load().entries.single().hiddenReason)
        assertTrue(file(chart).isFile)
    }

    @Test
    fun withdrawDropsMatchesButHidesFavourites() {
        val plain = save("a")
        val favourite = save("b")
        val other = save("c")
        userState.setFavourite(favourite.sha256, true)

        val removed = LibraryMaintenance.withdraw(store, userState, "RIGHTS_POLICY_CHANGED") { it.key != other.key }

        assertEquals(1, removed)
        val entries = store.load().entries.associateBy { it.key }
        assertEquals(setOf(favourite.key, other.key), entries.keys)
        assertEquals("RIGHTS_POLICY_CHANGED", entries.getValue(favourite.key).hiddenReason)
        assertEquals("", entries.getValue(other.key).hiddenReason)
        assertFalse(file(plain).exists())
        assertTrue(file(favourite).isFile)
    }

    @Test
    fun withdrawDoesNothingDestructiveWhenUserStateIsUnreadable() {
        val entry = save("a")
        File(directory, UserStateStore.FILE_NAME).writeText("garbage")

        LibraryMaintenance.withdraw(store, userState, "X") { true }

        assertTrue(file(entry).isFile)
        assertEquals(1, store.load().entries.size)
    }

    @Test
    fun removeKeyRefusesToDeleteTheLastCopyOfAFavouriteButRemovesOthers() {
        val favourite = save("a")
        val plain = save("b")
        userState.setFavourite(favourite.sha256, true)

        assertFalse(LibraryMaintenance.removeKey(store, userState, favourite.key))
        assertTrue(LibraryMaintenance.removeKey(store, userState, plain.key))

        assertEquals(listOf(favourite.key), store.load().entries.map { it.key })
        assertTrue(file(favourite).isFile)
        assertFalse(file(plain).exists())
    }

    @Test
    fun evictRemovesEveryAliasAndTheSharedFile() {
        val sha = "e".repeat(64)
        val one = save("a", sha = sha)
        save("b", sha = sha, provider = AssetProvider.ESA_WEBB)
        val other = save("c")

        assertEquals(2, LibraryMaintenance.evict(store, sha))

        assertEquals(listOf(other.key), store.load().entries.map { it.key })
        assertFalse(file(one).exists())
    }

    @Test
    fun unblockClearsOnlyTheBlockedDecisionsForThatPicture() {
        val entry = save("a")
        userState.block(listOf(entry), 1L)
        store.update {
            it.copy(
                rejections = mapOf(
                    entry.key to RejectionRecord(LibraryMaintenance.BLOCKED, 1L),
                    "NASA:other" to RejectionRecord("NOT_A_PHOTOGRAPH", 1L)
                )
            )
        }

        assertTrue(LibraryMaintenance.unblock(store, userState, userState.load().blocked.single().id))

        assertTrue(userState.load().blocked.isEmpty())
        assertEquals(setOf("NASA:other"), store.load().rejections.keys)
    }

    @Test
    fun deleteOrphanFilesOnlyTouchesLibraryNamedFilesAndStaleParts() {
        val kept = save("a")
        val orphan = File(store.imagesDirectory, "${"a".repeat(64)}.jpg").apply { writeBytes(byteArrayOf(1)) }
        val note = File(store.imagesDirectory, "notes.txt").apply { writeText("x") }
        val stale = File(store.imagesDirectory, "x.part").apply { writeBytes(byteArrayOf(1)); setLastModified(0L) }
        val fresh = File(store.imagesDirectory, "y.part").apply { writeBytes(byteArrayOf(1)) }
        val claimed = File(store.imagesDirectory, "${"b".repeat(64)}.jpg").apply { writeBytes(byteArrayOf(1)) }
        LibraryMaintenance.claim(claimed)

        LibraryMaintenance.deleteOrphanFiles(store, System.currentTimeMillis())
        LibraryMaintenance.release(claimed)

        assertTrue(file(kept).isFile)
        assertFalse(orphan.exists())
        assertTrue(note.isFile)
        assertFalse(stale.exists())
        assertTrue(fresh.isFile)
        assertTrue(claimed.isFile)
    }
}
