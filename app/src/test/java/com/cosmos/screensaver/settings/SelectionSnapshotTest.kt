package com.cosmos.screensaver.settings

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionSnapshotTest {
    private val all = AssetProvider.entries.toSet()
    private val preferred = ImageSelection(quality = QualityPreference.UHD_PREFERRED)

    private fun image(id: String, width: Int = 3840, height: Int = 2160, provider: AssetProvider = AssetProvider.ESA_HUBBLE, sha: String? = null): CatalogueEntry {
        val base = CatalogueStoreTest.entry(id)
        val hash = sha ?: base.sha256
        return base.copy(provider = provider, widthPx = width, heightPx = height, sha256 = hash, fileName = "$hash.jpg")
    }

    private fun build(entries: List<CatalogueEntry>, selection: ImageSelection = preferred, providers: Set<AssetProvider> = all, state: UserState = UserState()) =
        SelectionSnapshot.build(entries, selection, providers, state)

    @Test
    fun nineteenDistinctUhdPicturesKeepTheFallbackAndTwentyEndIt() {
        val hd = image("hd", 1920, 1080)
        assertTrue(build(listOf(hd) + (1..19).map { image("u$it") }).fallbackActive)
        val twenty = build(listOf(hd) + (1..20).map { image("u$it") })
        assertFalse(twenty.fallbackActive)
        assertEquals(20, twenty.eligible.size)
    }

    @Test
    fun aliasesOfOnePictureCountOnceTowardsTheThreshold() {
        val aliases = (1..19).flatMap { index ->
            val sha = index.toString(16).padStart(64, '0')
            listOf(
                image("h$index", sha = sha),
                image("w$index", provider = AssetProvider.ESA_WEBB, sha = sha)
            )
        }
        val snapshot = build(aliases + image("hd", 1920, 1080))
        assertEquals(19, snapshot.qualifyingCount)
        assertTrue(snapshot.fallbackActive)
        assertEquals(20, snapshot.eligible.size)
        assertEquals(38 + 1, snapshot.eligibleKeys.size)
    }

    @Test
    fun disablingOneSourceKeepsAnAliasFromTheOther() {
        val sha = "c".repeat(64)
        val entries = listOf(image("h1", sha = sha), image("w1", provider = AssetProvider.ESA_WEBB, sha = sha))
        assertEquals(1, build(entries, providers = setOf(AssetProvider.ESA_WEBB)).eligible.size)
        assertEquals(1, build(entries, providers = setOf(AssetProvider.ESA_HUBBLE)).eligible.size)
        assertEquals(1, build(entries).eligible.size)
        assertEquals(0, build(entries, providers = emptySet()).eligible.size)
    }

    @Test
    fun anOffViewFavouriteIsNotEligible() {
        val entry = image("a")
        val state = UserState(favourites = setOf(entry.sha256))
        assertEquals(0, build(listOf(entry), providers = setOf(AssetProvider.NASA), state = state).eligible.size)
    }

    @Test
    fun blockedAndHiddenEntriesAreNeverEligible() {
        val blocked = image("a")
        val hidden = image("b").copy(hiddenReason = "NOT_A_PHOTOGRAPH")
        val fine = image("c")
        val state = UserState(blocked = listOf(com.cosmos.screensaver.catalogue.BlockedRecord("b-1", setOf(blocked.key), blocked.sha256, "t", "s", 1L)))
        assertEquals(listOf(fine.key), build(listOf(blocked, hidden, fine), state = state).eligible.map { it.key })
    }

    @Test
    fun physicalBytesCountsSharedFilesOnce() {
        val sha = "d".repeat(64)
        val entries = listOf(
            image("h1", sha = sha).copy(byteCount = 10),
            image("w1", provider = AssetProvider.ESA_WEBB, sha = sha).copy(byteCount = 10),
            image("o").copy(byteCount = 5)
        )
        assertEquals(15L, SelectionSnapshot.physicalBytes(entries))
        assertEquals(2, SelectionSnapshot.physicalFiles(entries))
    }
}
