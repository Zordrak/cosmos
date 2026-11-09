package com.cosmos.screensaver.catalogue

import com.cosmos.screensaver.sync.RemovalFence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryExplicitCommandsTest {
    private lateinit var directory: File
    private lateinit var store: CatalogueStore
    private lateinit var userState: UserStateStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("cosmos-explicit").toFile()
        store = CatalogueStore(directory)
        userState = UserStateStore(directory)
        RemovalFence.clearForTests()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        RemovalFence.clearForTests()
    }

    private fun save(id: String, sha: String? = null): CatalogueEntry {
        val base = CatalogueStoreTest.entry(id)
        val hash = sha ?: base.sha256
        val entry = base.copy(sha256 = hash, fileName = "$hash.jpg")
        store.update { it.copy(entries = it.entries + entry) }
        store.imagesDirectory.mkdirs()
        File(store.imagesDirectory, entry.fileName).writeBytes(byteArrayOf(1, 2, 3))
        return entry
    }

    private fun file(entry: CatalogueEntry) = File(store.imagesDirectory, entry.fileName)

    @Test
    fun removingAFavouriteDeletesItEndsItsProtectionAndFencesInFlightDownloads() {
        val favourite = save("a")
        val other = save("b")
        userState.setFavourite(favourite.sha256, true)

        val result = LibraryMaintenance.removePicture(store, userState, favourite.sha256, atMillis = 100L)

        assertEquals(LibraryMaintenance.Action.DONE, result)
        assertEquals(listOf(other.key), store.load().entries.map { it.key })
        assertFalse(file(favourite).exists())
        assertTrue(file(other).isFile)
        assertFalse(userState.load().isFavourite(favourite.sha256))
        assertTrue(RemovalFence.removedSince(favourite.key, "", 100L))
        assertFalse(RemovalFence.removedSince(other.key, other.sha256, 0L))
        assertTrue(userState.load().blocked.isEmpty())
    }

    @Test
    fun removingAPictureRemovesEveryAliasAndItsSingleFile() {
        val sha = "c".repeat(64)
        val first = save("a", sha)
        val second = save("b", sha)

        val result = LibraryMaintenance.removePicture(store, userState, sha)

        assertEquals(LibraryMaintenance.Action.DONE, result)
        assertTrue(store.load().entries.isEmpty())
        assertFalse(file(first).exists())
        assertTrue(RemovalFence.removedSince(second.key, "", 0L))
    }

    @Test
    fun removingAMissingPictureIsReportedAsGoneAndChangesNothing() {
        val kept = save("a")

        assertEquals(LibraryMaintenance.Action.GONE, LibraryMaintenance.removePicture(store, userState, "nope"))

        assertEquals(1, store.load().entries.size)
        assertTrue(file(kept).isFile)
    }

    @Test
    fun anUnreadableUserStateRefusesTheRemovalAndDeletesNothing() {
        val entry = save("a")
        File(directory, UserStateStore.FILE_NAME).writeText("garbage")

        assertEquals(LibraryMaintenance.Action.UNREADABLE, LibraryMaintenance.removePicture(store, userState, entry.sha256))
        assertEquals(LibraryMaintenance.Action.UNREADABLE, LibraryMaintenance.blockPicture(store, userState, entry.sha256))

        assertTrue(file(entry).isFile)
        assertEquals(1, store.load().entries.size)
    }

    @Test
    fun blockingSavesTheBlockThenRemovesTheFavouriteAndFile() {
        val entry = save("a")
        val other = save("b")
        userState.setFavourite(entry.sha256, true)

        val result = LibraryMaintenance.blockPicture(store, userState, entry.sha256, nowMillis = 5L, atMillis = 7L)

        assertEquals(LibraryMaintenance.Action.DONE, result)
        val state = userState.load()
        val record = state.blocked.single()
        assertTrue(entry.key in record.keys)
        assertEquals(entry.sha256, record.sha256)
        assertEquals(5L, record.blockedAtEpochMillis)
        assertFalse(state.isFavourite(entry.sha256))
        assertEquals(listOf(other.key), store.load().entries.map { it.key })
        assertFalse(file(entry).exists())
        assertTrue(RemovalFence.removedSince(entry.key, entry.sha256, 7L))
    }

    @Test
    fun unblockingBringsTheRecordBackOutOfTheBlockedList() {
        val entry = save("a")
        LibraryMaintenance.blockPicture(store, userState, entry.sha256)
        val record = userState.load().blocked.single()

        assertTrue(LibraryMaintenance.unblock(store, userState, record.id))

        assertTrue(userState.load().blocked.isEmpty())
        assertFalse(LibraryMaintenance.unblock(store, userState, record.id))
    }

    @Test
    fun theFenceOnlyHonoursMarksMadeAfterARunStartedAndForgetsOldOnes() {
        RemovalFence.note(setOf("k1"), "h1", atMillis = 50L)

        assertTrue(RemovalFence.removedSince("k1", "", 50L))
        assertTrue(RemovalFence.removedSince("zz", "h1", 10L))
        assertFalse(RemovalFence.removedSince("k1", "", 51L))
        assertFalse(RemovalFence.removedSince("zz", "", 0L))
        assertFalse(RemovalFence.removedSince("zz", "h2", 0L))

        RemovalFence.note(setOf("k2"), "h2", atMillis = 50L + 7L * 60 * 60 * 1000)
        assertFalse(RemovalFence.removedSince("k1", "h1", 0L))
        assertTrue(RemovalFence.removedSince("k2", "", 0L))
    }

    @Test
    fun theFenceIsBounded() {
        repeat(500) { RemovalFence.note(setOf("k$it"), "h$it", atMillis = it.toLong()) }

        assertFalse(RemovalFence.removedSince("k0", "h0", 0L))
        assertTrue(RemovalFence.removedSince("k499", "", 0L))
    }
}
