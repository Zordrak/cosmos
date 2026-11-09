package com.cosmos.screensaver.ui

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.settings.SelectionSnapshot

/**
 * What the saved pictures are made of, relative to the current "What to show" choices. Everything
 * counts distinct pictures (aliases once) and distinct files (each file once), and favourites are
 * never part of what Tidy or Clear can remove. This is the same partition the Undo engine applies,
 * so the numbers shown beforehand match what a confirmed change removes.
 */
internal data class CacheComposition(
    /** Pictures the slideshow can show right now. */
    val matching: Int = 0,
    /** Non-favourite pictures the choices would not show: what Tidy removes. */
    val keptPictures: Int = 0,
    val keptFiles: Int = 0,
    val keptBytes: Long = 0L,
    /** Favourites the choices would not show; they are never removed. */
    val favouritesOutOfView: Int = 0,
    /** Every non-favourite picture: what Clear removes. */
    val clearablePictures: Int = 0,
    val clearableBytes: Long = 0L
) {
    companion object {
        fun of(entries: List<CatalogueEntry>, eligibleIdentities: Set<String>, state: UserState): CacheComposition {
            val byIdentity = entries.groupBy(SelectionSnapshot::identity)
            val removable = byIdentity.filterValues { aliases -> aliases.none { state.isFavourite(it.sha256) } }
            val kept = removable.filterKeys { it !in eligibleIdentities }
            val favouritesOutOfView = byIdentity.count { (identity, aliases) ->
                identity !in eligibleIdentities && aliases.any { state.isFavourite(it.sha256) }
            }
            return CacheComposition(
                matching = eligibleIdentities.count { it in byIdentity },
                keptPictures = kept.size,
                keptFiles = SelectionSnapshot.physicalFiles(kept.values.flatten()),
                keptBytes = SelectionSnapshot.physicalBytes(kept.values.flatten()),
                favouritesOutOfView = favouritesOutOfView,
                clearablePictures = removable.size,
                clearableBytes = SelectionSnapshot.physicalBytes(removable.values.flatten())
            )
        }
    }
}
