package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshCoordinatorTest {
    private val hour = 60L * 60 * 1000
    private val populated = Catalogue(entries = listOf(CatalogueStoreTest.entry("heic9901a")), lastAttemptAtEpochMillis = 0L)

    @Test
    fun refreshesDailyWhenImagesExist() {
        assertFalse(RefreshCoordinator.isStale(populated, 19 * hour))
        assertTrue(RefreshCoordinator.isStale(populated, 20 * hour))
    }

    @Test
    fun retriesSoonerWhenEmpty() {
        val empty = Catalogue(lastAttemptAtEpochMillis = 100 * hour)
        assertFalse(RefreshCoordinator.isStale(empty, 100 * hour + 5 * 60 * 1000))
        assertTrue(RefreshCoordinator.isStale(empty, 100 * hour + 10 * 60 * 1000))
    }

    @Test
    fun treatsClockGoingBackwardsAsStale() {
        assertTrue(RefreshCoordinator.isStale(populated.copy(lastAttemptAtEpochMillis = 50 * hour), 10 * hour))
    }

    @Test
    fun refreshesImmediatelyWhenEntriesUseAnOlderRightsPolicy() {
        val outdated = populated.copy(entries = populated.entries.map { it.copy(policyVersion = "1") })
        assertTrue(RefreshCoordinator.isStale(outdated, 1 * hour))
    }
}
