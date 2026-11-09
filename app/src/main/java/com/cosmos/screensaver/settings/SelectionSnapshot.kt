package com.cosmos.screensaver.settings

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.source.ContentFilter

/**
 * The single answer to "which saved pictures can be shown right now", shared by the slideshow,
 * counts, previews, eviction priority and Tidy (contract C3).
 *
 * Aliases (the same bytes served by two sources) are filtered by source, block, content and
 * licence first, and only then reduced to one representative per picture. The quality fallback is
 * decided over distinct pictures, so aliases never inflate the threshold.
 */
internal class SelectionSnapshot private constructor(
    /** One representative entry per picture, in a stable order. */
    val eligible: List<CatalogueEntry>,
    /** Every eligible alias key, including those of pictures already represented once. */
    val eligibleKeys: Set<String>,
    /** All eligible aliases of each picture, keyed by [identity]. */
    val aliasesByIdentity: Map<String, List<CatalogueEntry>>,
    /** Distinct pictures meeting the preferred (non-fallback) quality. */
    val qualifyingCount: Int,
    val fallbackActive: Boolean,
    /** Stamp supplied by the caller so a UI can discard an older asynchronous result. */
    val revision: Long
) {
    val eligibleIdentities: Set<String> get() = aliasesByIdentity.keys

    companion object {
        fun identity(entry: CatalogueEntry): String = entry.sha256.ifBlank { entry.key }

        /** Entries that are enabled, unblocked, visible and screened: the cohort the fallback is judged over. */
        fun usable(
            entries: List<CatalogueEntry>,
            enabledProviders: Set<AssetProvider>,
            state: UserState
        ): List<CatalogueEntry> = entries.filter { entry ->
            entry.provider in enabledProviders &&
                entry.hiddenReason.isBlank() &&
                ContentFilter.check(entry.title) == null &&
                !state.isBlocked(entry.key, entry.sha256)
        }

        fun build(
            entries: List<CatalogueEntry>,
            selection: ImageSelection,
            enabledProviders: Set<AssetProvider>,
            state: UserState,
            revision: Long = 0L
        ): SelectionSnapshot {
            val cohort = usable(entries, enabledProviders, state)
            val qualifying = cohort
                .filter { selection.licenceAccepts(it) && selection.meetsMinimum(it) }
                .map(::identity)
                .toSet()
            val useFallback = selection.fallbackActive(qualifying.size)
            val aliases = cohort
                .filter { selection.qualifies(it, useFallback) }
                .sortedWith(compareBy<CatalogueEntry>({ it.provider.name }, { it.key }))
                .groupBy(::identity)
            val representatives = aliases.values.map { it.first() }.sortedBy { it.key }
            return SelectionSnapshot(
                eligible = representatives,
                eligibleKeys = aliases.values.flatten().map { it.key }.toSet(),
                aliasesByIdentity = aliases,
                qualifyingCount = qualifying.size,
                fallbackActive = useFallback,
                revision = revision
            )
        }

        /** Bytes on disk: aliases share one file, so each file is counted once. */
        fun physicalBytes(entries: List<CatalogueEntry>): Long =
            entries.distinctBy { it.fileName }.sumOf { it.byteCount }

        fun physicalFiles(entries: List<CatalogueEntry>): Int = entries.distinctBy { it.fileName }.size
    }
}
