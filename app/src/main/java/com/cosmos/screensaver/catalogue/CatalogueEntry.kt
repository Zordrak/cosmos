package com.cosmos.screensaver.catalogue

import com.cosmos.screensaver.rights.AssetProvider

internal data class EvidenceRecord(
    val url: String,
    val statement: String,
    val scope: String,
    val retrievedAtEpochMillis: Long
)

/** A rights-cleared, downloaded image with the provenance required to display and audit it. */
internal data class CatalogueEntry(
    val provider: AssetProvider,
    val providerId: String,
    val title: String,
    val sourceLabel: String,
    val sourcePageUrl: String,
    val assetUrl: String,
    val credit: String,
    val rightsBasis: String,
    val licenceName: String,
    val licenceUrl: String,
    val territory: String,
    val evidence: List<EvidenceRecord>,
    val validatedAtEpochMillis: Long,
    val policyVersion: String,
    val admittedAtEpochMillis: Long,
    val sha256: String,
    val byteCount: Long,
    val widthPx: Int,
    val heightPx: Int,
    val fileName: String,
    val alteration: String = ALTERATION_NONE
) {
    val key: String get() = keyOf(provider, providerId)

    companion object {
        /** The stored file is byte-identical to the source; it is only scaled to fit when shown. */
        const val ALTERATION_NONE = "UNALTERED_SCALED_TO_FIT_ON_DISPLAY"

        fun keyOf(provider: AssetProvider, providerId: String) = "${provider.name}:$providerId"
    }
}

internal data class RejectionRecord(val reason: String, val atEpochMillis: Long)

internal data class Catalogue(
    val entries: List<CatalogueEntry> = emptyList(),
    val rejections: Map<String, RejectionRecord> = emptyMap(),
    val lastAttemptAtEpochMillis: Long = 0L,
    val lastSuccessAtEpochMillis: Long = 0L,
    val lastSummary: String = ""
)
