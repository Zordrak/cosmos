package com.cosmos.screensaver.sync

/** What the device reports about the storage that holds the saved pictures. */
internal sealed interface StorageMeasurement {
    data class Known(val totalBytes: Long, val availableBytes: Long) : StorageMeasurement

    /** The probe failed. Nothing may be deleted or newly granted on the strength of a failed probe. */
    data object Unknown : StorageMeasurement
}

/**
 * How many bytes the saved pictures may occupy right now. This is an intended upper bound:
 * protected favourites and pictures awaiting Undo can sit above it, in which case no new picture
 * is saved until there is capacity again. Build one with [compute]; all maths is saturating and
 * every input is validated as non-negative.
 */
internal data class CacheBudget(
    val known: Boolean,
    /** The owner's chosen size, or [AUTO_LIMIT_BYTES] for Automatic. */
    val limitBytes: Long,
    /** Free space that must be left for the rest of the TV. */
    val reserveBytes: Long,
    /** Physically available bytes at measurement; [Long.MAX_VALUE] when no physical limit applies. */
    val freeBytes: Long,
    /** Most the saved pictures could occupy while the reserve is kept. */
    val safeCapacityBytes: Long,
    val budgetBytes: Long
) {
    /** Free space is below the reserve: the device, not the owner's choice, is the constraint. */
    val devicePressure: Boolean get() = known && freeBytes < reserveBytes

    /** The reserve, not the chosen limit, decides the budget. */
    val spaceConstrained: Boolean get() = known && safeCapacityBytes < limitBytes

    /** Bytes held above the budget; zero when the measurement is unknown (never delete on a guess). */
    fun deficit(usedBytes: Long): Long = if (known) (usedBytes.coerceAtLeast(0L) - budgetBytes).coerceAtLeast(0L) else 0L

    companion object {
        const val MIB = 1024L * 1024
        const val GIB = 1024L * MIB
        const val AUTO_LIMIT_BYTES = 2L * GIB
        const val MIN_RESERVE_BYTES = 3L * GIB
        const val RESERVE_FRACTION = 0.15

        /** A budget that has no measurement behind it. */
        val UNKNOWN = CacheBudget(
            known = false, limitBytes = AUTO_LIMIT_BYTES, reserveBytes = MIN_RESERVE_BYTES,
            freeBytes = 0L, safeCapacityBytes = 0L, budgetBytes = 0L
        )

        /**
         * [ownedBytes] is everything Cosmos can give back: saved pictures, pending Undo files and
         * its own in-flight temporary files. [configuredLimit] of 0 means Automatic.
         */
        fun compute(measurement: StorageMeasurement, configuredLimit: Long, ownedBytes: Long): CacheBudget {
            val known = measurement as? StorageMeasurement.Known ?: return UNKNOWN
            if (known.totalBytes <= 0L || known.availableBytes < 0L || known.availableBytes > known.totalBytes) return UNKNOWN
            val limit = if (configuredLimit > 0L) configuredLimit else AUTO_LIMIT_BYTES
            val reserve = maxOf(MIN_RESERVE_BYTES, (known.totalBytes * RESERVE_FRACTION).toLong())
            val owned = ownedBytes.coerceAtLeast(0L)
            val safe = (saturatingAdd(owned, known.availableBytes) - reserve).coerceAtLeast(0L)
            return CacheBudget(
                known = true, limitBytes = limit, reserveBytes = reserve, freeBytes = known.availableBytes,
                safeCapacityBytes = safe, budgetBytes = minOf(limit, safe)
            )
        }

        /** A fixed budget with no physical limit, for tests and for streaming, which has its own floor. */
        fun fixed(budgetBytes: Long, freeBytes: Long = Long.MAX_VALUE) = CacheBudget(
            known = true, limitBytes = budgetBytes, reserveBytes = 0L, freeBytes = freeBytes,
            safeCapacityBytes = budgetBytes, budgetBytes = budgetBytes
        )

        private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}

/** Why a picture could not be saved. Each reason has different advice and none is a generic "no space". */
internal enum class NoRoomReason { USER_LIMIT, FAVOURITES, DEVICE_PRESSURE, UNAVAILABLE, UNREADABLE }

/** The separate storage conditions the UI and tests ask about; never parsed out of English text. */
internal data class SpaceStatus(
    /** The chosen size is reached while the TV has plenty of space. */
    val userLimitFull: Boolean = false,
    /** Only protected favourites remain, so nothing can be replaced. */
    val favouritesPreventSaving: Boolean = false,
    /** The TV's free space is at or below the reserve. */
    val devicePressure: Boolean = false,
    val measurementUnavailable: Boolean = false
) {
    val any: Boolean get() = userLimitFull || favouritesPreventSaving || devicePressure || measurementUnavailable

    companion object {
        fun of(reason: NoRoomReason): SpaceStatus = when (reason) {
            NoRoomReason.USER_LIMIT -> SpaceStatus(userLimitFull = true)
            NoRoomReason.FAVOURITES -> SpaceStatus(favouritesPreventSaving = true)
            NoRoomReason.DEVICE_PRESSURE -> SpaceStatus(devicePressure = true)
            NoRoomReason.UNAVAILABLE -> SpaceStatus(measurementUnavailable = true)
            NoRoomReason.UNREADABLE -> SpaceStatus()
        }

        /**
         * The live state for [usedBytes] of saved pictures of which [protectedBytes] are favourites.
         * "Full" means one more typical download would not fit.
         */
        fun evaluate(budget: CacheBudget, usedBytes: Long, protectedBytes: Long, downloadReserveBytes: Long): SpaceStatus {
            if (!budget.known) return SpaceStatus(measurementUnavailable = true)
            val full = usedBytes + downloadReserveBytes > budget.budgetBytes
            val removable = usedBytes - protectedBytes
            return SpaceStatus(
                userLimitFull = full && !budget.spaceConstrained && removable > 0L,
                favouritesPreventSaving = full && removable <= 0L && protectedBytes > 0L,
                devicePressure = budget.devicePressure || (full && budget.spaceConstrained)
            )
        }
    }
}
