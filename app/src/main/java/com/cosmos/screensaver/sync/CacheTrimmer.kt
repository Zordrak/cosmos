package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.LibraryMaintenance
import com.cosmos.screensaver.catalogue.StoreRead
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.catalogue.UserStateRead
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.SelectionSnapshot

/**
 * Network-free removal of whole saved pictures, in the order they should go: pictures the current
 * view settings would not show first, then the oldest admitted, with a stable tie-break.
 * Favourites are never candidates, and every removal re-checks that under the library lock, so a
 * picture favourited while this runs is never deleted from a stale list.
 */
internal object CacheTrimmer {
    /** One unique picture: all its source aliases share one file and one identity. */
    data class Group(val identity: String, val bytes: Long, val admittedAt: Long, val inView: Boolean)

    data class Result(
        val removedPictures: Int = 0,
        val removedBytes: Long = 0L,
        /** The goal was reached (or no goal remained). */
        val satisfied: Boolean = false,
        /** Candidates ran out while only protected pictures remained. */
        val protectedRemain: Boolean = false,
        val failures: Int = 0,
        /** The saved library or the user's choices could not be read, so nothing was touched. */
        val unreadable: Boolean = false
    )

    /** Removable pictures in removal order. Favourites and in-use files are excluded by the caller's sets. */
    fun candidates(entries: List<CatalogueEntry>, state: UserState, inView: Set<String>): List<Group> {
        val protectedIdentities = entries.filter { state.isFavourite(it.sha256) }.map(SelectionSnapshot::identity).toSet()
        return entries.groupBy(SelectionSnapshot::identity)
            .filterKeys { it !in protectedIdentities }
            .map { (identity, aliases) ->
                Group(
                    identity = identity,
                    bytes = aliases.distinctBy { it.fileName }.sumOf { it.byteCount },
                    admittedAt = aliases.minOf { it.admittedAtEpochMillis },
                    inView = identity in inView
                )
            }
            .sortedWith(compareBy({ it.inView }, { it.admittedAt }, { it.identity }))
    }

    /** Bytes of pictures that can never be removed here (favourites), each file counted once. */
    fun protectedBytes(entries: List<CatalogueEntry>, state: UserState): Long =
        entries.filter { state.isFavourite(it.sha256) }.distinctBy { it.fileName }.sumOf { it.byteCount }

    /**
     * Removes candidates one at a time, re-reading the live library each time, until [fits] accepts
     * the bytes now saved. Stops without touching anything when the library cannot be read.
     */
    fun evictUntil(
        store: CatalogueStore,
        userState: UserStateStore,
        selection: ImageSelection,
        providers: Set<AssetProvider>,
        fits: (savedBytes: Long) -> Boolean
    ): Result {
        var removed = 0
        var removedBytes = 0L
        var failures = 0
        val skipped = mutableSetOf<String>()
        while (true) {
            val entries = (store.read() as? StoreRead.Ok)?.catalogue?.entries
            val state = (userState.read() as? UserStateRead.Ok)?.state
            if (entries == null || state == null) {
                return Result(removed, removedBytes, unreadable = true, failures = failures)
            }
            if (fits(SelectionSnapshot.physicalBytes(entries))) {
                return Result(removed, removedBytes, satisfied = true, failures = failures)
            }
            val inView = SelectionSnapshot.build(entries, selection, providers, state).eligibleIdentities
            val remaining = candidates(entries, state, inView).filter { it.identity !in skipped }
            val next = remaining.firstOrNull()
                ?: return Result(
                    removed, removedBytes, protectedRemain = protectedBytes(entries, state) > 0L, failures = failures
                )
            when (LibraryMaintenance.evict(store, userState, next.identity)) {
                0 -> {
                    failures++
                    skipped += next.identity
                }
                LibraryMaintenance.PROTECTED -> skipped += next.identity
                else -> {
                    removed++
                    removedBytes += next.bytes
                }
            }
        }
    }

    /** Removes candidates until the saved pictures fit within [budgetBytes], or none are left to remove. */
    fun trimToBudget(
        store: CatalogueStore,
        userState: UserStateStore,
        selection: ImageSelection,
        providers: Set<AssetProvider>,
        budgetBytes: Long
    ): Result = evictUntil(store, userState, selection, providers) { it <= budgetBytes }
}
