package com.cosmos.screensaver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheBudgetTest {
    private val gib = CacheBudget.GIB
    private val mib = CacheBudget.MIB

    private fun known(totalGib: Long, freeGib: Long) = StorageMeasurement.Known(totalGib * gib, freeGib * gib)

    @Test
    fun autoUsesTwoGibOnAnRoomyDevice() {
        val budget = CacheBudget.compute(known(32, 20), 0L, 0L)

        assertEquals(2 * gib, budget.budgetBytes)
        assertFalse(budget.devicePressure)
        assertFalse(budget.spaceConstrained)
    }

    @Test
    fun reserveIsTheLargerOfThreeGibAndFifteenPercent() {
        assertEquals(3 * gib, CacheBudget.compute(known(16, 10), 0L, 0L).reserveBytes)
        assertEquals((64 * gib * 0.15).toLong(), CacheBudget.compute(known(64, 40), 0L, 0L).reserveBytes)
    }

    @Test
    fun aChosenLimitIsHonouredWhenThereIsRoom() {
        val budget = CacheBudget.compute(known(64, 50), 8 * gib, 0L)

        assertEquals(8 * gib, budget.budgetBytes)
    }

    @Test
    fun theReserveCapsTheBudgetAndMarksItConstrained() {
        // 16 GiB total: reserve 3 GiB. 4 GiB free leaves 1 GiB safe.
        val budget = CacheBudget.compute(known(16, 4), 8 * gib, 0L)

        assertEquals(1 * gib, budget.budgetBytes)
        assertTrue(budget.spaceConstrained)
        assertFalse(budget.devicePressure)
    }

    @Test
    fun ownedBytesAreGivenBackToTheBudget() {
        val withoutOwned = CacheBudget.compute(known(16, 4), 8 * gib, 0L)
        val withOwned = CacheBudget.compute(known(16, 4), 8 * gib, 1 * gib)

        assertEquals(withoutOwned.budgetBytes + gib, withOwned.budgetBytes)
    }

    @Test
    fun belowTheReserveIsDevicePressureWithAZeroBudget() {
        val budget = CacheBudget.compute(known(16, 2), 0L, 0L)

        assertEquals(0L, budget.budgetBytes)
        assertTrue(budget.devicePressure)
        assertEquals(0L, budget.deficit(0L))
        assertEquals(500 * mib, budget.deficit(500 * mib))
    }

    @Test
    fun exactlyAtTheReserveIsNotPressure() {
        val budget = CacheBudget.compute(known(16, 3), 0L, 0L)

        assertFalse(budget.devicePressure)
        assertEquals(0L, budget.budgetBytes)
    }

    @Test
    fun invalidMeasurementsAreUnknown() {
        assertFalse(CacheBudget.compute(StorageMeasurement.Unknown, 0L, 0L).known)
        assertFalse(CacheBudget.compute(StorageMeasurement.Known(0L, 0L), 0L, 0L).known)
        assertFalse(CacheBudget.compute(StorageMeasurement.Known(-1L, 5L), 0L, 0L).known)
        assertFalse(CacheBudget.compute(StorageMeasurement.Known(10L * gib, -1L), 0L, 0L).known)
        assertFalse(CacheBudget.compute(StorageMeasurement.Known(10L * gib, 11L * gib), 0L, 0L).known)
    }

    @Test
    fun anUnknownBudgetNeverAsksForDeletion() {
        assertEquals(0L, CacheBudget.UNKNOWN.deficit(50 * gib))
    }

    @Test
    fun hugeInputsSaturateInsteadOfOverflowing() {
        val budget = CacheBudget.compute(StorageMeasurement.Known(Long.MAX_VALUE, Long.MAX_VALUE), 0L, Long.MAX_VALUE)

        assertTrue(budget.known)
        assertEquals(2 * gib, budget.budgetBytes)
    }

    @Test
    fun negativeOwnedBytesAreTreatedAsZero() {
        assertEquals(
            CacheBudget.compute(known(16, 4), 8 * gib, 0L).budgetBytes,
            CacheBudget.compute(known(16, 4), 8 * gib, -5L).budgetBytes
        )
    }

    @Test
    fun evaluateReportsAFullUserLimitOnlyWhenSomethingCanBeReplaced() {
        val budget = CacheBudget.fixed(2 * gib)

        val status = SpaceStatus.evaluate(budget, 2 * gib, 0L, 40 * mib)

        assertTrue(status.userLimitFull)
        assertFalse(status.favouritesPreventSaving)
        assertFalse(status.devicePressure)
    }

    @Test
    fun evaluateBlamesFavouritesWhenOnlyFavouritesRemain() {
        val budget = CacheBudget.fixed(2 * gib)

        val status = SpaceStatus.evaluate(budget, 2 * gib, 2 * gib, 40 * mib)

        assertTrue(status.favouritesPreventSaving)
        assertFalse(status.userLimitFull)
        assertFalse(status.devicePressure)
    }

    @Test
    fun evaluateBlamesTheDeviceWhenTheReserveIsTheConstraint() {
        val budget = CacheBudget.compute(known(16, 4), 8 * gib, 0L)

        val status = SpaceStatus.evaluate(budget, 1 * gib, 0L, 40 * mib)

        assertTrue(status.devicePressure)
        assertFalse(status.userLimitFull)
    }

    @Test
    fun evaluateWithRoomLeftIsQuiet() {
        val status = SpaceStatus.evaluate(CacheBudget.fixed(2 * gib), 1 * gib, 0L, 40 * mib)

        assertFalse(status.any)
    }

    @Test
    fun evaluateWithAnUnknownMeasurementSaysSo() {
        assertTrue(SpaceStatus.evaluate(CacheBudget.UNKNOWN, 0L, 0L, 40 * mib).measurementUnavailable)
    }
}
