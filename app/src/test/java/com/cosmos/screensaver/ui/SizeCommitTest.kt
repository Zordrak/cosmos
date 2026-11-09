package com.cosmos.screensaver.ui

import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.sync.CacheBudget
import com.cosmos.screensaver.sync.LimitPort
import com.cosmos.screensaver.sync.StorageMeasurement
import com.cosmos.screensaver.sync.TrashBin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SizeCommitTest {
    private lateinit var dir: File
    private var limitValue = 4096L * CacheBudget.MIB
    private val mib = CacheBudget.MIB
    private val providers = AssetProvider.entries.toSet()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cosmos-size").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = CatalogueStore(dir)

    private fun bin() = TrashBin(
        store = store(),
        userState = UserStateStore(dir),
        limit = object : LimitPort {
            override fun current() = limitValue
            override fun write(value: Long): Boolean {
                limitValue = value
                return true
            }
        },
        monotonicMillis = { 1_000L },
        wallMillis = { 1_000_000L }
    )

    private fun seed(vararg ids: String): List<CatalogueEntry> {
        val entries = ids.map { CatalogueStoreTest.entry(it).copy(byteCount = 300 * mib) }
        val store = store()
        store.imagesDirectory.mkdirs()
        entries.forEach { File(store.imagesDirectory, it.fileName).writeText("bytes-${it.providerId}") }
        store.save(Catalogue(entries = entries))
        return entries
    }

    private val roomy = StorageMeasurement.Known(100L * CacheBudget.GIB, 60L * CacheBudget.GIB)

    private fun chooser(bin: TrashBin, candidate: Long, measure: StorageMeasurement = roomy) = SizeCommit.chooser(
        measure = { measure },
        trashBytes = { bin.bytesOnDisk() },
        candidate = candidate,
        selection = ImageSelection(),
        providers = providers
    )

    private fun live() = store().loadStrict().entries

    @Test
    fun committingASizeThatFitsJustSavesIt() {
        seed("heic9901a", "heic9902a")
        val bin = bin()
        val outcome = SizeCommit.commit(bin, chooser(bin, 4096 * mib), 4096 * mib)
        assertEquals(SizeCommit.Outcome.Applied, outcome)
        assertEquals(2, live().size)
        assertNull(bin.pending())
    }

    @Test
    fun aSmallerSizeSetsPicturesAsideAndUndoPutsTheOldSizeBack() {
        seed("heic9901a", "heic9902a", "heic9903a")
        val bin = bin()
        val smaller = 512 * mib
        val outcome = SizeCommit.commit(bin, chooser(bin, smaller), smaller)
        outcome as SizeCommit.Outcome.Undoable
        assertEquals(2, outcome.pending.pictures)
        assertTrue(outcome.pending.restoresLimit)
        assertEquals(smaller, limitValue)
        assertEquals(1, live().size)

        val undone = bin.undo()
        assertEquals(TrashBin.UndoResult.Status.DONE, undone.status)
        assertEquals(4096 * mib, limitValue)
        assertEquals(3, live().size)
    }

    @Test
    fun theConfirmationIsRevalidatedAgainstTheLiveLibrary() {
        seed("heic9901a", "heic9902a", "heic9903a")
        val bin = bin()
        val smaller = 512 * mib
        val shown = bin.preview(chooser(bin, smaller))
        // Something changes the library between the dialog and the confirmation.
        val grown = live() + CatalogueStoreTest.entry("heic9904a").copy(byteCount = 300 * mib)
        File(store().imagesDirectory, grown.last().fileName).writeText("x")
        store().save(Catalogue(entries = grown))

        val outcome = SizeCommit.commit(bin, chooser(bin, smaller), smaller, expected = shown)
        outcome as SizeCommit.Outcome.Changed
        assertEquals(shown.pictures + 1, outcome.preview.pictures)
        assertEquals(4, live().size)
        assertEquals(4096 * mib, limitValue)
        assertNull(bin.pending())
    }

    @Test
    fun anUnknownMeasurementRemovesNothing() {
        seed("heic9901a", "heic9902a")
        val bin = bin()
        val smaller = 512 * mib
        val outcome = SizeCommit.commit(bin, chooser(bin, smaller, StorageMeasurement.Unknown), smaller)
        assertEquals(SizeCommit.Outcome.Applied, outcome)
        assertEquals(2, live().size)
    }

    @Test
    fun previewingNeverChangesAnything() {
        seed("heic9901a", "heic9902a", "heic9903a")
        val bin = bin()
        val preview = bin.preview(chooser(bin, 512 * mib))
        assertEquals(2, preview.pictures)
        assertFalse(bin.pending() != null)
        assertEquals(3, live().size)
        assertEquals(4096 * mib, limitValue)
    }

    @Test
    fun aSecondChangeWhileOneCanStillBeUndoneIsRefused() {
        seed("heic9901a", "heic9902a", "heic9903a")
        val bin = bin()
        SizeCommit.commit(bin, chooser(bin, 512 * mib), 512 * mib)
        val again = SizeCommit.commit(bin, chooser(bin, 1024 * mib), 1024 * mib)
        assertTrue(again is SizeCommit.Outcome.Refused)
        assertEquals(512 * mib, limitValue)
    }
}
