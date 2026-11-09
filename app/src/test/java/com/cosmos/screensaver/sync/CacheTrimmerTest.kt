package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.QualityPreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CacheTrimmerTest {
    private lateinit var directory: File
    private lateinit var store: CatalogueStore
    private lateinit var userState: UserStateStore
    private val selection = ImageSelection(quality = QualityPreference.ANY)
    private val all = AssetProvider.entries.toSet()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("cosmos-trimmer").toFile()
        store = CatalogueStore(directory)
        userState = UserStateStore(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun save(
        id: String,
        bytes: Long = 100,
        admittedAt: Long = 1_000,
        provider: AssetProvider = AssetProvider.ESA_HUBBLE,
        sha: String? = null
    ): CatalogueEntry {
        val base = CatalogueStoreTest.entry(id)
        val hash = sha ?: base.sha256
        val entry = base.copy(
            provider = provider, sha256 = hash, fileName = "$hash.jpg", byteCount = bytes, admittedAtEpochMillis = admittedAt
        )
        store.update { it.copy(entries = it.entries + entry) }
        store.imagesDirectory.mkdirs()
        File(store.imagesDirectory, entry.fileName).writeBytes(byteArrayOf(1))
        return entry
    }

    private fun keys() = store.load().entries.map { it.key }.toSet()

    @Test
    fun replacingTakesOnlySeenPicturesFromTheSourceHoldingTheMost() {
        val h1 = save("h1", sha = "a".repeat(64))
        val h2 = save("h2", sha = "b".repeat(64))
        val h3 = save("h3", sha = "c".repeat(64))
        val w1 = save("w1", sha = "d".repeat(64), provider = AssetProvider.ESA_WEBB)
        val hashes = setOf(h1, h2, h3, w1).map { it.sha256 }.toSet()
        // h3 is not shown; w1 was shown longest ago; h2 before h1.
        userState.commitDisplay(w1.sha256, 2_000, hashes)
        userState.commitDisplay(h2.sha256, 3_000, hashes)
        userState.commitDisplay(h1.sha256, 4_000, hashes)
        val fits = { saved: Long -> saved <= 200 }

        val result = CacheTrimmer.evictUntil(store, userState, selection, all, { 5_000L }, replacing = true, fits = fits)

        assertTrue(result.satisfied)
        assertEquals(setOf(h3.key, w1.key), keys())
    }

    @Test
    fun replacingStopsRatherThanTakeAPictureNotYetSeen() {
        save("h1", sha = "a".repeat(64))
        save("h2", sha = "b".repeat(64))

        val result = CacheTrimmer.evictUntil(store, userState, selection, all, { 5_000L }, replacing = true) { it <= 100 }

        assertFalse(result.satisfied)
        assertFalse(result.protectedRemain)
        assertEquals(2, keys().size)
    }

    @Test
    fun removesTheOldestFirstUntilTheSavedBytesFit() {
        val old = save("a", admittedAt = 1)
        val mid = save("b", admittedAt = 2)
        val new = save("c", admittedAt = 3)

        val result = CacheTrimmer.trimToBudget(store, userState, selection, all, 200)

        assertTrue(result.satisfied)
        assertEquals(1, result.removedPictures)
        assertEquals(setOf(mid.key, new.key), keys())
        assertFalse(File(store.imagesDirectory, old.fileName).exists())
    }

    @Test
    fun picturesOutsideTheCurrentViewGoBeforeNewerOnesInView() {
        val webb = save("w", admittedAt = 1, provider = AssetProvider.ESA_WEBB)
        val hubble = save("h", admittedAt = 2)
        val hubbleOnly = setOf(AssetProvider.ESA_HUBBLE)

        CacheTrimmer.trimToBudget(store, userState, selection, hubbleOnly, 100)

        assertEquals(setOf(hubble.key), keys())
        assertFalse(File(store.imagesDirectory, webb.fileName).exists())
    }

    @Test
    fun outOfViewPicturesGoFirstEvenWhenNewer() {
        val hubble = save("h", admittedAt = 1)
        val webb = save("w", admittedAt = 9, provider = AssetProvider.ESA_WEBB)

        CacheTrimmer.trimToBudget(store, userState, selection, setOf(AssetProvider.ESA_HUBBLE), 100)

        assertEquals(setOf(hubble.key), keys())
        assertFalse(File(store.imagesDirectory, webb.fileName).exists())
    }

    @Test
    fun favouritesAreNeverRemovedAndAreReported() {
        val favourite = save("a", admittedAt = 1)
        userState.setFavourite(favourite.sha256, true)

        val result = CacheTrimmer.trimToBudget(store, userState, selection, all, 0)

        assertFalse(result.satisfied)
        assertTrue(result.protectedRemain)
        assertEquals(0, result.removedPictures)
        assertEquals(setOf(favourite.key), keys())
    }

    @Test
    fun aSharedFileCountsOnceAndAllAliasesGoTogether() {
        val sha = "e".repeat(64)
        save("a", sha = sha, admittedAt = 1)
        save("b", sha = sha, admittedAt = 2, provider = AssetProvider.ESA_WEBB)
        val keep = save("c", admittedAt = 3)

        val result = CacheTrimmer.trimToBudget(store, userState, selection, all, 100)

        assertEquals(1, result.removedPictures)
        assertEquals(100L, result.removedBytes)
        assertEquals(setOf(keep.key), keys())
    }

    @Test
    fun anUnreadableLibraryTouchesNothing() {
        val entry = save("a")
        File(directory, UserStateStore.FILE_NAME).writeText("garbage")

        val result = CacheTrimmer.trimToBudget(store, userState, selection, all, 0)

        assertTrue(result.unreadable)
        assertEquals(0, result.removedPictures)
        assertEquals(setOf(entry.key), keys())
    }

    @Test
    fun nothingIsRemovedWhenAlreadyWithinBudget() {
        save("a")

        val result = CacheTrimmer.trimToBudget(store, userState, selection, all, 1_000)

        assertTrue(result.satisfied)
        assertEquals(0, result.removedPictures)
    }

    @Test
    fun candidatesExcludeFavouritesAndAreOrdered() {
        val a = save("a", admittedAt = 5)
        val b = save("b", admittedAt = 3)
        val c = save("c", admittedAt = 1)
        val state = UserState(favourites = setOf(c.sha256))

        val groups = CacheTrimmer.candidates(listOf(a, b, c), state, setOf(a.sha256, b.sha256))

        assertEquals(listOf(b.sha256, a.sha256), groups.map { it.identity })
    }
}
