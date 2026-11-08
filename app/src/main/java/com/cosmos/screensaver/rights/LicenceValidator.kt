package com.cosmos.screensaver.rights

import com.cosmos.screensaver.source.DjangoplicitySite
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections
import java.util.Locale

internal enum class AssetProvider {
    NASA,
    ESA_HUBBLE,
    ESA_WEBB,
    ESO,
    NOIRLAB
}

internal enum class RightsUseScope {
    PERSONAL_NONCOMMERCIAL_SCREENSAVER,
    OTHER,
    UNKNOWN
}

internal data class RequestedUse(
    val territory: String?,
    val scope: RightsUseScope?
)

internal data class RightsUseContext(
    val territory: String,
    val scope: RightsUseScope
)

internal data class RightsCandidate(
    val provider: AssetProvider?,
    val providerId: String?,
    val sourcePageUrl: String?,
    val originalAssetUrl: String?,
    val creatorCredit: String?,
    val intendedUse: RequestedUse?
)

internal data class AssetIdentity(
    val provider: AssetProvider,
    val providerId: String,
    val sourcePageUrl: String,
    val originalAssetUrl: String
)

internal enum class EvidenceScope {
    INDIVIDUAL_ASSET,
    PROVIDER_OR_COLLECTION,
    UNKNOWN
}

internal enum class RightsBasis {
    NASA_PUBLIC_DOMAIN,
    CC_BY_4_0,
    UNSUPPORTED,
    UNKNOWN
}

internal enum class RightsOwner {
    NASA_FEDERAL_GOVERNMENT_WORK,
    ESA_HUBBLE,
    ESA_WEBB,
    ESO,
    NOIRLAB,
    OTHER,
    UNKNOWN
}

internal enum class RightsCoverage {
    ENTIRE_ASSET,
    PARTIAL,
    UNKNOWN
}

internal enum class RestrictionAssessment {
    CLEAR,
    RESTRICTED,
    CONFLICTING,
    UNKNOWN
}

internal enum class UsePermission {
    CACHE_AND_SCREENSAVER_DISPLAY,
    RESTRICTED,
    UNKNOWN
}

internal data class EvidenceSource(
    val evidenceUrl: String,
    val exactStatement: String,
    val scope: EvidenceScope,
    val retrievedAtEpochMillis: Long
)

internal data class VerifiedAssetEvidence(
    val subject: AssetIdentity,
    val intendedUse: RightsUseContext,
    val sources: List<EvidenceSource>,
    val creatorCredit: String,
    val basis: RightsBasis,
    val owner: RightsOwner,
    val coverage: RightsCoverage,
    val restrictions: RestrictionAssessment,
    val permittedUse: UsePermission,
    val licenceUrl: String?
)

internal enum class RightsRejection {
    UNSUPPORTED_PROVIDER,
    INVALID_ASSET_ID,
    INVALID_SOURCE_URL,
    INVALID_ASSET_URL,
    MISSING_CREDIT,
    MISSING_USE_CONTEXT,
    INVALID_USE_TERRITORY,
    UNSUPPORTED_USE,
    VERIFIER_UNAVAILABLE,
    MISSING_EVIDENCE,
    AMBIGUOUS_EVIDENCE,
    CONFLICTING_EVIDENCE,
    PERMISSION_RESTRICTED,
    EVIDENCE_ASSET_MISMATCH,
    EVIDENCE_USE_MISMATCH,
    NON_ASSET_EVIDENCE,
    INVALID_EVIDENCE_URL,
    MISSING_RIGHTS_STATEMENT,
    CREDIT_MISMATCH,
    INCOMPLETE_RIGHTS_COVERAGE,
    UNSUPPORTED_RIGHTS_BASIS,
    INVALID_OWNERSHIP,
    INVALID_LICENCE_URL,
    INVALID_EVIDENCE_TIMESTAMP
}

internal sealed interface EvidenceVerification {
    data class Verified(val evidence: VerifiedAssetEvidence) : EvidenceVerification
    data class Rejected(val reason: RightsRejection) : EvidenceVerification
}

/**
 * An implementation may return Verified only after checking authoritative evidence for the
 * exact asset, complete credit, intended use throughout the requested territory, and all known
 * conflicting restrictions. Provider-wide terms, API availability, or an unverified claim are
 * not sufficient.
 */
internal fun interface AssetEvidenceVerifier {
    fun verify(asset: AssetIdentity, intendedUse: RightsUseContext): EvidenceVerification
}

internal data class ValidatedRights(
    val asset: AssetIdentity,
    val intendedUse: RightsUseContext,
    val creatorCredit: String,
    val basis: RightsBasis,
    val evidenceSources: List<EvidenceSource>,
    val licenceUrl: String?,
    val validatedAtEpochMillis: Long,
    val policyVersion: String
)

internal sealed interface RightsDecision {
    data class RightsCleared(val rights: ValidatedRights) : RightsDecision
    data class Rejected(val reason: RightsRejection) : RightsDecision
}

internal class LicenceValidator(
    private val verifier: AssetEvidenceVerifier = AssetEvidenceVerifier { _, _ ->
        EvidenceVerification.Rejected(RightsRejection.VERIFIER_UNAVAILABLE)
    },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    fun validate(candidate: RightsCandidate): RightsDecision {
        val provider = candidate.provider
            ?: return reject(RightsRejection.UNSUPPORTED_PROVIDER)
        val providerId = candidate.providerId?.takeIf { it.isNotBlank() && it == it.trim() }
            ?: return reject(RightsRejection.INVALID_ASSET_ID)
        val sourcePageUrl = candidate.sourcePageUrl?.takeIf { isApprovedUrl(it, provider) }
            ?: return reject(RightsRejection.INVALID_SOURCE_URL)
        val originalAssetUrl = candidate.originalAssetUrl?.takeIf { isApprovedUrl(it, provider) }
            ?: return reject(RightsRejection.INVALID_ASSET_URL)
        val credit = candidate.creatorCredit?.takeIf { it.isNotBlank() }
            ?: return reject(RightsRejection.MISSING_CREDIT)
        val requestedUse = candidate.intendedUse
            ?: return reject(RightsRejection.MISSING_USE_CONTEXT)
        val territory = requestedUse.territory?.takeIf { it in ISO_TERRITORIES }
            ?: return reject(RightsRejection.INVALID_USE_TERRITORY)
        if (requestedUse.scope != RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER) {
            return reject(RightsRejection.UNSUPPORTED_USE)
        }
        val asset = AssetIdentity(provider, providerId, sourcePageUrl, originalAssetUrl)
        val intendedUse = RightsUseContext(territory, RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER)

        val evidence = when (val result = verifier.verify(asset, intendedUse)) {
            is EvidenceVerification.Rejected -> return reject(result.reason)
            is EvidenceVerification.Verified -> result.evidence
        }

        if (evidence.subject != asset) return reject(RightsRejection.EVIDENCE_ASSET_MISMATCH)
        if (evidence.intendedUse != intendedUse) return reject(RightsRejection.EVIDENCE_USE_MISMATCH)
        val sources = Collections.unmodifiableList(evidence.sources.toList())
        if (sources.isEmpty()) return reject(RightsRejection.MISSING_EVIDENCE)
        if (sources.any { it.scope == EvidenceScope.UNKNOWN }) {
            return reject(RightsRejection.AMBIGUOUS_EVIDENCE)
        }
        if (sources.none { it.scope == EvidenceScope.INDIVIDUAL_ASSET }) {
            return reject(RightsRejection.NON_ASSET_EVIDENCE)
        }
        if (sources.any { !isApprovedUrl(it.evidenceUrl, provider) }) {
            return reject(RightsRejection.INVALID_EVIDENCE_URL)
        }
        if (sources.any { it.exactStatement.isBlank() }) {
            return reject(RightsRejection.MISSING_RIGHTS_STATEMENT)
        }
        if (evidence.creatorCredit != credit) return reject(RightsRejection.CREDIT_MISMATCH)
        if (evidence.coverage != RightsCoverage.ENTIRE_ASSET) {
            return reject(RightsRejection.INCOMPLETE_RIGHTS_COVERAGE)
        }
        when (evidence.restrictions) {
            RestrictionAssessment.CLEAR -> Unit
            RestrictionAssessment.RESTRICTED -> return reject(RightsRejection.PERMISSION_RESTRICTED)
            RestrictionAssessment.CONFLICTING -> return reject(RightsRejection.CONFLICTING_EVIDENCE)
            RestrictionAssessment.UNKNOWN -> return reject(RightsRejection.AMBIGUOUS_EVIDENCE)
        }
        when (evidence.permittedUse) {
            UsePermission.CACHE_AND_SCREENSAVER_DISPLAY -> Unit
            UsePermission.RESTRICTED -> return reject(RightsRejection.PERMISSION_RESTRICTED)
            UsePermission.UNKNOWN -> return reject(RightsRejection.AMBIGUOUS_EVIDENCE)
        }

        when (provider) {
            AssetProvider.NASA -> {
                if (evidence.basis != RightsBasis.NASA_PUBLIC_DOMAIN) {
                    return reject(RightsRejection.UNSUPPORTED_RIGHTS_BASIS)
                }
                if (evidence.owner != RightsOwner.NASA_FEDERAL_GOVERNMENT_WORK) {
                    return reject(RightsRejection.INVALID_OWNERSHIP)
                }
                if (evidence.licenceUrl != null) return reject(RightsRejection.CONFLICTING_EVIDENCE)
            }
            AssetProvider.ESA_HUBBLE, AssetProvider.ESA_WEBB, AssetProvider.ESO, AssetProvider.NOIRLAB -> {
                if (evidence.basis != RightsBasis.CC_BY_4_0) {
                    return reject(RightsRejection.UNSUPPORTED_RIGHTS_BASIS)
                }
                if (evidence.owner == RightsOwner.UNKNOWN) {
                    return reject(RightsRejection.INVALID_OWNERSHIP)
                }
                if (evidence.licenceUrl != CC_BY_4_0_URL) {
                    return reject(RightsRejection.INVALID_LICENCE_URL)
                }
            }
        }

        val validatedAt = currentTimeMillis()
        if (
            validatedAt <= 0L ||
            sources.any { it.retrievedAtEpochMillis <= 0L || it.retrievedAtEpochMillis > validatedAt }
        ) {
            return reject(RightsRejection.INVALID_EVIDENCE_TIMESTAMP)
        }

        return RightsDecision.RightsCleared(
            ValidatedRights(
                asset = asset,
                intendedUse = intendedUse,
                creatorCredit = credit,
                basis = evidence.basis,
                evidenceSources = sources,
                licenceUrl = evidence.licenceUrl,
                validatedAtEpochMillis = validatedAt,
                policyVersion = POLICY_VERSION
            )
        )
    }

    private fun reject(reason: RightsRejection) = RightsDecision.Rejected(reason)

    private fun isApprovedUrl(value: String, provider: AssetProvider): Boolean {
        if (value != value.trim()) return false
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val path = uri.rawPath
        val approvedHost = when (provider) {
            AssetProvider.NASA -> host == "nasa.gov" || host.endsWith(".nasa.gov")
            else -> DjangoplicitySite.forProvider(provider)?.hosts?.contains(host) == true
        }
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            !path.isNullOrEmpty() &&
            path != "/" &&
            approvedHost
    }

    companion object {
        const val POLICY_VERSION = "3"
        const val CC_BY_4_0_URL = "https://creativecommons.org/licenses/by/4.0/"
        private val ISO_TERRITORIES = Locale.getISOCountries().toSet()
    }
}
