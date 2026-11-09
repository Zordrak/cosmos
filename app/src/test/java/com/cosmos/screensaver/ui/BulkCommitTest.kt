package com.cosmos.screensaver.ui

import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.sync.TrashBin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BulkCommitTest {
    private lateinit var dir: File
    private val viewing = setOf(AssetProvider.NASA)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cosmos-bulk").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = CatalogueStore(dir)

    private fun users() = UserStateStore(dir)

    private fun bin() = TrashBin(
        store = store(),
        userState = users(),
        limit = null,
        monotonicMillis = { 1_000L },
        wallMillis = { 1_000_000L }
    )

    private fun saved(id: String, provider: AssetProvider, bytes: Long = 100L): CatalogueEntry =
        CatalogueStoreTest.entry(id).copy(provider = provider, byteCount = bytes)

    private fun seed(vararg entries: CatalogueEntry) {
        val store = store()
        store.imagesDirectory.mkdirs()
        entries.forEach { File(store.imagesDirectory, it.fileName).writeText("bytes-${it.providerId}") }
        store.save(Catalogue(entries = entries.toList()))
    }

    private fun live() = store().loadStrict().entries

    private val tidy = TrashBin.Chooser.notShown(ImageSelection(), viewing)
    private val clear = TrashBin.Chooser.everything()

    @Test
    fun tidyRemovesOnlyPicturesTheChoicesWouldNotShow() {
        seed(saved("n1", AssetProvider.NASA), saved("h1", AssetProvider.ESA_HUBBLE), saved("h2", AssetProvider.ESA_HUBBLE))
        val bin = bin()
        val shown = bin.preview(tidy)
        assertEquals(2, shown.pictures)
        val outcome = BulkCommit.commit(bin, TrashBin.Kind.TIDY, tidy, shown)
        outcome as BulkCommit.Outcome.Undoable
        assertEquals(TrashBin.Kind.TIDY, outcome.pending.kind)
        assertEquals(listOf("n1"), live().map { it.providerId })
        assertEquals(TrashBin.UndoResult.Status.DONE, bin.undo().status)
        assertEquals(3, live().size)
    }

    @Test
    fun clearRemovesEverythingButFavouritesAndUndoRestoresIt() {
        val favourite = saved("n1", AssetProvider.NASA)
        seed(favourite, saved("n2", AssetProvider.NASA), saved("h1", AssetProvider.ESA_HUBBLE))
        users().setFavourite(favourite.sha256, true)
        val bin = bin()
        val shown = bin.preview(clear)
        assertEquals(2, shown.pictures)
        assertEquals(1, shown.favouritesKept)
        BulkCommit.commit(bin, TrashBin.Kind.CLEAR, clear, shown) as BulkCommit.Outcome.Undoable
        assertEquals(listOf("n1"), live().map { it.providerId })
        assertEquals(TrashBin.UndoResult.Status.DONE, bin.undo().status)
        assertEquals(3, live().size)
    }

    @Test
    fun anOffViewFavouriteSurvivesTidy() {
        val favourite = saved("h1", AssetProvider.ESA_HUBBLE)
        seed(favourite, saved("h2", AssetProvider.ESA_HUBBLE))
        users().setFavourite(favourite.sha256, true)
        val bin = bin()
        BulkCommit.commit(bin, TrashBin.Kind.TIDY, tidy, bin.preview(tidy)) as BulkCommit.Outcome.Undoable
        assertEquals(listOf("h1"), live().map { it.providerId })
    }

    @Test
    fun nothingToRemoveIsReportedAndChangesNothing() {
        seed(saved("n1", AssetProvider.NASA))
        val bin = bin()
        val outcome = BulkCommit.commit(bin, TrashBin.Kind.TIDY, tidy, bin.preview(tidy))
        assertEquals(BulkCommit.Outcome.Nothing, outcome)
        assertEquals(1, live().size)
        assertNull(bin.pending())
    }

    @Test
    fun theConfirmationIsRevalidatedAgainstTheLiveLibrary() {
        seed(saved("n1", AssetProvider.NASA), saved("h1", AssetProvider.ESA_HUBBLE))
        val bin = bin()
        val shown = bin.preview(tidy)
        val extra = saved("h2", AssetProvider.ESA_HUBBLE)
        File(store().imagesDirectory, extra.fileName).writeText("x")
        store().save(Catalogue(entries = live() + extra))

        val outcome = BulkCommit.commit(bin, TrashBin.Kind.TIDY, tidy, shown)
        outcome as BulkCommit.Outcome.Changed
        assertEquals(2, outcome.preview.pictures)
        assertEquals(3, live().size)
        assertNull(bin.pending())
    }

    @Test
    fun aSecondBulkChangeWhileOneCanBeUndoneIsRefused() {
        seed(saved("n1", AssetProvider.NASA), saved("h1", AssetProvider.ESA_HUBBLE), saved("h2", AssetProvider.ESA_HUBBLE))
        val bin = bin()
        BulkCommit.commit(bin, TrashBin.Kind.TIDY, tidy, bin.preview(tidy)) as BulkCommit.Outcome.Undoable
        val again = BulkCommit.commit(bin, TrashBin.Kind.CLEAR, clear, bin.preview(clear))
        assertTrue(again is BulkCommit.Outcome.Refused)
        assertEquals(listOf("n1"), live().map { it.providerId })
    }
}
