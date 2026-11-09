package com.cosmos.screensaver.ui

import com.cosmos.screensaver.catalogue.BlockedRecord
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.UserState

/** One saved picture as the list shows it. Aliases (the same bytes from two sources) are one row (C1). */
internal data class LibraryRow(
    /** The picture's identity (content hash, or the source key when none is recorded). Selection and commands use this, never a list position. */
    val id: String,
    /** Every source entry for this picture, in a stable order; never empty. */
    val aliases: List<CatalogueEntry>,
    val favourite: Boolean,
    /** False when the picture is kept but does not match the current viewing choices. */
    val inCurrentChoices: Boolean,
    val shownCount: Long,
    val lastShownAtEpochMillis: Long
) {
    val primary: CatalogueEntry get() = aliases.first()
    val title: String get() = aliases.firstOrNull { it.title.isNotBlank() }?.title ?: primary.providerId
    val sources: String get() = aliases.map { it.sourceLabel }.distinct().joinToString(" \u00B7 ")
    val bytes: Long get() = primary.byteCount
    val widthPx: Int get() = aliases.firstOrNull { it.widthPx > 0 && it.heightPx > 0 }?.widthPx ?: 0
    val heightPx: Int get() = aliases.firstOrNull { it.widthPx > 0 && it.heightPx > 0 }?.heightPx ?: 0
    val admittedAtEpochMillis: Long get() = aliases.maxOf { it.admittedAtEpochMillis }
    val fileName: String get() = primary.fileName
    val hiddenReason: String get() = aliases.firstNotNullOfOrNull { it.hiddenReason.takeIf(String::isNotBlank) }.orEmpty()

    /** The list's second line: where it is from, its size, and why it is not being shown if so. */
    val subtitle: String
        get() = listOfNotNull(
            sources,
            if (widthPx > 0) "$widthPx \u00D7 $heightPx" else null,
            ByteSizes.format(bytes),
            if (hiddenReason.isNotBlank()) "Not shown" else if (!inCurrentChoices) "Kept for later" else null
        ).joinToString(" \u00B7 ")

    /** Changes whenever the file or its identity does, so a cached thumbnail is never reused for different bytes. */
    val imageKey: String get() = "$id:${primary.fileName}:${primary.byteCount}"
}

internal object LibraryRows {
    /**
     * One row per distinct picture, newest saved first (ties broken by identity so the order is
     * stable). [eligibleIdentities] is the current selection snapshot's identities.
     */
    fun build(
        entries: List<CatalogueEntry>,
        state: UserState,
        eligibleIdentities: Set<String>
    ): List<LibraryRow> = entries
        .groupBy { it.sha256.ifBlank { it.key } }
        .map { (id, group) ->
            val aliases = group.sortedWith(compareBy<CatalogueEntry>({ it.provider.name }, { it.key }))
            val stats = state.stats[id]
            LibraryRow(
                id = id,
                aliases = aliases,
                favourite = aliases.any { state.isFavourite(it.sha256) },
                inCurrentChoices = id in eligibleIdentities,
                shownCount = stats?.shownCount ?: 0L,
                lastShownAtEpochMillis = stats?.lastShownAtEpochMillis ?: 0L
            )
        }
        .sortedWith(compareByDescending<LibraryRow> { it.admittedAtEpochMillis }.thenBy { it.id })

    /** Blocked pictures, most recently blocked first. */
    fun blocked(state: UserState): List<BlockedRecord> =
        state.blocked.sortedWith(compareByDescending<BlockedRecord> { it.blockedAtEpochMillis }.thenBy { it.id })

    /**
     * Where the selection should be after the list changed: the same picture if it is still there,
     * otherwise the nearest surviving neighbour (the one after it, else the one before), else the
     * first row. -1 for an empty list.
     */
    fun resolveSelection(newIds: List<String>, selectedId: String?, previousIds: List<String>): Int {
        if (newIds.isEmpty()) return -1
        if (selectedId == null) return 0
        newIds.indexOf(selectedId).takeIf { it >= 0 }?.let { return it }
        val old = previousIds.indexOf(selectedId)
        if (old < 0) return 0
        val survivors = newIds.toHashSet()
        for (i in old + 1 until previousIds.size) if (previousIds[i] in survivors) return newIds.indexOf(previousIds[i])
        for (i in old - 1 downTo 0) if (previousIds[i] in survivors) return newIds.indexOf(previousIds[i])
        return 0
    }
}

internal data class DetailSection(val heading: String, val lines: List<String>)

/** The words in the details pane, kept pure so every provenance state can be checked. */
internal object LibraryDetails {
    fun sections(
        row: LibraryRow,
        nowMillis: Long,
        formatDate: (Long) -> String,
        fileExists: Boolean
    ): List<DetailSection> {
        val about = mutableListOf<String>()
        about += listOfNotNull(
            if (row.widthPx > 0) "${row.widthPx} \u00D7 ${row.heightPx}" else null,
            ByteSizes.format(row.bytes)
        ).joinToString(" \u00B7 ")
        about += "Saved " + formatDate(row.admittedAtEpochMillis)
        about += LibraryFormat.describeShown(row.shownCount, row.lastShownAtEpochMillis, nowMillis)
        if (row.favourite) about += "Favourite: kept on this TV and shown more often in Fair shuffle."
        if (row.hiddenReason.isNotBlank()) about += "Kept but not shown: ${row.hiddenReason}."
        else if (!row.inCurrentChoices) about += "Not in your current choices. Kept for later and replaced first when room is needed."
        if (!fileExists) about += "The picture file is missing from this TV."

        val sections = mutableListOf(DetailSection("This picture", about))
        row.aliases.forEach { sections += provenance(it, formatDate) }
        return sections
    }

    private fun provenance(entry: CatalogueEntry, formatDate: (Long) -> String): DetailSection {
        val lines = mutableListOf<String>()
        lines += "Credit: " + entry.credit.ifBlank { "not recorded" }
        if (entry.sourcePageUrl.isNotBlank()) lines += "Page: ${entry.sourcePageUrl}"
        if (entry.assetUrl.isNotBlank()) lines += "File: ${entry.assetUrl}"
        if (entry.rightsBasis == CatalogueEntry.RIGHTS_NOT_CHECKED) {
            lines += "Licence: not checked (personal-use mode)"
        } else {
            val name = entry.licenceName.ifBlank { entry.rightsBasis }
            lines += "Licence: $name" + if (entry.licenceUrl.isNotBlank()) " (${entry.licenceUrl})" else ""
            entry.evidence.forEach { evidence ->
                lines += "Evidence: ${evidence.statement.ifBlank { evidence.scope }} (${evidence.url})"
            }
            if (entry.validatedAtEpochMillis > 0L) {
                lines += "Checked ${formatDate(entry.validatedAtEpochMillis)}" +
                    if (entry.policyVersion.isNotBlank()) ", policy ${entry.policyVersion}" else ""
            }
        }
        if (entry.sha256.isNotBlank()) lines += "Checksum: ${entry.sha256.take(16)}\u2026"
        return DetailSection(entry.sourceLabel.ifBlank { entry.key }, lines)
    }

    fun blocked(record: BlockedRecord, formatDate: (Long) -> String): List<DetailSection> {
        val lines = mutableListOf<String>()
        lines += "Blocked " + formatDate(record.blockedAtEpochMillis)
        lines += "Cosmos will not save or show this picture, from any source it was known under."
        lines += "Known as: " + record.keys.sorted().joinToString(", ")
        if (!record.sha256.isNullOrBlank()) lines += "Checksum: ${record.sha256.take(16)}\u2026"
        return listOf(DetailSection(record.sourceLabel, lines))
    }
}
