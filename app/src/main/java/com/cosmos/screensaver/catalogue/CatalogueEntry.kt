package com.cosmos.screensaver.catalogue

import com.cosmos.screensaver.rights.AssetProvider

internal data class EvidenceRecord(
    val url: String,
    val statement: String,
    val scope: String,
    val retrievedAtEpochMillis: Long
)

/**
 * A downloaded image with its provenance. [rightsBasis] is [RIGHTS_NOT_CHECKED] when it was
 * admitted in personal-use mode, in which case the licence fields and evidence are empty.
 */
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
    val alteration: String = ALTERATION_NONE,
    /** Why a protected (favourite) picture is kept but never shown; blank when it can be shown. */
    val hiddenReason: String = "",
    /** The content-filter version this picture was last screened against; blank when never screened. */
    val contentPolicy: String = ""
) {
    val key: String get() = keyOf(provider, providerId)

    companion object {
        /** The stored file is byte-identical to the source; it is scaled or cropped only when shown. */
        const val ALTERATION_NONE = "UNALTERED_FILE_DISPLAY_PER_SETTINGS"
        const val RIGHTS_NOT_CHECKED = "NOT_CHECKED_PERSONAL_USE"

        fun keyOf(provider: AssetProvider, providerId: String) = "${provider.name}:$providerId"
    }
}

internal data class RejectionRecord(val reason: String, val atEpochMillis: Long)

internal data class Catalogue(
    val entries: List<CatalogueEntry> = emptyList(),
    val rejections: Map<String, RejectionRecord> = emptyMap(),
    val lastAttemptAtEpochMillis: Long = 0L,
    val lastSuccessAtEpochMillis: Long = 0L,
    val lastSummary: String = "",
    /** [com.cosmos.screensaver.settings.ImageSelection.fingerprint] used by the last refresh. */
    val selectionFingerprint: String = "",
    /**
     * The last refresh stopped with room left while a source could not be read (a timeout, say),
     * so another look is worth making soon rather than after the usual day.
     */
    val moreToFetch: Boolean = false
)
