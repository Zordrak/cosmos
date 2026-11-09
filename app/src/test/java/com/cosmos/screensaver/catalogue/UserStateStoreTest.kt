package com.cosmos.screensaver.catalogue

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class UserStateStoreTest {
    private fun directory() = Files.createTempDirectory("cosmos-user").toFile()

    private fun entry(id: String, provider: AssetProvider = AssetProvider.ESA_HUBBLE) =
        CatalogueStoreTest.entry(id).copy(provider = provider)

    private fun populated() = UserState(
        favourites = setOf("hashA", "hashB"),
        blocked = listOf(BlockedRecord("b-1", setOf("NASA:X", "ESA_HUBBLE:y"), "hashC", "A title", "ESA/Hubble", 77L)),
        stats = mapOf("hashA" to PictureStats(3, 1000L, 9L)),
        displayOrdinal = 9L,
        lastShownHash = "hashA",
        cachedOrder = CachedOrder(
            FairState(true, mapOf("hashA" to 4, "hashB" to 0), setOf("hashA", "hashB"), listOf("hashB"), 2),
            SequentialCursor("NASA", "title", "hashA")
        ),
        streamOrder = StreamOrder(
            FairState(true, mapOf("NASA:Q" to 8), setOf("NASA:Q"), listOf("NASA:Z"), 1),
            SequentialCursor("ESA_WEBB", "", "weic1"),
            "NASA:Q",
            listOf(StreamAttempt("NASA:Z", 2, 5L, 50L)),
            listOf("NASA:Q" to "hashA", "NASA:R" to "hashB")
        ),
        seedVersion = 1
    )

    @Test
    fun roundTripsEveryField() {
        assertEquals(populated(), UserStateJson.decode(UserStateJson.encode(populated())))
        assertEquals(UserState(), UserStateJson.decode(UserStateJson.encode(UserState())))
    }

    @Test
    fun firstRunIsEmptyAndHealthy() {
        val store = UserStateStore(directory())
        assertEquals(UserStateRead.Ok(UserState()), store.read())
        assertTrue(store.setFavourite("hashA", true))
        assertTrue(store.load().isFavourite("hashA"))
    }

    @Test
    fun savedStateSurvivesANewStoreInstance() {
        val dir = directory()
        UserStateStore(dir).update { populated() }
        assertEquals(populated(), UserStateStore(dir).load())
    }

    @Test
    fun recoversTheLastGoodBackup() {
        val dir = directory()
        val store = UserStateStore(dir)
        store.setFavourite("one", true)
        store.setFavourite("two", true)
        File(dir, UserStateStore.FILE_NAME).writeText("{ not json")
        val read = UserStateStore(dir).read()
        assertTrue(read is UserStateRead.Ok)
        assertEquals(setOf("one"), (read as UserStateRead.Ok).state.favourites)
    }

    @Test
    fun anUnreadableStateIsNeverTreatedAsEmptyForChanges() {
        val dir = directory()
        File(dir, UserStateStore.FILE_NAME).writeText("garbage")
        val store = UserStateStore(dir)
        assertTrue(store.read() is UserStateRead.Failed)
        assertEquals(UserState(), store.load())
        assertThrows(LibraryUnavailableException::class.java) { store.loadStrict() }
        assertFalse(store.setFavourite("hashA", true))
        assertEquals("garbage", File(dir, UserStateStore.FILE_NAME).readText())
        assertTrue(store.quarantineCorrupt(5L))
        assertTrue(File(dir, "${UserStateStore.FILE_NAME}.corrupt-5").exists())
        assertTrue(store.setFavourite("hashA", true))
    }

    @Test
    fun anUnsupportedFormatIsPreservedAndNeverQuarantined() {
        val dir = directory()
        val text = """{"formatVersion": 99}"""
        File(dir, UserStateStore.FILE_NAME).writeText(text)
        val store = UserStateStore(dir)
        val read = store.read() as UserStateRead.Failed
        assertTrue(read.unsupported)
        assertFalse(store.quarantineCorrupt(5L))
        assertFalse(store.setFavourite("hashA", true))
        assertEquals(text, File(dir, UserStateStore.FILE_NAME).readText())
    }

    @Test
    fun aFailedWriteLeavesThePreviousStateIntact() {
        val dir = directory()
        UserStateStore(dir).setFavourite("kept", true)
        var failedOnce = false
        val failing = UserStateStore(dir, ops = object : FileOps by FileOps.Real {
            override fun rename(from: File, to: File): Boolean {
                if (to.name == UserStateStore.FILE_NAME && !failedOnce) {
                    failedOnce = true
                    return false
                }
                return from.renameTo(to)
            }
        })
        assertFalse(failing.setFavourite("lost", true))
        assertEquals(setOf("kept"), UserStateStore(dir).load().favourites)
        assertEquals(setOf("kept"), failing.load().favourites)
    }

    @Test
    fun aFailedMigrationBlocksChanges() {
        val store = UserStateStore(directory(), unavailableReason = "migration failed")
        assertFalse(store.setFavourite("hashA", true))
        assertTrue(store.read() is UserStateRead.Failed)
    }

    @Test
    fun favouritesAreByHashAndBlankHashesAreRefused() {
        val store = UserStateStore(directory())
        assertFalse(store.setFavourite(" ", true))
        store.setFavourite("hashA", true)
        store.setFavourite("hashA", true)
        assertEquals(setOf("hashA"), store.load().favourites)
        store.setFavourite("hashA", false)
        assertTrue(store.load().favourites.isEmpty())
    }

    @Test
    fun blockingRecordsEveryAliasAndTheHashAndDropsTheFavourite() {
        val store = UserStateStore(directory())
        val first = entry("heic9901a")
        val alias = first.copy(provider = AssetProvider.ESA_WEBB, providerId = "weic1")
        store.setFavourite(first.sha256, true)
        assertTrue(store.block(listOf(first, alias), 100L))

        val state = store.load()
        assertTrue(state.isBlocked(first.key, null))
        assertTrue(state.isBlocked(alias.key, null))
        assertTrue(state.isBlocked("NASA:other", first.sha256))
        assertFalse(state.isBlocked("NASA:other", "different"))
        assertFalse(state.isBlocked(null, ""))
        assertFalse(state.isFavourite(first.sha256))
        assertEquals(1, state.blocked.size)
    }

    @Test
    fun blockingTheSamePictureAgainMergesTheRecord() {
        val store = UserStateStore(directory())
        val first = entry("heic9901a")
        store.block(listOf(first), 1L)
        val id = store.load().blocked.single().id
        store.block(listOf(first.copy(provider = AssetProvider.ESA_WEBB, providerId = "weic1")), 2L)

        val record = store.load().blocked.single()
        assertEquals(id, record.id)
        assertEquals(1L, record.blockedAtEpochMillis)
        assertEquals(setOf(first.key, "ESA_WEBB:weic1"), record.keys)
    }

    @Test
    fun unblockRemovesTheRecordAndIsReportedExplicitly() {
        val store = UserStateStore(directory())
        store.block(listOf(entry("heic9901a")), 1L)
        val id = store.load().blocked.single().id
        assertNull(store.unblock("missing"))
        assertEquals(id, store.unblock(id)?.id)
        assertTrue(store.load().blocked.isEmpty())
    }

    @Test
    fun theSeedBlocksTheCometOnceAndCapturesItsHash() {
        val dir = directory()
        val comet = entry("PIA23165", AssetProvider.NASA)
        val store = UserStateStore(dir)
        assertTrue(store.ensureSeeded(Catalogue(entries = listOf(comet)), 10L))
        val record = store.load().blocked.single()
        assertEquals(comet.sha256, record.sha256)
        assertTrue(UserState.SEEDED_COMET_KEY in record.keys)

        store.unblock(record.id)
        assertTrue(store.ensureSeeded(Catalogue(entries = listOf(comet)), 20L))
        assertTrue(UserStateStore(dir).load().blocked.isEmpty())
        assertEquals(UserState.SEED_VERSION, UserStateStore(dir).load().seedVersion)
    }

    @Test
    fun theSeedStillBlocksTheKeyWhenTheEntryIsNotInTheLibrary() {
        val store = UserStateStore(directory())
        store.ensureSeeded(Catalogue(), 10L)
        val state = store.load()
        assertTrue(state.isBlocked(UserState.SEEDED_COMET_KEY, null))
        assertNull(state.blocked.single().sha256)
    }

    @Test
    fun theSeedDoesNotRunOnAnUnreadableState() {
        val dir = directory()
        File(dir, UserStateStore.FILE_NAME).writeText("garbage")
        assertFalse(UserStateStore(dir).ensureSeeded(Catalogue(), 1L))
        assertEquals("garbage", File(dir, UserStateStore.FILE_NAME).readText())
    }

    @Test
    fun commitDisplayAdvancesHistoryAndOrderTogether() {
        val store = UserStateStore(directory())
        assertTrue(store.commitDisplay("hashA", 500L, setOf("hashA")) { it.copy(cachedOrder = CachedOrder(cursor = SequentialCursor("NASA", "t", "hashA"))) })
        assertTrue(store.commitDisplay("hashA", 900L, setOf("hashA")))
        val state = store.load()
        assertEquals(PictureStats(2, 900L, 2L), state.stats["hashA"])
        assertEquals(2L, state.displayOrdinal)
        assertEquals("hashA", state.lastShownHash)
        assertEquals("hashA", state.cachedOrder.cursor?.id)
    }

    @Test
    fun aClockGoingBackwardsDoesNotChangeTheOrdinal() {
        val store = UserStateStore(directory())
        store.commitDisplay("a", 5000L, emptySet())
        store.commitDisplay("b", 10L, emptySet())
        val state = store.load()
        assertTrue(state.stats.getValue("b").lastShownOrdinal > state.stats.getValue("a").lastShownOrdinal)
    }

    @Test
    fun theHistoryCapNeverPrunesSavedFavouriteOrBlockedPictures() {
        val saved = "saved"
        val state = UserState(
            favourites = setOf("fav"),
            blocked = listOf(BlockedRecord("b", setOf("k"), "blocked", "", "", 0L)),
            stats = buildMap {
                put(saved, PictureStats(1, 0L, 0L))
                put("fav", PictureStats(1, 0L, 0L))
                put("blocked", PictureStats(1, 0L, 0L))
                repeat(UserState.HISTORY_LIMIT + 50) { put("stream$it", PictureStats(1, 0L, (it + 1).toLong())) }
            }
        )
        val pruned = state.pruned(setOf(saved))

        assertTrue(saved in pruned.stats && "fav" in pruned.stats && "blocked" in pruned.stats)
        assertEquals(UserState.HISTORY_LIMIT + 3, pruned.stats.size)
        assertFalse("stream0" in pruned.stats)
        assertTrue("stream${UserState.HISTORY_LIMIT + 49}" in pruned.stats)
    }

    @Test
    fun aFailedAttemptNeverCountsAsAView() {
        val store = UserStateStore(directory())
        store.update { it.copy(streamOrder = it.streamOrder.copy(attempts = listOf(StreamAttempt("NASA:Z", 1, 1L, 2L)))) }
        val state = store.load()
        assertTrue(state.stats.isEmpty())
        assertEquals(0L, state.displayOrdinal)
        assertNull(state.lastShownHash)
    }

    @Test
    fun concurrentFavouriteBlockAndPlaybackUpdatesDoNotOverwriteEachOther() {
        val dir = directory()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val tasks = (0 until 40).map { index ->
            pool.submit(java.util.concurrent.Callable {
                start.await()
                val store = UserStateStore(dir)
                when (index % 3) {
                    0 -> store.setFavourite("fav$index", true)
                    1 -> store.block(listOf(entry("heic99${index.toString().padStart(2, '0')}a")), index.toLong())
                    else -> store.commitDisplay("shown$index", index.toLong(), emptySet())
                }
            })
        }
        start.countDown()
        tasks.forEach { assertTrue(it.get()) }
        pool.shutdown()

        val state = UserStateStore(dir).load()
        assertEquals((0 until 40).count { it % 3 == 0 }, state.favourites.size)
        assertEquals((0 until 40).count { it % 3 == 1 }, state.blocked.size)
        assertEquals((0 until 40).count { it % 3 == 2 }.toLong(), state.displayOrdinal)
    }

    @Test
    fun aBlockIsSavedBeforeAnAdmissionCheckCanSeeItAsAbsent() {
        val dir = directory()
        val store = UserStateStore(dir)
        val picture = entry("heic9901a")
        assertFalse(store.load().isBlocked(picture.key, picture.sha256))
        store.block(listOf(picture), 1L)
        assertTrue(UserStateStore(dir).load().isBlocked(picture.key, picture.sha256))
    }
}
