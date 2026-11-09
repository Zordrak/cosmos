package com.cosmos.screensaver.ui

import com.cosmos.screensaver.sync.CacheBudget
import com.cosmos.screensaver.sync.StorageMeasurement

/** The parts of the TV's storage, in the order the bar draws them. */
internal enum class BarKind { OTHER_USED, FAVOURITES, SAVED, UNDO, ROOM, UNALLOCATED, RESERVED }

internal data class BarSegment(val kind: BarKind, val bytes: Long)

/**
 * The bar as pure numbers. [segments] always lists every [BarKind] and their bytes add up to exactly
 * [totalBytes] (never negative, never overlapping); a measurement that cannot be trusted gives an
 * unknown layout with no segments instead of invented ones. Markers are byte offsets from the left
 * edge and add no bytes of their own.
 */
internal data class StorageBarLayout(
    val known: Boolean,
    val totalBytes: Long,
    val segments: List<BarSegment>,
    /** Where the chosen size ends, or -1 when unknown. */
    val limitMarkerBytes: Long,
    /** Where Cosmos can really grow to when the TV's free space (not the chosen size) is the limit, else -1. */
    val capacityMarkerBytes: Long,
    /** How far free space is below the amount Cosmos aims to leave free; zero when it is not. */
    val reserveShortfallBytes: Long,
    val budget: CacheBudget
) {
    fun bytes(kind: BarKind): Long = segments.firstOrNull { it.kind == kind }?.bytes ?: 0L

    /** Everything Cosmos itself holds: favourites, other saved pictures and files waiting for Undo. */
    val cosmosBytes: Long get() = bytes(BarKind.FAVOURITES) + bytes(BarKind.SAVED) + bytes(BarKind.UNDO)

    companion object {
        val UNKNOWN = StorageBarLayout(
            known = false, totalBytes = 0L, segments = emptyList(), limitMarkerBytes = -1L,
            capacityMarkerBytes = -1L, reserveShortfallBytes = 0L, budget = CacheBudget.UNKNOWN
        )
    }
}

internal object StorageBarModel {
    /**
     * [limitBytes] is the size being shown (0 = Automatic); [favouriteBytes], [savedBytes] (the
     * non-favourite saved pictures) and [undoBytes] are what Cosmos holds on disk.
     */
    fun layout(
        measurement: StorageMeasurement,
        limitBytes: Long,
        favouriteBytes: Long,
        savedBytes: Long,
        undoBytes: Long
    ): StorageBarLayout {
        val favourites = favouriteBytes.coerceAtLeast(0L)
        val saved = savedBytes.coerceAtLeast(0L)
        val undo = undoBytes.coerceAtLeast(0L)
        val owned = saturatingAdd(saturatingAdd(favourites, saved), undo)
        val budget = CacheBudget.compute(measurement, limitBytes, owned)
        val known = measurement as? StorageMeasurement.Known
        if (!budget.known || known == null) return StorageBarLayout.UNKNOWN.copy(budget = budget)

        val total = known.totalBytes
        val free = known.availableBytes
        val used = total - free

        // If Cosmos claims more than the TV says is used, the measurement is stale: trim Cosmos's
        // parts (undo first) rather than let the segments exceed the total.
        val shownFavourites = minOf(favourites, used)
        val shownSaved = minOf(saved, used - shownFavourites)
        val shownUndo = minOf(undo, used - shownFavourites - shownSaved)
        val other = used - shownFavourites - shownSaved - shownUndo

        val reserved = minOf(free, budget.reserveBytes)
        val aboveReserve = free - reserved
        val room = (budget.budgetBytes - owned).coerceIn(0L, aboveReserve)
        val unallocated = aboveReserve - room

        val segments = listOf(
            BarSegment(BarKind.OTHER_USED, other),
            BarSegment(BarKind.FAVOURITES, shownFavourites),
            BarSegment(BarKind.SAVED, shownSaved),
            BarSegment(BarKind.UNDO, shownUndo),
            BarSegment(BarKind.ROOM, room),
            BarSegment(BarKind.UNALLOCATED, unallocated),
            BarSegment(BarKind.RESERVED, reserved)
        )
        val limitMarker = minOf(total, saturatingAdd(other, budget.limitBytes))
        val capacityMarker = if (budget.budgetBytes < budget.limitBytes) {
            minOf(total, saturatingAdd(other, budget.budgetBytes))
        } else {
            -1L
        }
        return StorageBarLayout(
            known = true,
            totalBytes = total,
            segments = segments,
            limitMarkerBytes = limitMarker,
            capacityMarkerBytes = capacityMarker,
            reserveShortfallBytes = (budget.reserveBytes - free).coerceAtLeast(0L),
            budget = budget
        )
    }

    private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}

/** Separate things worth telling the owner about the size they are looking at. */
internal enum class SpaceNote {
    MEASUREMENT_UNAVAILABLE,

    /** The TV's free space, not the chosen size, decides how much can be saved. */
    CONSTRAINED_BY_FREE_SPACE,
    FAVOURITES_ABOVE_TARGET,
    UNDO_PENDING
}

internal data class SpaceAdvice(
    /** About how many pictures fit in the space Cosmos can really use. */
    val capacityPictures: Int,
    /** True when [capacityPictures] assumes a typical size because too few pictures are saved to average. */
    val estimated: Boolean,
    val averageBytes: Long,
    val notes: Set<SpaceNote>
)

internal object SpaceAdvisor {
    const val ASSUMED_PICTURE_BYTES = 4L * 1024 * 1024
    const val MIN_SAMPLE_PICTURES = 5

    fun advise(
        layout: StorageBarLayout,
        uniquePictures: Int,
        uniqueBytes: Long,
        favouriteBytes: Long,
        undoBytes: Long
    ): SpaceAdvice {
        if (!layout.known) {
            return SpaceAdvice(0, estimated = true, averageBytes = ASSUMED_PICTURE_BYTES, notes = setOf(SpaceNote.MEASUREMENT_UNAVAILABLE))
        }
        val estimated = uniquePictures < MIN_SAMPLE_PICTURES || uniqueBytes <= 0L
        val average = if (estimated) ASSUMED_PICTURE_BYTES else (uniqueBytes / uniquePictures).coerceAtLeast(1L)
        val capacity = (layout.budget.budgetBytes / average).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val notes = mutableSetOf<SpaceNote>()
        if (layout.budget.spaceConstrained) notes += SpaceNote.CONSTRAINED_BY_FREE_SPACE
        if (favouriteBytes > layout.budget.budgetBytes) notes += SpaceNote.FAVOURITES_ABOVE_TARGET
        if (undoBytes > 0L) notes += SpaceNote.UNDO_PENDING
        return SpaceAdvice(capacity, estimated, average, notes)
    }
}
