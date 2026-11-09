package com.cosmos.screensaver.ui

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.SelectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheCompositionTest {
    private fun entry(id: String, bytes: Long = 100L, provider: AssetProvider = AssetProvider.ESA_HUBBLE) =
        CatalogueStoreTest.entry(id).copy(byteCount = bytes, provider = provider)

    private fun compose(entries: List<CatalogueEntry>, inView: Set<String>, favourites: Set<String> = emptySet()) =
        CacheComposition.of(entries, inView.map { id -> SelectionSnapshot.identity(entries.first { it.providerId == id }) }.toSet(),
            UserState(favourites = favourites.map { id -> entries.first { it.providerId == id }.sha256 }.toSet()))

    @Test
    fun partitionsMatchingKeptAndFavourites() {
        val entries = listOf(entry("a"), entry("b", 200), entry("c", 300), entry("d", 400))
        val c = compose(entries, inView = setOf("a"), favourites = setOf("d"))
        assertEquals(1, c.matching)
        assertEquals(2, c.keptPictures)
        assertEquals(500L, c.keptBytes)
        assertEquals(1, c.favouritesOutOfView)
        assertEquals(3, c.clearablePictures)
        assertEquals(600L, c.clearableBytes)
    }

    @Test
    fun aFavouriteInViewIsNotCountedAsOutOfView() {
        val entries = listOf(entry("a"), entry("b"))
        val c = compose(entries, inView = setOf("a"), favourites = setOf("a"))
        assertEquals(0, c.favouritesOutOfView)
        assertEquals(1, c.keptPictures)
        assertEquals(1, c.clearablePictures)
    }

    @Test
    fun aliasesOfOnePictureCountOnceAndShareTheirFile() {
        val base = entry("a", 100)
        val alias = base.copy(provider = AssetProvider.NASA)
        val entries = listOf(base, alias, entry("b", 50))
        val c = CacheComposition.of(entries, emptySet(), UserState())
        assertEquals(2, c.keptPictures)
        assertEquals(2, c.keptFiles)
        assertEquals(150L, c.keptBytes)
    }

    @Test
    fun aFavouriteAliasProtectsTheWholePicture() {
        val base = entry("a")
        val alias = base.copy(provider = AssetProvider.NASA)
        val c = CacheComposition.of(listOf(base, alias), emptySet(), UserState(favourites = setOf(base.sha256)))
        assertEquals(0, c.keptPictures)
        assertEquals(0, c.clearablePictures)
        assertEquals(1, c.favouritesOutOfView)
    }

    @Test
    fun emptyLibraryHasNothingToRemove() {
        val c = CacheComposition.of(emptyList(), emptySet(), UserState())
        assertEquals(CacheComposition(), c)
    }

    @Test
    fun matchesTheSelectionEngineAtTheQualityFallbackThreshold() {
        // Composition must agree with the one eligibility source whichever side of the fallback it is on.
        val entries = (1..5).map { entry("p$it") }
        val selection = ImageSelection()
        val snapshot = SelectionSnapshot.build(entries, selection, AssetProvider.entries.toSet(), UserState())
        val c = CacheComposition.of(entries, snapshot.eligibleIdentities, UserState())
        assertEquals(snapshot.eligible.size, c.matching)
        assertEquals(entries.size - snapshot.eligible.size, c.keptPictures)
    }
}
