package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.LicenceMode
import com.cosmos.screensaver.settings.QualityPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshCoordinatorTest {
    private val hour = 60L * 60 * 1000
    private val selection = ImageSelection()
    private val all = AssetProvider.entries.toSet()
    private val populated = Catalogue(
        entries = listOf(CatalogueStoreTest.entry("heic9901a")),
        lastAttemptAtEpochMillis = 0L,
        selectionFingerprint = selection.fingerprint
    )

    @Test
    fun refreshesDailyWhenImagesExist() {
        assertFalse(RefreshCoordinator.isStale(populated, 19 * hour, selection, all))
        assertTrue(RefreshCoordinator.isStale(populated, 20 * hour, selection, all))
    }

    @Test
    fun retriesSoonerWhenEmpty() {
        val empty = Catalogue(lastAttemptAtEpochMillis = 100 * hour, selectionFingerprint = selection.fingerprint)
        assertFalse(RefreshCoordinator.isStale(empty, 100 * hour + 5 * 60 * 1000, selection, all))
        assertTrue(RefreshCoordinator.isStale(empty, 100 * hour + 10 * 60 * 1000, selection, all))
    }

    @Test
    fun retriesSoonerWhenNothingInTheLibraryCanBeShown() {
        val lowResolution = populated.copy(entries = populated.entries.map { it.copy(widthPx = 1920, heightPx = 1080) })
        assertTrue(RefreshCoordinator.isStale(lowResolution, 1 * hour, selection, all))
        assertTrue(RefreshCoordinator.isStale(populated, 1 * hour, selection, setOf(AssetProvider.ESA_WEBB)))
        assertFalse(RefreshCoordinator.isStale(populated, 1 * hour, selection, setOf(AssetProvider.ESA_HUBBLE)))
    }

    @Test
    fun treatsClockGoingBackwardsAsStale() {
        assertTrue(RefreshCoordinator.isStale(populated.copy(lastAttemptAtEpochMillis = 50 * hour), 10 * hour, selection, all))
    }

    @Test
    fun refreshesImmediatelyWhenImageSettingsChange() {
        val changed = ImageSelection(LicenceMode.OPEN_LICENCE_ONLY, QualityPreference.FHD_OR_BETTER)
        assertTrue(RefreshCoordinator.isStale(populated, 1 * hour, changed, all))
    }
}