package com.cosmos.screensaver.ui

import com.cosmos.screensaver.sync.CacheBudget
import com.cosmos.screensaver.sync.StorageMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBarModelTest {
    private val gib = CacheBudget.GIB
    private val mib = CacheBudget.MIB

    private fun known(totalGib: Long, freeGib: Long) = StorageMeasurement.Known(totalGib * gib, freeGib * gib)

    private fun assertPartition(layout: StorageBarLayout) {
        assertEquals(BarKind.entries, layout.segments.map { it.kind })
        assertTrue(layout.segments.all { it.bytes >= 0L })
        assertEquals(layout.totalBytes, layout.segments.sumOf { it.bytes })
    }

    @Test
    fun segmentsPartitionTheTotalExactly() {
        val layout = StorageBarModel.layout(known(32, 20), 4 * gib, 100 * mib, 500 * mib, 50 * mib)
        assertTrue(layout.known)
        assertPartition(layout)
        assertEquals(100 * mib, layout.bytes(BarKind.FAVOURITES))
        assertEquals(500 * mib, layout.bytes(BarKind.SAVED))
        assertEquals(50 * mib, layout.bytes(BarKind.UNDO))
        assertEquals(12 * gib - 650 * mib, layout.bytes(BarKind.OTHER_USED))
        assertEquals(650 * mib, layout.cosmosBytes)
    }

    @Test
    fun reservedIsWhatTheTvKeepsFreeAndRoomIsWhatCosmosMayStillUse() {
        // 100 GiB TV, 40 free: reserve is 15 GiB, so up to 25 GiB could be used; the limit allows 4.
        val layout = StorageBarModel.layout(known(100, 40), 4 * gib, 0, 1 * gib, 0)
        assertPartition(layout)
        assertEquals(15 * gib, layout.bytes(BarKind.RESERVED))
        assertEquals(3 * gib, layout.bytes(BarKind.ROOM))
        assertEquals(22 * gib, layout.bytes(BarKind.UNALLOCATED))
        assertEquals(-1L, layout.capacityMarkerBytes)
        assertEquals(0L, layout.reserveShortfallBytes)
    }

    @Test
    fun freeSpaceBelowTheLimitMovesTheCapacityMarker() {
        // 32 GiB TV, 5 free; reserve 4.8 GiB leaves almost nothing, far below the 8 GiB limit.
        val layout = StorageBarModel.layout(known(32, 5), 8 * gib, 0, 1 * gib, 0)
        assertPartition(layout)
        assertTrue(layout.capacityMarkerBytes in 0 until layout.limitMarkerBytes)
        assertTrue(layout.budget.spaceConstrained)
    }

    @Test
    fun reserveShortfallIsReportedWhenFreeSpaceIsBelowTheReserve() {
        val layout = StorageBarModel.layout(known(32, 2), 4 * gib, 0, 1 * gib, 0)
        assertPartition(layout)
        assertEquals(2 * gib, layout.bytes(BarKind.RESERVED))
        assertEquals(0L, layout.bytes(BarKind.ROOM))
        assertTrue(layout.reserveShortfallBytes > 0L)
    }

    @Test
    fun cosmosPartsAreTrimmedWhenTheyClaimMoreThanTheTvSaysIsUsed() {
        val layout = StorageBarModel.layout(known(10, 9), 2 * gib, 3 * gib, 3 * gib, 3 * gib)
        assertPartition(layout)
        assertEquals(0L, layout.bytes(BarKind.OTHER_USED))
        assertEquals(1 * gib, layout.cosmosBytes)
        assertEquals(1 * gib, layout.bytes(BarKind.FAVOURITES))
        assertEquals(0L, layout.bytes(BarKind.UNDO))
    }

    @Test
    fun anUnreliableMeasurementGivesAnUnknownLayoutWithNoInventedSegments() {
        val unknown = StorageBarModel.layout(StorageMeasurement.Unknown, 4 * gib, 1, 1, 1)
        assertFalse(unknown.known)
        assertTrue(unknown.segments.isEmpty())
        val zero = StorageBarModel.layout(StorageMeasurement.Known(0, 0), 4 * gib, 1, 1, 1)
        assertFalse(zero.known)
        val inconsistent = StorageBarModel.layout(StorageMeasurement.Known(10, 20), 4 * gib, 1, 1, 1)
        assertFalse(inconsistent.known)
    }

    @Test
    fun theLimitMarkerNeverPassesTheEndOfTheBar() {
        val layout = StorageBarModel.layout(known(8, 7), 16 * gib, 0, 0, 0)
        assertTrue(layout.limitMarkerBytes <= layout.totalBytes)
    }

    @Test
    fun negativeInputsAreTreatedAsZero() {
        val layout = StorageBarModel.layout(known(32, 20), 0, -1, -1, -1)
        assertPartition(layout)
        assertEquals(0L, layout.cosmosBytes)
    }

    @Test
    fun adviceUsesTheRealAverageOnceThereIsASample() {
        val layout = StorageBarModel.layout(known(100, 60), 4 * gib, 0, 40 * mib, 0)
        val advice = SpaceAdvisor.advise(layout, uniquePictures = 10, uniqueBytes = 40 * mib, favouriteBytes = 0, undoBytes = 0)
        assertFalse(advice.estimated)
        assertEquals(4 * mib, advice.averageBytes)
        assertEquals(1024, advice.capacityPictures)
    }

    @Test
    fun adviceAssumesATypicalSizeWithTooFewPictures() {
        val layout = StorageBarModel.layout(known(100, 60), 2 * gib, 0, 0, 0)
        val advice = SpaceAdvisor.advise(layout, uniquePictures = 2, uniqueBytes = 2 * mib, favouriteBytes = 0, undoBytes = 0)
        assertTrue(advice.estimated)
        assertEquals(SpaceAdvisor.ASSUMED_PICTURE_BYTES, advice.averageBytes)
        assertEquals(512, advice.capacityPictures)
    }

    @Test
    fun adviceFlagsFavouritesAboveTheTargetUndoAndFreeSpaceLimits() {
        val layout = StorageBarModel.layout(known(32, 6), 8 * gib, 1 * gib, 0, 10 * mib)
        val advice = SpaceAdvisor.advise(layout, 10, 1 * gib, favouriteBytes = 1 * gib, undoBytes = 10 * mib)
        assertTrue(SpaceNote.CONSTRAINED_BY_FREE_SPACE in advice.notes)
        assertTrue(SpaceNote.UNDO_PENDING in advice.notes)
        val tiny = StorageBarModel.layout(known(100, 60), 512 * mib, 600 * mib, 0, 0)
        val favourites = SpaceAdvisor.advise(tiny, 10, 600 * mib, favouriteBytes = 600 * mib, undoBytes = 0)
        assertTrue(SpaceNote.FAVOURITES_ABOVE_TARGET in favourites.notes)
    }

    @Test
    fun adviceForAnUnknownMeasurementSaysSo() {
        val advice = SpaceAdvisor.advise(StorageBarLayout.UNKNOWN, 10, 1, 0, 0)
        assertEquals(setOf(SpaceNote.MEASUREMENT_UNAVAILABLE), advice.notes)
        assertEquals(0, advice.capacityPictures)
    }

    @Test
    fun barWidthsAddUpToTheTotalAndKeepSmallPartsVisible() {
        val widths = StorageBarView.widths(listOf(8_000_000_000L, 1_000L, 0L, 500_000_000L), 900, 6)
        assertEquals(900, widths.sum())
        assertTrue(widths[1] >= 6)
        assertEquals(0, widths[2])
        assertTrue(widths.all { it >= 0 })
    }

    @Test
    fun barWidthsOfNothingAreAllZero() {
        assertEquals(listOf(0, 0), StorageBarView.widths(listOf(0L, 0L), 900, 6).toList())
        assertEquals(listOf(0), StorageBarView.widths(listOf(5L), 0, 6).toList())
    }
}
